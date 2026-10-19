"""Remove the drawn floor line where it reaches past Watson's paws and body. The originals are already transparent.
Only alpha changes, and only on the line's tails; every kept pixel keeps its exact colour and alpha.

usage: cut_line.py SRC.webp OUT.png
"""
import sys
from PIL import Image

src, out = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGBA")
w, h = im.size
px = im.load()


def extent(y):
    xs = [x for x in range(w) if px[x, y][3] > 128]
    return (min(xs), max(xs)) if xs else None


# The line: the first rows near the floor whose opaque extent jumps past the row above it.
prev = extent(960)
top = None
for y in range(961, h):
    e = extent(y)
    if e and prev and (e[0] < prev[0] - 4 or e[1] > prev[1] + 4):
        top = y
        break
    prev = e
left, right = extent(top - 2)
cleared = 0
for y in range(top - 1, min(h, top + 12)):
    for x in range(w):
        if (x < left - 2 or x > right + 2) and px[x, y][3] > 0:
            r, g, b, _ = px[x, y]
            px[x, y] = (r, g, b, 0)
            cleared += 1
im.save(out)
print(f"{src.split('/')[-1]}: line starts at y={top}; kept x {left - 2}..{right + 2} in rows {top - 1}..{top + 11}; "
      f"cleared {cleared} px; visible bbox now {im.getchannel('A').point(lambda v: 255 if v > 8 else 0).getbbox()}")
