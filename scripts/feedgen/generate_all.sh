#!/usr/bin/env bash
# Regenerate all preset test feeds into app/src/main/assets/test-feeds/ (needs network; ~3-6 minutes).
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=".venv/bin/python"
[ -x "$PY" ] || { echo "Missing .venv: python3 -m venv .venv && .venv/bin/pip install jsonschema" >&2; exit 1; }
for s in gen_test_subtitles gen_blender gen_archive_films gen_archive_series gen_hls_test; do
  echo "== $s" >&2
  "$PY" -I "scripts/feedgen/$s.py"
done
du -ch app/src/main/assets/test-feeds/*.json | tail -1
