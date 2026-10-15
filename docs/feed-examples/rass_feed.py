#!/usr/bin/env python3
"""Tiny Rass_player feed builder/server (feed format v1, see docs/feed-spec.md).

Python 3.8+, standard library only.

Two ways to describe your catalog:

1. Edit the CATALOG list below (used when --media-dir is not given).
2. Point it at a folder of video files (--media-dir). File name conventions:

       Movie Name (2019).mp4             -> movie "Movie Name", year 2019
       Movie Name (2019).fa.srt          -> Persian subtitle (also .en.srt, .fa.vtt, .en.vtt)
       Movie Name (2019).jpg             -> poster (.jpg/.jpeg/.png/.webp)
       Movie Name (2019)-backdrop.jpg    -> backdrop
       Series Name (2020)/               -> a series (one folder per series)
           poster.jpg, backdrop.jpg      -> series artwork
           S01E02 Episode title.mp4      -> season 1, episode 2
           S01E02 Episode title.fa.srt   -> its Persian subtitle
           S01E02 Episode title.jpg      -> its thumbnail

   Video files: .mp4, .m4v (played as mp4) and .m3u8 (played as HLS).
   Titles containing Persian letters become title_fa, others title_en.
   Ids are derived from the file path, so keep file names stable.

Commands:

    # write a static feed file (upload it as /.well-known/rass-feed.json)
    python3 rass_feed.py build --media-dir ./videos --base-url https://mysite.nl/videos/ > rass-feed.json

    # serve the feed (and optionally the videos) from this computer
    python3 rass_feed.py serve --media-dir ./videos --serve-media --port 8080 [--token SECRET]

The server answers at /.well-known/rass-feed.json and /rass-feed.json. With --serve-media the files of
--media-dir are served under /media/ (with Range support, so seeking works). With a token (--token or the
RASS_FEED_TOKEN environment variable) every request must carry "Authorization: Bearer <token>".
For real use on the internet, put it behind a web server with HTTPS.
"""

import argparse
import datetime as dt
import hashlib
import hmac
import json
import mimetypes
import os
import re
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FEED_NAME = "My videos"

# Used when --media-dir is not given. Same fields as the feed spec. Relative URLs resolve against the feed URL;
# start them with "/" (as here), because the feed lives in /.well-known/.
CATALOG = [
    {
        "id": "my-first-movie",
        "type": "movie",
        "title_fa": "اولین فیلم من",
        "title_en": "My First Movie",
        "year": 2024,
        "poster": "/media/my-first-movie.jpg",
        "streams": [{"url": "/media/my-first-movie.mp4", "type": "mp4", "quality": "1080p"}],
        "subtitles": [{"lang": "fa", "url": "/media/my-first-movie.fa.srt", "format": "srt"}],
    },
]

DEFAULT_MP4_QUALITY = "1080p"  # label for .mp4 files; the script cannot know the real height
VIDEO_TYPES = {".mp4": "mp4", ".m4v": "mp4", ".m3u8": "hls"}
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")
SUB_RE = re.compile(r"^(?P<stem>.+)\.(?P<lang>fa|en)\.(?P<fmt>srt|vtt)$", re.I)
NAME_RE = re.compile(r"^(?P<name>.+?)\s*\((?P<year>\d{4})\)\s*$")
EPISODE_RE = re.compile(r"^[Ss](?P<s>\d{1,4})[Ee](?P<e>\d{1,5})(?:\s*[-.]?\s*(?P<title>.*))?$")
PERSIAN_RE = re.compile(r"[؀-ۿ]")
FEED_PATHS = ("/.well-known/rass-feed.json", "/rass-feed.json")


def newest_mtime_iso(root):
    """Content date = newest modification time in the folder tree (stable, so the ETag stays stable)."""
    newest = os.path.getmtime(root)
    for folder, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for name in dirs + files:
            newest = max(newest, os.path.getmtime(os.path.join(folder, name)))
    t = dt.datetime.fromtimestamp(newest, dt.timezone.utc).replace(microsecond=0)
    return t.isoformat().replace("+00:00", "Z")


def mtime_iso(path):
    t = dt.datetime.fromtimestamp(os.path.getmtime(path), dt.timezone.utc).replace(microsecond=0)
    return t.isoformat().replace("+00:00", "Z")


def make_id(prefix, rel_path):
    return prefix + "-" + hashlib.sha1(rel_path.encode("utf-8")).hexdigest()[:12]


