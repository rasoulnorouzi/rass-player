# QA report: first emulator run (2026-10-15)

Emulator only: AVD `MiTV_API30` (Android 11, 32-bit x86, 1920×1080 at 320 dpi), debug build, driven with
`adb input keyevent` + `uiautomator dump`. Screenshots are in `docs/qa/screenshots/` (`scale-*.png` were taken
after the global UI scale of 0.62 was added in `MainActivity`).

## Build and tests

- `./gradlew clean :app:assembleDebug :app:testDebugUnitTest`: **green, 288 unit tests, 0 failures**.
- `SearchIndexTest.perf20kDocs` made robust: functional checks first, a warm-up, then the **median of 7 runs per
  query**, with a 25 ms budget for both `suggest` and `search` (it measures about 3–5 ms locally).
- `CatalogRepositoryTest` was flaky. The cause was a real race in `CatalogRepository` (fixed, see below).

## Results

| Area | Item | Result |
|---|---|---|
| First launch | Presets load (Blender, IA films + series, HLS test); hero + rows | Pass, after fix 1 |
| | Initial focus on the hero Play button | Pass, after fix 2 (was on "Settings") |
| | Nav bar: Up from content, collapse on scroll | Pass |
| | Back: content → nav bar → "press again" toast → exit | Pass |
| | Partial-error banner | Not tested (no failing source added) |
| Movies browse | Genre chips, sort chips (A–Z), source filter (Blender) | Pass |
| Detail (movie) | My List add/remove, watched/unwatched, resume/start-over buttons, cast, source badge | Pass |
| Detail (series) | Seasons/episodes | Not tested (time) |
| Playback | Blender MP4 (archive.org) plays, position advances; seek +30 s; pause/resume (media key) | Pass |
| | Resume dialog after exit and re-entry; Continue Watching + Recently played rows | Pass. Fix 5: no second prompt after "Resume" |
| | HLS test: quality menu lists 1080p/720p/480p/288p/184p + Auto, switching to 288p applied | Pass |
| | Failover title, Source menu | Not tested (time) |
| Subtitles | Persian default on, renders RTL with ی/ک; pooled into the HLS copy of BBB | Pass |
| | Windows-1256 file, VTT, 2 s-late file, live sync/style changes, ru hidden | Not tested on device (unit tests cover decoding/parsing) |
| Series in player | Episodes panel, next/prev, auto-next, skip intro | Not tested (time) |
| Search | Persian keyboard, suggestions, results with source badges, type/source filters, recents | Pass |
| | English keyboard | Pass, after fix 4 (layout was mirrored) |
| | D-pad out of the query field | Pass, after fix 3 (focus trap) |
| | Content filter: tagged title (Night of the Living Dead) found by search | Pass. "Not on Home" not checked row by row |
| Settings | Root opens and scrolls; Private connection page | Pass |
| | Sources autodiscovery, Storage, Backup, About, PIN, Updates, Online subtitles | Not tested (time; manager asked to finish) |
| Private connection | In-app connection check (new): exit IP ≠ host IP, exit country DE (host NL), "compare with direct" shows ✓ | Pass |
| | Socket sampling, 93 samples over ~110 s while browsing + archive.org MP4 + Mux HLS via the private connection | Pass: app sockets went to **1 remote endpoint (the proxy server, port 8443)**; **0 connections** to archive.org, `test-streams.mux.dev`, Cloudflare or ipify |
| | Kill switch with no working config | Code + unit tests only (`ProxyHttpTest`: blocked/pending fail fast, route guard, pool eviction). Not forced on device because the user's real subscription is stored there |
| Process death / rotation | — | Skipped (manager: low value) |

No crashes, ANRs or `AndroidRuntime` errors were logged during the session.

## Bugs found and fixed

1. **First launch sometimes showed "no active sources"** (about 1 in 3 fresh installs). DataStore's `data` flow
   handed some collectors a stale empty value and never the seeded presets. New `data/DataStoreCell.kt` reads the
   DataStore once, then serves every collector from memory and sets the value from its own writes. Used by
   `data/source/SourceStore.kt` and `data/settings/SettingsRepository.kt`. 5/5 fresh starts passed afterwards.
2. **Catalog state briefly reported "done" between cache load and refresh** (Home could flash empty or partial
   state; this also made `CatalogRepositoryTest` flaky). `data/catalog/CatalogRepository.kt` now marks a source as
   loading before the refresh job starts.
