#!/usr/bin/env python3
"""Draw a city's metro over its plan, from the metro probe's run/metromap.txt.

    python scripts/metro_map.py run/metromap.txt out.png [scale]

Each chunk is coloured by the lot planned there (roads, buildings, parks, wild land); over it go the two lines (Red
east-west, Blue north-south) with their stations, entrances and depot, the railway station, and the walk from the
station down to the metro.
"""
import sys
from collections import Counter
from PIL import Image, ImageDraw, ImageFont

src, out = sys.argv[1], sys.argv[2]
scale = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0

site, hub, walk, lines, stations, chunks = None, None, [], [], [], []
for raw in open(src, encoding='utf-8'):
    p = raw.rstrip('\n').split(' ')
    if p[0] == 'SITE':
        site = tuple(int(v) for v in p[1:5])
    elif p[0] == 'HUB':
        hub = [float(v) for v in p[1:9]]
    elif p[0] == 'W':
        walk.append((int(p[1]), int(p[2]), int(p[3])))
    elif p[0] == 'L':
        lines.append((p[1], *[int(v) for v in p[2:8]]))
    elif p[0] == 'S':
        stations.append((p[1], *[int(v) for v in p[2:6]], ' '.join(p[6:])))
    elif p[0] == 'C':
        chunks.append((int(p[1]), int(p[2]), p[3]))

xs = [c[0] * 16 for c in chunks] + [c[0] * 16 + 16 for c in chunks]
zs = [c[1] * 16 for c in chunks] + [c[1] * 16 + 16 for c in chunks]
if hub:
    xs += [hub[0], hub[2]]
    zs += [hub[1], hub[3]]
pad = 40
X0, X1, Z0, Z1 = min(xs) - pad, max(xs) + pad, min(zs) - pad - 30, max(zs) + pad
W, H = int((X1 - X0) * scale), int((Z1 - Z0) * scale) + 110


def px(x, z):
    return ((x - X0) * scale, (z - Z0) * scale)


def font(size):
    for name in ('arialbd.ttf', 'arial.ttf', 'DejaVuSans-Bold.ttf', 'DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


img = Image.new('RGB', (W, H), (246, 244, 238))
d = ImageDraw.Draw(img)
kinds = Counter(k for _, _, k in chunks)

COL = {
    'road': (120, 120, 126), 'building': (214, 196, 168), 'tall': (176, 158, 136), 'park': (150, 196, 120),
    'wild': (206, 226, 186), 'water': (150, 190, 226), 'farm': (226, 214, 140), 'civic': (196, 176, 206),
    'other': (226, 220, 206), 'none': (246, 244, 238),
}


def kind_of(name):
    n = name.lower()
    if name == '-':
        return 'none'
    if 'metroentrance' in n:
        return 'road'
    if 'road' in n or 'bridge' in n or 'roundabout' in n or 'tunnel' in n:
        return 'road'
    if 'park' in n or 'garden' in n or 'cemetery' in n or 'plaza' in n:
        return 'park'
    if 'nature' in n or 'mountain' in n or 'wild' in n:
        return 'wild'
    if 'water' in n or 'shore' in n or 'quay' in n or 'river' in n or 'harbour' in n:
        return 'water'
    if 'farm' in n or 'orchard' in n:
        return 'farm'
    if 'office' in n or 'tower' in n or 'highrise' in n:
        return 'tall'
    if 'museum' in n or 'hospital' in n or 'school' in n or 'church' in n or 'library' in n or 'town' in n:
        return 'civic'
    if 'building' in n or 'house' in n or 'store' in n or 'shop' in n or 'apartment' in n or 'mall' in n \
            or 'warehouse' in n or 'factory' in n or 'lot' in n:
        return 'building'
    return 'other'


for cx, cz, k in chunks:
    x, z = cx * 16, cz * 16
    a, b = px(x, z), px(x + 16, z + 16)
    d.rectangle([a[0], a[1], b[0] - 1, b[1] - 1], fill=COL[kind_of(k)])

LINE = {'ew': (229, 57, 53), 'ns': (30, 136, 229)}
DARK = {'ew': (150, 24, 22), 'ns': (12, 72, 140)}
# the lines: a bar down the middle of their row, the depot hatched
for tag, ax, az, bx, bz, dx, dz in lines:
    ew = tag == 'ew'
    w = 5 * scale
    if ew:
        mz = (az + bz + 1) / 2
        d.rectangle([*px(ax, mz - 3), *px(bx + 1, mz + 3)], fill=LINE[tag])
        d.rectangle([*px(dx, mz - 3), *px(bx + 1, mz + 3)], fill=(90, 90, 90))
    else:
        mx = (ax + bx + 1) / 2
        d.rectangle([*px(mx - 3, az), *px(mx + 3, bz + 1)], fill=LINE[tag])
        d.rectangle([*px(mx - 3, dz), *px(mx + 3, bz + 1)], fill=(90, 90, 90))

# entrances: the line's colour, ringed, in their chunk
for cx, cz, k in chunks:
    if 'MetroEntrance' in k:
        c = px(cx * 16 + 8, cz * 16 + 8)
        r = 5 * scale
        d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=(255, 255, 255), outline=(30, 30, 30), width=2)
        d.text((c[0] - 3 * scale, c[1] - 5 * scale), 'M', fill=(30, 30, 30), font=font(int(9 * scale)))