def split_name(name):
    """'Movie Name (2019)' -> ('Movie Name', 2019); 'Movie Name' -> ('Movie Name', None)."""
    m = NAME_RE.match(name)
    if m:
        return m.group("name").strip(), int(m.group("year"))
    return name.strip(), None


def title_fields(name):
    key = "title_fa" if PERSIAN_RE.search(name) else "title_en"
    return {key: name[:300]}


class UrlMaker:
    def __init__(self, base_url):
        self.base = base_url if base_url.endswith("/") else base_url + "/"

    def __call__(self, rel_path):
        return self.base + urllib.parse.quote(rel_path.replace(os.sep, "/"))


def find_sidecars(folder, stem, files, url):
    """Subtitles (stem.fa.srt ...) and an image (stem.jpg ...) next to a video."""
    subs, image = [], None
    for f in sorted(files):
        m = SUB_RE.match(f)
        if m and m.group("stem") == stem:
            subs.append({"lang": m.group("lang").lower(), "url": url(os.path.join(folder, f)),
                         "format": m.group("fmt").lower()})
        elif os.path.splitext(f)[0] == stem and f.lower().endswith(IMAGE_EXTS):
            image = url(os.path.join(folder, f))
    return subs, image


def find_image(folder, stem, files, url):
    for f in sorted(files):
        if os.path.splitext(f)[0] == stem and f.lower().endswith(IMAGE_EXTS):
            return url(os.path.join(folder, f))
    return None


def scan_media_dir(media_dir, url):
    titles = []
    entries = sorted(os.listdir(media_dir))
    top_files = [e for e in entries if os.path.isfile(os.path.join(media_dir, e))]

    # Movies: video files directly in the media folder.
    for f in top_files:
        stem, ext = os.path.splitext(f)
        if ext.lower() not in VIDEO_TYPES:
            continue
        name, year = split_name(stem)
        subs, poster = find_sidecars("", stem, top_files, url)
        title = {"id": make_id("m", f), "type": "movie", **title_fields(name)}
        if year:
            title["year"] = year
        if poster:
            title["poster"] = poster
        backdrop = find_image("", stem + "-backdrop", top_files, url)
        if backdrop:
            title["backdrop"] = backdrop
        title["added_at"] = mtime_iso(os.path.join(media_dir, f))
        kind = VIDEO_TYPES[ext.lower()]
        title["streams"] = [{"url": url(f), "type": kind, "quality": "auto" if kind == "hls" else DEFAULT_MP4_QUALITY}]
        if subs:
            title["subtitles"] = subs
        titles.append(title)

    # Series: one sub-folder per series, episodes named SxxEyy.
    for d in entries:
        folder = os.path.join(media_dir, d)
        if not os.path.isdir(folder) or d.startswith("."):
            continue
        files = sorted(e for e in os.listdir(folder) if os.path.isfile(os.path.join(folder, e)))
        seasons = {}
        for f in files:
            stem, ext = os.path.splitext(f)
            m = EPISODE_RE.match(stem)
            if ext.lower() not in VIDEO_TYPES or not m:
                continue
            s, e = int(m.group("s")), int(m.group("e"))
            subs, still = find_sidecars(d, stem, files, url)
            ep = {"id": "s%de%d" % (s, e), "number": e}
            ep_title = (m.group("title") or "").strip()
            if ep_title:
                ep.update(title_fields(ep_title))
            if still:
                ep["still"] = still
            ep["added_at"] = mtime_iso(os.path.join(folder, f))
            kind = VIDEO_TYPES[ext.lower()]
            ep["streams"] = [{"url": url(os.path.join(d, f)), "type": kind,
                              "quality": "auto" if kind == "hls" else DEFAULT_MP4_QUALITY}]
            if subs:
                ep["subtitles"] = subs
            season = seasons.setdefault(s, {})
            season.setdefault(e, ep)  # first file wins if two have the same SxxEyy
        if not seasons:
            continue
        name, year = split_name(d)
        title = {"id": make_id("s", d), "type": "series", **title_fields(name)}
        if year:
            title["year"] = year
        for key, stem in (("poster", "poster"), ("backdrop", "backdrop")):
            img = find_image(d, stem, files, url)
            if img:
                title[key] = img
        title["added_at"] = mtime_iso(folder)
        title["seasons"] = [
            {"number": s, "episodes": [eps[e] for e in sorted(eps)]} for s, eps in sorted(seasons.items())
        ]
        titles.append(title)
    return titles


