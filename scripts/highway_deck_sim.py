"""Simulates CityWorld highway deck profiles (Highways.finish) over ground dumped from a world, and scores candidate
profiles: reversals, short bumps, climb, bridge and tunnel share, cut and fill. The ground dump came from a temporary
block in Highways.finish (one CSV per link: i,x,z,ground,wet,a,b,sea,clearance,fillMax,tunnel,grade); see
NAVIGATION-PLAN.md. Usage: python scripts/highway_deck_sim.py <ground dir>

"""
import csv, glob, math, os, sys

STEP = 2
DEEP_CUT = None


def load(path):
    rows = list(csv.DictReader(open(path)))
    g = [float(r["ground"]) for r in rows]
    wet = [r["wet"] == "1" for r in rows]
    r0 = rows[0]
    p = dict(a=int(r0["a"]), b=int(r0["b"]), sea=int(r0["sea"]), clearance=int(r0["clearance"]),
             fillMax=int(r0["fillMax"]), tunnel=int(r0["tunnel"]), grade=float(r0["grade"]))
    return g, wet, p


def box(vals, half):
    n = len(vals)
    pre = [0.0]
    for v in vals:
        pre.append(pre[-1] + v)
    out = []
    for i in range(n):
        lo, hi = max(0, i - half), min(n - 1, i + half)
        out.append((pre[hi + 1] - pre[lo]) / (hi - lo + 1))
    return out


def grade_and_pin(deck, p):
    n = len(deck)
    per = p["grade"] * STEP
    d = list(deck)
    for i in range(n):
        fa, fb = i * per, (n - 1 - i) * per
        up = min(p["a"] + fa, p["b"] + fb)
        lo = max(p["a"] - fa, p["b"] - fb)
        d[i] = max(lo, min(up, d[i]))
    for i in range(1, n):
        d[i] = max(d[i - 1] - per, min(d[i - 1] + per, d[i]))
    for i in range(n - 2, -1, -1):
        d[i] = max(d[i + 1] - per, min(d[i + 1] + per, d[i]))
    return d


def current(g, wet, p):
    return grade_and_pin(box(g, 12), p)


def wide(half):
    def f(g, wet, p):
        return grade_and_pin(box(box(g, half), half), p)
    return f


def taut(tol_fill, tol_cut, pre_smooth=4, round_half=4):
    """The smoothest line inside a tube around the (lightly smoothed) ground: clipped smoothing, coarse to fine."""
    def f(g, wet, p):
        n = len(g)
        gs = box(g, pre_smooth)
        lo = [gs[i] - tol_cut for i in range(n)]
        hi = [gs[i] + tol_fill for i in range(n)]
        floor = p["sea"] + p["clearance"]
        for i in range(n):
            if wet[i]:
                lo[i] = max(lo[i], floor)
                hi[i] = max(hi[i], lo[i]) + 1e9  # over water: as high as it likes
        d = [min(hi[i], max(lo[i], gs[i])) for i in range(n)]
        for half in (128, 64, 32, 16, 8, 4, 2, 1):
            for _ in range(12):
                s = box(d, half)
                d = [min(hi[i], max(lo[i], s[i])) for i in range(n)]
        d = box(d, round_half)  # round the corners a little
        return grade_and_pin(d, p)
    return f


def kinds(deck, g, wet, p):
    n = len(deck)
    ys = [round(v) for v in deck]
    over = [wet[i] or ys[i] - g[i] > p["fillMax"] for i in range(n)]
    under = [(not wet[i]) and g[i] - ys[i] > p["tunnel"] for i in range(n)]
    k = []
    for i in range(n):
        br = any(over[j] for j in range(max(0, i - 3), min(n, i + 4)))
        k.append("B" if br else "T" if under[i] else "G")
    i = 0
    while i < n:
        if k[i] != "T":
            i += 1
            continue
        j = i
        while j < n and k[j] == "T":
            j += 1
        deepest = max(g[t] - ys[t] for t in range(i, j))
        if (j - i) * STEP < 16 and (DEEP_CUT is None or deepest <= DEEP_CUT):
            for t in range(i, j):
                k[t] = "G"
        i = j
    return k


def score(deck, g, wet, p):
    n = len(deck)
    climb = sum(abs(deck[i] - deck[i - 1]) for i in range(1, n))
    d, rev, short, last, ext = 0, 0, 0, -1, deck[0]
    for i in range(1, n):
        if d >= 0 and deck[i] > ext:
            ext, d = deck[i], 1
        elif d <= 0 and deck[i] < ext:
            ext, d = deck[i], -1
        elif (d == 1 and ext - deck[i] >= 1) or (d == -1 and deck[i] - ext >= 1):
            rev += 1
            if last >= 0 and (i - last) * STEP < 200:
                short += 1
            last, d, ext = i, -d, deck[i]
    k = kinds(deck, g, wet, p)
    work = [abs(deck[i] - g[i]) for i in range(n) if k[i] == "G"]
    return dict(n=n, len=(n - 1) * STEP, climb=climb, rev=rev, short=short, B=k.count("B"), T=k.count("T"),
                work=sum(work), cutmax=max([g[i] - deck[i] for i in range(n) if k[i] == "G"] + [0]))


def main():
    files = sorted(glob.glob(os.path.join(sys.argv[1], "*.csv")))
    data = [load(f) for f in files]
    global DEEP_CUT
    cands = [("current", current, None), ("taut4", taut(4, 4), None), ("taut4/dc", taut(4, 4), 18),
             ("taut5/dc", taut(5, 5), 18), ("taut6/dc", taut(6, 6), 18), ("taut6r8/dc", taut(6, 6, 4, 8), 18),
             ("taut8/dc", taut(8, 8), 18), ("f7c5/dc", taut(7, 5), 18), ("f7c5r8/dc", taut(7, 5, 4, 8), 18)]
    print(f"{'candidate':12} {'rev/km':>7} {'short':>6} {'climb/km':>9} {'bridge%':>8} {'tunnel%':>8} {'avg|cut/fill|':>14} {'maxcut':>7}")
    for name, f, dc in cands:
        DEEP_CUT = dc
        tot = dict(n=0, len=0, climb=0, rev=0, short=0, B=0, T=0, work=0, cutmax=0, gsamples=0)
        for g, wet, p in data:
            s = score(f(g, wet, p), g, wet, p)
            for key in ("n", "len", "climb", "rev", "short", "B", "T", "work"):
                tot[key] += s[key]
            tot["gsamples"] += s["n"] - s["B"] - s["T"]
            tot["cutmax"] = max(tot["cutmax"], s["cutmax"])
        km = tot["len"] / 1000
        print(f"{name:12} {tot['rev']/km:7.1f} {tot['short']:6d} {tot['climb']/km:9.1f} {100*tot['B']/tot['n']:8.1f} "
              f"{100*tot['T']/tot['n']:8.1f} {tot['work']/max(1,tot['gsamples']):14.2f} {tot['cutmax']:7.1f}")


if __name__ == "__main__":
    main()