# stations: boxes over the line, named
f_station = font(max(11, int(12 * scale)))
for tag, ax, az, bx, bz, name in stations:
    ew = tag == 'ew'
    if ew:
        mz = (az + bz + 1) / 2
        box = [*px(ax, mz - 7), *px(bx + 1, mz + 7)]
    else:
        mx = (ax + bx + 1) / 2
        box = [*px(mx - 7, az), *px(mx + 7, bz + 1)]
    d.rectangle(box, fill=(255, 255, 255), outline=DARK[tag], width=max(2, int(3 * scale)))
    if ew:
        tx, ty = box[0], box[1] - 16 * scale if (stations.index((tag, ax, az, bx, bz, name)) % 2) else box[3] + 3
        d.text((tx, ty), name, fill=DARK[tag], font=f_station)
    else:
        d.text((box[2] + 4, (box[1] + box[3]) / 2 - 6), name, fill=DARK[tag], font=f_station)

# the railway station and the walk
if hub:
    d.rectangle([*px(hub[0], hub[1]), *px(hub[2], hub[3])], outline=(60, 60, 70), width=2)
    c = px(hub[0], hub[1])
    d.text((c[0] + 6, c[1] + 6), 'RAILWAY STATION', fill=(40, 40, 50),
           font=font(max(12, int(12 * scale))))
if walk:
    pts = [px(x + 0.5, z + 0.5) for x, z, _ in walk]
    d.line(pts, fill=(255, 140, 0), width=max(3, int(4 * scale)))
    s, e = pts[0], pts[-1]
    for (cx, cy), label in ((s, 'forecourt stairs, y%d' % (walk[0][2] + 1)), (e, 'into the concourse, y%d' % (walk[-1][2] + 1))):
        d.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=(255, 140, 0), outline=(80, 40, 0), width=2)
        d.text((cx + 8, cy + 6), label, fill=(120, 60, 0), font=font(max(12, int(11 * scale))))

# title and legend
d.rectangle([0, 0, W, 26], fill=(30, 30, 36))
title = 'Metro: Red Line (east-west, 48 below the street) and Blue Line (north-south, 64 below)'
d.text((8, 5), title, fill=(255, 255, 255), font=font(14))
ly = H - 100
items = [('road', 'road'), ('building', 'buildings'), ('tall', 'offices / towers'), ('civic', 'civic'),
         ('park', 'park'), ('wild', 'wild land'), ('water', 'water'), ('farm', 'farm')]
x = 10
for k, label in items:
    d.rectangle([x, ly, x + 16, ly + 16], fill=COL[k], outline=(80, 80, 80))
    d.text((x + 22, ly), label, fill=(30, 30, 30), font=font(12))
    x += 30 + 8 * len(label)
ly += 26
x = 10
for col, label in ((LINE['ew'], 'Red Line'), (LINE['ns'], 'Blue Line'), ((90, 90, 90), 'depot sidings'),
                   ((255, 140, 0), 'walk from the railway station')):
    d.rectangle([x, ly + 5, x + 30, ly + 11], fill=col)
    d.text((x + 36, ly), label, fill=(30, 30, 30), font=font(12))
    x += 50 + 8 * len(label)
d.ellipse([x, ly, x + 14, ly + 14], fill=(255, 255, 255), outline=(30, 30, 30), width=2)
d.text((x + 20, ly), 'street entrance', fill=(30, 30, 30), font=font(12))
ly += 26
d.text((10, ly), 'lot kinds seen: ' + ', '.join('%s %d' % kv for kv in kinds.most_common(14)), fill=(90, 90, 90),
       font=font(10))
img.save(out)
print('wrote', out, W, 'x', H, '; kinds', dict(kinds.most_common(30)))
