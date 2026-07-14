#!/usr/bin/env bash
# Pinned desktop FFmpeg bundle — FFmpeg 7.1 (GPL) on every desktop platform (#1035).
#
# Before this, the bundle was fragmented: gyan git-snapshot on Windows,
# johnvansickle 7.0.2 stable on Linux, and two *different* martin-riedl
# git-master snapshots on the two macOS arches. Transcode output differs by
# ffmpeg version, so that made Desktop compression non-reproducible across
# platforms. This standardizes on the 7.1 release line from a single
# distributor per OS, and SHA256SUMS pins the exact bytes.
#
#   win64 / linux64 / linuxarm64 -> BtbN FFmpeg-Builds, gpl, n7.1
#   macos arm64 / x64            -> OSXExperts, 7.1
#
# Re-run to refresh; commit the updated binaries + SHA256SUMS together.
set -euo pipefail

VERSION=7.1
DEST="$(cd "$(dirname "$0")/../../src/jvmMain/resources/ffmpeg" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch_btbn() { # <platform-dir> <btbn-infix> <ext>
  local plat="$1" infix="$2" ext="$3"
  local url="https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n${VERSION}-latest-${infix}-gpl-${VERSION}.${ext}"
  local ar="$TMP/$plat.$ext" xd="$TMP/$plat"
  echo ">> $plat  $url"
  curl -fL --retry 3 -o "$ar" "$url"
  mkdir -p "$xd"
  case "$ext" in
    zip)    unzip -q "$ar" -d "$xd" ;;
    tar.xz) tar  -xJf "$ar" -C "$xd" ;;
  esac
  local e=""; [ "$plat" = windows-x64 ] && e=".exe"
  cp "$(find "$xd" -path '*/bin/ffmpeg'$e  -type f | head -1)" "$DEST/$plat/ffmpeg$e"
  cp "$(find "$xd" -path '*/bin/ffprobe'$e -type f | head -1)" "$DEST/$plat/ffprobe$e"
  rm -rf "$ar" "$xd"
}

fetch_osxexperts() { # <platform-dir> <arch-suffix>
  local plat="$1" suffix="$2" tool
  for tool in ffmpeg ffprobe; do
    local url="https://www.osxexperts.net/${tool}${VERSION//./}${suffix}.zip"
    local ar="$TMP/${plat}-${tool}.zip" xd="$TMP/${plat}-${tool}"
    echo ">> $plat/$tool  $url"
    curl -fL --retry 3 -o "$ar" "$url"
    mkdir -p "$xd"; unzip -q -o "$ar" -d "$xd"
    cp "$(find "$xd" -type f -name "$tool" | head -1)" "$DEST/$plat/$tool"
    rm -rf "$ar" "$xd"
  done
}

fetch_btbn windows-x64 win64      zip
fetch_btbn linux-x64   linux64    tar.xz
fetch_btbn linux-arm64 linuxarm64 tar.xz
fetch_osxexperts macos-arm64 arm
fetch_osxexperts macos-x64   intel

chmod +x "$DEST"/linux-x64/ff{mpeg,probe} "$DEST"/linux-arm64/ff{mpeg,probe} \
         "$DEST"/macos-arm64/ff{mpeg,probe} "$DEST"/macos-x64/ff{mpeg,probe}

( cd "$DEST" && find . -type f \( -name 'ffmpeg*' -o -name 'ffprobe*' \) \
    | sort | xargs shasum -a 256 ) > "$(dirname "$0")/SHA256SUMS"

echo "DONE — pinned FFmpeg $VERSION. Checksums in $(dirname "$0")/SHA256SUMS"
