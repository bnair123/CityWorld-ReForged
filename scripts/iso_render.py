#!/usr/bin/env python3
"""Render a box of a saved world as an isometric picture: how a building LOOKS, without a client.

    python scripts/iso_render.py <region dir> x0 x1 y0 y1 z0 z1 out.png [scale] [view]

view is the corner the camera stands at: ne, nw, se or sw (default se: looking north-west). Blocks are drawn as
shaded cubes in painter's order, coloured from a small table of vanilla blocks (by name, so a mod's block takes a
colour from its name's hash). Thin blocks (panes, bars, walls, slabs, stairs, lanterns) are drawn smaller, so a
window reads as a window and a parapet as a parapet.
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from region_dump import block_at_fn  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

AIR = {'air', 'cave_air', 'void_air'}
COL = {
    'bricks': (150, 74, 58), 'stone_bricks': (128, 128, 128), 'polished_andesite': (136, 140, 138),
    'smooth_stone': (160, 160, 160), 'smooth_stone_slab': (165, 165, 165), 'stone_brick_wall': (120, 120, 120),
    'stone_brick_stairs': (125, 125, 125), 'stone_brick_slab': (130, 130, 130), 'glass_pane': (170, 210, 230),
    'glass': (185, 220, 235), 'light_gray_concrete': (155, 155, 148), 'white_concrete': (225, 228, 228),
    'black_concrete': (20, 22, 26), 'polished_deepslate': (72, 72, 76), 'grass_block': (100, 160, 70),
    'yellow_concrete': (240, 175, 20), 'sea_lantern': (210, 235, 225), 'lantern': (240, 180, 80),
    'spruce_stairs': (110, 80, 50), 'stone': (120, 120, 120), 'potted_azalea': (90, 130, 60),
    'cauldron': (60, 60, 64), 'oak_sign': (170, 135, 80), 'dirt': (130, 95, 65), 'water': (50, 90, 200),
    'oak_leaves': (60, 120, 40), 'oak_log': (100, 80, 50), 'iron_bars': (110, 110, 115),
    'deepslate_tiles': (60, 60, 64), 'polished_blackstone': (45, 40, 48), 'copper_block': (190, 110, 80),
    'oxidized_copper': (80, 160, 140), 'weathered_copper': (100, 150, 120), 'exposed_copper': (160, 120, 100),
    'quartz_block': (235, 230, 222), 'smooth_quartz': (236, 232, 225), 'quartz_pillar': (232, 228, 220),
    'chiseled_stone_bricks': (118, 118, 118), 'calcite': (222, 224, 220), 'mud_bricks': (140, 105, 80),
    'terracotta': (152, 94, 68), 'red_terracotta': (143, 61, 46), 'dark_oak_planks': (66, 43, 20),
    'spruce_planks': (114, 84, 48), 'deepslate_brick_stairs': (70, 70, 74), 'deepslate_brick_slab': (70, 70, 74),
    'deepslate_bricks': (70, 70, 74), 'dark_prismarine': (52, 92, 76), 'blackstone': (42, 36, 40),
    'gold_block': (240, 200, 60), 'cut_copper': (190, 108, 80), 'cut_copper_stairs': (190, 108, 80),
    'oxidized_cut_copper': (80, 160, 140), 'oxidized_cut_copper_stairs': (80, 160, 140),
    'oxidized_cut_copper_slab': (80, 160, 140), 'brick_stairs': (150, 74, 58), 'brick_slab': (150, 74, 58),
    'brick_wall': (150, 74, 58), 'polished_andesite_stairs': (136, 140, 138), 'polished_andesite_slab': (136, 140, 138),
    'smooth_stone_stairs': (160, 160, 160), 'andesite_wall': (130, 130, 130), 'light_gray_stained_glass_pane': (160, 170, 175),
    'gray_stained_glass_pane': (110, 120, 128), 'cyan_stained_glass_pane': (90, 150, 170), 'end_rod': (240, 235, 220),
    'redstone_lamp': (200, 150, 90), 'chain': (70, 70, 74), 'clock_face': (230, 230, 230), 'stripped_dark_oak_log': (80, 55, 30), 'escalator_step': (70, 74, 80), 'escalator_side': (40, 40, 44),
    'red_concrete': (142, 33, 33), 'psd_door': (200, 120, 40), 'psd_glass': (150, 200, 225), 'psd_glass_end': (150, 200, 225), 'psd_top': (230, 230, 230), 'pids_1': (20, 20, 30), 'pids_pole': (90, 90, 95), 'ticket_barrier_entrance_1': (30, 160, 60), 'ticket_barrier_exit_1': (200, 50, 50), 'ticket_machine': (240, 200, 40), 'route_sign_standing_light': (40, 80, 160), 'platform_na_1': (200,200,200), 'platform': (210,210,200), 'villager': (0,0,0), 'blue_concrete': (45, 47, 143),
}
THIN = ('pane', 'bars', 'wall', 'fence', 'lantern', 'chain', 'sign', 'rod', 'button', 'torch', 'potted', 'rail', 'carpet')


def colour(name):
    n = name.split('[')[0].split(':')[-1]
    if n in COL:
        return COL[n]
    if n.endswith('_slab') or n.endswith('_stairs'):
        base = n.rsplit('_', 1)[0]
        for k in (base, base + 's'):
            if k in COL:
                return COL[k]
    h = hashlib.md5(n.encode()).digest()
    return (60 + h[0] % 160, 60 + h[1] % 160, 60 + h[2] % 160)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def main():
    a = sys.argv
    region = a[1]
    x0, x1, y0, y1, z0, z1 = (int(v) for v in a[2:8])
    out = a[8]
    s = float(a[9]) if len(a) > 9 else 6.0
    view = a[10] if len(a) > 10 else 'se'
    at = block_at_fn(region)
    # camera at the view corner: flip axes so it always looks toward -x, -z in screen terms
    fx = -1 if view in ('nw', 'sw') else 1
    fz = -1 if view in ('nw', 'ne') else 1
    blocks = []
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                n = at(x, y, z)
                if n.split('[')[0] in AIR:
                    continue
                blocks.append((x, y, z, n))
    # local coordinates increasing toward the camera
    def loc(x, z):
        return ((x - x0) if fx == 1 else (x1 - x)), ((z - z0) if fz == 1 else (z1 - z))
    W = (x1 - x0 + z1 - z0 + 4) * s * 0.866 + 20
    H = ((x1 - x0 + z1 - z0) * 0.5 + (y1 - y0) + 4) * s + 20
    img = Image.new('RGB', (int(W), int(H)), (236, 240, 244))
    d = ImageDraw.Draw(img)
    ox = (z1 - z0 + 2) * s * 0.866 + 10
    oy = (y1 - y0 + 2) * s + 10

    def pt(lx, ly, lz):
        return (ox + (lx - lz) * s * 0.866, oy + (lx + lz) * s * 0.5 - ly * s)

    solid = set()
    for x, y, z, n in blocks:
        if not any(t in n for t in THIN):
            solid.add((x, y, z))
    blocks.sort(key=lambda b: (sum(loc(b[0], b[2])), b[1]))
    for x, y, z, n in blocks:
        lx, lz = loc(x, z)
        c = colour(n)
        thin = any(t in n for t in THIN)
        slab = n.split('[')[0].endswith('_slab') and 'type=double' not in n
        top_slab = slab and 'type=top' in n
        i = 0.32 if thin else 0.0
        h0 = 0.5 if top_slab else 0.0
        h1 = 0.5 if slab and not top_slab else (0.85 if thin and ('lantern' in n or 'potted' in n) else 1.0)
        a0, a1 = lx + i, lx + 1 - i
        b0, b1 = lz + i, lz + 1 - i
        yb, yt = y - y0 + h0, y - y0 + h1
        # hidden faces: skip a face whose neighbour toward the camera is solid
        nx = (x + fx, y, z) in solid
        nz = (x, y, z + fz) in solid
        nu = (x, y + 1, z) in solid
        if not nx:
            d.polygon([pt(a1, yb, b0), pt(a1, yb, b1), pt(a1, yt, b1), pt(a1, yt, b0)], fill=shade(c, 0.78))
        if not nz:
            d.polygon([pt(a0, yb, b1), pt(a1, yb, b1), pt(a1, yt, b1), pt(a0, yt, b1)], fill=shade(c, 0.62))
        if not nu:
            d.polygon([pt(a0, yt, b0), pt(a1, yt, b0), pt(a1, yt, b1), pt(a0, yt, b1)], fill=shade(c, 1.0))
    img.save(out)
    print('wrote', out, img.size, len(blocks), 'blocks')


if __name__ == '__main__':
    main()
