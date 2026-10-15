# Rass_player backup format v1

Status: **v1, 2026-10-15** (R11.2). Code: `app/src/main/java/nl/rass/player/data/backup/Backup.kt`.

A backup is one UTF-8 JSON file. Settings → Backup exports it; import reads it back on the same or another
device (later also Samsung/LG). Unknown keys are ignored, missing keys get defaults, so adding optional fields
doesn't need a new version.

## Root

| Field | Type | Meaning |
|---|---|---|
| `format` | `"rass-player-backup"` | Required. Anything else is rejected ("not a Rass_player backup"). |
| `version` | integer | `1`. A file with a higher version is rejected ("made by a newer app"). |
| `createdAt` | integer | Epoch milliseconds. |
| `appVersion` | string or null | `versionName` of the app that wrote it. |
| `includesSecrets` | boolean | `true` when tokens / custom header values are in the file **in plain text**. |
| `sources` | array of Source | Settings → Sources, in order. |
| `settings` | Settings or null | App preferences (`AppSettings`). |
| `myList` | array | `{ catalogKey, type ("movie"\|"series"), addedAt }` |
| `progress` | array | `{ catalogKey, episodeKey ("" for movies), positionMs, durationMs, updatedAt, completed, hiddenFromContinue }` |
| `history` | array | Recently played: `{ catalogKey, episodeKey, type, playedAt }` |
| `subtitleOffsets` | array | `{ key, offsetMs, updatedAt }`; `key` = `<titleKey>\|<episodeKey>\|<subtitle URL hash>` |
| `recentSearches` | array | `{ normKey, query, searchedAt }` |
| `seenEpisodes` | array | `{ catalogKey, episodeKey, seenAt }` ("new episode" badges) |

`catalogKey` / `episodeKey` are the canonical keys of feed-spec §12.5 (`tmdb:movie:<n>`, `tmdb:series:<n>`,
`imdb:<id>`, `src:<source-uuid>:<feed id>`; episodes `<title key>#s<season>e<episode>`), so the data matches the
same titles after a restore, across sources.

### Source

`{ id, name, url, enabled, order, token, headerName, headerValue, allowTokenToMediaHosts, allowTokenOverHttp,
refreshIntervalMin, isPreset, addedAt }`. `token` and `headerValue` are `null` unless the user ticked
"include access tokens" on export (`includesSecrets: true`). `id` is the source's UUID; keep it, because
`src:` keys contain it.

### Settings

The `AppSettings` object: `defaultQuality` (`"auto"` or `"720p"` style), `autoplayNext`, `resumeBehavior`
(`ASK`/`ALWAYS_RESUME`/`ALWAYS_START_OVER`), `subtitleLanguage` (`FA`/`EN`/`OFF`), `subtitlesAutoOn`,
`subtitleStyleJson`, `updateUrl`, `autoCheckUpdates`, `imageCacheMb`, `httpCacheMb`, `totalCacheCapMb`.

## Import rules

- **Sources** are merged by `id`: a source in the file replaces the device's source with the same id (or the same
  URL); device sources not in the file are kept after the imported ones. If the file has no secret for a source
  that already exists on the device, the device keeps its token. Imported tokens are encrypted at rest again.
  Preset test sources are not re-seeded after a restore.
- **Settings** are replaced.
- **My List, subtitle offsets, recent searches, seen episodes:** upserted.
- **Progress / history:** a row replaces the device's row only if it is newer (`updatedAt` / `playedAt`).

## Privacy

A file with `includesSecrets: true` contains the access tokens in plain text. The UI must say so before export.
The private connection (R10) subscriptions are never part of this backup.
