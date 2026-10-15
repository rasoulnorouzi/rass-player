# Third-party services
## Online subtitle services (R6.7)

Code: `app/src/main/java/nl/rass/player/player/subtitle/online/`. Both services are optional. **The user enters
their own API keys in Settings; no key is bundled in the APK**, and keys/tokens are sent only to their own
provider host and never logged.

| Service | API | Notes |
|---|---|---|
| SubDL | `GET https://api.subdl.com/api/v1/subtitles` (`api_key`, `imdb_id` / `tmdb_id` / `film_name`, `type=movie\|tv`, `season_number`, `episode_number`, `languages=FA,EN`, `year`, `subs_per_page`). Response: `status`, `results[]`, `subtitles[]` (`release_name`, `lang`, `language`, `url`, `season`, `episode`, `hi`, `full_season`). Downloads: ZIP at `https://dl.subdl.com` + `url`. | Free key from the SubDL account panel. Free-plan request limits apply; HTTP 429 is shown to the user. |
| OpenSubtitles.com | REST `https://api.opensubtitles.com/api/v1`: `GET /subtitles`, `POST /login`, `POST /download {file_id}` -> temporary `link`. Headers: `Api-Key`, `User-Agent: Rass_player v0.1.0`, `Authorization: Bearer <token>` for downloads. | Free API consumer key + the user's own OpenSubtitles account (needed for downloads). Daily download quota per account (about 20 on the free tier); HTTP 406 means exhausted, shown in Persian with the reset time. Links are valid about 3 hours (never cached). |

Verification status: the official doc pages (subdl.com/api-doc, Stoplight) could not be fetched from the build
environment (Cloudflare challenge / JS-only), so request and response shapes were implemented from the published
API descriptions and are covered by fixture-based tests only. **Re-check against live responses with a real key
before release.**

Attribution: the Settings screen should state that subtitles are provided by SubDL and OpenSubtitles.com, and the
subtitle menu shows the provider name next to each online result. The app is a personal, non-commercial client;
check each service's current terms before any wider distribution.

## Xray engine: libv2ray.aar (R10, private connection)

| Item | Value |
|---|---|
| Library | `libv2ray.aar` from [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) (the engine v2rayNG uses), prebuilt release asset |
| Version | **v26.10.10** (published 2026-10-11; "Lib v40"), built from Xray-core `v1.260327.1-0.20261010092107-701af60772cd` |
| SHA-256 | `15db760bfbc24c0492ee997784b48a7b5e1bc5032950d617cd252fc2195f7ac7` (64,942,583 bytes; matches the GitHub release digest) |
| Location | `app/libs/libv2ray.aar`, git-ignored; fetch + verify with `scripts/fetch-xray-aar.sh` (pins version and hash; update both together) |
| Licenses | AndroidLibXrayLite wrapper: **LGPL-3.0**. Xray-core: **MPL-2.0**. Go runtime / gomobile: BSD-3-Clause. Copies of the license texts belong in `app/src/main/assets/licenses/` and Settings → About before any release build. LGPL note: we link the unmodified prebuilt library as a separate `.so`/AAR, so it stays replaceable. |
| Java API used | `libv2ray.Libv2ray.initCoreEnv/newCoreController/checkVersionX`, `CoreController.startLoop(json, tunFd=0)/stopLoop()`, `go.Seq.setContext`. Inspected from the AAR's `classes.jar` / `libv2ray-sources.jar`, not guessed. |
| Packaging | Native libs for armeabi-v7a, arm64-v8a (+ debug ABIs) via `abiFilters`; the AAR's `geoip.dat` / `geosite.dat` (~28 MB) are excluded in `app/build.gradle.kts` because our config never uses geo rules. |
| Size | `libgojni.so` is ~37 MB per ABI uncompressed, ~14 MB compressed in the APK (`useLegacyPackaging = true`). Debug APK: 19.4 MB before the AAR, 79.9 MB after (4 ABIs incl. x86); ARM-only release adds ~29 MB. |
