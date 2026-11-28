package me.daddychurchill.CityWorld.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A cable car from a city up to a viewpoint on a big mountain beside it, on vanilla land: rare by design (owner,
 * 2026-11-28: "only for ones near big mountains ... it should be tasteful"). A city gets one only when a peak stands at
 * least {@link #MIN_RISE} blocks above its streets, within {@link #MAX_REACH} blocks of its edge, inside its own cell,
 * and straight out from the city along a compass axis, so the stations are square to the world.
 * <p>
 * The car runs from a base station just outside the city's edge (where its ring has brought the ground to the city's
 * level) to a summit station on the peak, with a viewing terrace beyond it. Two cables, up on the right as one looks
 * at the mountain and down on the left, held up by pylons where the ground would come within {@link #CLEARANCE} of
 * them. Every number is a pure function of the site and vanilla's heights, memoised per site.
 */
public final class CableCars {
    /** How far above the city's streets a peak must stand. */
    public static final int MIN_RISE = 64;
    /** The furthest a peak may stand past the city's ring, and the nearest. */
    static final int MAX_REACH = 360, MIN_REACH = 48;
    /** Cable above the ground at its lowest (a cabin hangs about six below it). */
    public static final int CLEARANCE = 14;
    /** The highest pylon, cable above its foot, and the most pylons. */
    static final int MAX_PYLON = 44, MAX_PYLONS = 8;
    /** The most the base station may stand above the city's streets (its steps come down to them). */
    static final int MAX_BASE_RISE = 28;
    /** The cable's node above a station's deck (the cabin's floor meets the deck's top). */
    public static final int NODE_RISE = 6;
    /** How far apart the two cables are, either side of the line. */
    public static final int GAUGE = 2;
    /** A station's length behind its node (the loop round), and the terrace past the summit station. */
    public static final int LOOP = 11, STATION_BACK = 19, TERRACE = 14;

    /** One pylon, {@code u} blocks along the line from the base node, its cable at {@code cable}, its foot at {@code ground}. */
    public record Pylon(int u, int cable, int ground) {
    }

    /**
     * A cable car. The line runs from the base node {@code (baseX, baseZ)} along {@code (dx, dz)} (a unit compass step)
     * {@code length} blocks to the summit node; {@code (cx, cz)} = the step clockwise of it (the up cable's side).
     * {@code baseDeck}/{@code topDeck}: the y of each station's deck block.
     */
    public record Car(CitySites.Site site, int baseX, int baseZ, int dx, int dz, int length, int baseDeck, int topDeck,
            int peakX, int peakZ, int peak, List<Pylon> pylons) {
        public int cx() {
            return -dz;
        }

        public int cz() {
            return dx;
        }

        /** World x of the point {@code u} along and {@code v} across (positive: the up cable's side). */
        public int x(int u, int v) {
            return baseX + dx * u + cx() * v;
        }

        public int z(int u, int v) {
            return baseZ + dz * u + cz() * v;
        }

        public int baseNode() {
            return baseDeck + NODE_RISE;
        }

        public int topNode() {
            return topDeck + NODE_RISE;
        }

        /** The cable's height {@code u} along the line (between the pylons, straight). */
        public double cableAt(double u) {
            int pu = 0, py = baseNode();
            for (Pylon p : pylons) {
                if (u <= p.u())
                    return py + (p.cable() - py) * (u - pu) / Math.max(1, p.u() - pu);
                pu = p.u();
                py = p.cable();
            }
            return py + (topNode() - py) * (u - pu) / Math.max(1, length - pu);
        }

        /** The block bounds of everything the car draws: {minX, minZ, maxX, maxZ}. */
        public int[] box() {
            int u0 = -STATION_BACK - 2, u1 = length + LOOP + 4 + TERRACE, v = TERRACE / 2 + 4;
            int xa = x(u0, -v), xb = x(u1, v), za = z(u0, -v), zb = z(u1, v);
            return new int[] { Math.min(xa, xb), Math.min(za, zb), Math.max(xa, xb), Math.max(za, zb) };
        }

        public boolean touches(int x0, int z0, int x1, int z1) {
            int[] b = box();
            return b[0] <= x1 && b[2] >= x0 && b[1] <= z1 && b[3] >= z0;
        }

        /** An id for MTR and the log: the site's centre and the line's start. */
        public long id() {
            long h = 0x4341_424CL ^ (long) site.centreX() * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(site.centreZ(), 21)
                    ^ Long.rotateLeft(baseX * 31L + baseZ, 43);
            h ^= h >>> 29;
            return h == 0 ? 1 : h;
        }
    }

    /** Why a site has no car (for the survey), or the car. */
    public record Verdict(Car car, String refusal) {
    }

    private final CitySites sites;
    private final Highways highways;
    private final Railways railways;
    private final ConcurrentHashMap<CitySites.Site, Verdict> memo = new ConcurrentHashMap<>();

    public CableCars(CitySites sites, Highways highways, Railways railways) {
        this.sites = sites;
        this.highways = highways;
        this.railways = railways;
    }

    /** The city's cable car, or null (planned the first time a site is asked about: some hundreds of exact columns). */
    public Car of(CitySites.Site site) {
        return verdict(site).car();
    }

    public Verdict verdict(CitySites.Site site) {
        if (site == null)
            return new Verdict(null, "no city");
        Verdict known = memo.get(site);
        if (known != null)
            return known;
        Verdict v;
        try {
            v = plan(site);
        } catch (RuntimeException e) {
            v = new Verdict(null, "failed: " + e);
        }
        Verdict was = memo.putIfAbsent(site, v);
        return was != null ? was : v;
    }

    /** The cable car whose drawing reaches into this square of blocks, if any (one city's, the cell's). */
    public List<Car> touching(int x0, int z0, int x1, int z1) {
        List<Car> found = new ArrayList<>();
        int cell = sites.cell();
        long seen = Long.MIN_VALUE;
        for (int[] c : new int[][] { { x0, z0 }, { x1, z0 }, { x0, z1 }, { x1, z1 } }) {
            long key = ((long) Math.floorDiv(c[0], cell) << 32) ^ (Math.floorDiv(c[1], cell) & 0xffffffffL);
            if (key == seen)
                continue;
            seen = key;
            Car car = of(sites.siteAt(c[0], c[1]));
            if (car != null && car.touches(x0, z0, x1, z1) && !found.contains(car))
                found.add(car);
        }
        return found;
    }

    /** Vanilla's ground at a column as the car meets it: the city's ring's easing applied. */
    int ground(CitySites.Site site, int x, int z) {
        int top = sites.heights().top(x, z);
        long dx = x - site.centreX(), dz = z - site.centreZ();
        long reach = site.reach();
        return dx * dx + dz * dz <= reach * reach ? sites.groundAt(site, x, z, top) : top;
    }

    private Verdict plan(CitySites.Site site) {
        int cell = sites.cell();
        int cellX0 = Math.floorDiv(site.centreX(), cell) * cell, cellZ0 = Math.floorDiv(site.centreZ(), cell) * cell;
        int margin = 48;
        int inner = site.reach() + MIN_REACH, outer = site.reach() + MAX_REACH;
        // the highest ground round the city, on a coarse grid of exact columns
        int bestX = 0, bestZ = 0, best = Integer.MIN_VALUE;
        int step = 64;
        for (int gx = site.centreX() - outer; gx <= site.centreX() + outer; gx += step)
            for (int gz = site.centreZ() - outer; gz <= site.centreZ() + outer; gz += step) {
                double d = Math.hypot(gx - site.centreX(), gz - site.centreZ());
                if (d < inner || d > outer)
                    continue;
                if (gx < cellX0 + margin || gx > cellX0 + cell - margin || gz < cellZ0 + margin || gz > cellZ0 + cell - margin)
                    continue;
                // vanilla's own cheap estimate (the highways' grid) where there are highways: exact columns cost a
                // millisecond each, and most cities have no mountain
                int top = highways != null ? highways.gridTop(gx, gz) : sites.heights().top(gx, gz);
                if (top > best) {
                    best = top;
                    bestX = gx;
                    bestZ = gz;
                }
            }
        if (best == Integer.MIN_VALUE)
            return new Verdict(null, "no ground in reach");
        best = sites.heights().top(bestX, bestZ);
        if (best - site.level() < MIN_RISE - 16)
            return new Verdict(null, "highest " + best + " is " + (best - site.level()) + " above the streets");
        // climb to the summit: finer and finer steps uphill
        for (int s : new int[] { 16, 8, 4, 2, 1 }) {
            boolean moved = true;
            for (int guard = 0; moved && guard < 12; guard++) {
                moved = false;
                for (int[] o : new int[][] { { s, 0 }, { -s, 0 }, { 0, s }, { 0, -s } }) {
                    int t = sites.heights().top(bestX + o[0], bestZ + o[1]);
                    if (t > best) {
                        best = t;
                        bestX += o[0];
                        bestZ += o[1];
                        moved = true;
                    }
                }
            }
        }
        int rise = best - site.level();
        if (rise < MIN_RISE)
            return new Verdict(null, "summit " + best + " is only " + rise + " above the streets");
        if (best < sites.seaLevel() + MIN_RISE)
            return new Verdict(null, "summit " + best + " too low");
        // square to the world: the compass axis nearest the bearing, the line through the summit
        int ox = bestX - site.centreX(), oz = bestZ - site.centreZ();
        int dx, dz;
        if (Math.abs(ox) >= Math.abs(oz)) {
            dx = Integer.signum(ox);
            dz = 0;
        } else {
            dx = 0;
            dz = Integer.signum(oz);
        }
        int across = dx != 0 ? oz : ox;
        if (Math.abs(across) > site.radius() * 0.85)
            return new Verdict(null, "summit off the city's axis (" + across + " across, radius " + site.radius() + ")");
        // the base: the first column out along the line past the city's edge, by a few blocks
        int baseX = 0, baseZ = 0;
        boolean found = false;
        for (int r = 0; r <= site.reach() + 32; r++) {
            int x = dx != 0 ? site.centreX() + dx * r : bestX, z = dz != 0 ? site.centreZ() + dz * r : bestZ;
            if (outsideDisc(site, x, z, dx, dz)) {
                baseX = x;
                baseZ = z;
                found = true;
                break;
            }
        }
        if (!found)
            return new Verdict(null, "no edge on the line");
        int length = Math.abs(dx != 0 ? bestX - baseX : bestZ - baseZ);
        if (length < 96)
            return new Verdict(null, "summit only " + length + " from the base");
        // the summit node a few blocks short of the top, the deck at the top
        int topDeck = best;
        // the base stands on the ground at its node, up the slope the city's ring makes toward the mountain, its back
        // door on steps down toward the end of a street
        int baseDeck = ground(site, baseX, baseZ);
        if (baseDeck < sites.seaLevel() - 1)
            return new Verdict(null, "base under the sea");
        if (baseDeck - site.level() > MAX_BASE_RISE || baseDeck < site.level() - 4)
            return new Verdict(null, "base " + (baseDeck - site.level()) + " above the streets");
        // the profile: the ground every 4 blocks along the line, across the cables' width
        int samples = length / 4;
        int[] groundAt = new int[samples + 1];
        for (int i = 0; i <= samples; i++) {
            int u = Math.min(length, i * 4), g = Integer.MIN_VALUE;
            for (int v : new int[] { -GAUGE - 1, 0, GAUGE + 1 })
                g = Math.max(g, ground(site, baseX + dx * u + -dz * v, baseZ + dz * u + dx * v));
            groundAt[i] = g;
        }
        List<Pylon> pylons = new ArrayList<>();
        String why = span(groundAt, 0, baseDeck + NODE_RISE, samples, topDeck + NODE_RISE, pylons, 0, samples);
        if (why != null)
            return new Verdict(null, why);
        pylons.sort(java.util.Comparator.comparingInt(Pylon::u));
        breakLongSpans(groundAt, baseDeck + NODE_RISE, topDeck + NODE_RISE, length, pylons);
        Car car = new Car(site, baseX, baseZ, dx, dz, length, baseDeck, topDeck, bestX, bestZ, best, List.copyOf(pylons));
        // nothing else's ground: highways, railways, their stations and depots
        int[] b = car.box();
        if (highways != null && !highways.linksTouching(b[0], b[1], b[2], b[3]).isEmpty())
            return new Verdict(null, "a highway crosses it");
        if (railways != null && (!railways.linesTouching(b[0], b[1], b[2], b[3]).isEmpty()
                || !railways.hubsTouching(b[0], b[1], b[2], b[3]).isEmpty()
                || !railways.depotsTouching(b[0], b[1], b[2], b[3]).isEmpty()))
            return new Verdict(null, "a railway crosses it");
        return new Verdict(car, null);
    }

    /**
     * Hang a cable from sample {@code a} (cable at {@code ya}) to {@code b} ({@code yb}): where it would come within
     * {@link #CLEARANCE} of the ground, a pylon at the worst sample, and each half again. Null, or why it cannot.
     */
    private static String span(int[] ground, int a, int ya, int b, int yb, List<Pylon> pylons, int depth, int last) {
        if (b - a <= 1)
            return null;
        int worst = -1;
        double worstGap = Double.MAX_VALUE;
        for (int i = a + 1; i < b; i++) {
            double cable = ya + (yb - ya) * (i - a) / (double) (b - a);
            // near a station the cabin comes down to its platform: the clearance grows with the distance out
            double gap = cable - ground[i] - clearance(Math.min(i, last - i) * 4) + CLEARANCE;
            if (gap < worstGap) {
                worstGap = gap;
                worst = i;
            }
        }
        if (worstGap >= CLEARANCE)
            return null;
        if (pylons.size() >= MAX_PYLONS || depth > 10)
            return "too many pylons";
        // a pylon a little higher than the clearance needs, so the cable sags clear on both sides
        int cable = ground[worst] + CLEARANCE + 4;
        if (cable - ground[worst] > MAX_PYLON)
            return "a pylon too tall";
        pylons.add(new Pylon(worst * 4, cable, ground[worst]));
        String w = span(ground, a, ya, worst, cable, pylons, depth + 1, last);
        return w != null ? w : span(ground, worst, cable, b, yb, pylons, depth + 1, last);
    }

    /** The longest a cable runs between two supports where a tower could stand under it (a gorge may be longer). */
    static final int MAX_SPAN = 200;

    /**
     * A tower under any span longer than {@link #MAX_SPAN}: on the highest ground of its middle half, the cable still
     * straight through it (so the clearance the spans already have is kept), as long as the tower is no taller than
     * {@link #MAX_PYLON} and a half. Sorted by u, as {@code pylons} must be on the way in.
     */
    private static void breakLongSpans(int[] ground, int yBase, int yTop, int length, List<Pylon> pylons) {
        for (int guard = 0; guard < 12 && pylons.size() < MAX_PYLONS; guard++) {
            boolean added = false;
            int pu = 0, py = yBase;
            for (int k = 0; k <= pylons.size() && !added; k++) {
                int nu = k < pylons.size() ? pylons.get(k).u() : length;
                int ny = k < pylons.size() ? pylons.get(k).cable() : yTop;
                if (nu - pu > MAX_SPAN) {
                    int best = -1;
                    for (int i = (pu + (nu - pu) / 4) / 4; i <= (nu - (nu - pu) / 4) / 4 && i < ground.length; i++)
                        if (best < 0 || ground[i] > ground[best])
                            best = i;
                    if (best >= 0) {
                        int u = best * 4;
                        int cable = (int) Math.round(py + (ny - py) * (u - pu) / (double) (nu - pu));
                        if (cable - ground[best] <= MAX_PYLON * 3 / 2 && cable - ground[best] >= CLEARANCE) {
                            pylons.add(k, new Pylon(u, cable, ground[best]));
                            added = true;
                        }
                    }
                }
                pu = nu;
                py = ny;
            }
            if (!added)
                return;
        }
    }

    /** The clearance the cable needs {@code d} blocks out from the nearer station (the drawing cuts what is closer). */
    public static int clearance(int d) {
        return Math.min(CLEARANCE, 3 + d / 3);
    }

    /** Whether a base station here (its node at x, z, the line along dx, dz) stands wholly outside the city's disc. */
    private boolean outsideDisc(CitySites.Site site, int x, int z, int dx, int dz) {
        for (int u : new int[] { -STATION_BACK - 2, 0, 3 })
            for (int v : new int[] { -7, 0, 7 })
                if (sites.edgeDistance(site, x + dx * u - dz * v, z + dz * u + dx * v) < 2)
                    return false;
        return true;
    }
}