3. **Initial focus landed on the Settings nav item** and was taken back by the nav bar when the billboard rebuilt.
   In `ui/AppShell.kt`, `ShellController.navUserActive` (set by a key press or by Back) separates the user's own
   nav focus from focus Compose handed to the bar. `ui/home/HomeScreen.kt` then returns focus to the hero.
4. **Search query field trapped the D-pad** (`BasicTextField` used the arrows to move the cursor).
   `ui/search/SearchScreen.kt` `QueryField` now moves focus on every arrow key.
5. **English on-screen keyboard was mirrored** (q on the right). `ui/search/keyboard/OnScreenKeyboard.kt` now
   draws English left-to-right and Persian right-to-left, with arrow keys and the exit edge mapped per layout.
6. **Resume asked twice**: the Detail button "ادامه از mm:ss" and Continue Watching cards opened the player,
   which asked again. New `Route.Player.resume` flag (`ui/navigation/Routes.kt`, encoded in `AppShell.kt`), set
   by `DetailScreen` and `HomeModels`. `PlayerViewModel.openItem` then resumes without asking.
7. **New feature, connection check (R10)**: `net/ConnectionCheck.kt` (`ExitInfoParser` for Cloudflare trace and
   ipify, `ConnectionChecker`). The check goes through the shared client; a direct comparison runs only on tap,
   with its own pool so the connection isn't reused. UI buttons «بررسی اتصال» / «مقایسه با اتصال مستقیم» are in
   `ui/settings/PrivatePage.kt`, with strings in `strings_settings.xml`. Tests: `net/ExitInfoParserTest.kt`.
   Results stay in memory and are never logged.

## Leak review (code)

Every HTTP path uses the shared `OkHttpClient` from `di/HttpModule.kt` or a client built from it with `newBuilder()`.
Those clients keep the live proxy selector, the kill-switch interceptors and the shared pool:

- feeds and autodiscovery (`DocumentFetcher`), Coil images and trailers (`@MediaHttpClient`)
- Media3: `OkHttpDataSource` inside `DefaultDataSource`, so all HLS/DASH playlists and segments
- subtitles (`SubtitleLoader`), online subtitle providers, the updater

The proxy is `Proxy.Type.HTTP`, so OkHttp sends the hostname in CONNECT and does no local DNS lookup for remote
hosts. Xray routing is `AsIs`. No `HttpURLConnection`, `DownloadManager`, `WebView`, `MediaPlayer` or
`DefaultHttpDataSource` is used.

Two deliberate direct paths go through the plain client: subscription fetches use an explicit route (through the
proxy when connected, direct only for a user-triggered refresh when not connected), and the latency probes go
through their own test ports. The LAN add-source page is a local server only.

## Known limitations / follow-ups

- At 0.62 scale the hero title on Home and the title on Detail are still cut off at the top: the focused buttons
  scroll the billboard under the nav bar. Left for the design pass.
- Back from Detail to Search puts focus on the first keyboard key, not on the opened result.
- Recent searches are saved only when a result or suggestion is opened (by design).
- Untested on device: series and player episodes panel, failover/Source menu, subtitle styling and sync, Sources
  autodiscovery, Storage, Backup, PIN, Updates, rotation and process death.
- Test helper caveat: `uiautomator dump` takes about 1 s and can be stale right after a key press, so scripted
  navigation must re-check focus before pressing OK.

## Performance (emulator, indicative only: x86 + software GPU)

- App memory on Home after playback, private connection ON (includes Xray): **PSS 240 MB**, RSS 349 MB,
  peak RSS 369 MB. `dumpsys meminfo` returned nothing on this image, so the numbers come from
  `/proc/<pid>/smaps_rollup`.
- Scrolling Home rows: 240 frames, 84% janky, frame time p50 19 ms, p90 25 ms, p99 44 ms. This is not
  representative of the TV's Mali GPU; re-measure on hardware or with `-gpu host` profiling.
- Cold start (`am start -W`): about 1.8–2.1 s.

## APK sizes

- Release `dist/Rass_player-0.1.0.apk` (armeabi-v7a + arm64-v8a, R8, signed with the debug key): **29.7 MB**
  (29,721,996 bytes).
- Debug APK (4 ABIs): 82.4 MB.
