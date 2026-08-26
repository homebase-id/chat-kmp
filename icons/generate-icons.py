#!/usr/bin/env python3
"""Rasterise homebase_appicon.svg into the per-platform app icons.

    python3 icons/generate-icons.py

Needs rsvg-convert (brew install librsvg), Pillow, and macOS iconutil for the .icns.
"""
import os
import shutil
import subprocess
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "homebase_appicon.svg")
# macOS draws no mask of its own: the 1024 canvas carries an 824 body plus the
# 100pt margin Apple's icon grid reserves, or the dock renders it oversized.
MAC_BODY = 824


def render(size, out):
    subprocess.run(
        ["rsvg-convert", "-w", str(size), "-h", str(size), "-o", out, SRC], check=True
    )


def main():
    tmp = os.path.join(HERE, ".build")
    iconset = os.path.join(tmp, "icon.iconset")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(iconset)

    flat = os.path.join(tmp, "flat.png")
    render(1024, flat)

    body = os.path.join(tmp, "body.png")
    render(MAC_BODY, body)
    mac = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    inset = (1024 - MAC_BODY) // 2
    mac.paste(Image.open(body).convert("RGBA"), (inset, inset))

    for pt in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = pt * scale
            suffix = "" if scale == 1 else "@2x"
            mac.resize((px, px), Image.LANCZOS).save(
                os.path.join(iconset, f"icon_{pt}x{pt}{suffix}.png")
            )

    icns = os.path.join(HERE, "icon.icns")
    if sys.platform == "darwin":
        subprocess.run(["iconutil", "-c", "icns", iconset, "-o", icns], check=True)
    else:
        print("skipping icon.icns: iconutil is macOS-only", file=sys.stderr)

    src = Image.open(flat).convert("RGBA")
    src.save(os.path.join(HERE, "icon.png"))
    src.save(
        os.path.join(HERE, "icon.ico"),
        sizes=[(s, s) for s in (16, 32, 48, 64, 128, 256)],
    )

    window_icon = os.path.join(
        HERE,
        "..",
        "homebase-common",
        "src",
        "commonMain",
        "composeResources",
        "drawable",
        "homebase_icon_round.png",
    )
    src.resize((512, 512), Image.LANCZOS).save(window_icon)

    shutil.rmtree(tmp)
    for name in ("icon.icns", "icon.png", "icon.ico", window_icon):
        path = os.path.join(HERE, name)
        if os.path.exists(path):
            print(f"{os.path.basename(path)}: {os.path.getsize(path):,} bytes")


if __name__ == "__main__":
    main()
