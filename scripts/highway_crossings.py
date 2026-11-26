"""Where highways cross each other. Usage: python crossings.py <ground dir>  (links named link_ax_az_bx_bz.csv)"""
import csv, glob, itertools, os, sys


def load(path):
    rows = list(csv.DictReader(open(path)))
    pts = [(float(r["x"]), float(r["z"])) for r in rows][::4]  # every 8 blocks
    name = os.path.basename(path)[5:-4].split("_")
    a, b = (int(name[0]), int(name[1])), (int(name[2]), int(name[3]))
    return a, b, pts


def seg_hit(p1, p2, q1, q2):
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    d1, d2 = cross(q1, q2, p1), cross(q1, q2, p2)
    d3, d4 = cross(p1, p2, q1), cross(p1, p2, q2)
    if (d1 > 0) != (d2 > 0) and (d3 > 0) != (d4 > 0):
        t = d1 / (d1 - d2)
        return (p1[0] + (p2[0] - p1[0]) * t, p1[1] + (p2[1] - p1[1]) * t)
    return None


links = [load(f) for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.csv")))]
shared_pairs = shared_hits = other_pairs = other_hits = 0
multi = []
for (a1, b1, p), (a2, b2, q) in itertools.combinations(links, 2):
    shared = len({a1, b1} & {a2, b2}) > 0
    hits = []
    for i in range(len(p) - 1):
        for j in range(len(q) - 1):
            h = seg_hit(p[i], p[i + 1], q[j], q[j + 1])
            if h and all(abs(h[0] - k[0]) + abs(h[1] - k[1]) > 40 for k in hits):
                hits.append(h)
    if shared:
        # a shared city's own centre is not a crossing: links meet there by design (inside the city disc, ~320)
        city = ({a1, b1} & {a2, b2}).pop()
        hits = [h for h in hits if abs(h[0] - city[0]) + abs(h[1] - city[1]) > 400]
    if not hits:
        continue
    if shared:
        shared_pairs += 1
        shared_hits += len(hits)
    else:
        other_pairs += 1
        other_hits += len(hits)
    if len(hits) > 1:
        multi.append((len(hits), shared, a1, b1, a2, b2, [(round(h[0]), round(h[1])) for h in hits]))
print(f"{len(links)} links; pairs sharing a city that cross: {shared_pairs} ({shared_hits} crossings); "
      f"other pairs that cross: {other_pairs} ({other_hits} crossings)")
for m in sorted(multi, reverse=True):
    print(f"  {m[0]} crossings, {'shared city' if m[1] else 'no shared city'}: {m[2]}->{m[3]} x {m[4]}->{m[5]} at {m[6]}")
