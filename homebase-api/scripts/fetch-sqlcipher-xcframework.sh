#!/bin/sh
# Usage: fetch-sqlcipher-xcframework.sh <zip url> <sha256> <output SQLCipher.xcframework dir>
# The verified zip is kept in SQLCIPHER_CACHE_DIR (CI caches it) so a GitHub download outage doesn't fail the build.
set -eu
url="$1"
sha="$2"
out="$3"
cache="${SQLCIPHER_CACHE_DIR:-$HOME/.cache/homebase-sqlcipher}"
zip="$cache/SQLCipher-$sha.xcframework.zip"
work="$out.download"
mkdir -p "$cache"
if ! echo "$sha  $zip" | shasum -a 256 -c - >/dev/null 2>&1; then
  curl -fsSL --retry 6 --retry-delay 10 --retry-all-errors -o "$zip.part" "$url"
  echo "$sha  $zip.part" | shasum -a 256 -c - >/dev/null
  mv "$zip.part" "$zip"
fi
rm -rf "$work" "$out"
mkdir -p "$work"
unzip -q "$zip" -d "$work"
mv "$work/SQLCipher.xcframework" "$out"
rm -rf "$work"
