#!/usr/bin/env bash
# Build the Rass_player APK and copy it to dist/ with its version in the name.
# Usage: scripts/build-apk.sh [--debug]
#   default: assembleRelease -> dist/Rass_player-<versionName>.apk
#   --debug: assembleDebug   -> dist/Rass_player-<versionName>-debug.apk
set -euo pipefail

VARIANT=release
GRADLE_TASK=assembleRelease
SUFFIX=""

case "${1:-}" in
  "")
    ;;
  --debug)
    VARIANT=debug
    GRADLE_TASK=assembleDebug
    SUFFIX="-debug"
    ;;
  -h|--help)
    echo "Usage: $0 [--debug]"
    exit 0
    ;;
  *)
    echo "Unknown option: $1" >&2
    echo "Usage: $0 [--debug]" >&2
    exit 1
    ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

echo "==> Running ./gradlew :app:$GRADLE_TASK"
./gradlew ":app:$GRADLE_TASK"

GRADLE_FILE="app/build.gradle.kts"
if [[ ! -f "$GRADLE_FILE" ]]; then
  echo "Error: $GRADLE_FILE not found" >&2
  exit 1
fi

VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$GRADLE_FILE" | head -n 1 || true)"
if [[ -z "$VERSION" ]]; then
  echo "Error: versionName not found in $GRADLE_FILE" >&2
  exit 1
fi

APK_DIR="app/build/outputs/apk/$VARIANT"
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 1 -name '*.apk' -type f 2>/dev/null | sort)
if [[ ${#APKS[@]} -ne 1 ]]; then
  echo "Error: expected exactly one APK in $APK_DIR, found ${#APKS[@]}" >&2
  printf '  %s\n' "${APKS[@]}" >&2
  exit 1
fi

mkdir -p dist
DEST="dist/Rass_player-${VERSION}${SUFFIX}.apk"
cp "${APKS[0]}" "$DEST"

echo "==> Built $VARIANT APK"
echo "Path:   $ROOT/$DEST"
echo "Size:   $(du -h "$DEST" | cut -f1)"
echo "SHA-256: $(sha256sum "$DEST" | cut -d ' ' -f 1)"
