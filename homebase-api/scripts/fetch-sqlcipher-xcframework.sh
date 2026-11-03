#!/bin/sh
# Usage: fetch-sqlcipher-xcframework.sh <zip url> <sha256> <output SQLCipher.xcframework dir>
set -eu
url="$1"
sha="$2"
out="$3"
work="$out.download"
rm -rf "$work" "$out"
mkdir -p "$work"
curl -fsSL --retry 3 -o "$work/SQLCipher.xcframework.zip" "$url"
echo "$sha  $work/SQLCipher.xcframework.zip" | shasum -a 256 -c - >/dev/null
unzip -q "$work/SQLCipher.xcframework.zip" -d "$work"
mv "$work/SQLCipher.xcframework" "$out"
rm -rf "$work"
