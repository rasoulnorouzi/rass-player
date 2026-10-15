"""hls-test.json: two entries that exercise the player, not the catalog.

1. A multi-variant HLS test stream (Mux test stream, Big Buck Bunny, CC BY) -> quality menu (240p..1080p variants).
   The master playlist is fetched and its variants are counted; the entry is dropped if it has fewer than 3.
2. A movie with two streams where the first host is deliberately invalid (.invalid is a reserved TLD that never
   resolves) -> failover to the second, working stream.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

MASTER = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
INVALID = "https://invalid.example/rass-test/does-not-exist.mp4"
BBB_ITEM = "BigBuckBunnyFULLHD60FPS"
BBB_MP4 = "Big Buck Bunny - FULL HD 60FPS.mp4"
CC30 = "https://creativecommons.org/licenses/by/3.0/"


def main():
    status, _, body = common.http(MASTER)
    text = body.decode("utf-8", "replace")
    variants = re.findall(r"#EXT-X-STREAM-INF:.*RESOLUTION=(\d+)x(\d+)", text)
    common.log(f"HLS master status {status}, {len(variants)} variants: {[f'{w}x{h}' for w, h in variants]}")
    titles = []
    lic = {"name": "CC BY 3.0", "url": CC30, "attribution": "(c) copyright 2008, Blender Foundation | www.bigbuckbunny.org",
           "source_url": "https://archive.org/details/" + BBB_ITEM}
    poster = common.ia_img(BBB_ITEM)
    mp4_url = common.ia_download_url(BBB_ITEM, BBB_MP4)
    if status == 200 and len(variants) >= 3:
        titles.append({
            "id": "test-hls-quality-menu", "type": "movie",
            "title_en": "[TEST] HLS quality menu (Big Buck Bunny)", "title_fa": "[آزمایشی] منوی کیفیت HLS",
            "year": 2008, "runtime_min": 10, "genres": ["animation"], "poster": poster,
            "description_en": f"Test content. Adaptive HLS stream with {len(variants)} variants (240p to 1080p) from a public test server; use it to try the quality menu.",
            "description_fa": f"محتوای آزمایشی. پخش تطبیقی HLS با {len(variants)} کیفیت (از ۲۴۰ تا ۱۰۸۰) از یک سرور آزمایشی عمومی؛ برای امتحان منوی کیفیت.",
            "license": lic, "added_at": "2026-10-15",
            "streams": [{"url": MASTER, "type": "hls", "quality": "auto", "label": "HLS (auto)", "priority": 0,
                         "video_codec": "h264"}],
            "x_test_note": "multi-quality HLS",
        })
    else:
        common.log("HLS test stream unusable; entry dropped")
    if common.url_ok(mp4_url, want=("video/", "application/octet-stream")):
        titles.append({
            "id": "test-failover", "type": "movie",
            "title_en": "[TEST] Failover (first source is invalid)", "title_fa": "[آزمایشی] تعویض خودکار منبع",
            "year": 2008, "runtime_min": 10, "genres": ["animation"], "poster": poster,
            "description_en": "Test content. The first stream points to a host that does not exist (invalid.example); the player should fail over to the second stream by itself.",
            "description_fa": "محتوای آزمایشی. نشانی منبع اول وجود ندارد (invalid.example)؛ پخش‌کننده باید خودش به منبع دوم برود.",
            "license": lic, "added_at": "2026-10-15",
            "streams": [
                {"url": INVALID, "type": "mp4", "quality": "1080p", "priority": 0, "label": "منبع خراب (عمدی)"},
                {"url": mp4_url, "type": "mp4", "quality": "1080p", "priority": 1, "label": "Archive.org (backup)", "video_codec": "h264"},
            ],
            "x_test_note": "failover: first URL invalid on purpose",
        })
    feed = {
        "rass_feed_version": 1, "name": "Player tests: HLS and failover", "test": True,
        "description": "Test source for player features: multi-quality HLS and stream failover. Not real catalog content.",
        "updated_at": common.now_z(), "genres": common.genre_defs({"animation"}), "titles": titles,
    }
    common.validate_and_write(feed, "hls-test.json")


if __name__ == "__main__":
    main()
