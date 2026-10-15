"""Offline re-tagging of the already generated test feeds (no network): reads app/src/main/assets/test-feeds/*.json,
applies content_tags.txt + the keyword/genre rules (common.apply_content_tags), validates, rewrites.

Use it after editing content_tags.txt. The generators apply the same step when they write a feed.
Run:  .venv/bin/python -I scripts/feedgen/tag_feeds.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


def strip_tags(feed: dict) -> None:
    for t in feed["titles"]:
        for o in [t] + [e for s in t.get("seasons", []) for e in s.get("episodes", [])]:
            o.pop("content_rating", None)
            o.pop("content_warnings", None)


def main():
    known = set()
    for name in ("blender.json", "archive-films.json", "archive-series.json", "hls-test.json"):
        feed = json.loads((common.OUT_DIR / name).read_text(encoding="utf-8"))
        strip_tags(feed)
        known |= {t["id"] for t in feed["titles"]}
        common.validate_and_write(feed, name)
        tagged = [t for t in feed["titles"] if t.get("content_warnings") or t.get("content_rating")]
        common.log(f"  {name}: {len(tagged)} of {len(feed['titles'])} titles tagged")
    unknown = set(common.load_manual_tags()) - known
    if unknown:
        common.log("  tags for ids not in any feed:", sorted(unknown))


if __name__ == "__main__":
    main()
