package me.daddychurchill.CityWorld.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Railways between the cities of a vanilla-land world (7.4): ONE station per city ({@link Hub}) and a line for every
 * pair of cities a highway joins, from a pair of the station's tracks to a pair of the other's, so the lines make one
 * network: a train can run from city to city to city. EMPTY trackbed, for a player to lay Create or MTR track on. A pure
 * function of the seed, the {@link CitySites} and the highways' pairs; stations from the sites alone (the city's planner
 * paves each station's street), lines planned once each and drawn chunk by chunk, like {@link Highways}.
 *
 * <p><b>The track's geometry follows what both mods can lay</b> (Create 6, read from its source 2026-11-26:
 * {@code TrackShape}, {@code TrackPlacement}; MTR 4.1: {@code Angle}, {@code RailMath}): straight runs only in the eight
 * compass directions, every bend a 45° turn on an arc of {@code curveRadius} (a 90° change is two of them with a straight
 * between), every corner on a block's centre, climbs no steeper than {@code grade}, level through the stations.
 *
 * <p><b>A station</b> lies along a compass axis (east-west or north-south) just outside its city, and is a THROUGH
 * station or a TERMINUS by where its lines go: through when lines leave toward both ends of an axis (each within 67.5°
 * of one end), so a train runs on through it; a terminus, with buffer stops at the city end and a concourse across the
 * platforms, when they all go one way. A platform pair for each city (up to {@link #MAX_PAIRS}); past the platforms at
 * each end a level FAN tapers from the station's width down to one double-track TRUNK (the fan is where a player lays
 * the ladder of switches from every platform to the trunk), and every line leaving that end branches off the trunk at
 * its own junction, {@link #JUNCTION_SPACING} apart: the sharpest turn nearest the station, the straightest last,
 * carrying on where the trunk ends. So all of an end's lines share one pair of tracks into the station, and any train
 * can reach any platform. A through station's building stands on the city side with a footbridge over the tracks and stairs
 * down to every platform; a forecourt in front, and a road from it to the end of the station's street.
 */
public final class Railways {

    public static final int STEP = Highways.STEP;

    /** The eight compass directions, index k at 45k degrees from +x toward +z. */
    static final double[][] DIRS = new double[8][];
    static {
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(45 * k);
            double x = Math.round(Math.cos(a) * 1e9) / 1e9, z = Math.round(Math.sin(a) * 1e9) / 1e9;
            DIRS[k] = new double[] { x, z };
        }
    }

    /** Blocks between the two tracks' centres (Create's track is 2.75 wide; four apart keeps them from touching). */
    public static final int TRACK_SPACING = 4;
    /** Over a highway, the rail's deck stands at least this far above the road's (the road keeps 6 of headroom). */
    static final int OVER_HIGHWAY = 7;
    /** Under a highway, the rail's deck stands at least this far below the road's (its headroom, a roof, the deck). */
    static final int UNDER_HIGHWAY = 9;
    /** The most of a line, in blocks, that may run within a highway's width (a crossing at a slant is about this). */
    static final int ALONG_HIGHWAY = 40;
    /** How much longer than the shortest route that fits a line's route may be (then the cheapest of those wins). */
    static final double LENGTH_BUDGET = 1.25;
    /** Water wider than this along a line is a long crossing: the deck is kept down near the clearance over it. */
    static final int LONG_WATER = 192;
    /** A short tunnel becomes a cut only up to this much deeper than {@code tunnel}. */
    static final int DEEP_CUT_EXTRA = 6;

    // --- a station's shape ------------------------------------------------------------------------------------------

    /** Between the centres of two neighbouring pairs of tracks: a pair, an island platform 5 wide, the next pair. */
    public static final int PAIR_SPACING = 12;
    /** A side platform's width (an island's is {@code PAIR_SPACING - 7}). */
    public static final int PLATFORM_WIDTH = 4;
    /** From a track's centre to its platform's edge (MTR: about 1.5; Create's train is 2.75 wide). */
    public static final double PLATFORM_EDGE = 1.5;
    /**
     * The fan: a level bed past each end of the platforms that tapers from the station's width to the trunk's, at least
     * this long (longer for more pairs): where a player lays the ladder of switches from every platform to the trunk.
     */
    public static final int FAN_MIN = 64;
    /** From the fan's end to the first line's junction on the trunk. */
    public static final int JUNCTION_LEAD = 16;
    /** Between one line's junction on the trunk and the next. */
    public static final int JUNCTION_SPACING = 48;
    /** The trunk past the last junction (the straightest line carries on along it). */
    public static final int TRUNK_TAIL = 32;
    /** A line's straight run off its junction before its first bend begins (besides the curve's own tangent). */
    static final int STRAIGHT_LEAD = 16;
    /** The most pairs of tracks (platforms for that many cities) a station has. */
    static final int MAX_PAIRS = 4;
    /** A through station's building: its length along the tracks and its depth. */
    public static final int BUILDING_LENGTH = 24, BUILDING_DEPTH = 10;
    /** A terminus's concourse, across the platforms' city end: its depth. */
    public static final int CONCOURSE = 12;
    /** The paved forecourt in front of the building or concourse: its depth. */
    public static final int FORECOURT = 8;
    /** Where the footbridge crosses, along the platforms from the station's middle (3 wide). */
    public static final int FOOTBRIDGE = 6;
    /** How far the road to a station runs straight into its forecourt. */
    static final int APPROACH = 8;
    /** Half the paving of the road from a station to its city's street (a kerb beyond each side). */
    public static final int ACCESS_HALF = 2;
    /** The least gap between a city's ground and its station's. */
    static final int STATION_GAP = 16;
    /** How far a highway's run out of its city is kept from a station: its straight run, and as far again. */
    static final int HIGHWAY_RUN_KEPT = Highways.RUN_OUT * 2;
    /** Where a station looks for a way out of the highways: slid along its axis (through) or sideways (terminus). */
    private static final int[] SHIFTS = { 0, 48, -48, 96, -96, 144, -144 };

    /**
     * One railway line: the centreline from one station's junction to the other's (as a {@link Highways.Link}, so the
     * highways' drawing helpers read it), the corners of its route (block coordinates), and each straight run's compass
     * direction (index into {@link #DIRS}), for checking and for the map. {@code stationEndA}/{@code stationStartB} are
     * its first and last samples (the stations are drawn as stations, not as part of a line).
     */
    public record Line(Highways.Link link, int stationEndA, int stationStartB, List<double[]> corners, int[] headings) {
        /** Whether a sample is in a station: never, now that stations are their own (kept for the line's drawing). */
        public boolean inStation(int sample) {
            return false;
        }

        public boolean touches(int x0, int z0, int x1, int z1) {
            return link.touches(x0, z0, x1, z1);
        }
    }

    /** Where a line leaves a station: the end (+1 the end the station's direction points to, -1 the other) and pair. */
    public record Attach(int end, int slot) {
    }

    /**
     * A city's station. Local frame: {@code a} along the tracks (+ toward {@link #dir}), {@code c} across them, the city
     * on the negative side for a through station; for a terminus the city lies toward negative {@code a} (its buffer
     * end). {@code (cx, cz)}: the middle of the platforms, on a block's centre. {@code attach}: each partner city's line,
     * by {@link #key}. {@code entry}: the middle of the forecourt's city side, where the road arrives; {@code street}
     * and {@code road}: the city's street to it and the road from that street's end (either null where the city has no
     * street out that way).
     */
    public record Hub(CitySites.Site site, int dir, int side, double cx, double cz, int level, boolean terminus, int pairs,
            int length, int fan, int trunkPlus, int trunkMinus, Map<Long, Attach> attach, double entryX, double entryZ,
            Street street, Highways.Link road, int minX, int minZ, int maxX, int maxZ) {
        public double ux() {
            return DIRS[dir][0];
        }

        public double uz() {
            return DIRS[dir][1];
        }

        public double nx() {
            return -uz() * side;
        }

        public double nz() {
            return ux() * side;
        }

        public double along(double x, double z) {
            return (x - cx) * ux() + (z - cz) * uz();
        }

        public double across(double x, double z) {
            return (x - cx) * nx() + (z - cz) * nz();
        }

        public double x(double a, double c) {
            return cx + ux() * a + nx() * c;
        }

        public double z(double a, double c) {
            return cz + uz() * a + nz() * c;
        }

        /** The centre line of pair {@code j}, across the station. */
        public double pairCentre(int j) {
            return (j - (pairs - 1) / 2.0) * PAIR_SPACING;
        }

        /** From the station's centre line to its platforms' outer edge. */
        public double halfWidth() {
            return pairCentre(pairs - 1) + TRACK_SPACING / 2.0 + PLATFORM_EDGE + PLATFORM_WIDTH;
        }

        public double platformEnd() {
            return length / 2.0;
        }

        /** Where the fan meets the trunk, along the tracks from the station's middle (both ends alike). */
        public double fanEnd() {
            return length / 2.0 + fan;
        }

        /** How far the trunk runs past the fan at an end (+1 or -1): 0 where no line leaves that end. */
        public int trunk(int end) {
            return end > 0 ? trunkPlus : trunkMinus;
        }

        /** The far end of the trunk at an end, along the tracks from the station's middle. */
        public double trunkEnd(int end) {
            return fanEnd() + trunk(end);
        }

        /** Where a line's junction is on its end's trunk, along the tracks from the station's middle (unsigned). */
        public double junction(Attach at) {
            return fanEnd() + JUNCTION_LEAD + at.slot() * JUNCTION_SPACING;
        }

        /** The direction a player walks in along the road into the forecourt: from the city toward the station. */
        public double[] entryDir() {
            return terminus ? new double[] { ux(), uz() } : new double[] { nx(), nz() };
        }

        /** Where a line leaves: its junction, on the trunk's centre line. */
        public double[] exit(Attach at) {
            double a = at.end() * junction(at);
            return new double[] { x(a, 0), z(a, 0) };
        }

        /** How much trunk lies past a line's junction (the line shares it, level, until it bends clear). */
        public double trunkAfter(Attach at) {
            return trunkEnd(at.end()) - junction(at);
        }

        public int exitDir(Attach at) {
            return at.end() > 0 ? dir : (dir + 4) % 8;
        }

        public boolean touches(int x0, int z0, int x1, int z1) {
            return x1 >= minX && x0 <= maxX && z1 >= minZ && z0 <= maxZ;
        }
    }

    /** A city's key, as {@link Hub#attach} holds it. */
    public static long key(CitySites.Site site) {
        return ((long) site.centreX() << 32) ^ (site.centreZ() & 0xffffffffL);
    }

    private final CitySites sites;
    private final Highways highways;
    private final CityWorldSettingsData.Railways settings;
    private final int seaLevel;
    private final ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<java.util.Optional<Line>>> lines =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.Optional<Hub>> hubs = new ConcurrentHashMap<>();

    public Railways(CitySites sites, Highways highways, CityWorldSettingsData.Railways settings) {
        this.sites = sites;
        this.highways = highways;
        this.settings = settings;
        this.seaLevel = sites.seaLevel();
    }

    public CityWorldSettingsData.Railways settings() {
        return settings;
    }

    /** Half the trackbed's width: the bed is {@code 2 * bedHalf() + 1} blocks wide. */
    public int bedHalf() {
        return settings.tracks() == 2 ? TRACK_SPACING / 2 + 2 : 2;
    }

    /** The distance from the centreline to each track's centre (0 for a single track). */
    public double trackOffset() {
        return settings.tracks() == 2 ? TRACK_SPACING / 2.0 : 0;
    }

    // --- which lines and stations -------------------------------------------------------------------------------------

    /** How far a line's or station's box may reach past its cities' centres. */
    private int pad(CitySites.Site here, CitySites.Site there) {
        return Math.max(here.radius(), there.radius()) + settings.stationLength() + 2 * (FAN_MIN + 200) + 900;
    }

    /** Every line whose box reaches into this square of blocks (planned the first time: seconds, cold). */
    public List<Line> linesTouching(int x0, int z0, int x1, int z1) {
        List<Line> found = new ArrayList<>();
        int cell = sites.cell(), reach = highways.reach();
        int cx0 = Math.floorDiv(x0, cell) - reach - 1, cx1 = Math.floorDiv(x1, cell) + reach + 1;
        int cz0 = Math.floorDiv(z0, cell) - reach - 1, cz1 = Math.floorDiv(z1, cell) + reach;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                CitySites.Site here = highways.site(cx, cz);
                if (here == null)
                    continue;
                for (int dir = 0; dir < 2; dir++) {
                    int[] p = highways.partner(cx, cz, dir);
                    if (p == null)
                        continue;
                    CitySites.Site there = highways.site(p[0], p[1]);
                    int pad = pad(here, there);
                    int bx0 = Math.min(here.centreX(), there.centreX()) - pad, bx1 = Math.max(here.centreX(), there.centreX()) + pad;
                    int bz0 = Math.min(here.centreZ(), there.centreZ()) - pad, bz1 = Math.max(here.centreZ(), there.centreZ()) + pad;
                    if (x1 < bx0 || x0 > bx1 || z1 < bz0 || z0 > bz1)
                        continue;
                    Line line = line(cx, cz, dir, here, there);
                    if (line != null && line.touches(x0, z0, x1, z1))
                        found.add(line);
                }
            }
        return found;
    }

    /** The lines leaving or reaching the city in this cell (for the navigator and the survey). */
    public List<Line> linesOfCells(int cx0, int cz0, int cx1, int cz1) {
        List<Line> found = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                CitySites.Site here = highways.site(cx, cz);
                if (here == null)
                    continue;
                for (int dir = 0; dir < 2; dir++) {
                    int[] p = highways.partner(cx, cz, dir);
                    if (p == null)
                        continue;
                    Line line = line(cx, cz, dir, here, highways.site(p[0], p[1]));
                    if (line != null)
                        found.add(line);
                }
            }
        return found;
    }

    /** The stations of the cities in these cells (for the navigator and the survey). */
    public List<Hub> hubsOfCells(int cx0, int cz0, int cx1, int cz1) {
        List<Hub> found = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                CitySites.Site here = highways.site(cx, cz);
                Hub hub = here == null ? null : hubOf(here);
                if (hub != null)
                    found.add(hub);
            }
        return found;
    }

    /** Every station whose box (its yard, building, forecourt, road and their slopes) reaches into this square. */
    public List<Hub> hubsTouching(int x0, int z0, int x1, int z1) {
        List<Hub> found = new ArrayList<>();
        int cell = sites.cell();
        for (int cx = Math.floorDiv(x0, cell) - 1; cx <= Math.floorDiv(x1, cell) + 1; cx++)
            for (int cz = Math.floorDiv(z0, cell) - 1; cz <= Math.floorDiv(z1, cell) + 1; cz++) {
                CitySites.Site here = highways.site(cx, cz);
                Hub hub = here == null ? null : hubOf(here);
                if (hub != null && hub.touches(x0, z0, x1, z1))
                    found.add(hub);
            }
        return found;
    }

    private Line line(int cx, int cz, int dir, CitySites.Site here, CitySites.Site there) {
        long key = ((long) cx << 33) ^ ((long) cz << 1) ^ dir;
        var mine = new java.util.concurrent.CompletableFuture<java.util.Optional<Line>>();
        var known = lines.putIfAbsent(key, mine);
        if (known != null)
            return Waiting.join(known).orElse(null);
        try {
            Line planned = plan(here, there);
            mine.complete(java.util.Optional.ofNullable(planned));
            return planned;
        } catch (RuntimeException e) {
            me.daddychurchill.CityWorld.CityWorldMod.LOGGER.warn("CityWorld: railway {},{} dir {} FAILED", cx, cz, dir, e);
            lines.remove(key, mine);
            mine.complete(java.util.Optional.empty());
            return null;
        }
    }

    /** Whether a line or a station (with the slopes beside them) comes within {@code margin} blocks of this square. */
    public boolean crosses(int x0, int z0, int x1, int z1, int margin) {
        int reach = bedHalf() + Highways.SIDE + 1 + margin;
        for (Line line : linesTouching(x0 - reach, z0 - reach, x1 + reach, z1 + reach)) {
            double[] xs = line.link().x(), zs = line.link().z();
            for (int i = 0; i < xs.length; i++)
                if (xs[i] >= x0 - reach && xs[i] <= x1 + 1 + reach && zs[i] >= z0 - reach && zs[i] <= z1 + 1 + reach)
                    return true;
        }
        return !hubsTouching(x0 - margin, z0 - margin, x1 + margin, z1 + margin).isEmpty();
    }

    // --- stations -------------------------------------------------------------------------------------------------------

    static int nearestDir(double vx, double vz) {
        return Math.floorMod((int) Math.round(Math.atan2(vz, vx) / (Math.PI / 4)), 8);
    }

    /** The city's station, or null when it has no lines; worked out once, from the sites alone. */
    public Hub hubOf(CitySites.Site site) {
        long key = key(site);
        var known = hubs.get(key);
        if (known != null)
            return known.orElse(null);
        var made = java.util.Optional.ofNullable(hub(site));
        var raced = hubs.putIfAbsent(key, made);
        return (raced != null ? raced : made).orElse(null);
    }

    private static final double THROUGH_REACH = Math.cos(Math.toRadians(67.5));

    /**
     * A city's station: its kind and axis from the bearings to the cities its lines run to, then a place outside the
     * city's ground and clear of every highway's run out of it, then each line's end and pair, then the street and road.
     */
    private Hub hub(CitySites.Site site) {
        int cell = sites.cell();
        List<CitySites.Site> partners = highways.linkedTo(Math.floorDiv(site.centreX(), cell), Math.floorDiv(site.centreZ(), cell));
        if (partners.isEmpty())
            return null;
        int n = partners.size();
        double[][] bearing = new double[n][];
        for (int i = 0; i < n; i++) {
            double bx = partners.get(i).centreX() - site.centreX(), bz = partners.get(i).centreZ() - site.centreZ();
            double l = Math.max(1, Math.hypot(bx, bz));
            bearing[i] = new double[] { bx / l, bz / l };
        }
        // through on the axis whose ends both have a line within 67.5 degrees, and that bends the lines least
        int dir = -1;
        double bestCost = Double.MAX_VALUE;
        for (int d : new int[] { 0, 2 }) {
            boolean plus = false, minus = false;
            double cost = 0;
            for (double[] b : bearing) {
                double dot = b[0] * DIRS[d][0] + b[1] * DIRS[d][1];
                plus |= dot >= THROUGH_REACH;
                minus |= -dot >= THROUGH_REACH;
                cost += Math.acos(Math.min(1, Math.abs(dot)));
            }
            if (plus && minus && cost < bestCost) {
                bestCost = cost;
                dir = d;
            }
        }
        boolean terminus = dir < 0;
        if (terminus) {
            double mx = 0, mz = 0;
            for (double[] b : bearing) {
                mx += b[0];
                mz += b[1];
            }
            if (Math.hypot(mx, mz) < 1e-6) {
                mx = bearing[0][0];
                mz = bearing[0][1];
            }
            dir = Math.floorMod((int) Math.round(Math.atan2(mz, mx) / (Math.PI / 2)) * 2, 8);
        }
        double ux = DIRS[dir][0], uz = DIRS[dir][1];
        // which end each line leaves from
        int[] end = new int[n];
        for (int i = 0; i < n; i++)
            end[i] = terminus || bearing[i][0] * ux + bearing[i][1] * uz >= 0 ? 1 : -1;
        // each end's lines branch off its trunk one after another: the sharpest turn nearest the station, the
        // straightest last (it carries on where the trunk ends); ties by the city's key, so it is the same every time
        Map<Long, Attach> attach = new java.util.HashMap<>();
        int[] count = new int[2];
        for (int e : new int[] { 1, -1 }) {
            List<Integer> here = new ArrayList<>();
            for (int i = 0; i < n; i++)
                if (end[i] == e)
                    here.add(i);
            final int fe = e;
            here.sort(java.util.Comparator.<Integer>comparingDouble(i -> -Math.acos(Math.max(-1, Math.min(1,
                    fe * (bearing[i][0] * ux + bearing[i][1] * uz))))).thenComparingLong(i -> key(partners.get(i))));
            for (int j = 0; j < here.size(); j++)
                attach.put(key(partners.get(here.get(j))), new Attach(e, j));
            count[e > 0 ? 0 : 1] = here.size();
        }
        // a platform for every city a line runs to; the fan from the platforms down to the trunk, longer for more of them
        int pairs = Math.max(1, Math.min(MAX_PAIRS, n));
        int fan = (int) Math.ceil(Math.max(FAN_MIN, PAIR_SPACING * (pairs - 1) + 16) / 16.0) * 16;
        int trunkPlus = count[0] == 0 ? 0 : JUNCTION_LEAD + (count[0] - 1) * JUNCTION_SPACING + TRUNK_TAIL;
        int trunkMinus = count[1] == 0 ? 0 : JUNCTION_LEAD + (count[1] - 1) * JUNCTION_SPACING + TRUNK_TAIL;
        // even, so both ends of the platforms (and every junction) fall on block centres
        int length = Math.max(2, settings.stationLength() & ~1);
        double hw = (pairs - 1) / 2.0 * PAIR_SPACING + TRACK_SPACING / 2.0 + PLATFORM_EDGE + PLATFORM_WIDTH;
        double pe = length / 2.0;
        double reachHi = pe + fan + trunkPlus, reachLo = terminus ? pe + CONCOURSE + FORECOURT : pe + fan + trunkMinus;
        // the side away from the city (through): where the lines lean, else the other
        double lean = 0;
        for (double[] b : bearing)
            lean += -b[0] * uz + b[1] * ux;
        int prefer = lean >= 0 ? 1 : -1;
        List<double[]> gates = highways.gatesOf(site);
        double[] place = null;
        int side = prefer;
        double[] first = null;
        int firstSide = prefer;
        for (int s : terminus ? new int[] { 1 } : new int[] { prefer, -prefer }) {
            for (int shift : SHIFTS) {
                double[] p = terminus ? placeTerminus(site, dir, shift, hw, reachLo, reachHi)
                        : placeThrough(site, dir, s, shift, hw, reachLo, reachHi);
                if (p == null)
                    continue;
                if (first == null) {
                    first = p;
                    firstSide = s;
                }
                if (clearOfHighways(p[0], p[1], dir, s, terminus, hw, reachLo, reachHi, gates)) {
                    place = p;
                    side = s;
                    break;
                }
            }
            if (place != null)
                break;
        }
        if (place == null) {
            if (first == null)
                return null;
            place = first;
            side = firstSide;
        }
        double cx = Math.floor(place[0]) + 0.5, cz = Math.floor(place[1]) + 0.5;
        double nx = -uz * side, nz = ux * side;
        // the forecourt's entry, the street to it and the road from the street's end
        double ea, ec;
        if (terminus) {
            ea = -pe - CONCOURSE - FORECOURT;
            ec = 0;
        } else {
            ea = 0;
            ec = -hw - BUILDING_DEPTH - FORECOURT;
        }
        double ex = cx + ux * ea + nx * ec, ez = cz + uz * ea + nz * ec;
        double vx = terminus ? ux : nx, vz = terminus ? uz : nz;
        Street street = street(site, ex, ez, vx, vz);
        Highways.Link road = street == null ? null : accessRoad(site, street, ex, ez, vx, vz, site.level());
        // the box: the yard, fans, trunks, building, forecourt and road, with room for the slopes beside them
        int reach = settings.fillMax() + Highways.SIDE + 4;
        double cLo = terminus ? -hw - 2 : -hw - BUILDING_DEPTH - FORECOURT, cHi = hw + 2;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double a : new double[] { -reachLo, reachHi })
            for (double c : new double[] { cLo, cHi }) {
                double x = cx + ux * a + nx * c, z = cz + uz * a + nz * c;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        if (road != null) {
            minX = Math.min(minX, road.minX());
            minZ = Math.min(minZ, road.minZ());
            maxX = Math.max(maxX, road.maxX());
            maxZ = Math.max(maxZ, road.maxZ());
        }
        return new Hub(site, dir, side, cx, cz, site.level(), terminus, pairs, length, fan, trunkPlus, trunkMinus,
                java.util.Map.copyOf(attach), ex, ez, street, road, (int) Math.floor(minX) - reach,
                (int) Math.floor(minZ) - reach, (int) Math.ceil(maxX) + reach, (int) Math.ceil(maxZ) + reach);
    }

    /**
     * A through station beside its city: slid {@code shift} along its axis, out across it (on {@code side}) to the first
     * place its whole footprint, forecourt and approach included, is clear of city ground by {@link #STATION_GAP}.
     */
    private double[] placeThrough(CitySites.Site site, int dir, int side, int shift, double hw, double reachLo,
            double reachHi) {
        double ux = DIRS[dir][0], uz = DIRS[dir][1], nx = -uz * side, nz = ux * side;
        double aLo = -reachLo, aHi = reachHi;
        double back = hw + BUILDING_DEPTH + FORECOURT + APPROACH + STATION_GAP;
        for (double d = back; d < back + 800; d += 8) {
            double ox = site.centreX() + ux * shift + nx * d, oz = site.centreZ() + uz * shift + nz * d;
            if (clearOfCities(ox, oz, ux, uz, nx, nz, aLo - STATION_GAP, aHi + STATION_GAP, -back, hw + STATION_GAP))
                return new double[] { ox, oz };
        }
        return null;
    }

    /** A terminus out from its city: slid {@code shift} sideways, out along its axis to the first clear place. */
    private double[] placeTerminus(CitySites.Site site, int dir, int shift, double hw, double reachLo, double reachHi) {
        double ux = DIRS[dir][0], uz = DIRS[dir][1], nx = -uz, nz = ux;
        double back = reachLo + APPROACH + STATION_GAP, aHi = reachHi;
        for (double d = back; d < back + 800; d += 8) {
            double ox = site.centreX() + nx * shift + ux * d, oz = site.centreZ() + nz * shift + uz * d;
            if (clearOfCities(ox, oz, ux, uz, nx, nz, -back, aHi + STATION_GAP, -hw - STATION_GAP, hw + STATION_GAP))
                return new double[] { ox, oz };
        }
        return null;
    }

    /** Whether a rectangle in a station's frame holds no chunk of any city's ground (sampled every 8 blocks). */
    private boolean clearOfCities(double ox, double oz, double ux, double uz, double nx, double nz, double aLo, double aHi,
            double cLo, double cHi) {
        for (double a = aLo; a <= aHi + 0.01; a += Math.min(8, Math.max(1, aHi - a)))
            for (double c = cLo; c <= cHi + 0.01; c += Math.min(8, Math.max(1, cHi - c))) {
                int x = (int) Math.floor(ox + ux * a + nx * c), z = (int) Math.floor(oz + uz * a + nz * c);
                if (sites.isCityChunk(x >> 4, z >> 4))
                    return false;
                if (c >= cHi)
                    break;
            }
        return true;
    }

    /** Whether a station's footprint keeps clear of every highway's straight run out of its city. */
    private boolean clearOfHighways(double ox, double oz, int dir, int side, boolean terminus, double hw, double reachLo,
            double reachHi, List<double[]> gates) {
        double ux = DIRS[dir][0], uz = DIRS[dir][1], nx = -uz * side, nz = ux * side;
        double keep = highways.halfWidth() + 2 + 8;
        double aLo = -reachLo - (terminus ? APPROACH : 0) - keep;
        double aHi = reachHi + keep;
        double cLo = (terminus ? -hw : -hw - BUILDING_DEPTH - FORECOURT - APPROACH) - keep, cHi = hw + keep;
        for (double[] g : gates)
            for (int t = 0; t <= HIGHWAY_RUN_KEPT; t += 4) {
                double px = g[0] + g[2] * t - ox, pz = g[1] + g[3] * t - oz;
                double a = px * ux + pz * uz, c = px * nx + pz * nz;
                if (a >= aLo && a <= aHi && c >= cLo && c <= cHi)
                    return false;
            }
        return true;
    }

    // --- a station's street and road -------------------------------------------------------------------------------

    /**
     * A station's street through its city: the road line (chunk row {@code row} if it runs along x, column if not)
     * nearest the station's entry, kept off the lines the city's highways leave by, paved from the city's centre line
     * out to its gate ({@code gx, gz}: the outer edge of the last chunk of city ground on it) whatever the district would
     * have put there, as a highway's street is ({@link Highways#exitRoadAt}). From the sites alone: the city's planner
     * asks, and must never wait on a line being planned.
     */
    public record Street(boolean alongX, int sign, int row, int start, double gx, double gz) {
        int gateChunk() {
            return Math.floorDiv((int) Math.floor((alongX ? gx : gz) - sign * 8), 16);
        }
    }

    /**
     * The street that brings the shortest road to the entry ({@code px, pz}, walked into along {@code (vx, vz)}): along
     * the axis of that direction, on the road line nearest the entry or the next either side, never one a highway leaves
     * by on that side, and only one whose run from the centre line is city ground all the way. Null if none.
     */
    private Street street(CitySites.Site site, double px, double pz, double vx, double vz) {
        Street best = null, fallback = null;
        double bestCost = Double.MAX_VALUE;
        for (int axis = 0; axis < 2; axis++) {
            boolean alongX = axis == 0;
            double part = alongX ? vx : vz;
            if (Math.abs(part) < 1e-6)
                continue;
            int sign = part > 0 ? 1 : -1;
            int line = Highways.nearestRoadLine(Math.floorDiv((int) Math.floor(alongX ? pz : px), 16));
            java.util.Set<Integer> taken = highways.gateRows(site, alongX, sign);
            for (int row : new int[] { line, line - 5, line + 5 }) {
                Street st = gateOn(site, alongX, sign, row);
                if (st == null)
                    continue;
                if (fallback == null)
                    fallback = st;
                if (taken.contains(row))
                    continue;
                double out = alongX ? (px - st.gx) * sign : (pz - st.gz) * sign;
                if (out < 0)
                    continue;
                double cost = out + Math.abs(alongX ? pz - st.gz : px - st.gx);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = st;
                }
            }
        }
        return best != null ? best : fallback;
    }

    /**
     * A street out along a road line from the city's centre line to its edge; null if it starts off city ground, or if
     * the first chunk that is not city ground is only a gap with more city past it.
     */
    private Street gateOn(CitySites.Site site, boolean alongX, int sign, int row) {
        int start = Math.floorDiv(alongX ? site.centreX() : site.centreZ(), 16);
        if (!(alongX ? sites.isCityChunk(start, row) : sites.isCityChunk(row, start)))
            return null;
        int c = start;
        for (int i = 0; i < 64; i++) {
            boolean city = alongX ? sites.isCityChunk(c, row) : sites.isCityChunk(row, c);
            if (!city && i > 0)
                break;
            c += sign;
        }
        // not a gap in the city (a lake, a river) with more city beyond: the road would run on through the town
        for (int k = 1; k <= 3; k++)
            if (alongX ? sites.isCityChunk(c + sign * k, row) : sites.isCityChunk(row, c + sign * k))
                return null;
        double edge = sign > 0 ? c * 16.0 : (c + 1) * 16.0;
        double rowCentre = row * 16.0 + 8.0;
        return alongX ? new Street(true, sign, row, start, edge, rowCentre) : new Street(false, sign, row, start, rowCentre, edge);
    }

    /** The station street of the city whose ground this chunk is, or null. */
    private Street streetOf(int chunkX, int chunkZ) {
        CitySites.Site site = sites.influencing(chunkX, chunkZ);
        if (site == null || !sites.isCityChunk(chunkX, chunkZ))
            return null;
        Hub hub = hubOf(site);
        return hub == null ? null : hub.street();
    }

    /** Whether a station's street runs through this chunk of city ground ({@link Street}): the planner paves it. */
    public boolean streetAt(int chunkX, int chunkZ) {
        Street st = streetOf(chunkX, chunkZ);
        if (st == null)
            return false;
        int along = st.alongX ? chunkX : chunkZ, across = st.alongX ? chunkZ : chunkX, gate = st.gateChunk();
        return across == st.row && along >= Math.min(st.start, gate) && along <= Math.max(st.start, gate);
    }

    /**
     * The sides of a chunk a station's street leaves its city from ({@link Highways#EXIT_NORTH} and the rest): the
     * street runs on to that edge, where the road to the station takes it on.
     */
    public int exitsAt(int chunkX, int chunkZ) {
        Street st = streetOf(chunkX, chunkZ);
        if (st == null)
            return 0;
        int along = st.alongX ? chunkX : chunkZ, across = st.alongX ? chunkZ : chunkX;
        if (across != st.row || along != st.gateChunk())
            return 0;
        return st.alongX ? (st.sign > 0 ? Highways.EXIT_EAST : Highways.EXIT_WEST)
                : (st.sign > 0 ? Highways.EXIT_SOUTH : Highways.EXIT_NORTH);
    }

    /**
     * The road from the end of a station's street to its forecourt: out from the street's gate along the street, across
     * to the entry's line, and {@link #APPROACH} blocks straight into the middle of the forecourt's city side. Level at
     * the city's level; null when the entry is no way out from the gate.
     */
    private Highways.Link accessRoad(CitySites.Site site, Street st, double px, double pz, double vx, double vz, int level) {
        double ax = st.alongX ? st.sign : 0, az = st.alongX ? 0 : st.sign;
        if ((px - st.gx) * ax + (pz - st.gz) * az < 0)
            return null;
        double qx = px - vx * APPROACH, qz = pz - vz * APPROACH;
        double t = Math.max(8, (qx - st.gx) * ax + (qz - st.gz) * az);
        double cx = st.gx + ax * t, cz = st.gz + az * t;
        List<double[]> pts = new ArrayList<>();
        addRun(pts, st.gx, st.gz, cx, cz);
        addRun(pts, cx, cz, qx, qz);
        addRun(pts, qx, qz, px, pz);
        pts.add(new double[] { px, pz });
        int n = pts.size();
        double[] xs = new double[n], zs = new double[n], hs = new double[n];
        int[] ys = new int[n];
        Highways.Kind[] kinds = new Highways.Kind[n];
        int reach = ACCESS_HALF + Highways.SIDE + settings.fillMax() + 4;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
            ys[i] = level;
            hs[i] = level;
            kinds[i] = Highways.Kind.GROUND;
            minX = Math.min(minX, (int) Math.floor(xs[i]) - reach);
            maxX = Math.max(maxX, (int) Math.ceil(xs[i]) + reach);
            minZ = Math.min(minZ, (int) Math.floor(zs[i]) - reach);
            maxZ = Math.max(maxZ, (int) Math.ceil(zs[i]) + reach);
        }
        return new Highways.Link(site, site, xs, zs, ys, hs, kinds, minX, minZ, maxX, maxZ, 0);
    }

    /** The points of a straight run every {@link #STEP} blocks, from its start up to (not including) its end. */
    private static void addRun(List<double[]> pts, double x0, double z0, double x1, double z1) {
        double l = Math.hypot(x1 - x0, z1 - z0);
        int steps = (int) Math.floor(l / STEP);
        for (int i = 0; i < steps; i++) {
            double f = i * STEP / Math.max(l, 1e-9);
            pts.add(new double[] { x0 + (x1 - x0) * f, z0 + (z1 - z0) * f });
        }
    }

    // --- lines ----------------------------------------------------------------------------------------------------------

    /**
     * A line between two cities' stations: from its junction on station A's trunk, along the trunk until it bends
     * clear, along the cheapest of the route shapes, to its junction on station B's. Every run is a whole number of blocks (a diagonal a
     * whole number of diagonal steps) and the route lands exactly on B's pair, so every corner is a block's centre.
     */
    private Line plan(CitySites.Site a, CitySites.Site b) {
        long started = System.nanoTime();
        Hub ha = hubOf(a), hb = hubOf(b);
        if (ha == null || hb == null)
            return null;
        Attach xa = ha.attach().get(key(b)), xb = hb.attach().get(key(a));
        if (xa == null || xb == null)
            return null;
        // from one station's junction to the other's (a line keeps its junction even if it fails: Codex, so no other
        // line's place moves with it)
        double[] ea = ha.exit(xa), eb = hb.exit(xb);
        Shared shared = new Shared(ha.exitDir(xa), ha.trunkAfter(xa), hb.exitDir(xb), hb.trunkAfter(xb));
        Candidate best = best(ea, ha.exitDir(xa), eb, (hb.exitDir(xb) + 4) % 8, ha.level(), hb.level(), shared);
        if (best == null)
            return null;
        Candidate exact = candidate(ea, best.shape, ha.level(), hb.level(), false, shared);
        return exact == null ? null : exact.finish(a, b, started);
    }

    /**
     * The stretch of each station's trunk a line runs on before it bends clear: the trunk's heading at each end and how
     * much of it lies past the line's junction. Held level with the station, as the trunk is.
     */
    record Shared(int dirA, double trunkA, int dirB, double trunkB) {
    }

    /** The cheapest route shape between two pairs' ends, by the rough ground; null if none fits. */
    private Candidate best(double[] ea, int dirA, double[] eb, int arrive, int levelA, int levelB, Shared shared) {
        double dx = Math.round(eb[0] - ea[0]), dz = Math.round(eb[1] - ea[1]);
        // the shortest route that fits sets a budget: among those within LENGTH_BUDGET of it, the cheapest to build (the
        // owner: a line went the long way round a bay the highway crossed straight; Codex: by cost alone, 1,000 blocks of
        // bridge lost to 1,500 of flat land)
        List<Candidate> fits = new ArrayList<>();
        double shortest = Double.MAX_VALUE;
        for (List<Seg> shape : shapes(ea, dirA, eb, arrive)) {
            List<Seg> grid = exact(shape, dx, dz);
            if (grid == null)
                continue;
            Candidate c = candidate(ea, grid, levelA, levelB, true, shared);
            if (c == null)
                continue;
            fits.add(c);
            shortest = Math.min(shortest, c.x.length);
        }
        Candidate best = null;
        for (Candidate c : fits)
            if (c.x.length <= shortest * LENGTH_BUDGET && (best == null || c.cost < best.cost))
                best = c;
        return best;
    }


    /**
     * The runs of a solved shape made exact on the grid: each fixed run rounded up to whole units (a block, or a
     * diagonal step), then the free runs solved again for what is left, which between two block centres is whole units
     * too (of two neighbouring compass directions one is an axis and one a diagonal). Null if a free run would be too
     * short, or the ends cannot be met exactly.
     */
    private List<Seg> exact(List<Seg> shape, double dx, double dz) {
        int n = shape.size();
        double t = tangent();
        long[] units = new long[n];
        double[] min = new double[n];
        long rx = Math.round(dx), rz = Math.round(dz);
        Map<Integer, List<Integer>> free = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Seg s = shape.get(i);
            min[i] = i == 0 || i == n - 1 ? t + STRAIGHT_LEAD : 2 * t + 2;
            if (s.free) {
                free.computeIfAbsent(s.dir, k -> new ArrayList<>()).add(i);
                continue;
            }
            units[i] = (long) Math.ceil(s.length / unit(s.dir) - 1e-6);
            rx -= stepX(s.dir) * units[i];
            rz -= stepZ(s.dir) * units[i];
        }
        long[] total = new long[8];
        var dirs = new ArrayList<>(free.keySet());
        if (dirs.size() == 1) {
            int d = dirs.get(0);
            long sx = stepX(d), sz = stepZ(d);
            // (rx, rz) = k (sx, sz) for a whole k
            long k = sx != 0 ? rx / sx : rz / sz;
            if (k * sx != rx || k * sz != rz)
                return null;
            total[d] = k;
        } else if (dirs.size() == 2) {
            int u = dirs.get(0), v = dirs.get(1);
            long ux = stepX(u), uz = stepZ(u), vx = stepX(v), vz = stepZ(v);
            long det = ux * vz - uz * vx;
            if (det == 0)
                return null;
            long ku = rx * vz - rz * vx, kv = ux * rz - uz * rx;
            if (ku % det != 0 || kv % det != 0)
                return null;
            total[u] = ku / det;
            total[v] = kv / det;
        } else
            return null;
        for (int d : dirs) {
            if (total[d] < 0)
                return null;
            List<Integer> runs = free.get(d);
            long base = total[d] / runs.size(), extra = total[d] % runs.size();
            for (int k = 0; k < runs.size(); k++)
                units[runs.get(k)] = base + (k < extra ? 1 : 0);
        }
        List<Seg> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double len = units[i] * unit(shape.get(i).dir);
            if (len < min[i] - 1e-6)
                return null;
            out.add(new Seg(shape.get(i).dir, len, shape.get(i).free));
        }
        return out;
    }

    /** A compass direction's whole step on the grid: one block along an axis, or one diagonal block (√2). */
    private static double unit(int dir) {
        return dir % 2 == 0 ? 1 : Math.sqrt(2);
    }

    private static long stepX(int dir) {
        return Math.round(Math.signum(DIRS[dir][0]));
    }

    private static long stepZ(int dir) {
        return Math.round(Math.signum(DIRS[dir][1]));
    }

    /** One straight run of a route: its compass direction, its length, and whether the solver set it. */
    private record Seg(int dir, double length, boolean free) {
    }

    /** Two stations' pairs on one compass line (to this much): joined straight, no bends. */
    static final double STRAIGHT_SNAP = 0.5;

    private double tangent() {
        return settings.curveRadius() * Math.tan(Math.toRadians(22.5));
    }

    /**
     * The shapes worth trying between the stations' outer ends ({@code from}, leaving in {@code dirA}; {@code to},
     * arriving in {@code dirB}): the two compass directions either side of the bearing between the ends (p, q), the
     * long run first, last, or split round the short one, joined to the stations by 45° steps. Every run between two
     * bends is at least two curve tangents long (a run at either end, one), and the free runs are solved so the route
     * lands exactly on {@code to}.
     */
    private List<List<Seg>> shapes(double[] from, int dirA, double[] to, int dirB) {
        double dx = to[0] - from[0], dz = to[1] - from[1];
        double bearing = Math.atan2(dz, dx);
        int p = Math.floorMod((int) Math.floor(bearing / (Math.PI / 4)), 8), q = (p + 1) % 8;
        List<List<Seg>> out = new ArrayList<>();
        // both stations' pairs on one compass line: straight through
        if (dirA == dirB) {
            double along = dx * DIRS[dirA][0] + dz * DIRS[dirA][1];
            double aside = Math.abs(-dx * DIRS[dirA][1] + dz * DIRS[dirA][0]);
            if (along > 0 && aside <= STRAIGHT_SNAP)
                out.add(List.of(new Seg(dirA, along, true)));
        }
        // the two directions either side of the bearing, and the stations' own: a line may run on out of its station
        // (past its city) before it turns, or turn early and come straight into the other
        List<int[]> orders = new ArrayList<>(List.of(new int[] { p, q }, new int[] { q, p }, new int[] { p, q, p },
                new int[] { q, p, q }));
        int[] own = { dirA, dirB };
        for (int u : own)
            for (int v : new int[] { p, q, dirA, dirB }) {
                if (u == v || Math.floorMod(u - v, 8) == 4)
                    continue;
                orders.add(new int[] { u, v });
                orders.add(new int[] { v, u });
            }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] order : orders) {
            if (!seen.add(java.util.Arrays.toString(order)))
                continue;
            List<Seg> segs = new ArrayList<>();
            segs.add(new Seg(dirA, 0, false));
            for (int d : order)
                segs.add(new Seg(d, 0, true));
            segs.add(new Seg(dirB, 0, false));
            List<Seg> solved = solve(steps(segs), dx, dz);
            if (solved != null)
                out.add(solved);
        }
        // a small step sideways between two stations facing each other along one line: out, a diagonal one way, along,
        // the other diagonal back (fixed at its least), on (the lone 45-degree pair could not make so small a step)
        if (dirA == dirB)
            for (int turn : new int[] { 1, -1 }) {
                int d1 = Math.floorMod(dirA + turn, 8), d2 = Math.floorMod(dirA - turn, 8);
                List<Seg> segs = List.of(new Seg(dirA, 0, true), new Seg(d1, 0, true), new Seg(dirA, 0, true),
                        new Seg(d2, 0, false), new Seg(dirA, 0, true));
                List<Seg> solved = solve(segs, dx, dz);
                if (solved != null)
                    out.add(solved);
            }
        return out;
    }

    /** Puts 45° steps between runs that differ by more, and merges runs that do not differ. */
    private static List<Seg> steps(List<Seg> segs) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : segs) {
            if (!out.isEmpty()) {
                Seg last = out.get(out.size() - 1);
                int turn = Math.floorMod(s.dir - last.dir, 8);
                if (turn == 0) {
                    out.set(out.size() - 1, new Seg(last.dir, 0, last.free || s.free));
                    continue;
                }
                if (turn == 4)
                    return List.of(); // a reversal: no shape
                int step = turn < 4 ? 1 : -1;
                for (int d = Math.floorMod(last.dir + step, 8); d != s.dir; d = Math.floorMod(d + step, 8))
                    out.add(new Seg(d, 0, false));
            }
            out.add(s);
        }
        return out;
    }

    /**
     * The runs' lengths: each fixed run its least (two tangents between bends, one at either end), and the free runs of
     * the two free directions solved so the route ends at {@code (dx, dz)}; a direction's length is shared by its free
     * runs. Null if that needs a negative or too-short run.
     */
    private List<Seg> solve(List<Seg> segs, double dx, double dz) {
        if (segs.isEmpty())
            return null;
        double t = tangent();
        int n = segs.size();
        double[] min = new double[n];
        for (int i = 0; i < n; i++)
            min[i] = i == 0 || i == n - 1 ? t + STRAIGHT_LEAD : 2 * t + 2;
        java.util.LinkedHashMap<Integer, List<Integer>> freeByDir = new java.util.LinkedHashMap<>();
        double rx = dx, rz = dz;
        for (int i = 0; i < n; i++)
            if (segs.get(i).free)
                freeByDir.computeIfAbsent(segs.get(i).dir, k -> new ArrayList<>()).add(i);
            else {
                rx -= DIRS[segs.get(i).dir][0] * min[i];
                rz -= DIRS[segs.get(i).dir][1] * min[i];
            }
        if (freeByDir.size() != 2)
            return null;
        var dirs = new ArrayList<>(freeByDir.keySet());
        double[] u = DIRS[dirs.get(0)], v = DIRS[dirs.get(1)];
        double det = u[0] * v[1] - u[1] * v[0];
        if (Math.abs(det) < 1e-9)
            return null;
        double lu = (rx * v[1] - rz * v[0]) / det, lv = (u[0] * rz - u[1] * rx) / det;
        List<Seg> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Seg s = segs.get(i);
            double length = min[i];
            if (s.free) {
                List<Integer> same = freeByDir.get(s.dir);
                length = (s.dir == dirs.get(0) ? lu : lv) / same.size();
                if (length < min[i])
                    return null;
            }
            out.add(new Seg(s.dir, length, s.free));
        }
        return out;
    }

    /** A shape made real: its centreline, its profile and what it costs. */
    private final class Candidate {
        List<Seg> shape;
        double[] x, z, ground;
        boolean[] wet;
        double[] deck;
        int stationEndA, stationStartB;
        List<double[]> corners = new ArrayList<>();
        double cost;

        Line finish(CitySites.Site a, CitySites.Site b, long started) {
            int n = x.length;
            int[] ys = new int[n];
            for (int i = 0; i < n; i++)
                ys[i] = (int) Math.round(deck[i]);
            Highways.Kind[] kind = kinds(ys);
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int reach = bedHalf() + Highways.SIDE + 6;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, (int) Math.floor(x[i]) - reach);
                maxX = Math.max(maxX, (int) Math.ceil(x[i]) + reach);
                minZ = Math.min(minZ, (int) Math.floor(z[i]) - reach);
                maxZ = Math.max(maxZ, (int) Math.ceil(z[i]) + reach);
            }
            int[] headings = new int[shape.size()];
            for (int i = 0; i < headings.length; i++)
                headings[i] = shape.get(i).dir;
            var link = new Highways.Link(a, b, x, z, ys, deck, kind, minX, minZ, maxX, maxZ,
                    (System.nanoTime() - started) / 1_000_000);
            return new Line(link, stationEndA, stationStartB, corners, headings);
        }

        Highways.Kind[] kinds(int[] ys) {
            int n = ys.length;
            boolean[] over = new boolean[n], under = new boolean[n];
            for (int i = 0; i < n; i++) {
                over[i] = wet[i] || ys[i] - ground[i] > settings.fillMax();
                under[i] = !wet[i] && ground[i] - ys[i] > settings.tunnel();
            }
            Highways.Kind[] kind = new Highways.Kind[n];
            for (int i = 0; i < n; i++) {
                boolean bridge = false;
                for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3) && !bridge; j++)
                    bridge = over[j];
                kind[i] = bridge ? Highways.Kind.BRIDGE : under[i] ? Highways.Kind.TUNNEL : Highways.Kind.GROUND;
            }
            for (int i = 0; i < n;) {
                if (kind[i] != Highways.Kind.TUNNEL) {
                    i++;
                    continue;
                }
                int j = i, deepest = 0;
                while (j < n && kind[j] == Highways.Kind.TUNNEL) {
                    deepest = Math.max(deepest, (int) Math.round(ground[j] - ys[j]));
                    j++;
                }
                if ((j - i) * STEP < 24 && deepest <= settings.tunnel() + DEEP_CUT_EXTRA)
                    for (int t = i; t < j; t++)
                        kind[t] = Highways.Kind.GROUND;
                i = j;
            }
            return kind;
        }
    }

    /**
     * The centreline of a shape from station A's junction (the runs, an arc at every bend) to station B's, its ground, its
     * profile (level with each station at its end) and its cost; null where it runs over any city's ground.
     * {@code rough}: the cheap ground estimate, for choosing.
     */
    private Candidate candidate(double[] from, List<Seg> shape, int levelA, int levelB, boolean rough, Shared shared) {
        double r = settings.curveRadius(), t = tangent();
        List<double[]> dense = new ArrayList<>();
        Candidate c = new Candidate();
        c.shape = shape;
        // the corners from whole steps (a diagonal's √2 summed in floating point drifts off the block centres)
        double[] corner = from.clone();
        List<double[]> corners = new ArrayList<>();
        for (Seg s : shape) {
            long k = Math.round(s.length / unit(s.dir));
            corner = new double[] { corner[0] + stepX(s.dir) * k, corner[1] + stepZ(s.dir) * k };
            corners.add(corner);
        }
        double[] cursor = from.clone();
        for (int i = 0; i < shape.size(); i++) {
            Seg s = shape.get(i);
            double[] end = corners.get(i);
            boolean bendAfter = i < shape.size() - 1;
            double[] runTo = bendAfter ? new double[] { end[0] - DIRS[s.dir][0] * t, end[1] - DIRS[s.dir][1] * t } : end;
            line(dense, cursor, runTo);
            if (bendAfter) {
                int next = shape.get(i + 1).dir;
                int turn = Math.floorMod(next - s.dir, 8) == 1 ? 1 : -1; // +1: toward +45 degrees
                // the arc's centre: r to the turning side of the run's end
                double[] side = turn > 0 ? new double[] { -DIRS[s.dir][1], DIRS[s.dir][0] } : new double[] { DIRS[s.dir][1], -DIRS[s.dir][0] };
                double cx = runTo[0] + side[0] * r, cz = runTo[1] + side[1] * r;
                double start = Math.atan2(runTo[1] - cz, runTo[0] - cx);
                int steps = (int) Math.ceil(r * Math.PI / 4); // about a block apart
                for (int k = 0; k < steps; k++) {
                    double ang = start + turn * (Math.PI / 4) * k / steps;
                    dense.add(new double[] { cx + Math.cos(ang) * r, cz + Math.sin(ang) * r });
                }
                cursor = new double[] { end[0] + DIRS[next][0] * t, end[1] + DIRS[next][1] * t };
            } else
                cursor = end;
        }
        dense.add(corner.clone());
        c.corners = corners;
        double[][] samples = Highways.resample(dense, STEP);
        int n = samples.length;
        if (n < 2)
            return null;
        c.x = new double[n];
        c.z = new double[n];
        for (int i = 0; i < n; i++) {
            c.x[i] = samples[i][0];
            c.z[i] = samples[i][1];
        }
        // the resampling keeps its last sample when the end is under a quarter-block past it: the end is the pair's
        // exact centre, so it is the last sample
        c.x[n - 1] = corner[0];
        c.z[n - 1] = corner[1];
        // level while on a station's trunk: until the line is clear of the trunk's bed sideways, or past its end
        c.stationEndA = onTrunk(c, 0, 1, shared.dirA(), shared.trunkA());
        c.stationStartB = onTrunk(c, n - 1, -1, (shared.dirB() + 4) % 8, shared.trunkB());
        if (c.stationStartB <= c.stationEndA)
            return null;
        // no city's ground anywhere on it (the stations stand clear of their own)
        for (int i = 0; i < n; i += 4) {
            int cx = (int) Math.floor(c.x[i]) >> 4, cz = (int) Math.floor(c.z[i]) >> 4;
            if (sites.isCityChunk(cx, cz))
                return null;
        }
        // and never along a highway: crossing one is a few samples inside its width, running beside it many
        List<Highways.Link> roads = highways.linksTouching((int) min(c.x) - 32, (int) min(c.z) - 32, (int) max(c.x) + 32,
                (int) max(c.z) + 32);
        if (!roads.isEmpty()) {
            double close = highways.halfWidth() + 1 + bedHalf();
            int run = 0;
            for (int i = 0; i < n; i++) {
                run = Highways.nearest(roads, c.x[i], c.z[i], close) != null ? run + 1 : 0;
                if (run * STEP > ALONG_HIGHWAY)
                    return null;
            }
        }
        // the ground: the rough estimate every sample (choosing), or exact every few and interpolated (the line kept)
        c.ground = new double[n];
        c.wet = new boolean[n];
        int every = rough ? 1 : 4, last = -1;
        for (int i = 0; i < n; i++) {
            if (i % every != 0 && i != n - 1)
                continue;
            int bx = (int) Math.floor(c.x[i]), bz = (int) Math.floor(c.z[i]);
            int top = rough ? highways.gridTop(bx, bz) : highways.shaped(bx, bz, sites.heights().top(bx, bz));
            c.wet[i] = top < seaLevel - 1 || sites.isRiverColumn(bx, bz);
            c.ground[i] = c.wet[i] ? seaLevel + settings.clearance() : top;
            if (last >= 0)
                for (int j = last + 1; j < i; j++) {
                    double f = (j - last) / (double) (i - last);
                    c.ground[j] = c.ground[last] + (c.ground[i] - c.ground[last]) * f;
                    c.wet[j] = c.wet[last] && c.wet[i];
                }
            last = i;
        }
        c.deck = profile(c, levelA, levelB, rough);
        // what it costs: bridge, tunnel and earthwork, and a little per bend (a straighter line is a better railway)
        int[] ys = new int[n];
        for (int i = 0; i < n; i++)
            ys[i] = (int) Math.round(c.deck[i]);
        Highways.Kind[] kind = c.kinds(ys);
        double cost = shape.size() * 40;
        for (int i = 0; i < n; i++)
            // length counts first (the owner: the railway went the long way round a bay the highway crossed straight);
            // a bridge or tunnel block a little dearer than one on the ground, earthwork by its height
            cost += kind[i] == Highways.Kind.BRIDGE ? 1.6 : kind[i] == Highways.Kind.TUNNEL ? 2.2 : Math.abs(c.deck[i] - c.ground[i]) * 0.5 + 1;
        c.cost = cost;
        return c;
    }

    /** Clear of a trunk's bed sideways: its half-width and a line's, and a block between. */
    private double trunkClear() {
        return 2 * bedHalf() + 2;
    }

    /**
     * The last sample (walking from {@code from} by {@code step}) still on the station's trunk: within the trunk's
     * length past the junction and not yet clear of its bed sideways. {@code dir} is the direction along the trunk,
     * away from the station, as the line leaves.
     */
    private int onTrunk(Candidate c, int from, int step, int dir, double trunk) {
        double ux = DIRS[dir][0], uz = DIRS[dir][1];
        int last = from;
        for (int i = from + step; i >= 0 && i < c.x.length; i += step) {
            double dx = c.x[i] - c.x[from], dz = c.z[i] - c.z[from];
            double along = dx * ux + dz * uz, aside = Math.abs(-dx * uz + dz * ux);
            if (along > trunk || aside >= trunkClear())
                break;
            last = i;
        }
        return last;
    }

    private static void line(List<double[]> out, double[] a, double[] b) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1])));
        for (int s = 0; s < steps; s++) {
            double t = s / (double) steps;
            out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t });
        }
    }

    /**
     * The deck: level through each station (at its city's level), a taut string within {@code smoothing} of the ground
     * between, at least {@code clearance} over water, held to {@code grade} outward from the stations, and over or under
     * each highway it crosses.
     *
     * <p><b>Over or under.</b> Each crossing is decided against the line's natural height there (the profile with no
     * highways): over (the rail's deck {@link #OVER_HIGHWAY} above the road's) or under ({@link #UNDER_HIGHWAY} below:
     * the road's bank or bridge spans the rail), whichever moves the line less, and never one that cannot ramp to it at
     * the grade before a station. Raising every crossing (the first try) put 300 blocks of viaduct either side of each
     * and, by a station, a cliff. The chosen heights are applied last as ramps at the grade, so the grade step cannot
     * pull them back.
     */
    private double[] profile(Candidate c, int levelA, int levelB, boolean rough) {
        int n = c.x.length;
        double per = settings.grade() * STEP;
        double[] natural = solve(c, levelA, levelB, null, null);
        if (rough)
            return natural;
        List<Highways.Link> roads = highways.linksTouching((int) min(c.x) - 32, (int) min(c.z) - 32, (int) max(c.x) + 32,
                (int) max(c.z) + 32);
        if (roads.isEmpty())
            return natural;
        double crossReach = highways.halfWidth() + 2 + bedHalf();
        double[] road = new double[n];
        java.util.Arrays.fill(road, Double.NaN);
        for (int i = c.stationEndA + 1; i < c.stationStartB; i++) {
            var near = Highways.nearest(roads, c.x[i], c.z[i], crossReach);
            if (near != null && near.kind() != Highways.Kind.TUNNEL)
                road[i] = near.deck();
        }
        double[] over = new double[n], under = new double[n];
        java.util.Arrays.fill(over, Double.NEGATIVE_INFINITY);
        java.util.Arrays.fill(under, Double.POSITIVE_INFINITY);
        boolean any = false;
        for (int i = c.stationEndA + 1; i < c.stationStartB;) {
            if (Double.isNaN(road[i])) {
                i++;
                continue;
            }
            int j = i;
            double top = -Double.MAX_VALUE, bottom = Double.MAX_VALUE, nat = 0;
            while (j < c.stationStartB && !Double.isNaN(road[j])) {
                top = Math.max(top, road[j]);
                bottom = Math.min(bottom, road[j]);
                nat += natural[j];
                j++;
            }
            nat /= (j - i);
            double needOver = Math.max(0, top + OVER_HIGHWAY - nat), needUnder = Math.max(0, nat - (bottom - UNDER_HIGHWAY));
            // a height the line can reach at the grade from both stations' levels
            double fromA = (i - c.stationEndA) * per, fromB = (c.stationStartB - j) * per;
            double overAt = top + OVER_HIGHWAY, underAt = bottom - UNDER_HIGHWAY;
            boolean canOver = Math.abs(overAt - levelA) <= fromA && Math.abs(overAt - levelB) <= fromB;
            boolean canUnder = Math.abs(underAt - levelA) <= fromA && Math.abs(underAt - levelB) <= fromB;
            boolean goOver = canOver && (!canUnder || needOver <= needUnder);
            // neither in reach (a crossing just outside a station): a level crossing, the rail at the road's height,
            // where a player lays the track across the road
            double levelAt = (top + bottom) / 2;
            boolean canLevel = Math.abs(levelAt - levelA) <= fromA && Math.abs(levelAt - levelB) <= fromB;
            if (canOver || canUnder || canLevel) {
                any = true;
                for (int t = i; t < j; t++)
                    if (canOver && goOver)
                        over[t] = overAt;
                    else if (canUnder)
                        under[t] = underAt;
                    else
                        over[t] = under[t] = levelAt;
            }
            i = j;
        }
        if (!any)
            return natural;
        double[] deck = solve(c, levelA, levelB, over, under);
        // the crossings last, as ramps at the grade both ways (raised over, lowered under)
        double rampUp = Double.NEGATIVE_INFINITY, rampDown = Double.POSITIVE_INFINITY;
        for (int i = c.stationEndA + 1; i < c.stationStartB; i++) {
            double g = gradeStep(c, i);
            rampUp = Math.max(rampUp - g, over[i]);
            rampDown = Math.min(rampDown + g, under[i]);
            deck[i] = Math.min(Math.max(deck[i], rampUp), rampDown);
        }
        rampUp = Double.NEGATIVE_INFINITY;
        rampDown = Double.POSITIVE_INFINITY;
        for (int i = c.stationStartB - 1; i > c.stationEndA; i--) {
            double g = gradeStep(c, i + 1);
            rampUp = Math.max(rampUp - g, over[i]);
            rampDown = Math.min(rampDown + g, under[i]);
            deck[i] = Math.min(Math.max(deck[i], rampUp), rampDown);
        }
        return deck;
    }

    /** The taut, graded deck with these least heights ({@code over}) and greatest ({@code under}), either null. */
    private double[] solve(Candidate c, int levelA, int levelB, double[] over, double[] under) {
        int n = c.x.length;
        double band = settings.smoothing();
        double[] base = box(c.ground, 4);
        double[] lo = new double[n], hi = new double[n];
        double floor = seaLevel + settings.clearance();
        // a short stretch of water is spanned from bank to bank at whatever height the banks give; a long one (a bay,
        // the sea) keeps the deck down at the clearance, or the string ran straight from one high shore to the other
        // thirty blocks over the water (the owner's first ride)
        boolean[] longWet = new boolean[n];
        for (int i = 0; i < n;) {
            if (!c.wet[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && c.wet[j])
                j++;
            if ((j - i) * STEP > LONG_WATER)
                for (int t = i; t < j; t++)
                    longWet[t] = true;
            i = j;
        }
        for (int i = 0; i < n; i++) {
            lo[i] = base[i] - band;
            hi[i] = base[i] + band;
            if (c.wet[i]) {
                lo[i] = Math.max(lo[i], floor);
                hi[i] = longWet[i] ? Math.max(lo[i], floor + band) : Double.MAX_VALUE;
            }
            if (over != null && over[i] > lo[i]) {
                lo[i] = over[i];
                hi[i] = Math.max(hi[i], lo[i]);
            }
            if (under != null && under[i] < hi[i]) {
                hi[i] = under[i];
                lo[i] = Math.min(lo[i], hi[i]);
            }
            if (i <= c.stationEndA) {
                lo[i] = hi[i] = levelA;
            } else if (i >= c.stationStartB) {
                lo[i] = hi[i] = levelB;
            }
        }
        double[] deck = new double[n];
        for (int i = 0; i < n; i++)
            deck[i] = Math.min(hi[i], Math.max(lo[i], base[i]));
        for (int half = 128; half >= 1; half /= 2)
            for (int pass = 0; pass < 10; pass++) {
                double[] s = box(deck, half);
                for (int i = 0; i < n; i++)
                    deck[i] = Math.min(hi[i], Math.max(lo[i], s[i]));
            }
        deck = box(deck, 6);
        // the stations level again, and the grade held outward from them both ways
        for (int i = 0; i <= c.stationEndA; i++)
            deck[i] = levelA;
        for (int i = c.stationStartB; i < n; i++)
            deck[i] = levelB;
        // by each segment's own length: the last one, onto the station's pair, is shorter than a step
        for (int i = c.stationEndA + 1; i < c.stationStartB; i++) {
            double g = gradeStep(c, i);
            deck[i] = Math.max(deck[i - 1] - g, Math.min(deck[i - 1] + g, deck[i]));
        }
        for (int i = c.stationStartB - 1; i > c.stationEndA; i--) {
            double g = gradeStep(c, i + 1);
            deck[i] = Math.max(deck[i + 1] - g, Math.min(deck[i + 1] + g, deck[i]));
        }
        // and never under the clearance over water: the grade passes may have pulled a deck down off a lower bank;
        // raised there, ramping back at the grade both ways
        // (but no higher than the grade can climb from both stations' levels: a sample by a station that the water
        // would lift past that keeps the station's reach, or the step lands where the line leaves the trunk)
        double[] reach = new double[n];
        double up = levelA;
        for (int i = 0; i < n; i++) {
            if (i > c.stationEndA)
                up += gradeStep(c, i);
            reach[i] = up;
        }
        up = levelB;
        for (int i = n - 1; i >= 0; i--) {
            if (i < c.stationStartB)
                up += gradeStep(c, i + 1);
            reach[i] = Math.min(reach[i], up);
        }
        double rise = Double.NEGATIVE_INFINITY;
        for (int i = c.stationEndA + 1; i < c.stationStartB; i++) {
            rise = Math.max(rise - gradeStep(c, i), c.wet[i] ? Math.min(floor, reach[i]) : Double.NEGATIVE_INFINITY);
            deck[i] = Math.max(deck[i], Math.min(rise, reach[i]));
        }
        rise = Double.NEGATIVE_INFINITY;
        for (int i = c.stationStartB - 1; i > c.stationEndA; i--) {
            rise = Math.max(rise - gradeStep(c, i + 1), c.wet[i] ? Math.min(floor, reach[i]) : Double.NEGATIVE_INFINITY);
            deck[i] = Math.max(deck[i], Math.min(rise, reach[i]));
        }
        return deck;
    }

    /** The most the deck may change between samples {@code i - 1} and {@code i}: the grade over their distance. */
    private double gradeStep(Candidate c, int i) {
        return settings.grade() * Math.hypot(c.x[i] - c.x[i - 1], c.z[i] - c.z[i - 1]);
    }

    private static double min(double[] v) {
        double m = Double.MAX_VALUE;
        for (double d : v)
            m = Math.min(m, d);
        return m;
    }

    private static double max(double[] v) {
        double m = -Double.MAX_VALUE;
        for (double d : v)
            m = Math.max(m, d);
        return m;
    }

    private static double[] box(double[] v, int half) {
        int n = v.length;
        double[] pre = new double[n + 1];
        for (int i = 0; i < n; i++)
            pre[i + 1] = pre[i] + v[i];
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - half), hi = Math.min(n - 1, i + half);
            out[i] = (pre[hi + 1] - pre[lo]) / (hi - lo + 1);
        }
        return out;
    }
}
