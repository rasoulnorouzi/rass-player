# Private connection (R10): how it works and how to use it from other modules

Code: `app/src/main/java/nl/rass/player/net/`, shared client in `app/src/main/java/nl/rass/player/di/HttpModule.kt`.
Engine: prebuilt `libv2ray.aar` (Xray-core), see `docs/third-party.md`.

## Rule for every module: use the ONE shared OkHttpClient

Inject `okhttp3.OkHttpClient` (Hilt, unqualified). It is provided once by `di/HttpModule.kt` and follows the
private connection live: proxy on/off, port changes, and the kill switch. **Never** call `OkHttpClient()` /
`OkHttpClient.Builder()` for app traffic, and never use `HttpURLConnection` or Media3's `DefaultHttpDataSource`
(they would bypass the proxy and the kill switch).

- **Per-call tweaks** (timeouts, an extra interceptor): `sharedClient.newBuilder()...build()`. That keeps the
  proxy selector and both kill-switch interceptors. Don't replace `proxySelector` or set `.proxy(...)`.
- **App-wide settings** (disk cache, User-Agent, logging without bodies): add them in `HttpModule` before
  `ProxyHttp.install(...)`. Do not add a second `@Provides OkHttpClient` (duplicate binding breaks Hilt).
- **Coil 3**: `OkHttpNetworkFetcherFactory(callFactory = { sharedClient })`.
- **Media3**: `OkHttpDataSource.Factory(sharedClient)` (wrap in `DefaultDataSource.Factory(context, it)` for local files).
- **Updater / feeds / subtitles**: inject the client directly.
- Errors: when the kill switch blocks a call, OkHttp throws `PrivateConnectionUnavailableException`
  (an `IOException`, with `reason: FailReason?`). Show the Persian "private connection not connected" message for it.
- `@PlainHttpClient` is internal to `net/` (latency probes, subscription download). Don't use it elsewhere.

## Runtime design

- **App-only proxy, no VpnService.** Xray runs in-process (gomobile AAR), tied to the app process; no foreground
  service. It listens on two random free ports on `127.0.0.1` (SOCKS5 + HTTP, no auth). The app uses the HTTP
  inbound; OkHttp sends hostnames to the proxy (CONNECT), so DNS is resolved remotely. Xray's own DNS is tagged and
  routed through the proxy as well. Logs are off (gomobile pipes stdout to logcat).
  Note: any app on the TV could reach a loopback port while it is open; acceptable for a personal TV, and the
  ports change on every start.
- **Generated config** (`XrayConfigBuilder`, golden tests in `app/src/test/resources/net/`): app traffic
  (`socks-in`, `http-in`, Xray DNS) → `px-0`, the active config. Up to 2 standby configs `px-1..2` are reachable
  only via their own loopback probe inbounds `probe-1..2`. Dependency outbounds (e.g. BPB's fragment) are `hp-N-M`.
  There is no direct outbound and **no observatory/balancer**, because those can't be paused while video plays.
- **Light testing** (TV: quad Cortex-A55, ~2 GB RAM). Tests run only on enable or config change, on the user's
  "test", and when the active config **and** its standbys fail. `LatencyTester` uses **one temporary Xray instance
  per batch**, with one loopback HTTP inbound per config (`t-i` → `px-i`). It GETs
  `https://cp.cloudflare.com/generate_204` through those ports with **2 probes in parallel** (setting 1–4,
  `ProxySettings.testConcurrency`) and a 5 s timeout. Only one test instance exists at a time. If Xray rejects a
  batch (one malformed outbound), the batch is split in halves to isolate it.
- **Caps** (`TestPlanner`): at most **20 configs per run**, ordered last-known-good by latency, then untested, then
  failed (oldest first). The next 20 are tested only if no config in the batch works. "Test all" walks every batch
  in sequence. On app start, results younger than 24 h are reused without a test run.
- **Health + failover** (`ProxyManager`): every **5 min** the active config (+ the 2 standbys) is pinged through
  the running instance. A failing app request through the proxy triggers an early check (debounced 30 s). If the
  active config fails twice (5 s apart), the fastest healthy standby is promoted by a quick restart, without a test
  run. If none works, a re-test starts.
- **Pausing**: health checks and retries are **paused while video plays** and **while the app is in the
  background**. The player must call `ProxyManager.setPlaybackActive(true/false)`; visibility is wired in `RassApp`
  via `AppVisibility`. During playback, only failure-triggered checks run, because playback is then broken anyway.
- **States**: `Off` → `Connecting(TESTING|STARTING|RETESTING)` → `Connected(name, latency)` or `Failed(reason)`.
  `Failed` retries after 60 s, 120 s, 300 s, then every 10 min, and only in the foreground without playback.
  `NO_CONFIGS` / `SELECTED_CONFIG_MISSING` wait for a user change.
- **Resource estimate (not measured on the TV)**: all Xray instances share one in-process Go runtime, so a test
  batch adds one Xray core with ≤20 outbounds and ≤2 live TLS handshakes. That is roughly 10–25 MB of Go heap on
  top of the main instance's ~20–30 MB, and a short CPU burst per handshake on one or two A55 cores. Before this
  change, tests ran up to 4 separate instances at once. The library is mostly file-backed mapped pages. Verify on
  the emulator/TV with `dumpsys meminfo nl.rass.player` (Native/Other) and `top -H` during "test all".
- **Kill switch** (`RoutePolicy`, default on): switch on + not connected → requests wait up to 20 s while
  `Connecting`, and fail immediately when `Failed`. Three layers: application interceptor, live `ProxySelector`
  (returns an unroutable proxy, never `NO_PROXY`, when blocked), and a network interceptor that checks the actual
  connection's proxy. Idle pooled connections are evicted on every route change. Kill switch off = fail open (direct).
- **Subscription refresh** is user-initiated: through the proxy when connected, otherwise direct (the only way to
  recover when every config is dead). Background traffic never takes that direct path. User-Agent `v2rayNG/1.10.23`
  so panels return the v2ray/Xray format; body capped at 5 MB.
- **Formats**: Xray JSON (array of full configs like BPB, one config, or a bare outbound; `remarks` → name;
  freedom/blackhole/dns skipped unless a proxy outbound dials through them) and plain/base64 share-link lists:
  `vless`, `vmess`, `trojan`, `ss` (SIP002, SS-2022, legacy; no plugins), `hysteria2`/`hy2` (no obfs);
  transports tcp(+http header)/ws/httpupgrade/grpc/xhttp/kcp; security none/tls/reality.
- **Secrets** (`SecureConfigStore`): one blob, AES-256-GCM with a non-exportable Android Keystore key, in
  `noBackupFilesDir`, atomic writes. Chosen over `EncryptedFile` (androidx.security-crypto is deprecated) and Tink
  (extra dependency for one blob). If the key is lost the store resets to empty. Parse problems and test errors are
  non-secret short codes; nothing from configs or URLs is logged or put in exception messages.

## Not done yet (Phase 5b follow-ups)

- Settings UI (switch, subscriptions list, configs with latency, test-all, manual pick, kill switch).
- Persian strings for `FailReason` / `ConnectStep` / `PrivateConnectionUnavailableException`.
- Throughput benchmark on the TV (4K through Xray on the A55 CPU) and a real memory/CPU measurement of a test run.
- Player must call `ProxyManager.setPlaybackActive(...)` (player/ module).
- License texts in assets + About screen.
