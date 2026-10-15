# scripts/feedgen: preset test-feed generators

Generates the **test feeds** bundled in `app/src/main/assets/test-feeds/` (R12). Everything is produced
**offline, by hand, on the developer machine**; the app only reads the generated JSON and never calls the
Internet Archive or any other site's API (CLAUDE.md section 2). Only openly licensed / public-domain media is used.

```
scripts/feedgen/generate_all.sh          # all of it, ~4-6 min, needs network
.venv/bin/python -I scripts/feedgen/gen_blender.py    # or run one script
```

Python 3.13, stdlib only plus `jsonschema` from the project `.venv` (`python3 -m venv .venv && .venv/bin/pip install jsonschema`).
Every feed is validated against `docs/feed-schema.json` before it is written, plus the cross-checks the schema
cannot express (unique ids, known genre/featured ids). Shared code: `common.py`.

| Script | Output | Content |
|---|---|---|
| `gen_test_subtitles.py` | `subs/*` | Synthetic fa/en subtitles (our own text, 40 cues in the first 10 min) |
| `gen_blender.py` | `blender.json` | 6 Blender open movies (CC BY), all with a verified MP4 |
| `gen_archive_films.py` | `archive-films.json` | 150 public-domain feature films (Internet Archive) |
| `gen_archive_series.py` | `archive-series.json` | 4 public-domain TV series, 60 episodes |
| `gen_hls_test.py` | `hls-test.json` | multi-quality HLS entry + failover entry |

Run `gen_test_subtitles.py` before `gen_blender.py` (the latter checks the subtitle files exist). Total size is about 230 KB of JSON.

## Curation notes

**Blender.** `download.blender.org` now only offers zipped, `.mov` or `.mkv` files (no direct MP4), so every stream is an MP4 on the
Internet Archive mirror (`archive.org/download/<item>/<file>`), checked with a range GET and `ffprobe` (codec h264, real height).
Items used: `BigBuckBunnyFULLHD60FPS` (1080p) + `BigBuckBunny_124` (360p), `ElephantsDream_628` (240p only), `sintel_202604` (1080p class),
`Tears-of-Steel` (864x360 despite the file name), `CosmosLaundromatFirstCycle`, `springopenmovie`. The `license` per title is the
film's official Blender license (Big Buck Bunny CC BY 3.0, Elephants Dream CC BY 2.5, Sintel CC BY 3.0, Tears of Steel CC BY 3.0,
Cosmos Laundromat CC BY 4.0, Spring CC BY 4.0) with the Blender attribution line. Not used: `sintel_20260427` (3,598 s, not the film),
items with no or an NC/ND license. Only IMDb ids I am sure of are set (BBB, ED, Sintel, ToS); no TMDB ids. Posters and backdrops are the
archive `services/img/<item>` thumbnails (small; also used as backdrop).

**Films.** Official `advancedsearch` query `collection:(feature_films) AND mediatype:movies AND licenseurl:*publicdomain*`, sorted by
downloads, 260 candidates. Kept: `licenseurl` containing `/publicdomain/` (PD mark, CC0 or the legacy PD URL) and an `.mp4` whose URL answers
a range GET; duplicates (same normalized title + year, usually re-uploads) removed; first 150 kept. Stream: largest h.264 non-`_512kb`
MP4 (priority 0) plus the `_512kb` MP4 as a lower-quality backup (priority 1). The Archive does not always record video height, so
missing heights are guessed (main = 480p, `_512kb` = 240p). Genres come from `subject` via a keyword map (max 3; about 28% of titles end up
with none). `added_at` = `publicdate`. `title_fa` is omitted. The licence information is whatever the uploader set on the Archive; it is not
independently verified. Content is what people actually download (including 1930s exploitation films), not curated for taste.

**Series.** The Archive keeps one item per episode, so series are defined in `SERIES` in `gen_archive_series.py` by identifier pattern
(restricted to PD `licenseurl`) and an ordering rule, capped per series:

| Series | Pattern | Order | Episodes |
|---|---|---|---|
| The Beverly Hillbillies | `Beverly_Hillbillies_Ep*` | number in id | first 20 -> 2 seasons x 10 |
| The Adventures of Robin Hood | `Robin_Hood_*` (not Librivox) | number in id | first 14 -> 2 x 7 |
| Sherlock Holmes (1954) | `SherlockHolmes-TheCase*` | alphabetical (no numbers) | 12, one season |
| The Lucy Show | `TLS_Lucy_*` | alphabetical (no numbers) | 14 -> 2 x 7 |

Season split is **synthetic** (more than 12 episodes -> two equal seasons) to test season switching; it does not reflect broadcast seasons.
Episode `number` restarts at 1 per season. Each episode keeps its own PD check, MP4 pick and verification. Series stills and posters are
the episode / first-episode thumbnails.

**HLS test.** `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` (Big Buck Bunny, 5 variants 320x184 to 1920x1080, verified at generation time)
as `quality: auto`; plus a movie whose first stream is `https://invalid.example/...` (never resolves) followed by a working MP4, to exercise failover.
Both are titled `[TEST]` and carry `x_test_note`.

**Subtitles.** All text is our own ("زیرنویس آزمایشی شمارهٔ N — mm:ss", "Test subtitle number N"). Referenced as `asset:///test-feeds/subs/<file>`
(preset-only scheme, see `docs/feed-spec.md` section 8). Big Buck Bunny: `bbb.fa.cp1256.srt` (Windows-1256 bytes, no `encoding` hint, Arabic
yeh/kaf and ASCII digits since cp1256 has no Persian digits), `bbb.en.vtt`, `bbb.en.srt`, `bbb.en.late2s.srt` (every cue 2.0 s late, for the
sync control), `bbb.ru.srt` (Russian, the app must hide it). Sintel: `sintel.fa.srt` (UTF-8 with BOM), `sintel.en.srt`.
