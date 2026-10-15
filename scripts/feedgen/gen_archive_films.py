"""archive-films.json: public-domain feature films from the Internet Archive `feature_films` collection.

Search (official advancedsearch API), sorted by downloads, filtered on a public-domain / CC0 `licenseurl`,
then /metadata/<id> per item (<= 4 concurrent, small delay). Only items with an .mp4 file are kept.
"""
from __future__ import annotations

import re
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

TARGET = 150
CANDIDATES = 260
QUERY = "collection:(feature_films) AND mediatype:movies AND licenseurl:*publicdomain*"


def search() -> list[dict]:
    qs = urllib.parse.urlencode(
        [("q", QUERY), ("fl[]", "identifier"), ("fl[]", "downloads"), ("sort[]", "downloads desc"),
         ("rows", CANDIDATES), ("page", 1), ("output", "json")])
    data = common.get_json("https://archive.org/advancedsearch.php?" + qs)
    return data["response"]["docs"]


def norm_title(t: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", t.lower()).strip()


def pick_streams(files: list[dict], ident: str):
    mp4 = [f for f in files if f.get("name", "").lower().endswith(".mp4") and "/" not in f["name"]
           and not f["name"].lower().endswith(".thumbs")]
    if not mp4:
        return []

    def size(f):
        try:
            return int(f.get("size") or 0)
        except ValueError:
            return 0

    low = [f for f in mp4 if "_512kb" in f["name"].lower()]
    main = [f for f in mp4 if f not in low]
    # prefer h.264 derivatives; "MPEG4" originals (arbitrary codecs) only if there is nothing else
    h264 = [f for f in main if (f.get("format") or "").lower().startswith("h.264")]
    main = h264 or main
    chosen = []
    if main:
        chosen.append((max(main, key=size), "main"))
    if low:
        chosen.append((max(low, key=size), "low"))
    out = []
    for i, (f, kind) in enumerate(chosen):
        try:
            h = int(f.get("height") or 0)
        except ValueError:
            h = 0
        if h <= 0:
            h = 480 if kind == "main" else 240   # IA does not always record the height: documented guess
        quality = "1080p" if h >= 1000 else "720p" if h >= 700 else "480p" if h >= 420 else "360p" if h >= 300 else "240p"
        out.append({"url": common.ia_download_url(ident, f["name"]), "type": "mp4", "quality": quality,
                    "priority": 0 if i == 0 else 1, "label": "Archive.org" + ("" if i == 0 else " (low bitrate)"),
                    "bitrate_kbps": None, "video_codec": "h264" if (f.get("format") or "").lower().startswith("h.264") or kind == "low" else None,
                    "_len": float(f["length"]) if str(f.get("length", "")).replace(".", "", 1).isdigit() else None})
    return out


def build(ident: str):
    time.sleep(0.25)
    d = common.get_json(f"https://archive.org/metadata/{urllib.parse.quote(ident)}")
    if not d or "metadata" not in d:
        return None
    m = d["metadata"]
    lic = common.first(m.get("licenseurl"))
    if not common.is_public_domain(lic):
        return None
    title = common.strip_html(str(common.first(m.get("title")) or ""), 300)
    if not title:
        return None
    streams = pick_streams(d.get("files", []), ident)
    if not streams:
        return None
    runtime = streams[0].pop("_len", None)
    for s in streams[1:]:
        s.pop("_len", None)
    if not common.url_ok(streams[0]["url"], want=("video/", "application/octet-stream")):
        common.log(f"  verify failed: {ident}")
        return None
    streams = [s for s in streams if s is streams[0] or common.url_ok(s["url"], want=("video/", "application/octet-stream"))]
    desc = common.strip_html(str(common.first(m.get("description")) or ""))
    tid = re.sub(r"[^A-Za-z0-9._:-]", "_", "ia-" + ident)[:128]
    return {
        "id": tid, "type": "movie", "title_en": title,
        "year": common.year_of(m),
        "description_en": desc,
        "genres": common.map_genres(m.get("subject")),
        "poster": common.ia_img(ident),
        "added_at": common.ia_datetime(common.first(m.get("publicdate"))),
        "runtime_min": max(1, round(runtime / 60)) if runtime and runtime < 60000 else None,
        "license": common.license_obj(lic, ident),
        "streams": streams,
    }


def main():
    docs = search()
    common.log(f"{len(docs)} candidates from advancedsearch")
    ids = [x["identifier"] for x in docs]
    results = []
    with ThreadPoolExecutor(max_workers=4) as ex:
        for i, t in enumerate(ex.map(build, ids)):
            results.append(t)
            if (i + 1) % 25 == 0:
                common.log(f"  {i + 1}/{len(ids)} processed, {sum(1 for r in results if r)} usable")
    titles, seen = [], set()
    for t in results:                     # results keep download order
        if not t:
            continue
        key = (norm_title(t["title_en"]), t.get("year"))
        if key in seen:                   # IA often has several uploads of one film: keep the most downloaded
            continue
        seen.add(key)
        titles.append(t)
        if len(titles) >= TARGET:
            break
    used = {g for t in titles for g in t.get("genres", [])}
    feed = {
        "rass_feed_version": 1, "name": "Internet Archive films (test)", "test": True,
        "description": "Test source: public-domain / CC0 feature films from the Internet Archive feature_films collection, most downloaded first.",
        "updated_at": common.now_z(),
        "featured": [], "top10": [],
        "genres": common.genre_defs(used), "titles": titles,
    }
    # No backdrops here, so no featured list; top10 = the 10 most downloaded.
    feed["top10"] = [t["id"] for t in titles[:10]]
    del feed["featured"]
    common.validate_and_write(feed, "archive-films.json")


if __name__ == "__main__":
    main()
