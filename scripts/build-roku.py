#!/usr/bin/env python3
"""Package the Roku BrightScript channel into dist/roku-channel.zip.

Generates the manifest-required splash/icon PNGs (solid dark cards; replace with
real branding later) and zips <manifest>, source/, components/, images/ in the
layout Roku's sideload installer expects.
"""
import os
import struct
import subprocess
import sys
import zlib
from pathlib import Path

root = Path(sys.argv[0]).resolve().parent.parent
roku = root / "roku"
stage = root / "dist" / "roku-stage"
out = root / "dist" / "roku-channel.zip"


def png(path: Path, width: int, height: int, rgb=(14, 16, 22)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    row = b"\x00" + bytes(rgb) * width  # one filter byte per scanline (type 0)
    raw = row * height
    block = struct.pack(">II", width, height) + bytes([8, 2, 0, 0, 0])  # IHDR: bit depth 8, truecolour

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    body = chunk(b"IHDR", block) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + body)


def main() -> None:
    import shutil

    if stage.exists():
        shutil.rmtree(stage)
    for part in ("source", "components", "images"):
        shutil.copytree(roku / part, stage / part)
    shutil.copy2(roku / "manifest", stage / "manifest")
    png(stage / "images" / "icon.png", 512, 288, (8, 12, 9))
    png(stage / "images" / "splash.png", 1920, 1080, (5, 7, 10))
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    subprocess.run(["zip", "-X", "-q", "-r", str(out), "manifest", "source", "components", "images"], cwd=stage, check=True)
    print(f"Built: {out}")


if __name__ == "__main__":
    main()