def build_feed(args):
    if args.media_dir:
        base = args.base_url or "/media/"
        titles = scan_media_dir(args.media_dir, UrlMaker(base))
        updated = newest_mtime_iso(args.media_dir)
    else:
        titles = CATALOG
        updated = mtime_iso(os.path.abspath(__file__))  # edit the CATALOG -> the date changes
    return {
        "rass_feed_version": 1,
        "name": args.name,
        "updated_at": updated,
        "titles": titles,
    }


def feed_bytes(args):
    return json.dumps(build_feed(args), ensure_ascii=False, indent=1).encode("utf-8")


def make_handler(args, token):
    media_root = os.path.realpath(args.media_dir) if (args.media_dir and args.serve_media) else None

    class Handler(BaseHTTPRequestHandler):
        server_version = "rass-feed/1"

        def authorized(self):
            if not token:
                return True
            got = self.headers.get("Authorization", "")
            return hmac.compare_digest(got.encode("utf-8"), ("Bearer " + token).encode("utf-8"))

        def do_HEAD(self):
            self.do_GET(head=True)

        def do_GET(self, head=False):
            path = urllib.parse.urlsplit(self.path).path
            if not self.authorized():
                return self.reply(401, b"unauthorized\n", "text/plain", head,
                                  {"WWW-Authenticate": 'Bearer realm="rass-feed"'})
            if path in FEED_PATHS:
                return self.send_feed(head)
            if media_root and path.startswith("/media/"):
                return self.send_media(path[len("/media/"):], head)
            return self.reply(404, b"not found\n", "text/plain", head)

        def send_feed(self, head):
            body = feed_bytes(args)  # rebuilt per request so new files show up
            etag = '"%s"' % hashlib.sha256(body).hexdigest()[:32]
            extra = {"ETag": etag, "Cache-Control": "max-age=300"}
            if self.headers.get("If-None-Match") == etag:
                return self.reply(304, b"", None, True, extra)
            self.reply(200, body, "application/vnd.rass.feed+json", head, extra)

        def send_media(self, rel, head):
            full = os.path.realpath(os.path.join(media_root, urllib.parse.unquote(rel)))
            if not full.startswith(media_root + os.sep) or not os.path.isfile(full):
                return self.reply(404, b"not found\n", "text/plain", head)
            size = os.path.getsize(full)
            ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
            start, end, status = 0, size - 1, 200
            m = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", ""))
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
                else:  # suffix range: last N bytes
                    start = max(size - int(m.group(2)), 0)
                if start > end or start >= size:
                    return self.reply(416, b"", None, head, {"Content-Range": "bytes */%d" % size})
                status = 206
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.end_headers()
            if head:
                return
            with open(full, "rb") as fh:
                fh.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = fh.read(min(1 << 16, remaining))
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return  # the player closed the connection (normal when seeking)
                    remaining -= len(chunk)

        def reply(self, status, body, ctype, head, extra=None):
            self.send_response(status)
            if ctype:
                self.send_header("Content-Type", ctype)
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if not head and body:
                self.wfile.write(body)

        def log_message(self, fmt, *a):  # never log the Authorization header; path only
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % a))

    return Handler


def main():
    p = argparse.ArgumentParser(description="Build or serve a Rass_player feed (format v1).")
    p.add_argument("command", choices=["build", "serve"])
    p.add_argument("--media-dir", help="folder of video files to turn into a feed")
    p.add_argument("--base-url", help="URL prefix for files in --media-dir (default: /media/ on this server)")
    p.add_argument("--name", default=FEED_NAME, help="feed name")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--serve-media", action="store_true", help="also serve --media-dir under /media/")
    p.add_argument("--token", default=os.environ.get("RASS_FEED_TOKEN"),
                   help="require Authorization: Bearer <token> (default: $RASS_FEED_TOKEN)")
    args = p.parse_args()
    if args.media_dir and not os.path.isdir(args.media_dir):
        p.error("--media-dir is not a folder: %s" % args.media_dir)
    if args.serve_media and not args.media_dir:
        p.error("--serve-media needs --media-dir")

    if args.command == "build":
        sys.stdout.buffer.write(feed_bytes(args) + b"\n")
        return
    server = ThreadingHTTPServer((args.host, args.port), make_handler(args, args.token))
    print("Feed at http://%s:%d/.well-known/rass-feed.json%s" % (
        args.host, args.port, " (token required)" if args.token else ""), file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
