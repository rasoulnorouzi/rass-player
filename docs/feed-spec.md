# Rass_player feed format v1

Status: **v1, 2026-10-15.** This is the only content format the app reads (CLAUDE.md §2).
Machine-readable schema: [`feed-schema.json`](feed-schema.json) (JSON Schema draft 2020-12).
Examples: [`feed-examples/`](feed-examples/) (`minimal.json`, `update.json`, `rass-feed.php`, `rass_feed.py`).
Validate a file: `python3 -m venv .venv && .venv/bin/pip install jsonschema`, then check it against
`feed-schema.json` with `Draft202012Validator` (for `update.json`, against `#/$defs/update_manifest`).

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in RFC 2119.
"Client" means the Rass_player app. "Source" means one feed entry in Settings → Sources.
"Feed author" means whoever produces the JSON (a site, a script, `scripts/feedgen/`).

Contents:
1. Overview
2. Common types (URLs, ids, dates, text, images, languages)
3. Root document
4. Title
5. Movie fields
6. Series, seasons, episodes
7. Streams, and how the client chooses one and fails over
8. Subtitles
9. Markers
10. Validation and error handling
11. Large catalogs: `parts`
12. Merging several sources
13. Discovery (add a site by its address)
14. Access tokens and headers
15. Caching and refresh
16. `update.json` (app updates)
17. Complete minimal example
18. Site setup guide (plain language)

---

## 1. Overview

A feed is one UTF-8 JSON document (optionally split into several, §11) that lists titles (movies and series),
their artwork, metadata, playable stream URLs and subtitle URLs.

Boundaries that are part of the format (not optional):

- A feed only contains **direct media URLs** (HLS playlists, DASH manifests, MP4 files) and direct subtitle/image
  URLs. The client plays them as-is. There are no "embed page" URLs, no extractor hints, no decryption keys.
- A feed **cannot** ask the client to send request headers. There is no `headers`, `referer`, `origin` or
  `user_agent` field anywhere; if a feed contains one it is ignored. The only extra header the client ever sends
  is the user's own access token for the user's own site (§14), configured in Settings, never by the feed.
- Test feeds (R12) carry a `license` on every title (§4, `test: true`).

Field names are `snake_case`. All numbers are JSON numbers (not strings). All text is plain text (no HTML, no
Markdown); `\n` is allowed in descriptions and is shown as a line break.

## 2. Common types

### 2.1 URL

A URL is a string, 1–4096 characters, with no whitespace (encode spaces as `%20`).

- **Absolute:** MUST use `http` or `https` (case-insensitive) and have a host. All other schemes (`file:`,
  `content:`, `data:`, `javascript:`, `ftp:`, `rtmp:` …) are invalid.
- **Relative:** any relative reference per RFC 3986 (`poster.jpg`, `/media/a.mp4`, `../x.vtt`,
  `//cdn.example.com/a.m3u8`). The client resolves it against the **final URL of the document that contains it**
  (after redirects): the main feed URL for fields in the main feed, the part URL for fields in a part (§11), the
  `update.json` URL for `update.json` fields.
  Careful: for a feed at `https://site/.well-known/rass-feed.json`, `media/a.mp4` resolves to
  `https://site/.well-known/media/a.mp4`. Use root-relative paths (`/media/a.mp4`) or absolute URLs.
- `http` is allowed (home servers often have no TLS). The client SHOULD show a "not secure" mark on a source
  whose feed URL is `http`, and MUST warn when an access token is configured for an `http` source.

#### 2.1.1 Client-only preset scheme `asset:///` (not part of the feed format)

The app's built-in test sources (R12) have source URLs like `asset:///test-feeds/blender.json`: the client reads
that file from its own APK assets instead of the network. Inside a document loaded that way (and only there), URLs
may also be `asset:///<path>` (e.g. bundled test subtitles), and relative URLs resolve to `asset:///` too. Media
URLs in preset feeds are absolute `https`. Users cannot add `asset:///` sources, remote feeds can never use the
scheme (it is rejected like `file:`), asset documents are not written to the feed cache, and no token is ever sent
for them. Feed authors never need it; the schema doesn't accept it.

### 2.2 Id

A stable identifier: a string matching `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$` (1–128 chars, starts with a letter
or digit; then letters, digits, `.`, `_`, `:`, `-`). Ids are **case-sensitive**. A title id MUST NOT change
between feed updates; the client uses it to keep watch progress and My List entries (§12.5).

### 2.3 Dates

- `date`: `YYYY-MM-DD` (e.g. `2026-10-15`).
- `date-time`: RFC 3339 with an explicit offset, e.g. `2026-10-15T14:30:00Z` or `2026-10-15T16:30:00+02:00`.
  Seconds and fractional seconds are optional (`2026-10-15T14:30Z` is accepted).
- Where a field says `date or date-time`, both forms are accepted; a bare `date` means 00:00 UTC that day.

### 2.4 Text

| Kind | Length | Used for |
|---|---|---|
| short text | 1–300 chars | names, titles, labels |
| long text | 1–5000 chars | descriptions, release notes |

Empty strings are invalid (omit the field instead). Leading/trailing whitespace is trimmed by the client.

### 2.5 Language tag

BCP-47 tag matching `^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$`, e.g. `fa`, `fa-IR`, `en`, `en-US`.
The client compares only the **primary subtag**, case-insensitively (`fa-IR` counts as `fa`).

### 2.6 Image

An image is either a URL string, or an **image object** that lets the TV download a small version:

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `url` | URL | yes | Full-size image. Always used as the last fallback. |
| `sizes` | array of `{ "width": int, "url": URL }` | no | Pre-made sizes. `width` in pixels, 1–8192, unique within the array. |
| `template` | URL containing `{width}` | no | A resizing URL; the client replaces `{width}` with a number from `widths`. |
| `widths` | array of int (1–8192), unique, non-empty | with `template` | The widths the template accepts. Required when `template` is present. |

How the client picks an image for a slot that is `W` physical pixels wide:

1. If `sizes` exists: the entry with the **smallest `width` ≥ W**; if none is that big, the **largest** entry.
2. Else if `template` exists: the **smallest value in `widths` ≥ W** (else the largest), substituted for every
   `{width}` in the template.
3. Else: `url`.
4. If the chosen image fails to load, the client tries `url` once.

If both `sizes` and `template` are present, `sizes` wins.

