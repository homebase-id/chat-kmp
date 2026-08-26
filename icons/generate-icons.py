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
WINDOW_ICON = os.path.join(
    HERE, os.pardir, "homebase-common", "src", "commonMain",
    "composeResources", "drawable", "homebase_icon_round.png",
)


def main():
    tmp = os.path.join(HERE, ".build")
    iconset = os.path.join(tmp, "icon.iconset")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(iconset)

    master = os.path.join(tmp, "master.png")
    subprocess.run(
        ["rsvg-convert", "-w", "1024", "-h", "1024", "-o", master, SRC], check=True
    )
    art = Image.open(master).convert("RGBA")

    # Full bleed on purpose. macOS 26 masks and plates a legacy .icns itself, so art that
    # reserves Apple's old 100pt icon-grid margin gets inset twice and floats inside the
    # Dock's own tile. The baked squircle is what older macOS renders.
    for pt in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = pt * scale
            suffix = "" if scale == 1 else "@2x"
            art.resize((px, px), Image.LANCZOS).save(
                os.path.join(iconset, f"icon_{pt}x{pt}{suffix}.png")
            )

    outputs = [os.path.join(HERE, "icon.png"), os.path.join(HERE, "icon.ico"), WINDOW_ICON]
    art.save(outputs[0])
    art.save(outputs[1], sizes=[(s, s) for s in (16, 32, 48, 64, 128, 256)])
    art.resize((512, 512), Image.LANCZOS).save(outputs[2])

    icns = os.path.join(HERE, "icon.icns")
    if sys.platform == "darwin":
        subprocess.run(["iconutil", "-c", "icns", iconset, "-o", icns], check=True)
        outputs.append(icns)
    else:
        print("skipping icon.icns: iconutil is macOS-only", file=sys.stderr)

    shutil.rmtree(tmp)
    for path in outputs:
        print(f"{os.path.basename(path)}: {os.path.getsize(path):,} bytes")


if __name__ == "__main__":
    main()
