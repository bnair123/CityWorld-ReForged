package me.daddychurchill.CityWorld.Support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import me.daddychurchill.CityWorld.CityWorldGenerator;
import me.daddychurchill.CityWorld.compat.BlockFace;
import me.daddychurchill.CityWorld.compat.Material;
import me.daddychurchill.CityWorld.worldgen.CitySites;
import me.daddychurchill.CityWorld.worldgen.Railways;

/**
 * The walk from a city's railway station to its metro (7.4): a railed stairwell in the station's forecourt, down one
 * block a cell beside the station (outside the city's ground, so nothing of the city is in the way), then a level,
 * lit, tiled corridor at the metro concourse's height into the side of the nearest station's concourse. The route
 * is a pure function of the plan (the station, the lines), so every chunk draws its own cells of it.
 */
public final class MetroWalk {
    private MetroWalk() {
    }

    /** A cell of the walk: its floor block {@code h} (planner frame), the way it is walked, a turn. */
    record Cell(int x, int z, int h, int dx, int dz, boolean corner, boolean step) {
    }

    /** A planned walk; {@code deck} is the forecourt's floor (planner frame). */
    record Walk(List<Cell> cells, int deck, int minX, int minZ, int maxX, int maxZ, Material stripe, String line,
            String station) {
    }

    /** Cells at the start open to the sky (their ceiling would stand above the forecourt). */
    static final int OPEN = 5;
    private static final int[] SIDES = { 1, -1 };

    private static final Map<CityWorldGenerator, Map<CitySites.Site, Optional<Walk>>> MEMO = Collections
            .synchronizedMap(new WeakHashMap<>());

    static Walk of(CityWorldGenerator generator, CitySites.Site site) {
        if (site == null)
            return null;
        return MEMO.computeIfAbsent(generator, g -> new ConcurrentHashMap<>())
                .computeIfAbsent(site, s -> Optional.ofNullable(compute(generator, s))).orElse(null);
    }

    static Walk at(CityWorldGenerator generator, int chunkX, int chunkZ) {
        if (!Metro.planned(generator) || generator.railways == null)
            return null;
        Walk w = of(generator, generator.citySites.siteAt(chunkX * 16 + 8, chunkZ * 16 + 8));
        if (w == null || chunkX * 16 + 18 < w.minX || chunkX * 16 - 3 > w.maxX || chunkZ * 16 + 18 < w.minZ
                || chunkZ * 16 - 3 > w.maxZ)
            return null;
        return w;
    }

    /** Whether the walk has a cell in (or within reach of) this chunk. */
    public static boolean touches(CityWorldGenerator generator, int chunkX, int chunkZ) {
        Walk w = at(generator, chunkX, chunkZ);
        if (w == null)
            return false;
        for (Cell c : w.cells)
            if (near(c, chunkX, chunkZ))
                return true;
        return false;
    }

    /** The shift (world y minus planner y) of the city whose walk this chunk holds. */
    public static int shiftAt(CityWorldGenerator generator, int chunkX, int chunkZ) {
        CitySites.Site site = generator.citySites.siteAt(chunkX * 16 + 8, chunkZ * 16 + 8);
        return site == null ? 0 : generator.citySites.shift(site);
    }

    private static boolean near(Cell c, int chunkX, int chunkZ) {
        return c.x + 3 >= chunkX * 16 && c.x - 3 < chunkX * 16 + 16 && c.z + 3 >= chunkZ * 16 && c.z - 3 < chunkZ * 16 + 16;
    }

    // ---- the plan ------------------------------------------------------------------------------------------------

