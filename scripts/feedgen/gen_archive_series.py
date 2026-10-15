"""archive-series.json: a few curated public-domain TV series from the Internet Archive.

The Archive stores one item per episode, so each series is defined here by an identifier pattern (looked up with
the official advancedsearch API, restricted to public-domain / CC0 licenseurl) plus an ordering rule. Every
episode needs a PD license and an MP4; others are dropped. The number of episodes is capped and, above 12,
split into two seasons so season switching can be tested. Season grouping is synthetic (test data), not the
broadcast season structure.
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
from gen_archive_films import pick_streams  # noqa: E402

SERIES = [
    {
        "id": "ia-series-beverly-hillbillies", "title_en": "The Beverly Hillbillies", "title_fa": "هیل‌بیلی‌های بورلی هیلز",
        "year": 1962, "genres": ["comedy", "family"], "pattern": "Beverly_Hillbillies_Ep*", "order": r"_Ep(\d+)_?", "max": 20,
        "desc_en": "Sitcom: a mountain family strikes oil and moves to Beverly Hills. Episodes ordered by the Ep number in the item id.",
        "desc_fa": "کمدی موقعیت: خانواده‌ای کوه‌نشین در زمین خود نفت پیدا می‌کنند و به بورلی هیلز می‌روند. قسمت‌ها بر اساس شماره‌ی موجود در شناسه مرتب شده‌اند.",
    },
    {
        "id": "ia-series-robin-hood", "title_en": "The Adventures of Robin Hood", "title_fa": "ماجراهای رابین هود",
        "year": 1955, "genres": ["adventure", "action", "family"], "pattern": "Robin_Hood_* AND NOT identifier:*librivox*", "order": r"[Rr]obin_[Hh]ood_(\d+)", "max": 14,
        "desc_en": "The 1950s British adventure series about the outlaw of Sherwood Forest. Episodes ordered by the number in the item id.",
        "desc_fa": "مجموعه‌ی ماجراجویانه‌ی دهه‌ی ۱۹۵۰ درباره‌ی یاغی جنگل شروود. قسمت‌ها بر اساس شماره‌ی موجود در شناسه مرتب شده‌اند.",
    },
    {
        "id": "ia-series-sherlock-holmes-1954", "title_en": "Sherlock Holmes (1954)", "title_fa": "شرلوک هولمز (۱۹۵۴)",
        "year": 1954, "genres": ["mystery", "crime", "drama"], "pattern": "SherlockHolmes-TheCase*", "order": None, "max": 12,
        "desc_en": "Black-and-white detective series (1954). No episode numbers exist, so episodes are ordered alphabetically by title; one season.",
        "desc_fa": "مجموعه‌ی کارآگاهی سیاه‌وسفید (۱۹۵۴). چون شماره‌ی قسمت وجود ندارد، قسمت‌ها بر اساس حروف الفبای عنوان مرتب شده‌اند؛ یک فصل.",
    },
    {
        "id": "ia-series-lucy-show", "title_en": "The Lucy Show", "title_fa": "نمایش لوسی",
        "year": 1962, "genres": ["comedy"], "pattern": "TLS_Lucy_*", "order": None, "max": 14,
        "desc_en": "Classic sitcom starring Lucille Ball. No episode numbers, so episodes are ordered alphabetically by title and split into two seasons for testing.",
        "desc_fa": "کمدی موقعیت کلاسیک با بازی لوسیل بال. چون شماره‌ی قسمت وجود ندارد، قسمت‌ها بر اساس حروف الفبا مرتب و برای آزمایش به دو فصل تقسیم شده‌اند.",
    },
]
MIN_EPISODES = 6


def find_items(pattern: str) -> list[str]:
    q = f"identifier:{pattern} AND mediatype:movies AND licenseurl:*publicdomain*"
    qs = urllib.parse.urlencode([("q", q), ("fl[]", "identifier"), ("sort[]", "identifier asc"), ("rows", 200), ("output", "json")])
    data = common.get_json("https://archive.org/advancedsearch.php?" + qs)
    return [d["identifier"] for d in data["response"]["docs"]]


def episode_meta(ident: str):
    time.sleep(0.25)
    d = common.get_json(f"https://archive.org/metadata/{urllib.parse.quote(ident)}")
    if not d or "metadata" not in d:
        return None
    m = d["metadata"]
    lic = common.first(m.get("licenseurl"))
    if not common.is_public_domain(lic):
        return None
    streams = pick_streams(d.get("files", []), ident)
    if not streams or not common.url_ok(streams[0]["url"], want=("video/", "application/octet-stream")):
        return None
    runtime = streams[0].pop("_len", None)
    for s in streams[1:]:
        s.pop("_len", None)
    title = common.strip_html(str(common.first(m.get("title")) or ident), 300)
    return {"ident": ident, "title": title, "lic": lic, "streams": streams, "runtime": runtime,
            "desc": common.strip_html(str(common.first(m.get("description")) or ""), 600),
            "added": common.ia_datetime(common.first(m.get("publicdate")))}


def clean_ep_title(t: str, series_words: list[str]) -> str:
    t = re.sub(r"(?i)^(the )?beverly hillbillies\s*(ep\d+)?\s*:?\s*", "", t)
    t = re.sub(r"(?i)^sherlock holmes\s*-\s*", "", t)
    t = re.sub(r"^Robin_Hood_\d+_?-?_?", "", t).replace("_", " ")
    return t.strip() or "Episode"


def main():
    titles, used, report = [], set(), []
    for s in SERIES:
        ids = find_items(s["pattern"])
        if s["order"]:
            rx = re.compile(s["order"])
            numbered = [(int(rx.search(i).group(1)), i) for i in ids if rx.search(i)]
            numbered.sort()
            ordered = [i for _, i in numbered]
        else:
            ordered = sorted(ids)
        # fetch a few more than needed so dropped items don't starve the series
        want = ordered[: s["max"] + 4]
        with ThreadPoolExecutor(max_workers=4) as ex:
            metas = [m for m in ex.map(episode_meta, want) if m]
        metas = metas[: s["max"]]
        if len(metas) < MIN_EPISODES:
            report.append(f"{s['title_en']}: only {len(metas)} usable episodes -> excluded")
            continue
        per_season = (len(metas) + 1) // 2 if len(metas) > 12 else len(metas)
        seasons: dict[int, list] = {}
        for n, m in enumerate(metas):
            sn = n // per_season + 1
            ep_no = n % per_season + 1
            ep = {
                "id": re.sub(r"[^A-Za-z0-9._:-]", "_", m["ident"])[:128], "number": ep_no,
                "title_en": clean_ep_title(m["title"], []), "description_en": m["desc"],
                "still": common.ia_img(m["ident"]),
                "runtime_min": max(1, round(m["runtime"] / 60)) if m["runtime"] and m["runtime"] < 60000 else None,
                "added_at": m["added"],
                "streams": m["streams"],
            }
            seasons.setdefault(sn, []).append(ep)
        lic_urls = {m["lic"] for m in metas}
        first_lic = metas[0]["lic"]
        lic = common.license_obj(first_lic, metas[0]["ident"])
        lic["name"] = "Public domain" if not all("zero" in u for u in lic_urls) else "CC0 1.0"
        lic["attribution"] = "Each episode: public-domain license as set on its Internet Archive item"
        used.update(s["genres"])
        titles.append({
            "id": s["id"], "type": "series", "title_en": s["title_en"], "title_fa": s["title_fa"], "year": s["year"],
            "genres": s["genres"], "description_en": s["desc_en"], "description_fa": s["desc_fa"],
            "poster": common.ia_img(metas[0]["ident"]), "license": lic,
            "added_at": metas[0]["added"],
            "seasons": [{"number": sn, "episodes": eps} for sn, eps in sorted(seasons.items())],
        })
        report.append(f"{s['title_en']}: {len(metas)} episodes in {len(seasons)} season(s)")
    feed = {
        "rass_feed_version": 1, "name": "Internet Archive series (test)", "test": True,
        "description": "Test source: curated public-domain TV series from the Internet Archive (season split is synthetic).",
        "updated_at": common.now_z(),
        "top10": [t["id"] for t in titles],
        "genres": common.genre_defs(used), "titles": titles,
    }
    common.validate_and_write(feed, "archive-series.json")
    for r in report:
        common.log(" ", r)


if __name__ == "__main__":
    main()