Recommended artwork (JPEG or WebP for photos, PNG or WebP with transparency for logos; sRGB):

| Field | Aspect | Recommended full size | Typical TV display size (1080p) | Provide small sizes |
|---|---|---|---|---|
| `poster` | 2:3 | 500×750 to 1000×1500 | 185–260 px wide in rows | 185, 342, 500 |
| `backdrop` | 16:9 | 1920×1080 (min 1280×720) | full screen (hero, detail) | 780, 1280, 1920 |
| `logo` | free, wide | 500–800 px wide, transparent | ~400 px wide on the hero | 300, 500 |
| `still` (episode) | 16:9 | 640×360 to 1280×720 | 300–400 px wide | 300, 640 |
| `cast[].photo` | 2:3 | 300×450 | 120–160 px wide | 185 |

Keep each poster below ~150 KB at 500 px wide. The client never loads a full-size image into a row when a
smaller one is offered (CLAUDE.md §3 Performance).

### 2.7 Extension fields

Any object MAY contain extra keys that start with `x_` (e.g. `x_internal_note`). The client ignores them and the
schema accepts them. Other unknown keys are also ignored by the client (§10), but the schema rejects them so that
typos (`titel_en`) are caught while authoring.

## 3. Root document

```json
{
  "rass_feed_version": 1,
  "name": "My site",
  "updated_at": "2026-10-15T12:00:00Z",
  "genres": [ … ],
  "titles": [ … ]
}
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `rass_feed_version` | integer, `1` | yes | Format version. The client accepts exactly `1`. |
| `kind` | `"feed"` | no | Default `"feed"`. (Part documents use `"part"`, §11.) |
| `name` | short text | yes | Feed name; the default name of the source in Settings. |
| `updated_at` | date-time | yes | When the catalog content last changed. |
| `description` | long text | no | Shown in Settings → Sources. |
| `test` | boolean | no | `true` marks a **test feed** (R12): every title MUST have a `license`. Default `false`. |
| `featured` | array of title ids, unique, ≤ 20 | no | Hero billboard candidates, in order. |
| `top10` | array of title ids, unique, ≤ 10 | no | The Top 10 row, rank 1 first. |
| `genres` | array of Genre | no | Genres used by titles. Default `[]`. |
| `titles` | array of Title | yes | The catalog (may be `[]` when everything is in `parts`). |
| `parts` | array of URL, unique, ≤ 50 | no | Further catalog documents to merge (§11). |

Version rule: a non-additive change bumps `rass_feed_version` to `2`. Adding optional fields does not; old clients
ignore them. A client that gets a version it doesn't know rejects the document with the message
"This feed needs a newer version of Rass_player".

### 3.1 Genre

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `id` | Id | yes | Unique within the feed (including parts). Compared case-insensitively across sources (§12.4). |
| `name_fa` | short text | one of the two | Persian name (shown in the UI). |
| `name_en` | short text | one of the two | English name. |

At least one of `name_fa` / `name_en` is required. The UI shows `name_fa`, else `name_en`.

**Recommended genre ids** (use them so genres from different sources line up): `action`, `adventure`,
`animation`, `comedy`, `crime`, `documentary`, `drama`, `family`, `fantasy`, `history`, `horror`, `kids`,
`music`, `mystery`, `romance`, `science-fiction`, `thriller`, `war`, `western`. Other ids are allowed.

### 3.2 `featured` and `top10`

- Each entry is a title `id` from this feed. Unknown ids are dropped (warning, §10).
- `featured`: the client shows up to **10** of them in the hero, in order. A featured title SHOULD have a
  `backdrop`; titles without one are skipped for the hero. If no source supplies a usable `featured` list, the
  client uses the 10 most recently added titles that have a backdrop.
- `top10`: shown in order with rank badges 1–10. If absent or empty, the row is hidden.

## 4. Title

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `id` | Id | yes | Unique among all titles of this feed (main document + parts). |
| `type` | `"movie"` \| `"series"` | yes | |
| `title_fa` | short text | one of the two | Persian title. Shown first in the UI. |
| `title_en` | short text | one of the two | English title. Shown as the second line; searched (R7.1). |
| `original_title` | short text | no | Title in the original language; searched. |
| `year` | integer 1870–2100 | no | Release year (series: first-air year). Used for cross-feed matching (§12). |
| `rating` | number 0–10 | no | Shown with one decimal. |
| `genres` | array of Genre ids, unique | no | Ids that don't exist in the feed's `genres` are dropped (warning). |
| `description_fa` | long text | no | |
| `description_en` | long text | no | Shown only when `description_fa` is missing. |
| `poster` | Image | no (SHOULD) | 2:3 poster. Without it the client draws a text card. |
| `backdrop` | Image | no (SHOULD) | 16:9 background for hero and detail page. |
| `logo` | Image | no | Title logo for the hero, drawn instead of text when present. |
| `added_at` | date or date-time | no | When the title was added to this feed. Drives "Recently added" (R11.3). If missing, the client uses the time it first saw the title. |
| `runtime_min` | integer 1–1000 | no | Movie length, or typical episode length for a series. |
| `cast` | array of Person, ≤ 50 | no | Shown in feed order (R11.8). |
| `trailer` | Trailer | no | R11.7. |
| `license` | License | no; **required when the root has `test: true`** | Shown on the detail page (needed for CC BY attribution). |
| `ids` | External ids | no (SHOULD) | Cross-feed matching keys (§12). |
| `streams` | array of Stream, ≥ 1 | **movie: yes** | §7. |
| `subtitles` | array of Subtitle | movie: no | §8. |
| `markers` | Markers | movie: no | §9. |
| `seasons` | array of Season, ≥ 1 | **series: yes** | §6. |

At least one of `title_fa` / `title_en` is required. The UI shows `title_fa`, else `title_en`.

Type-specific fields: `streams`, `subtitles`, `markers` belong to movies only; `seasons` belongs to series only.
The schema rejects them on the wrong type; the client ignores them there.

### 4.1 Person (`cast[]`)

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `name` | short text | yes | |
| `role` | short text | no | Character name or job (e.g. "Director"). Free text. |
| `photo` | Image | no | 2:3 portrait. |

### 4.2 Trailer

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `url` | URL | yes | |
| `type` | `"hls"` \| `"mp4"` \| `"dash"` | yes | As in §7. |

### 4.3 License

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `name` | short text | yes | e.g. `"CC BY 3.0"`, `"Public domain"`, `"CC0 1.0"`. |
| `url` | URL | no | License text, e.g. `https://creativecommons.org/licenses/by/3.0/`. |
| `attribution` | short text | no | Required credit line, shown verbatim. |
| `source_url` | URL | no | Where the media came from (e.g. the Internet Archive item page). Display only; the client never fetches it. |

