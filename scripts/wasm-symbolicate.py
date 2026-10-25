#!/usr/bin/env python3
"""Turn a production web-app stack trace into Kotlin function names and source lines.

    __homebaseFirstError() output  ->  python3 scripts/wasm-symbolicate.py trace.txt

Reads the trace from a file or stdin. The .wasm content hash in the frame URLs selects the
symbols, which odin-core's deploy builds upload as the artifact chat-wasm-symbols-<hash>.
See WASM_CRASH_SYMBOLS.md.
"""

import argparse
import bisect
import io
import json
import os
import re
import subprocess
import sys
import zipfile

ARTIFACT_REPO = "homebase-id/odin-core"
CACHE_DIR = os.path.expanduser("~/.cache/homebase-wasm-symbols")
FRAME = re.compile(r"(?:([0-9a-f]{20})\.wasm)?:wasm-function\[(\d+)\]:0x([0-9a-f]+)")
B64 = {c: i for i, c in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")}


def leb(data, at):
    value = shift = 0
    while True:
        b = data[at]
        at += 1
        value |= (b & 0x7F) << shift
        if b < 0x80:
            return value, at
        shift += 7


def function_names(wasm_path):
    data = open(wasm_path, "rb").read()
    at = 8
    while at < len(data):
        section_id = data[at]
        size, at = leb(data, at + 1)
        end = at + size
        if section_id == 0:
            name_len, name_at = leb(data, at)
            if data[name_at:name_at + name_len] == b"name":
                at = name_at + name_len
                while at < end:
                    sub_id = data[at]
                    sub_size, at = leb(data, at + 1)
                    if sub_id == 1:
                        names = {}
                        count, p = leb(data, at)
                        for _ in range(count):
                            index, p = leb(data, p)
                            length, p = leb(data, p)
                            names[index] = data[p:p + length].decode("utf-8", "replace")
                            p += length
                        return names
                    at += sub_size
        at = end
    sys.exit(f"{wasm_path} has no function-name section; it is not a symbols file.")


def vlq_fields(segment):
    fields, value, shift = [], 0, 0
    for ch in segment:
        digit = B64[ch]
        value |= (digit & 31) << shift
        if digit & 32:
            shift += 5
        else:
            fields.append(-(value >> 1) if value & 1 else value >> 1)
            value = shift = 0
    return fields


def source_lines(map_path):
    source_map = json.load(open(map_path))
    sources = [re.sub(r"^(\.\./)+", "", s) for s in source_map["sources"]]
    entries = []
    column = source = line = 0
    for group in source_map["mappings"].split(";"):
        column = 0
        for segment in group.split(","):
            if not segment:
                continue
            fields = vlq_fields(segment)
            column += fields[0]
            if len(fields) >= 4:
                source += fields[1]
                line += fields[2]
                entries.append((column, source, line))
    entries.sort()
    return sources, entries


def download(wasm_hash):
    target = os.path.join(CACHE_DIR, wasm_hash)
    if os.path.exists(os.path.join(target, f"{wasm_hash}.wasm")):
        return target
    name = f"chat-wasm-symbols-{wasm_hash}"
    listing = json.loads(subprocess.run(
        ["gh", "api", f"repos/{ARTIFACT_REPO}/actions/artifacts?name={name}&per_page=1"],
        check=True, capture_output=True,
    ).stdout)
    artifacts = listing.get("artifacts", [])
    if not artifacts:
        sys.exit(f"No artifact {name} in {ARTIFACT_REPO}. Was this bundle built before symbols "
                 "were kept, or by a local build? Pass --symbols DIR for a local build.")
    if artifacts[0]["expired"]:
        sys.exit(f"Artifact {name} has expired (90-day retention).")
    print(f"Downloading {name} ({artifacts[0]['size_in_bytes'] // 1_000_000} MB)...", file=sys.stderr)
    archive = subprocess.run(
        ["gh", "api", artifacts[0]["archive_download_url"]], check=True, capture_output=True,
    ).stdout
    os.makedirs(target, exist_ok=True)
    zipfile.ZipFile(io.BytesIO(archive)).extractall(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("trace", nargs="?", help="file holding the trace (default: stdin)")
    parser.add_argument("--hash", help="20-hex .wasm content hash, when the frames don't show the file name")
    parser.add_argument("--symbols", help="directory holding <hash>.wasm and <hash>.wasm.map, instead of downloading")
    args = parser.parse_args()

    text = open(args.trace).read() if args.trace else sys.stdin.read()
    frames = [(m.group(1), int(m.group(2)), int(m.group(3), 16)) for m in FRAME.finditer(text)]
    if not frames:
        sys.exit("No wasm-function[N]:0xOFFSET frames found.")
    hashes = {h for h, _, _ in frames if h} | ({args.hash} if args.hash else set())
    if len(hashes) != 1:
        sys.exit(f"Need exactly one .wasm hash, found {sorted(hashes) or 'none'}; pass --hash.")
    wasm_hash = hashes.pop()

    directory = args.symbols or download(wasm_hash)
    names = function_names(os.path.join(directory, f"{wasm_hash}.wasm"))
    sources, entries = source_lines(os.path.join(directory, f"{wasm_hash}.wasm.map"))
    offsets = [e[0] for e in entries]

    for _, index, offset in frames:
        name = names.get(index, f"wasm-function[{index}]")
        i = bisect.bisect_right(offsets, offset) - 1
        location = ""
        if i >= 0:
            _, source, line = entries[i]
            if not sources[source].endswith("NATIVE_IMPLEMENTATIONS.kt"):
                location = f"  {sources[source]}:{line + 1}"
        print(f"{name}{location}")


if __name__ == "__main__":
    main()
