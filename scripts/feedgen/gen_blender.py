"""blender.json: Blender Foundation open movies (Creative Commons BY), played from their Internet Archive mirrors.

download.blender.org only offers zipped / .mov / .mkv files now (no direct MP4), so each stream is an
archive.org /download/ MP4. Every URL is verified with a range GET; streams or titles that fail are dropped.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

CC25 = "https://creativecommons.org/licenses/by/2.5/"
CC30 = "https://creativecommons.org/licenses/by/3.0/"
CC40 = "https://creativecommons.org/licenses/by/4.0/"

# (archive item, file name, quality by real video height, codec-verified-with-ffprobe, label)
MOVIES = [
    {
        "id": "blender-big-buck-bunny", "title_en": "Big Buck Bunny", "title_fa": "باک بانی بزرگ",
        "year": 2008, "runtime": 10, "genres": ["animation", "comedy", "family"], "imdb": "tt1254207",
        "director": "Sacha Goedegebure", "poster_item": "BigBuckBunnyFULLHD60FPS",
        "license": ("CC BY 3.0", CC30, "(c) copyright 2008, Blender Foundation | www.bigbuckbunny.org"),
        "license_item": "BigBuckBunnyFULLHD60FPS",
        "streams": [("BigBuckBunnyFULLHD60FPS", "Big Buck Bunny - FULL HD 60FPS.mp4", "1080p", "Full HD 60fps"),
                    ("BigBuckBunny_124", "Content/big_buck_bunny_720p_surround.mp4", "360p", "360p")],
        "desc_fa": "خرگوشی درشت‌هیکل و آرام صبح قشنگی را در جنگل شروع می‌کند، اما سه جونده‌ی شرور مزاحمش می‌شوند و او برای انتقام نقشه می‌کشد.",
        "desc_en": "A big, gentle rabbit starts a lovely morning in the forest until three bullying rodents spoil it, and he plans his revenge.",
        "subtitles": [
            ("fa", "bbb.fa.cp1256.srt", "srt", "فارسی (Windows-1256، آزمایشی)"),
            ("en", "bbb.en.vtt", "vtt", "English (VTT)"),
            ("en", "bbb.en.srt", "srt", "English (SRT)"),
            ("en", "bbb.en.late2s.srt", "srt", "English (2 s late)"),
            ("ru", "bbb.ru.srt", "srt", "Русский (should be hidden)"),
        ],
    },
    {
        "id": "blender-elephants-dream", "title_en": "Elephants Dream", "title_fa": "رؤیای فیل‌ها",
        "year": 2006, "runtime": 11, "genres": ["animation", "science-fiction", "fantasy"], "imdb": "tt0807840",
        "director": "Bassam Kurdali", "poster_item": "ElephantsDream_628",
        "license": ("CC BY 2.5", CC25, "(c) copyright 2006, Blender Foundation | Netherlands Media Art Institute | www.elephantsdream.org"),
        "license_item": "ElephantsDream_628",
        "streams": [("ElephantsDream_628", "ElephantsDream_720p_DivXPlus_512kb.mp4", "240p", "240p")],
        "desc_fa": "دو مرد با روحیه‌هایی کاملاً متفاوت در ساختمانی عظیم و ماشینی سرگردان می‌شوند؛ نخستین فیلم کوتاه متن‌باز دنیا که فقط با نرم‌افزار آزاد ساخته شد.",
        "desc_en": "Two men with very different outlooks wander through a vast, mechanical building. The first open movie made entirely with free software.",
        "subtitles": [],
    },
    {
        "id": "blender-sintel", "title_en": "Sintel", "title_fa": "سینتل",
        "year": 2010, "runtime": 15, "genres": ["animation", "fantasy", "adventure"], "imdb": "tt1727587",
        "director": "Colin Levy", "poster_item": "sintel_202604",
        "license": ("CC BY 3.0", CC30, "(c) copyright Blender Foundation | durian.blender.org"),
        "license_item": "sintel_202604",
        "streams": [("sintel_202604", "Sintel.mp4", "1080p", "1080p")],
        "desc_fa": "دختری جوان به نام سینتل در جست‌وجوی اژدهای کوچکی که با او دوست شده بود، سفری سخت و خطرناک را آغاز می‌کند.",
        "desc_en": "A young woman named Sintel sets out on a long and dangerous journey to find the baby dragon she once befriended.",
        "subtitles": [
            ("fa", "sintel.fa.srt", "srt", "فارسی (UTF-8 با BOM، آزمایشی)"),
            ("en", "sintel.en.srt", "srt", "English"),
        ],
    },
    {
        "id": "blender-tears-of-steel", "title_en": "Tears of Steel", "title_fa": "اشک‌های فولاد",
        "year": 2012, "runtime": 12, "genres": ["science-fiction", "action", "animation"], "imdb": "tt2285752",
        "director": "Ian Hubert", "poster_item": "Tears-of-Steel",
        "license": ("CC BY 3.0", CC30, "(CC) Blender Foundation | mango.blender.org"),
        "license_item": "Tears-of-Steel",
        "streams": [("Tears-of-Steel", "tears_of_steel_1080p.mp4", "360p", "360p")],
        "desc_fa": "گروهی از دانشمندان و رزمندگان در آمستردامِ آینده می‌کوشند با گذشته‌ای تلخ روبه‌رو شوند و جلوی تهدید ربات‌ها را بگیرند.",
        "desc_en": "A group of scientists and fighters in a future Amsterdam try to face a painful past and stop a robot threat. Live action with visual effects.",
        "subtitles": [],
    },
    {
        "id": "blender-cosmos-laundromat", "title_en": "Cosmos Laundromat: First Cycle", "title_fa": "لباسشویی کیهانی",
        "year": 2015, "runtime": 12, "genres": ["animation", "fantasy", "comedy"],
        "director": "Mathieu Auvray", "poster_item": "CosmosLaundromatFirstCycle",
        "license": ("CC BY 4.0", CC40, "(CC) Blender Foundation | gooseberry.blender.org"),
        "license_item": "CosmosLaundromatFirstCycle",
        "streams": [("CosmosLaundromatFirstCycle", "Cosmos Laundromat - First Cycle (1080p).mp4", "1080p", "1080p")],
        "desc_fa": "گوسفندی افسرده در جزیره‌ای متروک با فروشنده‌ای عجیب روبه‌رو می‌شود که هدیه‌ای باورنکردنی به او پیشنهاد می‌کند.",
        "desc_en": "On a desolate island, a depressed sheep meets a quirky salesman who offers him the gift of a lifetime.",
        "subtitles": [],
    },
    {
        "id": "blender-spring", "title_en": "Spring", "title_fa": "بهار",
        "year": 2019, "runtime": 8, "genres": ["animation", "fantasy", "adventure"],
        "director": "Andy Goralczyk", "poster_item": "springopenmovie",
        "license": ("CC BY 4.0", CC40, "(CC) Blender Foundation | cloud.blender.org/spring"),
        "license_item": "springopenmovie",
        "streams": [("springopenmovie", "springopenmovie.mp4", "1080p", "1080p")],
        "desc_fa": "دختری چوپان و سگش در کوهستان با ارواح باستانی طبیعت روبه‌رو می‌شوند و چرخه‌ی فصل‌ها به خطر می‌افتد.",
        "desc_en": "A shepherd girl and her dog face ancient spirits of nature in the mountains as the cycle of life is put at risk.",
        "subtitles": [],
    },
]


def main():
    subs_dir = common.SUBS_DIR
    titles, used, excluded = [], set(), []
    for m in MOVIES:
        streams = []
        for item, fname, quality, label in m["streams"]:
            url = common.ia_download_url(item, fname)
            if common.url_ok(url, want=("video/", "application/octet-stream")):
                streams.append({"url": url, "type": "mp4", "quality": quality, "priority": 0,
                                "label": label, "video_codec": "h264"})
                common.log(f"ok   {url}")
            else:
                common.log(f"FAIL {url}")
        if not streams:
            excluded.append(m["title_en"])
            continue
        poster = common.ia_img(m["poster_item"])
        if not common.url_ok(poster, want=("image/",)):
            poster = None
        subs = []
        for lang, fname, fmt, label in m["subtitles"]:
            assert (subs_dir / fname).exists(), f"missing subtitle {fname}: run gen_test_subtitles.py first"
            subs.append({"lang": lang, "url": f"asset:///test-feeds/subs/{fname}", "format": fmt, "label": label})
        lname, lurl, attribution = m["license"]
        t = {
            "id": m["id"], "type": "movie", "title_fa": m["title_fa"], "title_en": m["title_en"],
            "year": m["year"], "genres": m["genres"], "runtime_min": m["runtime"],
            "description_fa": m["desc_fa"], "description_en": m["desc_en"],
            "poster": poster, "backdrop": poster,
            "cast": [{"name": m["director"], "role": "Director"}],
            "license": {"name": lname, "url": lurl, "attribution": attribution,
                        "source_url": f"https://archive.org/details/{m['license_item']}"},
            "ids": {"imdb": m.get("imdb")} if m.get("imdb") else None,
            "streams": streams, "subtitles": subs or None,
            "added_at": "2026-10-15",
        }
        titles.append(t)
        used.update(m["genres"])
    feed = {
        "rass_feed_version": 1, "name": "Blender Open Movies (test)", "test": True,
        "description": "Test source: Blender Foundation open movies (Creative Commons BY), streamed as MP4 from Internet Archive mirrors. Includes Persian/English test subtitles.",
        "updated_at": common.now_z(),
        "featured": [t["id"] for t in titles if t["id"] in ("blender-sintel", "blender-big-buck-bunny", "blender-spring", "blender-tears-of-steel")],
        "top10": [t["id"] for t in titles],
        "genres": common.genre_defs(used), "titles": titles,
    }
    common.validate_and_write(feed, "blender.json")
    if excluded:
        common.log("excluded (no working MP4):", ", ".join(excluded))


if __name__ == "__main__":
    main()