    private static Walk compute(CityWorldGenerator generator, CitySites.Site site) {
        Railways railways = generator.railways;
        if (railways == null)
            return null;
        Railways.Hub hub = railways.hubOf(site);
        if (hub == null)
            return null;
        Metro.Lines lines = Metro.linesOf(generator, site);
        if (!lines.any())
            return null;
        int deck = hub.level() - generator.citySites.shift(site);
        boolean term = hub.terminus();
        double[] lateral = term ? new double[] { hub.nx(), hub.nz() } : new double[] { hub.ux(), hub.uz() };
        List<int[]> best = null;
        int bestCost = Integer.MAX_VALUE;
        Metro.Line bestLine = null;
        Metro.Station bestStation = null;
        for (double fs0 : new double[] { 4.5, -4.5, 7.5, -7.5, 2.5, -2.5 })
            for (int sign : SIDES) {
                int[] d = axis(lateral[0] * sign, lateral[1] * sign);
                double[] k = point(hub, 4.5, fs0);
                int kx = (int) Math.floor(k[0]), kz = (int) Math.floor(k[1]);
                if (!openFits(hub, kx, kz, d))
                    continue;
                for (Metro.Line line : new Metro.Line[] { lines.ew(), lines.ns() }) {
                    if (line == null)
                        continue;
                    Metro.Line other = line.ew() ? lines.ns() : lines.ew();
                    int floor = line.floor(generator) + 9;
                    int drop = deck - floor;
                    if (drop < OPEN + 1)
                        continue;
                    for (int i = 0; i < line.stations().size(); i++) {
                        if (lines.interchange() && i == line.centre())
                            continue; // the interchange stair stands beside the central stations
                        Metro.Station s = line.stations().get(i);
                        if (s.to() - s.from() < 2)
                            continue;
                        int b = (s.from() + 1) * 16 + 13;
                        for (int side : SIDES) {
                            int c = side > 0 ? 10 : 5;
                            int ex = line.blockX(b, c), ez = line.blockZ(b, c);
                            for (List<int[]> wps : routes(kx, kz, d, ex, ez, line.ew(), side, drop + 5)) {
                                int cost = score(wps, line, other, drop + 5);
                                if (cost < bestCost) {
                                    bestCost = cost;
                                    best = wps;
                                    bestLine = line;
                                    bestStation = s;
                                }
                            }
                        }
                    }
                }
            }
        if (best == null)
            return null;
        int floor = bestLine.floor(generator) + 9;
        List<Cell> cells = cells(best, deck, deck - floor);
        if (cells == null)
            return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minX = Math.min(minX, c.x);
            minZ = Math.min(minZ, c.z);
            maxX = Math.max(maxX, c.x);
            maxZ = Math.max(maxZ, c.z);
        }
        return new Walk(List.copyOf(cells), deck, minX, minZ, maxX, maxZ, bestLine.stripe(), bestLine.name(),
                Metro.stationName(generator, bestLine, bestStation));
    }

    /**
     * The ground the walk covers between the railway station's forecourt (reaching a few blocks back toward the station
     * building) and the metro station it leads to, as {minX, minZ, maxX, maxZ}, if this city's walk goes to the station
     * {@code station} of the line {@code line}; else null. A metro station's MTR area grown over it meets the railway
     * station's, and MTR then shows the two as an interchange.
     */
    public static int[] reach(CityWorldGenerator generator, CitySites.Site site, String line, String station) {
        Walk w = of(generator, site);
        if (w == null || !w.line().equals(line) || !w.station().equals(station) || w.cells().isEmpty())
            return null;
        Cell k = w.cells().get(0);
        int bx = k.x() - 4 * k.dx(), bz = k.z() - 4 * k.dz();
        return new int[] { Math.min(w.minX(), bx), Math.min(w.minZ(), bz), Math.max(w.maxX(), bx), Math.max(w.maxZ(), bz) };
    }

    /** The world axis nearest a direction, as a unit step. */
    private static int[] axis(double vx, double vz) {
        return Math.abs(vx) >= Math.abs(vz) ? new int[] { vx >= 0 ? 1 : -1, 0 } : new int[] { 0, vz >= 0 ? 1 : -1 };
    }

    /** World x, z of a point in the forecourt: {@code fa} from its outer edge toward the door, {@code fs} across it. */
    private static double[] point(Railways.Hub hub, double fa, double fs) {
        double a, c;
        if (hub.terminus()) {
            a = fa - hub.platformEnd() - Railways.CONCOURSE - Railways.FORECOURT;
            c = fs;
        } else {
            a = fs;
            c = fa - hub.halfWidth() - Railways.BUILDING_DEPTH - Railways.FORECOURT;
        }
        return new double[] { hub.x(a, c), hub.z(a, c) };
    }

    /** Where a block stands in the forecourt: {fa, fs}, or null outside it. */
    private static double[] inForecourt(Railways.Hub hub, int bx, int bz) {
        double px = bx + 0.5, pz = bz + 0.5;
        double a = Math.round(hub.along(px, pz) * 2) / 2.0, c = Math.round(hub.across(px, pz) * 2) / 2.0;
        double fa, fs, lo, hi;
        if (hub.terminus()) {
            fa = a - (-hub.platformEnd() - Railways.CONCOURSE - Railways.FORECOURT);
            fs = c;
            lo = -hub.halfWidth() - 1;
            hi = hub.halfWidth() + 1;
        } else {
            fa = c - (-hub.halfWidth() - Railways.BUILDING_DEPTH - Railways.FORECOURT);
            fs = a;
            lo = -Railways.BUILDING_LENGTH / 2.0 - 4;
            hi = Railways.BUILDING_LENGTH / 2.0 + 4;
        }
        if (fa < 1 || fa >= Railways.FORECOURT - 1 || fs < lo + 1 || fs >= hi - 1)
            return null;
        return new double[] { fa, fs };
    }

    /** The open stairwell (and the parapet past it) inside the forecourt, clear of the walk from its mouth to the door. */
    private static boolean openFits(Railways.Hub hub, int kx, int kz, int[] d) {
        for (int i = 0; i <= OPEN; i++)
            for (int l = -2; l <= 2; l++) {
                int bx = kx + d[0] * i - d[1] * l, bz = kz + d[1] * i + d[0] * l;
                double[] f = inForecourt(hub, bx, bz);
                if (f == null || Math.abs(f[1]) < 2.5)
                    return false;
            }
        return true;
    }

    /**
     * Candidate routes from {@code k} (first leg along {@code d}, at least {@code first} long) to {@code e}, whose
     * last leg runs across the line toward it from {@code side}.
     */
    private static List<List<int[]>> routes(int kx, int kz, int[] d, int ex, int ez, boolean ew, int side, int first) {
        // g: along the line, f: across it
        boolean dAlongG = ew ? d[0] != 0 : d[1] != 0;
        int kg = ew ? kx : kz, kf = ew ? kz : kx, eg = ew ? ex : ez, ef = ew ? ez : ex;
        int dg = dAlongG ? (ew ? d[0] : d[1]) : 0, df = dAlongG ? 0 : (ew ? d[1] : d[0]);
        List<List<int[]>> out = new ArrayList<>();
        int[] reach = { 8, 12, 20, 32, 48, 72 };
        if (dAlongG) {
            out.add(path(ew, new int[][] { { kg, kf }, { eg, kf }, { eg, ef } }));
            int pg = kg + dg * first;
            for (int m : reach) {
                int sf = ef + side * m;
                out.add(path(ew, new int[][] { { kg, kf }, { pg, kf }, { pg, sf }, { eg, sf }, { eg, ef } }));
            }
        } else {
            for (int m : reach) {
                int sf = ef + side * m;
                out.add(path(ew, new int[][] { { kg, kf }, { kg, sf }, { eg, sf }, { eg, ef } }));
            }
            int pf = kf + df * first;
            out.add(path(ew, new int[][] { { kg, kf }, { kg, pf }, { eg, pf }, { eg, ef } }));
        }
        out.removeIf(p -> !valid(p, d, ew, side, first));
        return out;
    }

    private static List<int[]> path(boolean ew, int[][] gf) {
        List<int[]> p = new ArrayList<>();
        for (int[] q : gf) {
            int[] xz = ew ? new int[] { q[0], q[1] } : new int[] { q[1], q[0] };
            if (!p.isEmpty() && p.get(p.size() - 1)[0] == xz[0] && p.get(p.size() - 1)[1] == xz[1])
                continue;
            p.add(xz);
        }
        // merge straight runs
        for (int i = p.size() - 2; i >= 1; i--) {
            int[] a = p.get(i - 1), b = p.get(i), c = p.get(i + 1);
            if ((a[0] == b[0] && b[0] == c[0]) || (a[1] == b[1] && b[1] == c[1]))
                p.remove(i);
        }
        return p;
    }

    /** Axis legs that turn at every waypoint, never back on themselves; the first long enough to go down in. */
    private static boolean valid(List<int[]> p, int[] d, boolean ew, int side, int first) {
        if (p.size() < 2)
            return false;
        int[] prev = null;
        for (int i = 1; i < p.size(); i++) {
            int[] a = p.get(i - 1), b = p.get(i);
            if (a[0] != b[0] && a[1] != b[1])
                return false;
            int len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            int[] dir = { Integer.signum(b[0] - a[0]), Integer.signum(b[1] - a[1]) };
            if (i == 1 && (dir[0] != d[0] || dir[1] != d[1] || len < first))
                return false;
            if (i == p.size() - 1) {
                // across the line, toward it from the chosen side
                int across = ew ? dir[1] : dir[0];
                if (across != -side || len < 8)
                    return false;
            } else if (len < 5)
                return false;
            if (prev != null && prev[0] * dir[0] + prev[1] * dir[1] != 0)
                return false; // straight on or back: not a turn
            prev = dir;
        }
        // never within five blocks of itself, apart from the cells beside it on the way
        List<int[]> cells = new ArrayList<>();
        for (int i = 1; i < p.size(); i++) {
            int[] a = p.get(i - 1), b = p.get(i);
            int dx = Integer.signum(b[0] - a[0]), dz = Integer.signum(b[1] - a[1]);
            for (int x = a[0], z = a[1]; x != b[0] || z != b[1]; x += dx, z += dz)
                cells.add(new int[] { x, z });
        }
        cells.add(p.get(p.size() - 1));
        Map<Long, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            for (int ox = -4; ox <= 4; ox++)
                for (int oz = -4; oz <= 4; oz++) {
                    Integer j = seen.get(key(c[0] + ox, c[1] + oz));
                    if (j != null && i - j > 8)
                        return false;
                }
            seen.put(key(c[0], c[1]), i);
        }
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Length, and dearly any run through the other line's band (its entrance shafts) or its own short of the end. */
    private static int score(List<int[]> p, Metro.Line line, Metro.Line other, int first) {
        int cost = 0;
        for (int i = 1; i < p.size(); i++) {
            int[] a = p.get(i - 1), b = p.get(i);
            int dx = Integer.signum(b[0] - a[0]), dz = Integer.signum(b[1] - a[1]);
            boolean last = i == p.size() - 1;
            for (int x = a[0], z = a[1]; x != b[0] || z != b[1]; x += dx, z += dz) {
                cost++;
                if (other != null && inBand(other, x, z))
                    cost += 50;
                if (!last && inBand(line, x, z))
                    cost += 1000;
            }
        }
        return cost;
    }

    /** Within a chunk of the line's row (and along its length). */
    private static boolean inBand(Metro.Line line, int x, int z) {
        int row = line.ew() ? Math.floorDiv(z, 16) - line.centreZ() : Math.floorDiv(x, 16) - line.centreX();
        int a = line.ew() ? Math.floorDiv(x, 16) - line.centreX() : Math.floorDiv(z, 16) - line.centreZ();
        return Math.abs(row) <= 1 && a >= line.from() - 1 && a <= line.to() + 1;
    }

    /** The cells, with their floors: down a step a cell from the second, never within two of a turn. */
    private static List<Cell> cells(List<int[]> p, int deck, int drop) {
        List<int[]> xz = new ArrayList<>();
        List<int[]> dirs = new ArrayList<>();
        java.util.Set<Integer> corners = new java.util.HashSet<>();
        for (int i = 1; i < p.size(); i++) {
            int[] a = p.get(i - 1), b = p.get(i);
            int dx = Integer.signum(b[0] - a[0]), dz = Integer.signum(b[1] - a[1]);
            if (i > 1)
                corners.add(xz.size()); // the turn: the first cell of each leg after the first
            for (int x = a[0], z = a[1]; x != b[0] || z != b[1]; x += dx, z += dz) {
                xz.add(new int[] { x, z });
                dirs.add(new int[] { dx, dz });
            }
        }
        int[] end = p.get(p.size() - 1);
        xz.add(end);
        dirs.add(dirs.get(dirs.size() - 1));
        List<Cell> out = new ArrayList<>();
        int h = deck, left = drop;
        for (int i = 0; i < xz.size(); i++) {
            boolean step = false;
            if (i > 0 && left > 0) {
                boolean clear = true;
                for (int j = i - 2; j <= i + 2; j++)
                    if (corners.contains(j))
                        clear = false;
                if (clear) {
                    h--;
                    left--;
                    step = true;
                }
            }
            out.add(new Cell(xz.get(i)[0], xz.get(i)[1], h, dirs.get(i)[0], dirs.get(i)[1], corners.contains(i), step));
        }
        return left == 0 ? out : null;
    }

    // ---- for the probe -------------------------------------------------------------------------------------------

    /** The chunks the walk of this city draws in (empty without one). */
    public static java.util.Set<Long> chunksOf(CityWorldGenerator generator, CitySites.Site site) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        Walk w = of(generator, site);
        if (w != null)
            for (Cell c : w.cells)
                for (int ox = -2; ox <= 2; ox += 2)
                    for (int oz = -2; oz <= 2; oz += 2)
                        out.add(net.minecraft.world.level.ChunkPos.asLong((c.x + ox) >> 4, (c.z + oz) >> 4));
        return out;
    }

    /** The walk's cells as {x, z, floor} in the planner's frame (empty without one). */
    public static List<int[]> cellsOf(CityWorldGenerator generator, CitySites.Site site) {
        Walk w = of(generator, site);
        List<int[]> out = new ArrayList<>();
        if (w != null)
            for (Cell c : w.cells)
                out.add(new int[] { c.x, c.z, c.h });
        return out;
    }

    /** The walk as generated: its route, and every cell whose way through is not clear (world frame). */
    public static String check(CityWorldGenerator generator, CitySites.Site site, net.minecraft.world.level.LevelReader level,
            int shift) {
        Walk w = of(generator, site);
        if (w == null)
            return "no walk";
        StringBuilder sb = new StringBuilder();
        Cell first = w.cells.get(0), last = w.cells.get(w.cells.size() - 1);
        sb.append(w.cells.size()).append(" cells from ").append(first.x).append(',').append(first.h + shift + 1).append(',')
                .append(first.z).append(" to ").append(last.x).append(',').append(last.h + shift + 1).append(',').append(last.z)
                .append(" (").append(w.line).append(", ").append(w.station).append(") turns");
        int blocked = 0;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < w.cells.size(); i++) {
            Cell c = w.cells.get(i);
            if (c.corner)
                sb.append(' ').append(c.x).append(',').append(c.z);
            for (int y = c.h + 1; y <= c.h + 2; y++) {
                var st = level.getBlockState(new net.minecraft.core.BlockPos(c.x, y + shift, c.z));
                if (!st.isAir() && !st.is(net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN)) {
                    blocked++;
                    if (bad.length() < 400)
                        bad.append(' ').append(i).append('@').append(c.x).append(',').append(y + shift).append(',').append(c.z)
                                .append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
                    break;
                }
            }
        }
        return sb.append("; blocked ").append(blocked).append(bad).toString();
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    /** This chunk's part of the walk, if any (chunk in the planner's frame). */
    public static void draw(CityWorldGenerator generator, RealBlocks chunk) {
        int cx = chunk.sectionX, cz = chunk.sectionZ;
        Walk w = at(generator, cx, cz);
        if (w == null)
            return;
        int x0 = cx * 16, z0 = cz * 16, deck = w.deck;
        List<Cell> cells = w.cells;
        boolean any = false;
        // the shell: floor, walls with the line's stripe, ceiling; a turn's all round
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (!near(c, cx, cz))
                continue;
            any = true;
            boolean open = i < OPEN;
            int top = open ? deck - 1 : c.h + 5;
            for (int u = -2; u <= 2; u++)
                for (int v = -2; v <= 2; v++) {
                    if (!c.corner && v != 0)
                        continue;
                    int bx = c.x - c.dz * u + c.dx * v, bz = c.z + c.dx * u + c.dz * v;
                    if (bx < x0 || bx >= x0 + 16 || bz < z0 || bz >= z0 + 16)
                        continue;
                    int lx = bx - x0, lz = bz - z0;
                    boolean wall = Math.max(Math.abs(u), Math.abs(v)) == 2;
                    for (int y = c.h - 1; y <= top; y++) {
                        Material m = Metro.SHELL;
                        if (wall && y >= c.h && y < c.h + 5)
                            m = y == c.h ? Metro.PLATFORM : y == c.h + 3 ? w.stripe : Metro.TILE;
                        chunk.setBlock(lx, y, lz, m);
                    }
                    if (open) {
                        chunk.setBlock(lx, deck, lz, Metro.SHELL);
                        if (wall && i > 0)
                            chunk.setBlock(lx, deck + 1, lz, Material.IRON_BARS);
                    }
                }
        }
        if (!any)
            return;
        // the way through: floor (a step where it goes down), air to the ceiling, or to the sky in the stairwell; down
        // each flight, with MTR, an escalator running up on the left (as one goes down) and the stairs on the right
        String[] esc = me.daddychurchill.CityWorld.integration.mtr.MtrStations.enabled() ? escalators(cells) : null;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (!near(c, cx, cz))
                continue;
            boolean open = i < OPEN;
            int top = open ? deck + 4 : c.h + 4;
            BlockFace up = face(-c.dx, -c.dz);
            for (int u = -1; u <= 1; u++)
                for (int v = -1; v <= 1; v++) {
                    if (!c.corner && v != 0)
                        continue;
                    int bx = c.x - c.dz * u + c.dx * v, bz = c.z + c.dx * u + c.dz * v;
                    if (bx < x0 || bx >= x0 + 16 || bz < z0 || bz >= z0 + 16)
                        continue;
                    int lx = bx - x0, lz = bz - z0;
                    if (c.step)
                        chunk.setStair(lx, c.h, lz, Material.POLISHED_ANDESITE_STAIRS, up);
                    else
                        chunk.setBlock(lx, c.h, lz, Metro.PLATFORM);
                    chunk.setBlocks(lx, c.h + 1, top + 1, lz, Material.AIR);
                }
            if (esc != null && esc[i + 2] != null)
                escalator(chunk, x0, z0, c.x, c.z, c.h, c.dx, c.dz, esc[i + 2]);
            // a light in the ceiling every third cell
            if (!open && i % 3 == 1 && in(c.x, c.z, x0, z0))
                chunk.setBlock(c.x - x0, c.h + 5, c.z - z0, Metro.LIGHT);
        }
        // the stairwell's dressing: a parapet past its foot with the board, a glass canopy over it on four posts, lamps
        // hanging under it; with MTR, the escalator's top landing out on the forecourt
        if (cells.size() > OPEN + 1) {
            Cell k = cells.get(0), past = cells.get(OPEN);
            if (esc != null && esc[1] != null)
                escalator(chunk, x0, z0, k.x - k.dx, k.z - k.dz, k.h, k.dx, k.dz, esc[1]);
            for (int u = -2; u <= 2; u++)
                put(chunk, x0, z0, past.x - past.dz * u, past.z + past.dx * u, deck + 1, Material.STONE_BRICKS);
            for (int i = 0; i < OPEN; i++)
                for (int u = -2; u <= 2; u++) {
                    Cell c = cells.get(i);
                    int bx = c.x - c.dz * u, bz = c.z + c.dx * u;
                    boolean post = (i == 0 || i == OPEN - 1) && Math.abs(u) == 2;
                    boolean rim = i == 0 || i == OPEN - 1 || Math.abs(u) == 2;
                    if (post)
                        for (int y = deck + 1; y <= deck + 3; y++)
                            put(chunk, x0, z0, bx, bz, y, Material.STONE_BRICKS);
                    put(chunk, x0, z0, bx, bz, deck + 4, rim ? Material.POLISHED_ANDESITE : Material.GLASS);
                    if (u == 0 && (i == 1 || i == OPEN - 2) && in(bx, bz, x0, z0))
                        chunk.setHangingLantern(bx - x0, deck + 3, bz - z0, Material.LANTERN);
                }
            Cell before = cells.get(OPEN - 1);
            if (in(before.x, before.z, x0, z0))
                chunk.setWallSign(before.x - x0, deck + 1, before.z - z0, face(-k.dx, -k.dz), "METRO", w.line,
                        PlaceNames.fit(w.station, 15), "↓ trains");
        }
        // a board at the foot of the stairs, and one at the concourse end pointing back up
        signOnWall(chunk, x0, z0, cells, firstFlat(cells), "METRO →", w.line, PlaceNames.fit(w.station, 15), "");
        signOnWall(chunk, x0, z0, cells, cells.size() - 3, "← RAILWAY", "STATION", "trains to", "other cities");
        chunk.reconnect(0, 16, deck - 1, deck + 3, 0, 16);
    }

    /**
     * The escalators: each flight (a run of steps) straight from two cells above its top to one past its foot, each of
     * those cells with the orientation MTR itself gives it from its neighbours (so a later update agrees). Index
     * {@code i + 2} holds cell {@code i}; 0 and 1 stand before the first cell, out on the forecourt.
     */
    static String[] escalators(List<Cell> cells) {
        int n = cells.size();
        String[] o = new String[n + 2];
        int i = 0;
        while (i < n) {
            if (!cells.get(i).step) {
                i++;
                continue;
            }
            int r = i;
            while (i < n && cells.get(i).step)
                i++;
            int s = i - 1;
            if (r < 1 || s + 1 >= n || s - r < 1)
                continue;
            Cell first = cells.get(r), foot = cells.get(s + 1), top = cells.get(r - 1);
            boolean ok = !foot.step && foot.h == cells.get(s).h && top.h == first.h + 1;
            for (int k = Math.max(0, r - 2); k <= s + 1 && ok; k++)
                ok = !cells.get(k).corner && cells.get(k).dx == first.dx && cells.get(k).dz == first.dz;
            if (r >= 2)
                ok &= !cells.get(r - 2).step && cells.get(r - 2).h == top.h;
            if (!ok)
                continue;
            o[r] = "landing_top";
            o[r + 1] = "transition_top";
            for (int k = r; k < s; k++)
                o[k + 2] = "slope";
            o[s + 2] = "transition_bottom";
            o[s + 3] = "landing_bottom";
        }
        return o;
    }

    /** One cell of an escalator: the pair of steps (left of the way down) and their handrails, running uphill. */
    private static void escalator(RealBlocks chunk, int x0, int z0, int x, int z, int h, int dx, int dz, String orientation) {
        // uphill is back along the walk; looking uphill, the left half is u = 0 and the right u = -1 (its partner)
        net.minecraft.core.Direction uphill = face(-dx, -dz).toDirection();
        for (int u : new int[] { 0, -1 }) {
            int bx = x - dz * u, bz = z + dx * u;
            if (!in(bx, bz, x0, z0))
                continue;
            boolean left = u == 0;
            var step = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.escalator(true, uphill, left, orientation);
            var rail = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.escalator(false, uphill, left, orientation);
            if (step == null || rail == null)
                return;
            chunk.setBlockState(bx - x0, h, bz - z0, step);
            chunk.setBlockState(bx - x0, h + 1, bz - z0, rail);
        }
    }

    private static int firstFlat(List<Cell> cells) {
        for (int i = 1; i < cells.size(); i++)
            if (!cells.get(i).step && cells.get(i - 1).step)
                return Math.min(cells.size() - 1, i + 1);
        return cells.size() - 1;
    }

    /** A wall sign on the corridor's right-hand wall at cell {@code i}, facing in. */
    private static void signOnWall(RealBlocks chunk, int x0, int z0, List<Cell> cells, int i, String... lines) {
        if (i <= 0 || i >= cells.size())
            return;
        Cell c = cells.get(i);
        if (c.corner)
            return;
        // the cell beside the wall: one to the right of the walk (right of (dx,dz) is (-dz,dx))
        int bx = c.x - c.dz, bz = c.z + c.dx;
        if (!in(bx, bz, x0, z0))
            return;
        chunk.setWallSign(bx - x0, c.h + 2, bz - z0, face(c.dz, -c.dx), lines);
    }

    private static boolean in(int bx, int bz, int x0, int z0) {
        return bx >= x0 && bx < x0 + 16 && bz >= z0 && bz < z0 + 16;
    }

    private static void put(RealBlocks chunk, int x0, int z0, int bx, int bz, int y, Material m) {
        if (in(bx, bz, x0, z0))
            chunk.setBlock(bx - x0, y, bz - z0, m);
    }

    private static BlockFace face(int dx, int dz) {
        if (dx > 0)
            return BlockFace.EAST;
        if (dx < 0)
            return BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
}
