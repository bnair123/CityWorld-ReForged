"""Read railways.txt (probe survey:railways) and report crossings, turn-backs, doubled routes; render a PNG.
Usage: python rail_survey.py railways.txt out.png"""
import itertools, math, sys
from PIL import Image, ImageDraw

hubs, lines = [], []
for row in open(sys.argv[1]):
    p = row.split()
    if p[0] == "HUB":
        hubs.append((int(p[1]), int(p[2]), float(p[3]), float(p[4]), int(p[5]), int(p[6]), p[7] == "true"))
    elif p[0] == "LINE":
        lines.append({"a": (int(p[1]), int(p[2])), "b": (int(p[3]), int(p[4])), "corr": p[5] == "true" or p[6] == "true", "pts": []})
    else:
        lines[-1]["pts"].append((float(p[0]), float(p[1]), float(p[2]), p[3]))

def length(pts):
    return sum(math.hypot(pts[i][0]-pts[i-1][0], pts[i][1]-pts[i-1][1]) for i in range(1, len(pts)))

def seg_hit(p1, p2, q1, q2):
    def cr(o, a, b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    d1, d2, d3, d4 = cr(q1, q2, p1), cr(q1, q2, p2), cr(p1, p2, q1), cr(p1, p2, q2)
    if (d1 > 0) != (d2 > 0) and (d3 > 0) != (d4 > 0):
        t = d1/(d1-d2); u = d3/(d3-d4)
        return t, u
    return None

print(f"{len(lines)} lines, {len(hubs)} stations, {sum(length(l['pts']) for l in lines):.0f} blocks of line")
for i, l in enumerate(lines):
    print(f"  L{i}: {l['a']} -> {l['b']}  {length(l['pts']):.0f} blocks  straight {math.dist(l['pts'][0][:2], l['pts'][-1][:2]):.0f}{'  corridor' if l['corr'] else ''}")

print("\nRAIL-RAIL CROSSINGS (height difference at the crossing; < 7 = they collide):")
pair_hits = {}
for (i, L), (j, M) in itertools.combinations(enumerate(lines), 2):
    P, Q = L["pts"][::2], M["pts"][::2]
    for a in range(len(P)-1):
        for b in range(len(Q)-1):
            h = seg_hit(P[a], P[a+1], Q[b], Q[b+1])
            if h:
                t, u = h
                x = P[a][0]+(P[a+1][0]-P[a][0])*t; z = P[a][1]+(P[a+1][1]-P[a][1])*t
                hl = P[a][2]+(P[a+1][2]-P[a][2])*t; hm = Q[b][2]+(Q[b+1][2]-Q[b][2])*u
                ang = abs(math.degrees(math.atan2(P[a+1][1]-P[a][1], P[a+1][0]-P[a][0]) - math.atan2(Q[b+1][1]-Q[b][1], Q[b+1][0]-Q[b][0]))) % 180
                pair_hits.setdefault((i, j), []).append((x, z, hl, hm, ang, P[a][3], Q[b][3]))
for (i, j), hits in pair_hits.items():
    for x, z, hl, hm, ang, kl, km in hits:
        print(f"  L{i} x L{j} at {x:.0f},{z:.0f}: decks {hl:.1f} / {hm:.1f}  dh {abs(hl-hm):.1f}  angle {min(ang,180-ang):.0f}  {kl}/{km}{'  COLLIDES' if abs(hl-hm) < 7 else ''}")
multi = {k: v for k, v in pair_hits.items() if len(v) >= 2}
print(f"\n{sum(len(v) for v in pair_hits.values())} crossings; {sum(1 for v in pair_hits.values() for h in v if abs(h[2]-h[3]) < 7)} collide; pairs crossing 2+ times: {[(f'L{i}',f'L{j}',len(v)) for (i,j),v in multi.items()]}")

print("\nTURN-BACKS (heading change over 160 blocks of line > 100 degrees):")
for i, l in enumerate(lines):
    pts = l["pts"]; step = math.dist(pts[0][:2], pts[1][:2]) or 2
    w = max(2, int(80/step)); k = w; last = -10**9
    while k < len(pts)-w:
        a = math.atan2(pts[k][1]-pts[k-w][1], pts[k][0]-pts[k-w][0]); b = math.atan2(pts[k+w][1]-pts[k][1], pts[k+w][0]-pts[k][0])
        d = abs((math.degrees(b-a)+180) % 360 - 180)
        if d > 100 and k - last > 3*w:
            print(f"  L{i} at {pts[k][0]:.0f},{pts[k][1]:.0f}: turns {d:.0f} deg")
            last = k
        k += 1

print("\nSHARED CORRIDORS (two lines within 48 blocks of each other for 300+ blocks):")
for (i, L), (j, M) in itertools.combinations(enumerate(lines), 2):
    run = best = 0; at = None
    Q = M["pts"][::4]
    for p in L["pts"][::4]:
        if min(math.dist(p[:2], q[:2]) for q in Q) < 48:
            run += 8; 
            if run > best: best, at = run, p
        else: run = 0
    if best >= 300:
        print(f"  L{i} & L{j}: {best} blocks side by side, near {at[0]:.0f},{at[1]:.0f}")

xs = [p[0] for l in lines for p in l["pts"]]; zs = [p[1] for l in lines for p in l["pts"]]
x0, x1, z0, z1 = min(xs)-200, max(xs)+200, min(zs)-200, max(zs)+200
S = 1600/max(x1-x0, z1-z0)
img = Image.new("RGB", (int((x1-x0)*S), int((z1-z0)*S)), "white"); d = ImageDraw.Draw(img)
def P(x, z): return ((x-x0)*S, (z-z0)*S)
for gx in range(int(x0//1000)*1000, int(x1)+1, 1000):
    d.line([P(gx, z0), P(gx, z1)], fill=(230,230,230)); d.text(P(gx+10, z0+10), str(gx), fill="gray")
for gz in range(int(z0//1000)*1000, int(z1)+1, 1000):
    d.line([P(x0, gz), P(x1, gz)], fill=(230,230,230)); d.text(P(x0+10, gz+10), str(gz), fill="gray")
cols = {"TUNNEL": (120,60,0), "BRIDGE": (0,90,200)}
for i, l in enumerate(lines):
    pts = l["pts"]
    for a, b in zip(pts, pts[1:]):
        d.line([P(*a[:2]), P(*b[:2])], fill=cols.get(a[3], (40,40,40)), width=3)
    m = pts[len(pts)//2]; d.text(P(m[0]+20, m[1]), f"L{i}", fill="purple")
for h in hubs:
    cx, cz = P(h[2], h[3]); d.rectangle([cx-6, cz-6, cx+6, cz+6], fill="orange" if h[6] else "green")
    d.text((cx+8, cz-14), f"{h[0]},{h[1]}", fill="black")
for v in pair_hits.values():
    for x, z, hl, hm, *_ in v:
        cx, cz = P(x, z); d.ellipse([cx-7, cz-7, cx+7, cz+7], outline="red" if abs(hl-hm) < 7 else "blue", width=3)
img.save(sys.argv[2])
