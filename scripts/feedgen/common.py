"""Shared helpers for the test-feed generators (stdlib only, plus jsonschema for validation).

Run scripts with the project venv in isolated mode:  .venv/bin/python -I scripts/feedgen/<script>.py
(-I drops the script directory from sys.path, so common.py is loaded by path below.)
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from html import unescape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "app/src/main/assets/test-feeds"
SUBS_DIR = OUT_DIR / "subs"
SCHEMA_PATH = ROOT / "docs/feed-schema.json"
UA = "RassPlayer-feedgen/1.0 (personal test-feed generator; offline use)"

# Small shared genre vocabulary (ids follow docs/feed-spec.md section 3.1).
GENRES = {
    "action": ("اکشن", "Action"),
    "adventure": ("ماجراجویی", "Adventure"),
    "animation": ("انیمیشن", "Animation"),
    "comedy": ("کمدی", "Comedy"),
    "crime": ("جنایی", "Crime"),
    "documentary": ("مستند", "Documentary"),
    "drama": ("درام", "Drama"),
    "family": ("خانوادگی", "Family"),
    "fantasy": ("فانتزی", "Fantasy"),
    "history": ("تاریخی", "History"),
    "horror": ("ترسناک", "Horror"),
    "music": ("موزیکال", "Music"),
    "mystery": ("معمایی", "Mystery"),
    "romance": ("عاشقانه", "Romance"),
    "science-fiction": ("علمی‌تخیلی", "Science Fiction"),
    "thriller": ("هیجان‌انگیز", "Thriller"),
    "war": ("جنگی", "War"),
    "western": ("وسترن", "Western"),
}

# Keyword (lower-case substring of a subject tag) -> genre id. First hits win, max 3 genres per title.
SUBJECT_MAP = [
    ("animat", "animation"), ("cartoon", "animation"),
    ("comedy", "comedy"), ("humor", "comedy"), ("sitcom", "comedy"), ("comedies", "comedy"),
    ("horror", "horror"), ("monster", "horror"), ("zombie", "horror"),
    ("sci-fi", "science-fiction"), ("science fiction", "science-fiction"), ("scifi", "science-fiction"),
    ("western", "western"), ("cowboy", "western"),
    ("crime", "crime"), ("gangster", "crime"), ("detective", "crime"), ("noir", "crime"),
    ("mystery", "mystery"),
    ("thriller", "thriller"), ("suspense", "thriller"),
    ("romance", "romance"), ("love", "romance"),
    ("war", "war"), ("military", "war"),
    ("documentary", "documentary"), ("newsreel", "documentary"),
    ("adventure", "adventure"), ("jungle", "adventure"),
    ("action", "action"), ("martial", "action"),
    ("fantasy", "fantasy"),
    ("musical", "music"), ("music", "music"),
    ("histor", "history"),
    ("family", "family"), ("children", "family"),
    ("drama", "drama"),
]


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def http(url: str, *, headers: dict | None = None, timeout: int = 40, retries: int = 3, method: str = "GET"):
    """Return (status, final_url, body_bytes). Retries on network errors / 5xx."""
    h = {"User-Agent": UA}
    h.update(headers or {})
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=h, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.geturl(), r.read() if method != "HEAD" else b""
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                return e.code, url, b""
            last = e
        except Exception as e:  # noqa: BLE001 - network errors of any kind
            last = e
        time.sleep(1.5 * (attempt + 1))
    log(f"  ! giving up on {url}: {last}")
    return 0, url, b""


def get_json(url: str, **kw):
    status, _, body = http(url, **kw)
    if status != 200:
        return None
    return json.loads(body)


def url_ok(url: str, *, want: tuple[str, ...] = ()) -> bool:
    """Verify with a 1-byte range GET (follows redirects). `want`: acceptable content-type prefixes."""
    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-1023"})
            with urllib.request.urlopen(req, timeout=40) as r:
                ctype = (r.headers.get("Content-Type") or "").lower()
                r.read(1024)
                if r.status not in (200, 206):
                    return False
                if want and not any(ctype.startswith(w) for w in want):
                    log(f"  ! {url}: unexpected content-type {ctype}")
                    return False
                return True
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                return False
            last = e
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(1.5 * (attempt + 1))
    log(f"  ! {url}: {last}")
    return False


def ia_download_url(identifier: str, filename: str) -> str:
    return f"https://archive.org/download/{urllib.parse.quote(identifier)}/{urllib.parse.quote(filename)}"


def ia_img(identifier: str) -> str:
    return f"https://archive.org/services/img/{urllib.parse.quote(identifier)}"


def first(v):
    if isinstance(v, list):
        return v[0] if v else None
    return v


def as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def strip_html(s: str, limit: int = 900) -> str | None:
    if not s:
        return None
    s = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</li>", "\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = unescape(s).replace("\xa0", " ")
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    s = re.sub(r"\n\s*\n+", "\n", s).strip()
    s = "\n".join(line.strip() for line in s.splitlines())
    if not s:
        return None
    if len(s) > limit:
        cut = s[:limit].rsplit(" ", 1)[0].rstrip(",.;:- ")
        s = cut + "…"
    return s


def license_obj(license_url: str, source_item: str, attribution: str | None = None, name: str | None = None) -> dict:
    u = license_url
    if name is None:
        if "publicdomain/zero" in u:
            name = "CC0 1.0"
        elif "/publicdomain/mark" in u:
            name = "Public Domain Mark 1.0"
        elif "publicdomain" in u:
            name = "Public domain"
        else:
            m = re.search(r"/licenses/([a-z\-]+)/([0-9.]+)", u)
            name = f"CC {m.group(1).upper()} {m.group(2)}" if m else "See license URL"
    lic = {"name": name, "url": u.replace("http://", "https://") if u.startswith("http://creativecommons") else u,
           "source_url": f"https://archive.org/details/{urllib.parse.quote(source_item)}"}
    if attribution:
        lic["attribution"] = attribution
    return lic


def is_public_domain(u: str | None) -> bool:
    return bool(u) and ("/publicdomain/" in u)  # PD mark, CC0, and the legacy /licenses/publicdomain/ URL


def ia_datetime(s: str | None) -> str | None:
    """IA 'publicdate' ('2011-07-01 21:10:23') -> RFC 3339 UTC."""
    if not s:
        return None
    m = re.match(r"(\d{4}-\d\d-\d\d)[ T](\d\d:\d\d:\d\d)", s)
    return f"{m.group(1)}T{m.group(2)}Z" if m else None


def year_of(meta: dict) -> int | None:
    for k in ("year", "date"):
        m = re.match(r"\s*(\d{4})", str(first(meta.get(k)) or ""))
        if m and 1870 <= int(m.group(1)) <= 2100:
            return int(m.group(1))
    return None


def map_genres(subjects) -> list[str]:
    tags = []
    for s in as_list(subjects):
        tags += [t.strip().lower() for t in re.split(r"[;,]", str(s)) if t.strip()]
    out: list[str] = []
    for key, gid in SUBJECT_MAP:
        if gid not in out and any(key in t for t in tags):
            out.append(gid)
    return out[:3]


def genre_defs(used: set[str]) -> list[dict]:
    return [{"id": g, "name_fa": GENRES[g][0], "name_en": GENRES[g][1]} for g in GENRES if g in used]


def now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def clean(d):
    """Drop None values recursively."""
    if isinstance(d, dict):
        return {k: clean(v) for k, v in d.items() if v is not None}
    if isinstance(d, list):
        return [clean(v) for v in d]
    return d


# ---------------------------------------------------------------------------------------------------------------
# Content tags (R13). Python port of the app's keyword/genre rules (domain/filter/ContentFilter.kt) plus the manual
# list content_tags.txt. Nothing is removed from a feed; titles only get content_rating / content_warnings.
# ---------------------------------------------------------------------------------------------------------------
TAGS_PATH = Path(__file__).resolve().parent / "content_tags.txt"
BLOCKED_GENRES = {
    "erotic", "erotica", "adult", "adults", "adult film", "sexploitation", "exploitation", "porn", "porno",
    "pornography", "xxx", "softcore", "hardcore",
    "اروتیک", "بزرگسالان", "بزرگسال", "سکسی", "پورن", "پورنو", "عریان",
}
NUDITY_WORDS = {"nude", "nudes", "nudity", "برهنه", "برهنگی", "عریان"}
DEFAULT_KEYWORDS = [
    "sex", "sexy", "sexual", "sexually", "sexuality", "sexploitation", "erotic", "erotica", "erotically",
    "nude", "nudes", "nudity", "porn", "porno", "pornographic", "pornography", "adult film",
    "adult films", "adult movie", "exploitation film", "exploitation films", "sex hygiene", "striptease", "stripper",
    "سکسی", "سکس", "جنسی", "برهنه", "برهنگی", "عریان", "اروتیک", "بزرگسالان", "پورن", "پورنو",
]
WARNINGS = ("sexual", "nudity", "gore", "disturbing", "violence", "horror", "drugs", "language")


def _norm(s: str) -> str:
    s = s.lower().translate({ord("ي"): "ی", ord("ى"): "ی", ord("ك"): "ک", 0x200C: " ", 0x200D: " ", 0x640: " "})
    s = "".join(c for c in s if not (0x64B <= ord(c) <= 0x65F or ord(c) == 0x670))
    return re.sub(r"\s+", " ", s).strip()


def _word_re(k: str):
    parts = [re.escape(p) for p in _norm(k).split(" ")]
    # letters/digits boundary (underscore counts as a separator), like the Kotlin \p{L}\p{N} lookarounds
    return re.compile(r"(?<![^\W_])" + r"[^\w]+".join(parts) + r"(?![^\W_])")


_KW = [(k, _word_re(k)) for k in DEFAULT_KEYWORDS]


def auto_warnings(texts: list[str], genre_names: list[str] = ()) -> list[str]:
    """Keyword / genre rules -> ['sexual'] or ['nudity', 'sexual'] (empty when nothing matches)."""
    out: list[str] = []
    for g in genre_names:
        if _norm(g) in BLOCKED_GENRES:
            out.append("sexual")
    for t in texts:
        n = _norm(t or "")
        for k, rx in _KW:
            if rx.search(n):
                out.append("nudity" if _norm(k) in NUDITY_WORDS else "sexual")
    return sorted(set(out), key=WARNINGS.index)


def load_manual_tags() -> dict[str, tuple[str | None, list[str]]]:
    tags: dict[str, tuple[str | None, list[str]]] = {}
    for line in TAGS_PATH.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        parts += [""] * (4 - len(parts))
        warns = [w for w in re.split(r"[,\s]+", parts[2]) if w]
        bad = [w for w in warns if w not in WARNINGS]
        assert not bad, f"unknown warning {bad} in content_tags.txt line: {line}"
        tags[parts[0]] = (parts[1] or None, warns)
    return tags


def apply_content_tags(feed: dict) -> dict:
    """Adds content_rating / content_warnings to every title (and episode) of a feed dict, in place."""
    manual = load_manual_tags()
    names = {g["id"]: [g.get("name_en", ""), g.get("name_fa", ""), g["id"]] for g in feed.get("genres", [])}

    def tag(obj: dict, key: str | None, genres: list[str]):
        rating, warns = manual.get(key, (None, [])) if key else (None, [])
        texts = [obj.get(k) for k in ("title_fa", "title_en", "original_title", "description_fa", "description_en")]
        auto = auto_warnings([t for t in texts if t], [n for g in genres for n in names.get(g, [g])])
        all_w = sorted(set(warns) | set(auto), key=WARNINGS.index)
        if rating:
            obj["content_rating"] = rating
        if all_w:
            obj["content_warnings"] = all_w

    for t in feed["titles"]:
        tag(t, t["id"], t.get("genres", []))
        for s in t.get("seasons", []):
            for e in s.get("episodes", []):
                tag(e, None, [])
    return feed


def validate_and_write(feed: dict, filename: str) -> Path:
    from jsonschema import Draft202012Validator  # from the project .venv

    feed = clean(apply_content_tags(feed))
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    errors = sorted(Draft202012Validator(schema).iter_errors(feed), key=lambda e: list(e.path))
    if errors:
        for e in errors[:20]:
            log("SCHEMA:", "/".join(map(str, e.path)), "-", e.message[:200])
        raise SystemExit(f"{filename}: {len(errors)} schema error(s)")
    # Cross-checks the schema cannot express (spec section 10).
    ids = [t["id"] for t in feed["titles"]]
    assert len(ids) == len(set(ids)), "duplicate title ids"
    gids = {g["id"] for g in feed.get("genres", [])}
    for t in feed["titles"]:
        for g in t.get("genres", []):
            assert g in gids, f"unknown genre {g} in {t['id']}"
    for k in ("featured", "top10"):
        for i in feed.get(k, []):
            assert i in set(ids), f"{k}: unknown id {i}"
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    p = OUT_DIR / filename
    p.write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    log(f"wrote {p} ({p.stat().st_size / 1024:.0f} KB, {len(ids)} titles)")
    return p
