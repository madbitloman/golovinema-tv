#!/usr/bin/env python3
"""Build the pixel wordmark and an SVG preview of the native Android TV banner."""
from pathlib import Path
import base64

ROOT = Path(__file__).resolve().parent.parent
GLYPHS = {
    'G': ['01110','10001','10000','10111','10001','10001','01110'],
    'O': ['01110','10001','10001','10001','10001','10001','01110'],
    'L': ['10000','10000','10000','10000','10000','10000','11111'],
    'V': ['10001','10001','10001','10001','10001','01010','00100'],
    'I': ['11111','00100','00100','00100','00100','00100','11111'],
    'N': ['10001','11001','11001','10101','10011','10011','10001'],
    'E': ['11111','10000','10000','11110','10000','10000','11111'],
    'M': ['10001','11011','10101','10101','10001','10001','10001'],
    'A': ['01110','10001','10001','11111','10001','10001','10001'],
}
paths = []
for index, letter in enumerate('GOLOVINEMA'):
    for y, row in enumerate(GLYPHS[letter]):
        for x, pixel in enumerate(row):
            if pixel == '1':
                paths.append(f'M{index*6+x},{y}h1v1h-1z')
path = ''.join(paths)
(ROOT / 'android/res/drawable/golovinema_wordmark.xml').write_text(
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
    'android:width="177dp" android:height="21dp" android:viewportWidth="59" android:viewportHeight="7">\n'
    f'    <path android:fillColor="#FFC262" android:pathData="{path}" />\n</vector>\n')
portrait = base64.b64encode((ROOT / 'assets/branding/golovinema-portrait.png').read_bytes()).decode('ascii')
(ROOT / 'assets/branding/golovinema-banner.svg').write_text(
    '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="320" height="180" viewBox="0 0 320 180">\n'
    '<rect width="320" height="180" fill="#0B0E14"/>\n'
    f'<image x="8" y="36" width="108" height="108" xlink:href="data:image/png;base64,{portrait}"/>\n'
    f'<path d="{path}" transform="translate(126 79) scale(3)" fill="#FFC262" shape-rendering="crispEdges"/>\n</svg>\n')
print('Updated native pixel wordmark and matching banner SVG preview.')
