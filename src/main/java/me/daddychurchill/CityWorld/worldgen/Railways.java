package me.daddychurchill.CityWorld.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Railways between the cities of a vanilla-land world (7.4): for every pair of cities a highway joins, a line on its own
 * route from a station just outside one city to a station just outside the other — EMPTY trackbed, for a player to lay
 * Create or MTR track on. A pure function of the seed, the {@link CitySites} and the highways' pairs, planned once per
 * line and drawn chunk by chunk, like {@link Highways}.
 *
 * <p><b>The track's geometry follows what both mods can lay</b> (Create 6, read from its source 2026-11-26:
 * {@code TrackShape}, {@code TrackPlacement}): straight runs only in the eight compass directions (the four axes and
 * the exact diagonals), every bend a 45° turn on an arc of {@code curveRadius} (a 90° change is two of them with a
 * straight between), so one Create curve spans each bend (its default {@code maxTrackPlacementLength} is 32 blocks:
 * a 45° turn of radius 32 is 25 across), and climbs no steeper than {@code grade}, level through the stations.
 *
 * <p><b>A line</b>: each station is a straight, level run of {@code stationLength} blocks pointing from its city toward
 * the other (the compass direction nearest the bearing between the centres), starting a little outside the city's
 * built ground and offset sideways from where the highway leaves. Between the two stations' outer ends the route is
 * one of a few shapes made of the two compass directions either side of the bearing (the long run first, last, or
 * split round the short one), each with as few 45° turns as it can; the one whose profile needs the least bridge,
 * tunnel and earthwork and that keeps clear of every other city is kept. The deck is a taut string within
 * {@code smoothing} of the ground (as the 7.4 highways), at least {@code clearance} over water, and at least a
 * bridge's height over any highway it crosses.
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
    /** How far outside the city's last built chunk a station starts. */
    static final int STATION_GAP = 24;
    /** How far sideways a station stands from the line out of its city's centre, away from the highway's gate. */
    static final int STATION_OFFSET = 56;
    /** Over a highway, the rail's deck stands at least this far above the road's (the road keeps 6 of headroom). */
    static final int OVER_HIGHWAY = 7;
    /** Under a highway, the rail's deck stands at least this far below the road's (its headroom, a roof, the deck). */
    static final int UNDER_HIGHWAY = 9;
    /** A short tunnel becomes a cut only up to this much deeper than {@code tunnel}. */
    static final int DEEP_CUT_EXTRA = 6;

    /**
     * One railway line: the centreline (as a {@link Highways.Link}, so the highways' drawing helpers read it), the
     * sample where station A ends and station B begins, the corners of its route (block coordinates), and each
     * straight run's compass direction (index into {@link #DIRS}), for checking and for the map.
     */
    public record Line(Highways.Link link, int stationEndA, int stationStartB, List<double[]> corners, int[] headings,
            List<Highways.Link> access) {
        public boolean inStation(int sample) {
            return sample <= stationEndA || sample >= stationStartB;
        }

        /** Whether the line, its stations' forecourts or the roads from them to their cities reach this square. */
        public boolean touches(int x0, int z0, int x1, int z1) {
            if (link.touches(x0, z0, x1, z1))
                return true;
            for (Highways.Link road : access)
                if (road.touches(x0, z0, x1, z1))
                    return true;
            return false;
        }

        /**
         * A station's city-side end (end 0 = A, 1 = B): {x, z, ux, uz, level}, (ux, uz) the unit direction into the
         * station, away from its city.
         */
        public double[] stationEnd(int end) {
            double[] xs = link.x(), zs = link.z();
            int i = end == 0 ? 0 : xs.length - 1, j = end == 0 ? 1 : xs.length - 2;
            double ux = xs[j] - xs[i], uz = zs[j] - zs[i], l = Math.hypot(ux, uz);
            return new double[] { xs[i], zs[i], ux / l, uz / l, link.h()[i] };
        }
    }

    /** How deep the paved forecourt between a station's city end and the road to its city is. */
    public static final int FORECOURT = 8;
    /** How far the road to a station runs straight into its yard. */
    static final int APPROACH = 8;
    /** Half the paving of the road from a station to its city's street (a kerb beyond each side). */
    public static final int ACCESS_HALF = 2;

    private final CitySites sites;
    private final Highways highways;
    private final CityWorldSettingsData.Railways settings;
    private final int seaLevel;
    private final ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<java.util.Optional<Line>>> lines =
            new ConcurrentHashMap<>();

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

    // --- which lines ----------------------------------------------------------------------------------------------

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
                    int pad = Math.max(here.radius(), there.radius()) + settings.stationLength() + STATION_OFFSET + 512;
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

    /** Whether a line's bed, kerbs and the slopes beside it come within {@code margin} blocks of this square. */
    public boolean crosses(int x0, int z0, int x1, int z1, int margin) {
        int reach = bedHalf() + Highways.SIDE + 1 + margin;
        for (Line line : linesTouching(x0 - reach, z0 - reach, x1 + reach, z1 + reach)) {
            double[] xs = line.link().x(), zs = line.link().z();
            for (int i = 0; i < xs.length; i++)
                if (xs[i] >= x0 - reach && xs[i] <= x1 + 1 + reach && zs[i] >= z0 - reach && zs[i] <= z1 + 1 + reach)
                    return true;
        }
        return false;
    }

    // --- stations and their cities --------------------------------------------------------------------------------

    /**
     * A station's street through its city: the road line (chunk row {@code row} if it runs along x, column if not)
     * nearest the station's city end, kept off the lines the city's highways leave by, paved from the city's centre
     * line out to its gate ({@code gx, gz}: the outer edge of the last chunk of city ground on it) whatever the district
     * would have put there, as a highway's street is ({@link Highways#exitRoadAt}). From the sites alone: the city's
     * planner asks, and must never wait on a line being planned.
     */
    record Street(boolean alongX, int sign, int row, int start, double gx, double gz) {
        int gateChunk() {
            return Math.floorDiv((int) Math.floor((alongX ? gx : gz) - sign * 8), 16);
        }
    }

    private final ConcurrentHashMap<List<Integer>, java.util.Optional<Street>> streets = new ConcurrentHashMap<>();

    /** The station's street, or null when no road line of the city reaches out toward the station. */
    Street streetOf(CitySites.Site site, CitySites.Site toward) {
        List<Integer> key = List.of(site.centreX(), site.centreZ(), toward.centreX(), toward.centreZ());
        var known = streets.get(key);
        if (known != null)
            return known.orElse(null);
        var made = java.util.Optional.ofNullable(street(site, toward));
        var raced = streets.putIfAbsent(key, made);
        return (raced != null ? raced : made).orElse(null);
    }

    /**
     * The street that brings the shortest road to the station's yard: along either axis the station's direction has a
     * part in (both for a diagonal station), on the road line nearest the yard or the next either side, never one a
     * highway leaves by on that side, and only one whose run from the centre line is city ground all the way.
     */
    private Street street(CitySites.Site site, CitySites.Site toward) {
        Station s = station(site, toward);
        double ux = DIRS[s.dir][0], uz = DIRS[s.dir][1];
        double px = s.x - ux * FORECOURT, pz = s.z - uz * FORECOURT;
        Street best = null, fallback = null;
        double bestCost = Double.MAX_VALUE;
        for (int axis = 0; axis < 2; axis++) {
            boolean alongX = axis == 0;
            double part = alongX ? ux : uz;
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
        if (best != null)
            return best;
        return fallback; // null: no road line of this city reaches out that way, and the station has no road
    }

    /** A street out along a road line from the city's centre line to its edge; null if it starts off city ground. */
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
        double edge = sign > 0 ? c * 16.0 : (c + 1) * 16.0;
        double rowCentre = row * 16.0 + 8.0;
        return alongX ? new Street(true, sign, row, start, edge, rowCentre) : new Street(false, sign, row, start, rowCentre, edge);
    }

    /** The streets of the city whose ground this chunk is, one toward each city it has a line to. */
    private List<Street> streetsOf(int chunkX, int chunkZ) {
        CitySites.Site site = sites.influencing(chunkX, chunkZ);
        if (site == null || !sites.isCityChunk(chunkX, chunkZ))
            return List.of();
        int cell = sites.cell();
        List<Street> found = new ArrayList<>();
        for (CitySites.Site there : highways.linkedTo(Math.floorDiv(site.centreX(), cell), Math.floorDiv(site.centreZ(), cell))) {
            Street st = streetOf(site, there);
            if (st != null)
                found.add(st);
        }
        return found;
    }

    /** Whether a station's street runs through this chunk of city ground ({@link Street}): the planner paves it. */
    public boolean streetAt(int chunkX, int chunkZ) {
        for (Street st : streetsOf(chunkX, chunkZ)) {
            int along = st.alongX ? chunkX : chunkZ, across = st.alongX ? chunkZ : chunkX, gate = st.gateChunk();
            if (across == st.row && along >= Math.min(st.start, gate) && along <= Math.max(st.start, gate))
                return true;
        }
        return false;
    }

    /**
     * The sides of a chunk a station's street leaves its city from ({@link Highways#EXIT_NORTH} and the rest): the
     * street runs on to that edge, where the road to the station takes it on.
     */
    public int exitsAt(int chunkX, int chunkZ) {
        int bits = 0;
        for (Street st : streetsOf(chunkX, chunkZ)) {
            int along = st.alongX ? chunkX : chunkZ, across = st.alongX ? chunkZ : chunkX;
            if (across == st.row && along == st.gateChunk())
                bits |= st.alongX ? (st.sign > 0 ? Highways.EXIT_EAST : Highways.EXIT_WEST)
                        : (st.sign > 0 ? Highways.EXIT_SOUTH : Highways.EXIT_NORTH);
        }
        return bits;
    }

    /**
     * The road from a station's forecourt to its city's street: out from the street's gate along the street, across
     * to the station's line, and {@link #APPROACH} blocks straight into the middle of the forecourt's city side. Level at the station's (the city's) level; null when the
     * forecourt is no way out from the gate (a city whose edge runs past its own station).
     */
    private Highways.Link accessRoad(CitySites.Site site, CitySites.Site toward, double sx, double sz, double ux, double uz,
            int level) {
        double l = Math.hypot(ux, uz);
        ux /= l;
        uz /= l;
        Street st = streetOf(site, toward);
        if (st == null)
            return null;
        double ax = st.alongX ? st.sign : 0, az = st.alongX ? 0 : st.sign;
        double px = sx - ux * FORECOURT, pz = sz - uz * FORECOURT;
        if ((px - st.gx) * ax + (pz - st.gz) * az < 0)
            return null;
        // the last stretch comes straight into the middle of the yard's city side, along the station
        double qx = px - ux * APPROACH, qz = pz - uz * APPROACH;
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
        int reach = ACCESS_HALF + Highways.SIDE + settings.fillMax() + 16;
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
        return new Highways.Link(site, toward, xs, zs, ys, hs, kinds, minX, minZ, maxX, maxZ, 0);
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

    // --- planning ---------------------------------------------------------------------------------------------------

    static int nearestDir(double vx, double vz) {
        return Math.floorMod((int) Math.round(Math.atan2(vz, vx) / (Math.PI / 4)), 8);
    }

    /** A station: where its city-side end is, the compass direction it points (away from its city), its level. */
    private record Station(double x, double z, int dir, int level) {
        /** The station's length on the grid: a diagonal one a whole number of diagonal steps. */
        double run(int length) {
            return dir % 2 == 0 ? length : Math.round(length / Math.sqrt(2)) * Math.sqrt(2);
        }

        double[] outer(int length) {
            double l = run(length);
            return new double[] { x + DIRS[dir][0] * l, z + DIRS[dir][1] * l };
        }
    }

    /**
     * The station of {@code site} toward {@code toward}: on the side the line leaves, offset {@link #STATION_OFFSET}
     * sideways from the city's centre line (first away from where the highway's gate would be: the side the bearing
     * leans to), starting {@link #STATION_GAP} past the last chunk of city ground along it. The first offset (56, then
     * 112, 168 blocks, each side) whose yard and platforms keep clear of every highway's run out of the city
     * ({@link #clearOfHighways}); from the sites alone, as the gates are.
     */
    private Station station(CitySites.Site site, CitySites.Site toward) {
        double bx = toward.centreX() - site.centreX(), bz = toward.centreZ() - site.centreZ();
        int dir = nearestDir(bx, bz);
        double ux = DIRS[dir][0], uz = DIRS[dir][1];
        double nx = -uz, nz = ux; // sideways (to the right of the direction of travel)
        // the highway's gate sits where the bearing leaves the disc, which is to the side the bearing leans from the
        // compass direction; the station goes to the other side
        double lean = (bx * nx + bz * nz) / Math.max(1, Math.hypot(bx, bz));
        int prefer = lean >= 0 ? -1 : 1;
        List<double[]> gates = highways.gatesOf(site);
        Station first = null;
        for (int k = 1; k <= 3; k++)
            for (int side : new int[] { prefer, -prefer }) {
                Station s = stationAt(site, dir, side * STATION_OFFSET * k);
                if (first == null)
                    first = s;
                if (clearOfHighways(s, gates))
                    return s;
            }
        return first;
    }

    /** The station offset {@code side} blocks to the right of the line out of the city's centre along {@code dir}. */
    private Station stationAt(CitySites.Site site, int dir, double side) {
        double ux = DIRS[dir][0], uz = DIRS[dir][1];
        double nx = -uz, nz = ux;
        double sx = site.centreX() + nx * side, sz = site.centreZ() + nz * side;
        // out along the direction to the first point well clear of city ground
        double along = 0;
        int clear = 0;
        for (int i = 0; i < 400 && clear < 2; i++) {
            along += 8;
            int px = (int) Math.floor(sx + ux * along), pz = (int) Math.floor(sz + uz * along);
            clear = sites.isCityChunk(px >> 4, pz >> 4) ? 0 : clear + 1;
        }
        along += STATION_GAP;
        return new Station(sx + ux * along, sz + uz * along, dir, site.level());
    }

    /** How far a highway's run out of its city is kept from a station: its straight run, and as far again. */
    static final int HIGHWAY_RUN_KEPT = Highways.RUN_OUT * 2;

    /**
     * Whether a station's yard, the road's last stretch into it and its platforms keep clear of every highway leaving
     * the city: each gate's straight run out ({@link #HIGHWAY_RUN_KEPT}), the road's half-width and kerbs, the
     * platforms and a margin apart.
     */
    private boolean clearOfHighways(Station s, List<double[]> gates) {
        double ux = DIRS[s.dir][0], uz = DIRS[s.dir][1];
        double l = s.run(settings.stationLength());
        double ax = s.x - ux * (FORECOURT + APPROACH), az = s.z - uz * (FORECOURT + APPROACH);
        double bx = s.x + ux * l, bz = s.z + uz * l;
        double keep = platformOuter() + highways.halfWidth() + 2 + 8;
        for (double[] g : gates) {
            double cx = g[0], cz = g[1], dx = g[0] + g[2] * HIGHWAY_RUN_KEPT, dz = g[1] + g[3] * HIGHWAY_RUN_KEPT;
            if (segmentDistance(ax, az, bx, bz, cx, cz, dx, dz) < keep)
                return false;
        }
        return true;
    }

    /** The outer edge of a station's platforms, from the line's centre. */
    public double platformOuter() {
        return (settings.tracks() == 2 ? trackOffset() + 1.5 : 1.5) + 4;
    }

    private static double segmentDistance(double ax, double az, double bx, double bz, double cx, double cz, double dx,
            double dz) {
        if (segmentsCross(ax, az, bx, bz, cx, cz, dx, dz))
            return 0;
        return Math.min(Math.min(pointSegment(ax, az, cx, cz, dx, dz), pointSegment(bx, bz, cx, cz, dx, dz)),
                Math.min(pointSegment(cx, cz, ax, az, bx, bz), pointSegment(dx, dz, ax, az, bx, bz)));
    }

    private static double pointSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az, len2 = vx * vx + vz * vz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
        return Math.hypot(ax + t * vx - px, az + t * vz - pz);
    }

    private static boolean segmentsCross(double ax, double az, double bx, double bz, double cx, double cz, double dx,
            double dz) {
        double d1 = cross(cx, cz, dx, dz, ax, az), d2 = cross(cx, cz, dx, dz, bx, bz);
        double d3 = cross(ax, az, bx, bz, cx, cz), d4 = cross(ax, az, bx, bz, dx, dz);
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0);
    }

    private static double cross(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    private Line plan(CitySites.Site a, CitySites.Site b) {
        long started = System.nanoTime();
        Station sa = snapped(station(a, b)), sb = snapped(station(b, a));
        int len = settings.stationLength();
        double[] ea = sa.outer(len), eb = sb.outer(len);
        int arrive = (sb.dir + 4) % 8; // travelling into station B
        Candidate best = null;
        Station bestB = null;
        for (List<Seg> shape : shapes(ea, sa.dir, eb, arrive)) {
            // on the block grid: each run a whole number of blocks (a diagonal a whole number of steps), so every
            // corner is a block's centre, as MTR lays its nodes; station B moves by what that rounding leaves (under
            // two blocks)
            List<Seg> grid = onGrid(shape);
            double ex = ea[0], ez = ea[1];
            for (Seg s : grid) {
                ex += DIRS[s.dir][0] * s.length;
                ez += DIRS[s.dir][1] * s.length;
            }
            Station shifted = new Station(sb.x + ex - eb[0], sb.z + ez - eb[1], sb.dir, sb.level);
            Candidate c = candidate(a, b, sa, shifted, grid, true);
            if (c != null && (best == null || c.cost < best.cost)) {
                best = c;
                bestB = shifted;
            }
        }
        if (best == null)
            return null;
        // the chosen shape again, with the exact ground
        Candidate exact = candidate(a, b, sa, bestB, best.shape, false);
        return exact == null ? null : exact.finish(a, b, started);
    }

    /** A station whose city-side end is a block's centre. */
    private static Station snapped(Station s) {
        return new Station(Math.floor(s.x) + 0.5, Math.floor(s.z) + 0.5, s.dir, s.level);
    }

    /** The runs rounded to the grid: an axis run to whole blocks, a diagonal to whole diagonal steps (√2 each). */
    private static List<Seg> onGrid(List<Seg> shape) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : shape) {
            double unit = s.dir % 2 == 0 ? 1 : Math.sqrt(2);
            out.add(new Seg(s.dir, Math.max(unit, Math.round(s.length / unit) * unit), s.free));
        }
        return out;
    }

    /** One straight run of a route: its compass direction, its length, and whether the solver set it. */
    private record Seg(int dir, double length, boolean free) {
    }

    /** How far sideways station B may move to put both stations on one straight line. */
    static final int STRAIGHT_SNAP = 32;

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
        // both stations on one compass line, or nearly: straight through, station B moved sideways onto it (a bend
        // pair could not make so small a step)
        if (dirA == dirB) {
            double along = dx * DIRS[dirA][0] + dz * DIRS[dirA][1];
            double aside = Math.abs(-dx * DIRS[dirA][1] + dz * DIRS[dirA][0]);
            if (along > 0 && aside <= STRAIGHT_SNAP)
                out.add(List.of(new Seg(dirA, along, true)));
        }
        int[][] orders = { { p, q }, { q, p }, { p, q, p }, { q, p, q } };
        for (int[] order : orders) {
            List<Seg> segs = new ArrayList<>();
            segs.add(new Seg(dirA, 0, false));
            for (int d : order)
                segs.add(new Seg(d, 0, true));
            segs.add(new Seg(dirB, 0, false));
            List<Seg> solved = solve(steps(segs), dx, dz);
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
            min[i] = (i == 0 || i == n - 1 ? 1 : 2) * t + (i == 0 || i == n - 1 ? 0 : 2);
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
            List<Highways.Link> access = new ArrayList<>(2);
            Highways.Link roadA = accessRoad(a, b, x[0], z[0], x[1] - x[0], z[1] - z[0], ys[0]);
            Highways.Link roadB = accessRoad(b, a, x[n - 1], z[n - 1], x[n - 2] - x[n - 1], z[n - 2] - z[n - 1], ys[n - 1]);
            if (roadA != null)
                access.add(roadA);
            if (roadB != null)
                access.add(roadB);
            return new Line(link, stationEndA, stationStartB, corners, headings, List.copyOf(access));
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
     * The centreline of a shape (station A, the runs with an arc at every bend, station B), its ground, its profile and
     * its cost; null where it runs through another city. {@code rough}: the cheap ground estimate, for choosing.
     */
    private Candidate candidate(CitySites.Site a, CitySites.Site b, Station sa, Station sb, List<Seg> shape, boolean rough) {
        int len = settings.stationLength();
        double r = settings.curveRadius(), t = tangent();
        List<double[]> dense = new ArrayList<>();
        Candidate c = new Candidate();
        c.shape = shape;
        // station A, from its city-side end out
        double[] at = { sa.x, sa.z };
        double[] ea = sa.outer(len);
        line(dense, at, ea);
        int stationEndDense = dense.size();
        // the runs, from corner to corner, each bend an arc tangent to both runs
        double[] corner = ea.clone();
        List<double[]> corners = new ArrayList<>();
        for (Seg s : shape) {
            corner = new double[] { corner[0] + DIRS[s.dir][0] * s.length, corner[1] + DIRS[s.dir][1] * s.length };
            corners.add(corner);
        }
        double[] cursor = ea.clone();
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
        int stationStartDense = dense.size();
        // station B, from its outer end in to its city-side end
        double[] eb = sb.outer(len);
        line(dense, eb, new double[] { sb.x, sb.z });
        dense.add(new double[] { sb.x, sb.z });
        c.corners = corners;
        double[][] samples = Highways.resample(dense, STEP);
        int n = samples.length;
        c.x = new double[n];
        c.z = new double[n];
        for (int i = 0; i < n; i++) {
            c.x[i] = samples[i][0];
            c.z[i] = samples[i][1];
        }
        // where the stations are in the resampled line: by distance along it
        c.stationEndA = (int) Math.round(sa.run(len) / STEP);
        c.stationStartB = n - 1 - (int) Math.round(sb.run(len) / STEP);
        // no other city's ground anywhere on it (its own two are left behind by the stations' gap)
        for (int i = 0; i < n; i += 4) {
            int cx = (int) Math.floor(c.x[i]) >> 4, cz = (int) Math.floor(c.z[i]) >> 4;
            if (sites.isCityChunk(cx, cz))
                return null;
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
        c.deck = profile(c, sa.level, sb.level, rough);
        // what it costs: bridge, tunnel and earthwork, and a little per bend (a straighter line is a better railway)
        int[] ys = new int[n];
        for (int i = 0; i < n; i++)
            ys[i] = (int) Math.round(c.deck[i]);
        Highways.Kind[] kind = c.kinds(ys);
        double cost = shape.size() * 40;
        for (int i = 0; i < n; i++)
            cost += kind[i] == Highways.Kind.BRIDGE ? 3 : kind[i] == Highways.Kind.TUNNEL ? 4 : Math.abs(c.deck[i] - c.ground[i]) * 0.5 + 1;
        c.cost = cost;
        return c;
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
            rampUp = Math.max(rampUp - per, over[i]);
            rampDown = Math.min(rampDown + per, under[i]);
            deck[i] = Math.min(Math.max(deck[i], rampUp), rampDown);
        }
        rampUp = Double.NEGATIVE_INFINITY;
        rampDown = Double.POSITIVE_INFINITY;
        for (int i = c.stationStartB - 1; i > c.stationEndA; i--) {
            rampUp = Math.max(rampUp - per, over[i]);
            rampDown = Math.min(rampDown + per, under[i]);
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
        for (int i = 0; i < n; i++) {
            lo[i] = base[i] - band;
            hi[i] = base[i] + band;
            if (c.wet[i]) {
                lo[i] = Math.max(lo[i], floor);
                hi[i] = Double.MAX_VALUE;
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
        double per = settings.grade() * STEP;
        for (int i = 0; i <= c.stationEndA; i++)
            deck[i] = levelA;
        for (int i = c.stationStartB; i < n; i++)
            deck[i] = levelB;
        for (int i = c.stationEndA + 1; i < c.stationStartB; i++)
            deck[i] = Math.max(deck[i - 1] - per, Math.min(deck[i - 1] + per, deck[i]));
        for (int i = c.stationStartB - 1; i > c.stationEndA; i--)
            deck[i] = Math.max(deck[i + 1] - per, Math.min(deck[i + 1] + per, deck[i]));
        return deck;
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
