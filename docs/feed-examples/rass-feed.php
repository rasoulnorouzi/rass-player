<?php
/*
 * Tiny Rass_player feed (format v1, see docs/feed-spec.md) for ordinary PHP hosting. PHP 7.0+.
 *
 * 1. Fill in $CATALOG below (one entry per movie or series).
 * 2. Upload this file to your site's main folder.
 * 3. Make it answer at /.well-known/rass-feed.json. With Apache, put this in .htaccess next to it:
 *
 *        RewriteEngine On
 *        RewriteRule ^\.well-known/rass-feed\.json$ rass-feed.php [L]
 *        RewriteRule ^rass-feed\.json$ rass-feed.php [L]
 *        # only if the token check always fails (host hides the Authorization header):
 *        CGIPassAuth On
 *
 *    With nginx:   location = /.well-known/rass-feed.json { rewrite ^ /rass-feed.php last; }
 *
 *    No rewrite possible? Add this to your home page's <head> instead:
 *        <link rel="alternate" type="application/vnd.rass.feed+json" href="/rass-feed.php">
 *
 * Static file instead: run `php rass-feed.php > rass-feed.json` on your computer and upload rass-feed.json
 * to /.well-known/rass-feed.json.
 *
 * Relative URLs resolve against the feed URL (/.well-known/rass-feed.json), so start them with "/".
 */

// Optional: set a long random secret to make the feed private. The TV sends "Authorization: Bearer <token>".
// Leave empty ('') for a public feed.
$TOKEN = '';

// Optional: also accept the token in a custom header (set the same name in the app), e.g. 'X-Feed-Key'.
$CUSTOM_HEADER = '';

$FEED_NAME = 'My site';

$GENRES = [
    ['id' => 'drama', 'name_fa' => 'درام', 'name_en' => 'Drama'],
    ['id' => 'comedy', 'name_fa' => 'کمدی', 'name_en' => 'Comedy'],
];

$CATALOG = [
    [
        'id' => 'my-first-movie',              // never change an id once published
        'type' => 'movie',
        'title_fa' => 'اولین فیلم من',
        'title_en' => 'My First Movie',
        'year' => 2024,
        'genres' => ['drama'],
        'poster' => '/videos/my-first-movie.jpg',
        'added_at' => '2026-10-15',
        'streams' => [
            ['url' => '/videos/my-first-movie-1080.mp4', 'type' => 'mp4', 'quality' => '1080p'],
            ['url' => '/videos/my-first-movie-720.mp4', 'type' => 'mp4', 'quality' => '720p'],
        ],
        'subtitles' => [
            ['lang' => 'fa', 'url' => '/videos/my-first-movie.fa.srt', 'format' => 'srt', 'encoding' => 'windows-1256'],
            ['lang' => 'en', 'url' => '/videos/my-first-movie.en.srt', 'format' => 'srt'],
        ],
    ],
    [
        'id' => 'my-series',
        'type' => 'series',
        'title_fa' => 'سریال من',
        'year' => 2025,
        'poster' => '/videos/my-series/poster.jpg',
        'seasons' => [
            [
                'number' => 1,
                'episodes' => [
                    [
                        'id' => 's1e1',
                        'number' => 1,
                        'title_fa' => 'قسمت اول',
                        'streams' => [['url' => '/videos/my-series/s01e01.mp4', 'type' => 'mp4', 'quality' => '720p']],
                    ],
                    [
                        'id' => 's1e2',
                        'number' => 2,
                        'title_fa' => 'قسمت دوم',
                        'streams' => [['url' => '/videos/my-series/s01e02.mp4', 'type' => 'mp4', 'quality' => '720p']],
                    ],
                ],
            ],
        ],
    ],
];

// ---------------------------------------------------------------------------------------------------------

$is_cli = (PHP_SAPI === 'cli');

function request_header($name)
{
    $key = 'HTTP_' . strtoupper(str_replace('-', '_', $name));
    if (isset($_SERVER[$key])) {
        return $_SERVER[$key];
    }
    if ($name === 'Authorization' && isset($_SERVER['REDIRECT_HTTP_AUTHORIZATION'])) {
        return $_SERVER['REDIRECT_HTTP_AUTHORIZATION'];
    }
    return '';
}

if (!$is_cli && $TOKEN !== '') {
    $ok = hash_equals('Bearer ' . $TOKEN, request_header('Authorization'))
        || ($CUSTOM_HEADER !== '' && hash_equals($TOKEN, request_header($CUSTOM_HEADER)));
    if (!$ok) {
        http_response_code(401);
        header('WWW-Authenticate: Bearer realm="rass-feed"');
        header('Content-Type: text/plain; charset=utf-8');
        echo "unauthorized\n";
        exit;
    }
}

$feed = [
    'rass_feed_version' => 1,
    'name' => $FEED_NAME,
    // Content date: the newest change of this file. Edit the catalog -> the date changes.
    'updated_at' => gmdate('Y-m-d\TH:i:s\Z', filemtime(__FILE__)),
    'genres' => $GENRES,
    'titles' => $CATALOG,
];

$body = json_encode($feed, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
if ($body === false) {
    if (!$is_cli) {
        http_response_code(500);
    }
    echo 'JSON error: ' . json_last_error_msg() . "\n";
    exit(1);
}

if ($is_cli) {
    echo $body, "\n";
    exit(0);
}

$etag = '"' . substr(hash('sha256', $body), 0, 32) . '"';
header('ETag: ' . $etag);
header('Cache-Control: max-age=300');
if (trim(request_header('If-None-Match')) === $etag) {
    http_response_code(304);
    exit;
}
header('Content-Type: application/vnd.rass.feed+json');
echo $body;
