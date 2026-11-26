"""Side views of highway decks, old vs smoothed, as one SVG. Usage: python deck_plot.py <ground dir> <out.svg>"""
import glob, os, sys
import highway_deck_sim as s

files = sorted(glob.glob(os.path.join(sys.argv[1], "*.csv")))
data = [(f, *s.load(f)) for f in files]
# the Deerford -> Eastside link, and the two with the most old climb per km
def climb(deck):
    return sum(abs(deck[i] - deck[i - 1]) for i in range(1, len(deck)))
ranked = sorted(data, key=lambda d: -climb(s.current(d[1], d[2], d[3])) / len(d[1]))
pick = [d for d in data if "1472_-640_5600_-2480" in d[0]] + ranked[:2]

W, H, PAD = 1400, 260, 40
parts = []
y0 = 0
for f, g, wet, p in pick:
    old = s.current(g, wet, p)
    new = s.taut(6, 6, 4, 8)(g, wet, p)
    n = len(g)
    span = min(n, 1000)  # the first 2 km, so bumps are visible
    lo = min(min(g[:span]), min(old[:span]), min(new[:span])) - 2
    hi = max(max(g[:span]), max(old[:span]), max(new[:span])) + 2
    sx = lambda i: PAD + (W - 2 * PAD) * i / (span - 1)
    sy = lambda v: y0 + PAD + (H - 2 * PAD) * (hi - v) / (hi - lo)
    name = os.path.basename(f).replace("link_", "").replace(".csv", "")
    parts.append(f'<text x="{PAD}" y="{y0 + 24}" font-size="16" font-family="sans-serif" fill="#ddd">'
                 f'Highway {name} (city centres) — first 2 km; height y {lo:.0f} to {hi:.0f}, exaggerated</text>')
    # water
    for i in range(span):
        if wet[i]:
            parts.append(f'<rect x="{sx(i):.1f}" y="{y0+PAD}" width="{(W-2*PAD)/span+0.5:.1f}" height="{H-2*PAD}" fill="#1d3b5a"/>')
    def line(vals, colour, width):
        pts = " ".join(f"{sx(i):.1f},{sy(vals[i]):.1f}" for i in range(span))
        parts.append(f'<polyline points="{pts}" fill="none" stroke="{colour}" stroke-width="{width}"/>')
    line(g, "#7a6a4f", 1.5)
    line(old, "#ff6b6b", 2)
    line(new, "#4dd2ff", 2.5)
    y0 += H
legend = (f'<text x="{PAD}" y="{y0 + 22}" font-size="15" font-family="sans-serif">'
          '<tspan fill="#b9a27a">■ ground</tspan>   <tspan fill="#ff6b6b">■ deck now (50-block average)</tspan>   '
          '<tspan fill="#4dd2ff">■ deck smoothed (±6 block band)</tspan>   <tspan fill="#5d8fbf">■ water</tspan></text>')
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{y0 + 40}" viewBox="0 0 {W} {y0 + 40}">'
       f'<rect width="100%" height="100%" fill="#16181d"/>' + "".join(parts) + legend + "</svg>")
open(sys.argv[2], "w", encoding="utf-8").write(svg)
print("wrote", sys.argv[2], [os.path.basename(d[0]) for d in pick])
