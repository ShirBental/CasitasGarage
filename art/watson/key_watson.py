"""Make the approved home art transparent without touching Watson: remove the cream paper and the tan ledge.

Above the ledge line the paper is removed by a flood fill from the edges. Below it, only his chin and the claws
of his front paw hang over the ledge; those are kept inside outlines traced from the painting's own pencil line,
and everything else (ledge, its shadow) goes.

usage: key_watson.py SRC.png OUT.png
"""
import sys
from collections import deque
from PIL import Image, ImageDraw, ImageFilter, ImageChops

src, out = sys.argv[1], sys.argv[2]
rgb = Image.open(src).convert("RGB")
w, h = rgb.size
px = rgb.load()

BG = (251, 244, 228)   # cream paper
CUT = 859              # just above the ledge's drawn back edge (y 860-868)

# Traced on 2x/3x zooms of the original, following the outside of the pencil line.
CHIN = [(582, CUT - 8), (582, CUT), (590, 872), (605, 888), (622, 905), (640, 921), (668, 932), (700, 942), (730, 948),
        (760, 954), (790, 958), (820, 960), (850, 959), (880, 957), (910, 952), (940, 948), (965, 940),
        (990, 931), (1010, 919), (1026, 906), (1036, 893), (1042, 880), (1047, 868), (1049, CUT), (1049, CUT - 8)]
PAW = [(1140, CUT - 8), (1140, CUT), (1142, 876), (1150, 884), (1162, 889), (1173, 894), (1195, 902), (1210, 908), (1222, 908),
       (1232, 903), (1245, 901), (1262, 902), (1280, 903), (1296, 908), (1306, 916), (1318, 916), (1328, 909),
       (1340, 908), (1350, 913), (1362, 913), (1370, 905), (1380, 897), (1394, 891), (1410, 888), (1424, 887),
       (1434, 885), (1440, 876), (1443, CUT), (1443, CUT - 8)]

def dist(p, q):
    return max(abs(a - b) for a, b in zip(p, q))

# 1. Paper above the cut: flood from the top and right edges, the head is enclosed by its outline.
gone = bytearray(w * h)
q = deque()
for x, y in [(x, 0) for x in range(w)] + [(w - 1, y) for y in range(CUT)]:
    if dist(px[x, y], BG) <= 16:
        gone[y * w + x] = 1; q.append((x, y))
while q:
    x, y = q.popleft()
    for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
        if 0 <= nx < w and 0 <= ny < CUT and not gone[ny * w + nx] and dist(px[nx, ny], BG) <= 16:
            gone[ny * w + nx] = 1; q.append((nx, ny))
upper = Image.frombytes("L", (w, h), bytes(0 if g else 255 for g in gone))
upper.paste(0, (0, CUT, w, h))
upper = upper.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))   # close pinholes in the fur
upper = upper.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.9))

# 2. Below the cut: the traced chin and claws, antialiased by drawing at 4x.
S = 4
big = Image.new("L", (w * S, h * S), 0)
d = ImageDraw.Draw(big)
for poly in (CHIN, PAW):
    d.polygon([(x * S, y * S) for x, y in poly], fill=255)
lower = big.resize((w, h), Image.LANCZOS)
lower.paste(0, (0, 0, w, CUT - 8))

mask = ImageChops.lighter(upper, lower)

# 3. Where his body leaves the painting (left edge, and its bottom where the ledge hid it), fade instead of a hard cut.
fade_w = 160
ramp = Image.linear_gradient("L").rotate(90).resize((fade_w, h))
edge = Image.new("L", (w, h), 255); edge.paste(ramp, (0, 0))
body_end = CHIN[0][0] - 30
fade_h = 70
vramp = Image.linear_gradient("L").transpose(Image.FLIP_TOP_BOTTOM).resize((body_end, fade_h))
bottom = Image.new("L", (w, h), 255)
bottom.paste(0, (0, CUT - 4, body_end, h)); bottom.paste(vramp, (0, CUT - 4 - fade_h))
bottom = bottom.filter(ImageFilter.GaussianBlur(14))
bottom.paste(255, (body_end + 30, 0, w, h))
mask = ImageChops.multiply(ImageChops.multiply(mask, edge), bottom)

o = rgb.convert("RGBA"); o.putalpha(mask)
o.save(out)
print("kept bbox", mask.point(lambda a: 255 if a > 8 else 0).getbbox())
