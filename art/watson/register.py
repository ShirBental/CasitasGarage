"""Place idle and excited on one 720x540 transparent canvas with one shared transform (the union of both outlines,
paws on the bottom edge). With --fit, excited is first resized by SCALE and moved by OFFSET to match idle's body.

usage: register.py IDLE.png EXCITED.png OUTDIR [--fit SCALE DX DY]
"""
import sys
from PIL import Image

idle_src, excited_src, out = sys.argv[1], sys.argv[2], sys.argv[3]
idle = Image.open(idle_src).convert("RGBA")
excited = Image.open(excited_src).convert("RGBA")
if "--fit" in sys.argv:
    i = sys.argv.index("--fit")
    s, dx, dy = float(sys.argv[i + 1]), int(sys.argv[i + 2]), int(sys.argv[i + 3])
    moved = Image.new("RGBA", excited.size, (0, 0, 0, 0))
    moved.alpha_composite(excited.resize((round(excited.width * s), round(excited.height * s)), Image.LANCZOS), (dx, dy))
    excited = moved


def visible(im):
    return im.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()


a, b = visible(idle), visible(excited)
box = (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
CW, CH = 720, 540
scale = min(CW / (box[2] - box[0]), CH / (box[3] - box[1]))
w, h = round((box[2] - box[0]) * scale), round((box[3] - box[1]) * scale)
x0, y0 = (CW - w) // 2, CH - h
for name, im in (("idle", idle), ("excited", excited)):
    canvas = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    canvas.alpha_composite(im.crop(box).resize((w, h), Image.LANCZOS), (x0, y0))
    canvas.save(f"{out}/watson-{name}.png", optimize=True)
print(f"union box {box} -> {w}x{h} at ({x0},{y0}) on {CW}x{CH}; scale {scale:.4f}")