### 4.4 External ids (`ids`)

| Field | Type | Meaning |
|---|---|---|
| `tmdb` | integer ≥ 1 | TMDB id. TMDB ids are per type: for a `movie` it is a TMDB *movie* id, for a `series` a TMDB *TV* id. |
| `imdb` | string `^tt[0-9]{7,10}$` | IMDb id (for a series: the series' id). |

Both optional. They are **data only**: the client never calls TMDB or IMDb.

## 5. Movie fields

A movie title (`type: "movie"`) MUST have `streams` (≥ 1 valid stream after validation, otherwise the title is
skipped) and MAY have `subtitles` and `markers`.

Exception (R6.6, subtitles are independent of video): a movie with no valid stream but with at least one valid
fa/en subtitle is kept as a **subtitle-only** title (warning, not skipped). It is never shown on its own; it only adds
its subtitles to a matching title from another source (§12.2). The same applies to an episode with no valid stream
but with fa/en subtitles, and to a series whose episodes are all subtitle-only.

## 6. Series, seasons, episodes

A series title (`type: "series"`) MUST have `seasons` with at least one valid season.

### 6.1 Season

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `number` | integer 0–9999 | yes | Unique within the title. `0` = specials. |
| `name` | short text | no | Display name; default "فصل N" (or "ویژه" for 0). |
| `episodes` | array of Episode, ≥ 1 | yes | A season with no valid episodes is skipped. |

The client sorts seasons by `number` ascending with `0` (specials) last, and episodes by `number` ascending.
Feed order of seasons/episodes doesn't matter.

### 6.2 Episode

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `id` | Id | yes | Unique among all episodes of this title. |
| `number` | integer 0–99999 | yes | Unique within the season. |
| `title_fa` | short text | no | Default title: "قسمت N". |
| `title_en` | short text | no | |
| `description_fa` | long text | no | |
| `description_en` | long text | no | Shown only when `description_fa` is missing. |
| `still` | Image | no | 16:9 thumbnail for the episode list and the in-player episodes panel (R5.8). |
| `runtime_min` | integer 1–1000 | no | |
| `air_date` | date | no | |
| `added_at` | date or date-time | no | When the episode appeared in the feed; drives "new episode" badges (R2.4, R11.3). If missing, the client uses the time it first saw the episode. |
| `streams` | array of Stream, ≥ 1 | yes | §7. An episode with no valid stream is skipped. |
| `subtitles` | array of Subtitle | no | §8. |
| `markers` | Markers | no | §9. |

## 7. Streams

### 7.1 Stream object

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `url` | URL | yes | HLS playlist (`.m3u8`), DASH manifest (`.mpd`) or progressive MP4 file. |
| `type` | `"hls"` \| `"mp4"` \| `"dash"` | yes | How to play it. The client never guesses from the extension. |
| `quality` | `"auto"` or `^[1-9][0-9]{2,3}p$` | yes | `"auto"` = adaptive (multi-variant HLS/DASH). Otherwise the video height label: `"2160p"`, `"1440p"`, `"1080p"`, `"720p"`, `"576p"`, `"480p"`, `"360p"`, `"240p"`. An `mp4` stream MUST NOT be `"auto"`. |
| `priority` | integer 0–1000 | no | Default `0`. **Lower = tried first.** Streams with the same priority form one *tier* (normally one server offering several qualities); a higher number is a backup tier. |
| `label` | short text | no | Name shown in the player's Source menu, e.g. "سرور ۲". Default: the URL's host. |
| `bitrate_kbps` | integer 1–1000000 | no | Average bitrate; shown in the Source menu. |
| `video_codec` | `"h264"` \| `"hevc"` \| `"vp9"` \| `"av1"` | no | Hint. Streams with a codec the device can't decode in hardware are moved to the end of the list (never removed). |
| `audio` | array of `{ "lang": language tag, "label": short text (optional) }` | no | Audio tracks contained in the stream, for display (R11.6). The player still reads the real tracks from the media. |

Multiple streams on one movie/episode can mean **qualities** (same priority, different `quality`) and/or
**backups** (different priority). Typical:

```json
"streams": [
  { "url": "https://a.example/bbb/master.m3u8", "type": "hls", "quality": "auto",  "priority": 0 },
  { "url": "https://a.example/bbb/1080.mp4",    "type": "mp4", "quality": "1080p", "priority": 0 },
  { "url": "https://a.example/bbb/720.mp4",     "type": "mp4", "quality": "720p",  "priority": 0 },
  { "url": "https://b.example/bbb/720.mp4",     "type": "mp4", "quality": "720p",  "priority": 1, "label": "Backup" }
]
```

### 7.2 Candidate list (which stream is tried first)

When the user presses Play on a movie or episode, the client builds one ordered **candidate list** from every
valid stream of that item in **every source** that carries the title (§12). For each candidate it knows:
`rank` (position of its source in Settings, 0 = top), `priority`, `quality`, `video_codec`, and `index`
(position in its feed's `streams` array). Then:

1. Resolve every URL (§2.1) and drop exact duplicate URLs, keeping the one that sorts first below.
2. Sort by these keys, in order:
   1. **Decodable:** streams whose `video_codec` is known *and* not hardware-decodable on this device go last.
      Missing `video_codec` counts as decodable.
   2. **Source rank** ascending (streams from the user's top source first).
   3. **`priority`** ascending.
   4. **Quality score** ascending (below).
   5. **`index`** ascending.

Quality score, with `D` = the display's max video height (e.g. 1080 or 2160) and the user's preferred quality
`Q` (Settings → Playback, default `auto`, R9.1):

- `Q = auto`: score 0 for `"auto"` streams; then fixed qualities with height ≤ D, highest first; then heights > D,
  lowest first.
- `Q = X` (e.g. 720): score 0 for streams with height exactly X; then `"auto"` streams (the player caps adaptive
  selection at height X); then heights < X, highest first; then heights > X, lowest first.

### 7.3 Failover (R5.6)

The player plays `candidates[0]`. A candidate **fails** when any of these happens:

| Trigger | Rule |
|---|---|
| Load/playback error | Media3 reports a fatal error for the media: HTTP status ≥ 400, network error after Media3's own retries, unsupported or unparsable format, decoder initialization failure. |
| Start timeout | No first video frame within **20 s** after the stream was opened. |
| Long stall | One buffering period longer than **15 s** while playing. |
| Repeated stalls | **3** buffering periods within **60 s** while playing. Buffering that starts within 5 s after a user seek or a manual quality/source change is not counted. |

Exception: if the device has no network connection (Android connectivity status), nothing is marked failed; the
player shows "No internet connection" and retries the same stream when the network returns.

On failure the player:

1. marks the candidate failed **for this playback session** (until the player screen is closed);
2. opens the next candidate in the list that is not failed, at the last playback position (no position
   loss beyond the buffered gap), keeping the selected subtitle, subtitle offset and audio language;
3. shows a short notice, e.g. "منبع ۲ از ۴ (Backup)" (index, total, `label` or host);
4. uses the markers of the source the new candidate came from (§9).

If every candidate has failed, playback stops with an error screen offering **Try again** (clears the failed
marks and starts again from `candidates[0]` at the same position) and the **Source** menu.

The client MAY also remember failures across sessions (for example, move a URL that failed in the last 30
minutes to the end of the list). It MUST NOT drop a stream permanently because of an old failure.

Adaptive switching *inside* one HLS/DASH stream is done by Media3 and is not failover.

### 7.4 Source and quality menus (R5.3, R5.7)

- **Source menu:** lists all candidates in candidate-list order: label (or host), source name, quality, and a
  "failed" mark. Choosing one plays it at the current position. After a manual choice, automatic failover
  continues with the next non-failed candidate after the chosen one in list order, wrapping to the top.
- **Quality menu:** "Auto", the variants of the current adaptive stream (read from its manifest), and every fixed
  `quality` offered by candidates in the current candidate's source and tier. Choosing a variant of the current
  stream is a track override. Choosing a fixed quality re-sorts the list with `Q` = that quality and plays the
  first non-failed candidate. The choice stays for the rest of the player session (next episodes too); the
  Settings default doesn't change.

## 8. Subtitles

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `lang` | language tag | yes | BCP-47. **The client lists only `fa` and `en`** (by primary subtag, R5.4); other languages are ignored without a warning. |
| `url` | URL | yes | Normal URL (§2.1), or the **preset-only client scheme** `asset:///<path>` (e.g. `asset:///test-feeds/subs/bbb.en.srt`), which the client resolves to a file in the app's own bundled assets. Only the built-in preset test feeds (R12) use it; the client SHOULD ignore `asset:///` URLs in feeds loaded from a user-added source, and no other `url` field accepts it. |
| `format` | `"srt"` \| `"vtt"` | yes | The client never guesses from the extension or content type. |
| `label` | short text | no | Shown in the subtitle menu. Default "فارسی" / "انگلیسی". |
| `encoding` | string `^[A-Za-z0-9._:-]{1,40}$` | no | Hint using a WHATWG encoding label, e.g. `windows-1256`, `utf-8`, `utf-16le`. |

Several files per language are allowed (e.g. two Persian translations); they are listed in feed order.

**Decoding (R6.4).** The client downloads the file and converts it to UTF-8 before handing it to Media3:

1. A byte-order mark (UTF-8, UTF-16LE, UTF-16BE) decides the encoding.
2. Otherwise, if the bytes are valid UTF-8, it is UTF-8.
3. Otherwise, the `encoding` hint, if present and supported.
4. Otherwise `windows-1256` when `lang` is `fa`, else `windows-1252`.

Subtitle sync offsets (R6.3) are remembered per item and per resolved subtitle URL.

## 9. Markers

All values are seconds from the start of the media (number ≥ 0, decimals allowed).

| Field | Meaning |
|---|---|
| `intro_start`, `intro_end` | "Skip intro" is shown while position ∈ [intro_start, intro_end) and jumps to `intro_end` (R11.1). |
| `recap_start`, `recap_end` | "Skip recap", same rule. |
| `credits_start` | Where the end credits begin. For series, the auto-next countdown starts here (R5.2); the item counts as watched from here even if that's below 90% (R4.3). |

Rules:
- `intro_start`/`intro_end` come as a pair, and so do `recap_start`/`recap_end`. A pair with one half missing, or
  with end ≤ start, is dropped (warning).
- Without `credits_start`, the auto-next countdown starts 30 s before the end.
- Markers belong to the files of the feed they come from. When the player plays a candidate from source S, it uses
  S's markers for that item; if S has none, the item has no markers for that candidate.

## 10. Validation and error handling

The client is **lenient per entry and strict per field**: a bad entry is skipped, the rest of the feed still
loads. The schema (`feed-schema.json`) is the strict authoring check; a feed that passes the schema loads without
warnings, except for what a schema can't detect: duplicate ids, unknown genre / `featured` / `top10` ids, and
marker pairs with end ≤ start.

### 10.1 What happens to what

| Problem | Client action | Counted as |
|---|---|---|
| Body isn't JSON, isn't a JSON object, or starts with `<` (an HTML page) | Reject the document. Message for `<`: "This address is a web page, not a Rass_player feed". | fatal |
| `rass_feed_version` missing or not `1` | Reject the document. | fatal |
| `titles` missing or not an array | Reject the document. | fatal |
| Document larger than 20 MB (decompressed) | Reject the document. | fatal |
| `name` / `updated_at` missing or invalid | Use the source name / fetch time. | warning |
| Unknown key (any object) | Ignore silently. | — |
| Field of a type-specific kind on the wrong type (`seasons` on a movie, `streams` on a series) | Ignore the field. | warning |
| Optional field invalid (wrong type, out of range, bad URL, bad date, empty string) | Treat the field as absent; keep the entry. | warning |
| Title: not an object, `id` missing/invalid/duplicate (later duplicate is skipped), `type` missing/unknown, both `title_fa` and `title_en` missing | Skip the title. | skipped |
| Movie with no valid stream; series with no valid season | Skip the title (unless it has fa/en subtitles: kept as subtitle-only, §5). | skipped (warning when kept) |
| Season: `number` missing/invalid/duplicate, no valid episode | Skip the season. | skipped |
| Episode: `id` missing/invalid/duplicate within title, `number` missing/invalid/duplicate within season, no valid stream | Skip the episode. | skipped |
| Stream: `url` invalid, `type` missing/unknown | Skip the stream. | skipped |
| Stream: `quality` missing/invalid | `hls`/`dash`: treat as `"auto"`. `mp4`: treat as unknown height, sorted after every known quality. | warning |
| Subtitle: `url` invalid, `lang` missing/invalid, `format` missing/unknown | Skip the subtitle. | skipped |
| Subtitle in another language than fa/en | Ignore. | — |
| Genre: `id` missing/invalid/duplicate, both names missing | Skip the genre. | skipped |
| Unknown id in `featured`, `top10` or a title's `genres` | Drop that reference. | warning |
| A part document fails to load or is fatal | Keep the main feed and the other parts; use that part's last good cached copy if any. | part error |

"Invalid id" means it doesn't match §2.2. "Duplicate" is checked after merging parts (§11): the first occurrence
in document order (main feed first, then parts in `parts` order) wins.

### 10.2 Report

"Test connection" (R1.2) and the error log (R11.13) show: titles loaded (movies / series / episodes), counts of
skipped entries, warnings and part errors, and the first 50 issues as `JSON path: message`, e.g.
`titles[12].streams[0].url: not an http(s) URL`. When a refresh produces a fatal error, the client keeps the
previous good catalog of that source and shows the error in Settings → Sources.

### 10.3 Size guidance

| Item | Recommendation | Hard limit (client) |
|---|---|---|
| One document (main feed or part) | ≤ 5 MB, gzip-compressed on the wire | 20 MB decompressed |
| Titles per document | ≤ 2,000 | — (bounded by size) |
| Titles per source (all parts) | — | 20,000; extra titles are dropped with a warning |
| `parts` | — | 50 |
| Streams per movie/episode | ≤ 10 | 50 (rest ignored) |
| Subtitles per movie/episode | ≤ 10 | 50 (rest ignored) |

The client accepts `Content-Encoding: gzip`. Use it; JSON compresses about 10×.

## 11. Large catalogs: `parts`

The main feed MAY list `parts`: URLs of further JSON documents. Each part looks like this:

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `rass_feed_version` | integer, `1` | yes | |
| `kind` | `"part"` | yes | Marks the document as a part. |
| `updated_at` | date-time | no | |
| `test` | boolean | no | Same meaning as in the main feed. Set it in parts of a test feed so the schema checks licenses there too. |
| `genres` | array of Genre | no | |
| `titles` | array of Title | yes | |

Rules:
- The client fetches all parts (up to 4 at a time) after the main feed. Parts are only read from the main feed's
  `parts`; a part's own `parts` or `featured`/`top10` are ignored (no nesting, no loops).
- The merged catalog of the source is: main `genres` then each part's `genres` in `parts` order; main `titles`
  then each part's `titles` in `parts` order. Ids must be unique across all of them (§10.1).
- `featured`/`top10` in the main feed may point at titles that are in parts.
- A part's relative URLs resolve against the part's own URL. Parts may live on another origin; the access token
  goes only to the feed's origin (§14).
- The source's test is "fully loaded" only when every part loaded; otherwise Settings shows "n of m parts loaded".

## 12. Merging several sources

Settings lists sources in a user-defined order. **Rank 0 is the top source** and wins every tie. Disabled
sources are not merged. Each source contributes its validated catalog (main + parts), or its last good cached
catalog if the refresh failed.

Feed-local `id`s are **never** compared across sources (two sites can both use `"1"`). Cross-source matching
uses `ids` and titles (below). This refines R1.4: "same title" = same `ids.tmdb` / `ids.imdb`, or same normalized
title and year.

### 12.1 Matching

The client walks the sources in rank order and, inside each source, the titles in document order. Each title
`C` either joins an existing **merged title** `M` or starts a new one. `M` keeps:
`M.type`; `M.tmdb` = the first non-missing `ids.tmdb` among its members in rank order; `M.imdb` likewise;
`M.year` = the first non-missing `year`; and the normalized `title_en`, `title_fa` and `original_title` of every
member.

`C` matches `M` when `C.type == M.type`, `M` has no member from `C`'s own source, and:

1. if `C.ids.tmdb` and `M.tmdb` are both present: they are equal (this decides; skip 2–3);
2. else if `C.ids.imdb` and `M.imdb` are both present: they are equal, case-insensitively (decides; skip 3);
3. else: `C.year` and `M.year` are both present and equal, **and** at least one of C's normalized `title_en`,
   `title_fa`, `original_title` equals the same field (normalized, non-empty) of some member of `M`.

If several merged titles match, `C` joins the one created first. Titles within one source never merge with each
other.

**Normalization** (shared with search, R7.2), applied in this order:
Unicode NFKC → lowercase (locale-independent) → `ي` (U+064A) and `ى` (U+0649) → `ی` (U+06CC); `ك` (U+0643) →
`ک` (U+06A9) → Persian (U+06F0–06F9) and Arabic-Indic (U+0660–0669) digits → ASCII digits → remove ZWNJ (U+200C),
ZWJ (U+200D) and tatweel (U+0640) → NFD and remove all combining marks (Unicode category Mn) → replace every
character that is not a letter or digit with a space → collapse runs of spaces → trim.
Example: `"Amélie  (2001)"` → `"amelie 2001"`; `"كارآگاه‌ها"` → `"کارآگاهها"`.

### 12.2 What the merged title contains

Members are ordered by rank; the first is the **primary**. The merged title keeps the list of its member sources
(for source badges and the per-source filter in search and browse, R7.5).

- **Metadata fields** (`title_fa`, `title_en`, `original_title`, `year`, `rating`, descriptions, images,
  `added_at`, `runtime_min`, `trailer`, `license`): taken from the primary; a field missing in the primary comes
  from the next member that has it. List fields (`genres`, `cast`) are taken whole from the first member with a
  non-empty list (no union).
- **Movie streams:** the candidate list of §7.2 is built from all members' streams; source rank keeps the
  primary's streams first, so other sources become **backup sources** automatically (R5.6).
- **Subtitles (R6.6):** all members' fa/en subtitles, the primary's first, deduplicated by resolved URL, each
  labeled with its source. They are offered whichever member's stream plays, and subtitle-only members (§5)
  contribute too. The primary is the highest-ranked member **with streams**.
- **Markers:** per member (§9).
- **Series:** seasons are united by `number`; episodes by (season `number`, episode `number`). Each merged
  episode takes metadata from the first member that has that episode (same field-fallback rule), and its candidate
  list and subtitles from every member that has it. An episode that exists only in a lower source is added.

### 12.3 `featured` and `top10` across sources

- `featured`: concatenate each source's `featured` in rank order, map every id to its merged title, remove
  duplicates and titles without a backdrop, keep the first 10.
- `top10`: taken from the highest-ranked source whose `top10` has at least one valid entry; never mixed.

### 12.4 Genres across sources

Genres are united by `id`, compared case-insensitively. The name comes from the highest-ranked source that defines
that id. Using the recommended ids (§3.1) makes genres from different sites line up.

### 12.5 Keys for local data

Watch progress, My List and history are stored under a **canonical key** so they survive feed changes and
match across sources:

- `tmdb:movie:<n>` / `tmdb:series:<n>` when `M.tmdb` exists;
- else `imdb:<id>` (lowercase) when `M.imdb` exists;
- else `src:<source-uuid>:<id>` using the primary's source (the client-generated UUID of the Settings entry) and
  title id.

Episode key: `<title key>#s<season number>e<episode number>`. If a title's key changes (a feed adds `ids` later),
the client MAY migrate stored data from the old key.

## 13. Discovery: add a site by its address (R1.5, R1.7)

The user types an address (or scans a QR code that contains it). The client:

1. **Normalizes the input:** trims it; if there is no scheme, prepends `https://`. Only `http`/`https` are
   accepted.
2. **Tries the input itself** if it has a path other than `/`: GET it. If the response is a valid feed → done
   (this is the feed URL). If it is HTML (`text/html` or `application/xhtml+xml`), look for the `<link>` tag (step 5)
   in that page.
3. GET `<origin>/.well-known/rass-feed.json`.
4. GET `<origin>/rass-feed.json`.
5. GET `<origin>/` and, if the response is HTML, read the first 512 KB and take the **first** element that is
   `<link rel="alternate" type="application/vnd.rass.feed+json" href="…">`. `rel` is a space-separated list that
   must contain `alternate` (case-insensitive); `type` must be `application/vnd.rass.feed+json`
   (case-insensitive, parameters ignored); `href` is resolved against the page URL (respecting `<base href>`).
   GET that URL.
6. If none of these yields a valid feed: "No Rass_player feed was found on this site", with a pointer to the
   site setup guide (§18).

A step "yields a valid feed" when the final response has status 200 and the body passes the fatal checks of §10.1.
A 401/403 at any step stops discovery with "This site needs an access token" (so the user can add one, §14).

- **Redirects:** followed, at most 5 per request, including `http`→`https`. An `https`→`http` redirect is
  followed only after asking the user.
- **No TLS:** if the input had no scheme and the `https` connection fails (connect/TLS error, not an HTTP error),
  the client repeats discovery once with `http://` and marks the source "not secure".
- The **saved feed URL** is the URL that was requested in the successful step (before redirects), so the site
  can move the file with a redirect later.
- **Content type:** feeds SHOULD be served as `application/vnd.rass.feed+json` (or `application/json`). The
  client doesn't require it, because static hosts often send `text/plain` or `application/octet-stream`; it
  judges the body.
- The client never renders the site's HTML and never runs its scripts. Step 5 is a plain text scan for the tag.

## 14. Access tokens and headers (R1.6)

A source in Settings may have **one** access credential, configured by the user (never by the feed):

- **Bearer token:** sent as `Authorization: Bearer <token>`.
- **Custom header:** a user-chosen header name and value, e.g. `X-Feed-Key: abc123`. The name must be an RFC 7230
  token and MUST NOT be one of `Host`, `Referer`, `Origin`, `User-Agent`, `Cookie`, `Authorization` (use the
  Bearer option), `Content-Length`, `Transfer-Encoding`, `Connection`, or start with `Sec-` or `Proxy-`.

Where it is sent:

- Only to requests whose **origin** (scheme + host + port) equals the origin of the source's feed URL: the feed,
  parts, images, subtitles and streams on that same origin, and the discovery requests of §13.
- **Never** to another origin, and never after a redirect that leaves the origin — unless the user turns on
  "Also send to media servers" for that source. Then it is also sent to the origins of URLs that appear in that
  source's own feed (parts, images, subtitles, streams), and still never to other sources' URLs.
- Never over `http` without the warning of §2.1 having been accepted.

Tokens are stored encrypted and never logged (CLAUDE.md §3).

## 15. Caching and refresh

- **Refresh interval** per source (R1.6): 15 min, 1 h, **6 h (default)**, 24 h, or manual only.
- On app start the client shows the cached catalog immediately and refreshes in the background every source whose
  catalog is older than its interval. Pull-to-refresh / "Refresh now" ignores the interval.
- **Conditional requests:** the client stores `ETag` and `Last-Modified` per document (main feed and each part)
  and sends `If-None-Match` / `If-Modified-Since`. A `304` means the cached copy is current.
- **Cache-Control:** `max-age=N` longer than the refresh interval postpones automatic refresh to N seconds
  (capped at 24 h). `no-cache` changes nothing (requests are conditional anyway). `no-store` is not honored for
  the catalog itself: the last good copy is always kept so browsing works offline (N3).
- The last good copy of every document is kept until the source is deleted or its URL changes (changing the URL
  clears that source's cache, CLAUDE.md §3).
- Images and subtitles use the HTTP cache with normal header rules (disk-cache size limits apply).
- Feed authors: send `ETag` or `Last-Modified`, and something like `Cache-Control: max-age=300`.

## 16. `update.json` (app updates, R8)

The user hosts a small JSON file and puts its URL in Settings → Updates. It is validated by
`#/$defs/update_manifest` in `feed-schema.json`.

```json
{
  "rass_update_version": 1,
  "package": "nl.rass.player",
  "version_code": 12,
  "version_name": "0.4.0",
  "min_sdk": 24,
  "released_at": "2026-11-01T10:00:00Z",
  "apks": [
    { "abi": "universal", "url": "rass-player-0.4.0.apk", "sha256": "<64 hex chars>", "size_bytes": 31457280 }
  ],
  "signing_cert_sha256": ["<64 hex chars>"],
  "release_notes_fa": "پخش‌کننده سریع‌تر شد."
}
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `rass_update_version` | integer, `1` | yes | |
| `package` | `"nl.rass.player"` | yes | Must equal the app's id; otherwise the file is rejected. |
| `version_code` | integer ≥ 1 | yes | Android versionCode. An update is offered only if it is greater than the installed one. |
| `version_name` | short text | yes | Shown to the user. |
| `min_sdk` | integer 21–100 | yes | Not offered if the device's API level is lower. |
| `released_at` | date-time | no | |
| `apks` | array, 1–5 | yes | One entry per ABI, or one `universal`. |
| `apks[].abi` | `"universal"` \| `"armeabi-v7a"` \| `"arm64-v8a"` \| `"x86_64"` \| `"x86"` | yes | Unique within `apks` (client check; the schema can't express it). If duplicated, the first entry wins. |
| `apks[].url` | URL | yes | Relative URLs resolve against `update.json`'s URL. |
| `apks[].sha256` | 64 hex chars (case-insensitive) | yes | SHA-256 of the APK file. |
| `apks[].size_bytes` | integer ≥ 1 | no | Shown before download; a mismatch fails the update. |
| `signing_cert_sha256` | array of 64-hex strings (colons allowed between byte pairs), 1–5 | yes | SHA-256 of the APK signing certificate(s). |
| `release_notes_fa` | long text | yes | Shown in the update dialog. |
| `release_notes_en` | long text | no | |
| `mandatory` | boolean | no | Default `false`. If `true`, the "Later" button is hidden (the user can still leave the dialog with Back). |

APK choice: walk the device's ABI list (`Build.SUPPORTED_ABIS`, most preferred first) and take the first ABI
that has an entry; if none, take `universal`; if none, "No update for this device".

Install steps (R8.3): download → check `size_bytes` (if given) → check `sha256` → read the APK's signing
certificate and require that its SHA-256 is in `signing_cert_sha256` **and** equals the installed app's
certificate → hand the file to the Android package installer. Any failed check deletes the download and shows the
reason. The client sends no token for `update.json` and follows the same redirect rules as §13.

## 17. Complete minimal example

One movie (with quality variants, a backup server, fa+en subtitles, markers) and one series with 2 seasons × 2
episodes. The same file is [`feed-examples/minimal.json`](feed-examples/minimal.json) and validates against the
schema. Hosts are `example.com` placeholders; relative URLs resolve against the feed URL.

```json
{
  "rass_feed_version": 1,
  "name": "Example feed",
  "updated_at": "2026-10-15T12:00:00Z",
  "featured": ["bbb"],
  "top10": ["bbb", "sample-series"],
  "genres": [
    { "id": "animation", "name_fa": "انیمیشن", "name_en": "Animation" },
    { "id": "comedy", "name_fa": "کمدی", "name_en": "Comedy" },
    { "id": "drama", "name_fa": "درام", "name_en": "Drama" }
  ],
  "titles": [
    {
      "id": "bbb",
      "type": "movie",
      "title_fa": "باک بانی بزرگ",
      "title_en": "Big Buck Bunny",
      "year": 2008,
      "rating": 6.5,
      "genres": ["animation", "comedy"],
      "description_fa": "خرگوشی بزرگ و آرام از سه جونده‌ی مزاحم انتقام می‌گیرد.",
      "description_en": "A large, gentle rabbit takes revenge on three rodents who bully him.",
      "poster": {
        "url": "https://media.example.com/bbb/poster.jpg",
        "sizes": [
          { "width": 185, "url": "https://media.example.com/bbb/poster-185.jpg" },
          { "width": 342, "url": "https://media.example.com/bbb/poster-342.jpg" }
        ]
      },
      "backdrop": {
        "url": "https://img.example.com/w1920/bbb-backdrop.jpg",
        "template": "https://img.example.com/w{width}/bbb-backdrop.jpg",
        "widths": [780, 1280, 1920]
      },
      "added_at": "2026-10-01",
      "runtime_min": 10,
      "cast": [{ "name": "Sacha Goedegebure", "role": "Director" }],
      "trailer": { "url": "https://media.example.com/bbb/trailer.mp4", "type": "mp4" },
      "license": {
        "name": "CC BY 3.0",
        "url": "https://creativecommons.org/licenses/by/3.0/",
        "attribution": "(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org"
      },
      "ids": { "tmdb": 10378, "imdb": "tt1254207" },
      "streams": [
        { "url": "https://media.example.com/bbb/master.m3u8", "type": "hls", "quality": "auto", "priority": 0 },
        { "url": "https://media.example.com/bbb/bbb-1080.mp4", "type": "mp4", "quality": "1080p", "priority": 0, "video_codec": "h264" },
        { "url": "https://media.example.com/bbb/bbb-720.mp4", "type": "mp4", "quality": "720p", "priority": 0, "video_codec": "h264" },
        { "url": "https://backup.example.net/bbb/bbb-720.mp4", "type": "mp4", "quality": "720p", "priority": 1, "label": "Backup" }
      ],
      "subtitles": [
        { "lang": "fa", "label": "فارسی", "url": "subs/bbb.fa.srt", "format": "srt", "encoding": "windows-1256" },
        { "lang": "en", "label": "English", "url": "subs/bbb.en.vtt", "format": "vtt" }
      ],
      "markers": { "credits_start": 568 }
    },
    {
      "id": "sample-series",
      "type": "series",
      "title_fa": "سریال نمونه",
      "title_en": "Sample Series",
      "year": 2024,
      "genres": ["drama"],
      "description_fa": "یک سریال نمونه برای آزمایش فصل‌ها و قسمت‌ها.",
      "poster": "images/sample-series-poster.jpg",
      "backdrop": "images/sample-series-backdrop.jpg",
      "added_at": "2026-10-10T08:00:00Z",
      "runtime_min": 25,
      "license": { "name": "CC0 1.0", "url": "https://creativecommons.org/publicdomain/zero/1.0/" },
      "seasons": [
        {
          "number": 1,
          "episodes": [
            {
              "id": "s1e1", "number": 1, "title_fa": "آغاز", "title_en": "The Beginning",
              "still": "images/s1e1.jpg", "runtime_min": 24, "air_date": "2024-01-05",
              "streams": [{ "url": "media/s1e1/master.m3u8", "type": "hls", "quality": "auto" }],
              "subtitles": [
                { "lang": "fa", "url": "media/s1e1/fa.srt", "format": "srt" },
                { "lang": "en", "url": "media/s1e1/en.srt", "format": "srt" }
              ],
              "markers": { "intro_start": 30, "intro_end": 85, "credits_start": 1380 }
            },
            {
              "id": "s1e2", "number": 2, "title_fa": "راه", "title_en": "The Road",
              "still": "images/s1e2.jpg", "runtime_min": 25, "air_date": "2024-01-12",
              "streams": [{ "url": "media/s1e2/master.m3u8", "type": "hls", "quality": "auto" }],
              "subtitles": [
                { "lang": "fa", "url": "media/s1e2/fa.srt", "format": "srt" },
                { "lang": "en", "url": "media/s1e2/en.srt", "format": "srt" }
              ],
              "markers": { "recap_start": 0, "recap_end": 40, "intro_start": 40, "intro_end": 95, "credits_start": 1420 }
            }
          ]
        },
        {
          "number": 2,
          "name": "فصل دوم",
          "episodes": [
            {
              "id": "s2e1", "number": 1, "title_fa": "بازگشت", "title_en": "The Return",
              "still": "images/s2e1.jpg", "runtime_min": 26, "air_date": "2025-02-02",
              "added_at": "2026-10-10T08:00:00Z",
              "streams": [
                { "url": "media/s2e1/1080.mp4", "type": "mp4", "quality": "1080p" },
                { "url": "media/s2e1/720.mp4", "type": "mp4", "quality": "720p" }
              ],
              "subtitles": [
                { "lang": "fa", "url": "media/s2e1/fa.vtt", "format": "vtt" },
                { "lang": "en", "url": "media/s2e1/en.vtt", "format": "vtt" }
              ]
            },
            {
              "id": "s2e2", "number": 2, "title_fa": "پایان", "title_en": "The End",
              "still": "images/s2e2.jpg", "runtime_min": 27, "air_date": "2025-02-09",
              "added_at": "2026-10-10T08:00:00Z",
              "streams": [
                { "url": "media/s2e2/1080.mp4", "type": "mp4", "quality": "1080p" },
                { "url": "media/s2e2/720.mp4", "type": "mp4", "quality": "720p" }
              ],
              "subtitles": [
                { "lang": "fa", "url": "media/s2e2/fa.vtt", "format": "vtt" },
                { "lang": "en", "url": "media/s2e2/en.vtt", "format": "vtt" }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

## 18. Site setup guide (plain language)

This section is for the person who runs the website. You don't need to be a programmer.

**What you are making:** one text file in JSON format that lists your movies and series and where the video files
are. Rass_player reads that file. It doesn't read your web pages.

### Option A: a static file (easiest)

1. Copy `feed-examples/minimal.json` and edit it: one entry per movie or series, with your own titles and the
   links to your video files (`.mp4` or `.m3u8`), posters and subtitles.
2. Check it: open https://www.jsonschemavalidator.net/ (or any JSON Schema validator), paste
   `feed-schema.json` on the left and your file on the right. Fix anything it reports.
3. Upload it to your site as **`/.well-known/rass-feed.json`** (create a folder named `.well-known` in your site's
   main folder). If your host doesn't allow folders starting with a dot, upload it as **`/rass-feed.json`**.
4. On the TV: Settings → Sources → Add → type your site address (`mysite.nl`) → Test. The app finds the file
   by itself.

Alternative to step 3: put the file anywhere and add this line inside `<head>` of your home page:

```html
<link rel="alternate" type="application/vnd.rass.feed+json" href="/files/my-feed.json">
```

### Option B: a small script that builds the feed for you

If your list changes often, let a script write the JSON:

- **PHP hosting** (most shared hosting): `feed-examples/rass-feed.php`. Fill in the `$CATALOG` list at the top,
  upload the file, and add the rewrite line from the comment at the top of the file so it answers at
  `/.well-known/rass-feed.json`. Or run `php rass-feed.php > rass-feed.json` on your computer and upload the
  result as a static file (option A).
- **A computer or NAS with Python 3**: `feed-examples/rass_feed.py`. Point it at a folder of video files:
  `python3 rass_feed.py serve --media-dir /path/to/videos --serve-media --port 8080`. It turns file names into
  titles (`Movie Name (2019).mp4`, `Series Name (2020)/S01E02 Title.mp4`), picks up subtitles next to the videos
  (`Movie Name (2019).fa.srt`), and serves the feed at `/.well-known/rass-feed.json`.
  `python3 rass_feed.py build --media-dir … --base-url https://mysite.nl/videos/ > rass-feed.json` writes a
  static file instead.

### Things you do *not* need

- **No CORS settings.** Rass_player is an app, not a web page, so browser cross-origin rules don't apply.
- **No special player page or embed code.** Only direct links to the video files.
- **No accounts in the app.** If you want privacy, use a token (below).

### Making the feed private with a token

1. Pick a long random secret, e.g. 32 letters and digits.
2. In the PHP or Python example, set the token (`$TOKEN = '…'` in PHP; `--token …` or the `RASS_FEED_TOKEN`
   environment variable in Python). The script then answers `401` to anyone who doesn't send
   `Authorization: Bearer <your token>`.
3. On the TV: Settings → Sources → your source → Access token → enter the same secret.
4. The app sends the token only to your own site's address. If your videos are on a different server that also
   checks the token, turn on "Also send to media servers" for that source.
5. Use `https` if you can; over `http` the token can be read by anyone on the network path.

Apache note: some hosts hide the `Authorization` header from PHP. If the token check always fails, add
`CGIPassAuth On` to `.htaccess` (or use a custom header such as `X-Feed-Key` in the app and the script).

### Tips

- Keep `id`s fixed forever; the TV uses them to remember where you stopped.
- Start your own file links with `/` (`/videos/film.mp4`) or write them in full (`https://mysite.nl/videos/film.mp4`).
- Add `ids.tmdb` or `ids.imdb` when you know them, so the same film on two of your sites is shown once with
  both as sources.
- Offer small posters (`sizes`), and gzip the feed (most servers do this automatically for `.json`).
- After changing the file, update `updated_at`. The TV checks again at its refresh interval (default 6 hours) or
  when you press Refresh.
