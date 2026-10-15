#!/usr/bin/env bash
# Downloads the pinned Xray engine (libv2ray.aar from 2dust/AndroidLibXrayLite) into app/libs/
# and verifies its SHA-256. Version and hash are recorded in docs/third-party.md; update both together.
set -euo pipefail
VERSION="v26.10.10"
SHA256="15db760bfbc24c0492ee997784b48a7b5e1bc5032950d617cd252fc2195f7ac7"
URL="https://github.com/2dust/AndroidLibXrayLite/releases/download/${VERSION}/libv2ray.aar"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/libv2ray.aar"

if [[ -f "$DEST" ]] && echo "${SHA256}  ${DEST}" | sha256sum -c --quiet - 2>/dev/null; then
  echo "libv2ray.aar ${VERSION} already present and verified."
  exit 0
fi
mkdir -p "$(dirname "$DEST")"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -fsSL -o "$tmp" "$URL"
echo "${SHA256}  ${tmp}" | sha256sum -c --quiet -
mv "$tmp" "$DEST"
trap - EXIT
echo "libv2ray.aar ${VERSION} downloaded and verified."
