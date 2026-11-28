package me.daddychurchill.CityWorld.Support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import me.daddychurchill.CityWorld.CityWorldGenerator;
import me.daddychurchill.CityWorld.Plats.PlatLot;
import me.daddychurchill.CityWorld.Plats.RoadLot;
import me.daddychurchill.CityWorld.Plats.Urban.MetroEntranceLot;
import me.daddychurchill.CityWorld.compat.BlockFace;
import me.daddychurchill.CityWorld.compat.Environment;
import me.daddychurchill.CityWorld.compat.Material;
import me.daddychurchill.CityWorld.worldgen.CitySites;

/**
 * The metro: a city's own underground railway, for MTR's trains (7.4, vanilla land). The subway is left as it is; the
 * metro runs well below it, on its own two levels, so the two never meet.
 * <p>
 * <b>Lines.</b> Each city on vanilla land big enough for one gets two straight lines through its centre, one east-west
 * and one north-south, inside its disc. The east-west line runs {@link #EW_DEPTH} below the street, the north-south one
 * {@link #NS_DEPTH}, so they cross without meeting. A line is a pure function of the city's site: nothing about it reads
 * a platmap, so any chunk can ask where it is.
 * <p>
 * <b>Along a line</b> (offsets in chunks from the city's centre chunk, + east or south): a terminal at the negative end,
 * the central station at 1..4 (both lines: the interchange is the chunk diagonal to both, 1,1), stations spread evenly
 * between, a terminal at the positive end, and past it the depot, where each track runs on four chunks as a siding. In a
 * smaller city the central station is the positive terminal.
 * <p>
 * <b>Across</b> (the chunk's other axis, 0..15): walls at 0 and 15, a track on a three-wide bed either side (centres
 * {@link #LOW_TRACK} and {@link #HIGH_TRACK}), and at a station an island platform between them, one block over the bed
 * (MTR's height). Over each station a concourse four wide runs its length; a stair at each end goes down to the island,
 * and passages off it lead to the street: up a stairwell in an entrance lot ({@link MetroEntranceLot}) beside the line,
 * planned with the platmap ({@link #placeEntrances}).
 * <p>
 * <b>Trains.</b> Where both crossovers fit ({@link Line#loops()}: one past the negative terminal, one before the
 * positive), a line runs as a loop: trains head out on the low track, cross over, reverse at the terminal and come
 * back on the high one, so many run at once. Otherwise each track is a shuttle of one train from its own depot siding,
 * calling at every station to the far terminal and back (see {@code integration/mtr/MtrStations}).
 */
public final class Metro {
    private Metro() {
    }

    /** Below the street: the east-west line's track floor, and the north-south one's. */
    public static final int EW_DEPTH = 48, NS_DEPTH = 64;
    /** Chunks: a station's length, the depot's, the least tunnel between two stations. */
    static final int STATION = 4, DEPOT = 4, GAP = 2;
    /** A crossover's length along (blocks; the tracks are 11 apart), and its speed (km/h). */
    static final int CROSSOVER = 28, CROSSOVER_SPEED = 40;
    /** The track centres, across the chunk. */
    public static final int LOW_TRACK = 2, HIGH_TRACK = 13;
    /** CitySites' disc wobble: a line stays this share inside the radius. */
    private static final double WOBBLE = 0.12;

    public static final Material SHELL = Material.SMOOTH_STONE, BED = Material.GRAY_CONCRETE,
            TILE = Material.WHITE_CONCRETE, PLATFORM = Material.POLISHED_ANDESITE, EDGE = Material.YELLOW_CONCRETE,
            LIGHT = Material.SEA_LANTERN, PILLAR = Material.STONE_BRICKS;
    /** Each line's colour: east-west red, north-south blue. */
    static final Material[] STRIPES = { Material.RED_CONCRETE, Material.BLUE_CONCRETE };

    // ---- the plan ------------------------------------------------------------------------------------------------

    /** A station: chunk offsets along its line, both inclusive. */
    public record Station(int from, int to) {
        public boolean holds(int a) {
            return a >= from && a <= to;
        }

        /** Along-block offsets of its platform's ends, from the line's centre chunk origin. */
        public int startBlock() {
            return from * 16;
        }

        public int endBlock() {
            return to * 16 + 15;
        }
    }

    /**
     * A line. {@code centreX, centreZ}: the city's centre chunk; {@code from..to}: the chunk offsets it covers along;
     * {@code depotFrom}: where its depot starts. The stations run from the negative terminal (first) to the positive
     * one (last); {@code centre} is the central station's index.
     */
    public record Line(CitySites.Site site, boolean ew, int centreX, int centreZ, int from, int to, List<Station> stations,
            int depotFrom, int centre) {

        public int chunkX(int a) {
            return ew ? centreX + a : centreX;
        }

        public int chunkZ(int a) {
            return ew ? centreZ : centreZ + a;
        }

        /** The offset of this chunk along the line, or {@link Integer#MIN_VALUE} when the line does not pass it. */
        public int along(int chunkX, int chunkZ) {
            if (ew ? chunkZ != centreZ : chunkX != centreX)
                return Integer.MIN_VALUE;
            int a = ew ? chunkX - centreX : chunkZ - centreZ;
            return a >= from && a <= to ? a : Integer.MIN_VALUE;
        }

        public Station stationAt(int a) {
            for (Station s : stations)
                if (s.holds(a))
                    return s;
            return null;
        }

        public Station positiveTerminal() {
            return stations.get(stations.size() - 1);
        }

        /** The track floor, in the planner's frame. */
        public int floor(CityWorldGenerator generator) {
            return generator.streetLevel - (ew ? EW_DEPTH : NS_DEPTH);
        }

        public Material stripe() {
            return STRIPES[ew ? 0 : 1];
        }

        public String name() {
            return ew ? "Red Line" : "Blue Line";
        }

        /** The line's colour for MTR. */
        public int colour() {
            return ew ? 0xE53935 : 0x1E88E5;
        }

        /** A stable id for this line (never 0). */
        public long id() {
            long h = 0x4D_45_54_52_4FL ^ ((long) site.centreX() << 32 ^ site.centreZ() & 0xffffffffL) * 0x9E3779B97F4A7C15L;
            h ^= ew ? 0x5A5A5A5AL : 0xA5A5A5A5L;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 31;
            return h == 0 ? 1 : h;
        }

        /** World block x and z of along-block {@code b} (from the centre chunk's origin) at across {@code c}. */
        public int blockX(int b, int c) {
            return ew ? centreX * 16 + b : centreX * 16 + c;
        }

        public int blockZ(int b, int c) {
            return ew ? centreZ * 16 + c : centreZ * 16 + b;
        }

        /** The along-block offsets of the track's nodes, first to last: the line's ends and every platform's. */
        public List<Integer> nodes() {
            java.util.TreeSet<Integer> must = new java.util.TreeSet<>();
            must.add(from * 16 + 1);
            must.add(to * 16 + 14);
            for (Station s : stations) {
                must.add(platformStart(s));
                must.add(s.endBlock());
            }
            must.add(sidingStart());
            if (loops()) {
                int[] n = crossNeg(), p = crossPos();
                must.add(n[0]);
                must.add(n[1]);
                must.add(p[0]);
                must.add(p[1]);
            }
            return new ArrayList<>(must);
        }

        /**
         * The crossover just past the negative terminal, along {near, far}: a train heading for the terminal on the low
         * track crosses to the high one here, so it reverses at the terminal's high platform and leaves on the high
         * track. Null where the tunnel to the next station is too short.
         */
        public int[] crossNeg() {
            if (stations.size() < 2)
                return null;
            int near = stations.get(0).endBlock() + 2, far = near + CROSSOVER;
            return far <= platformStart(stations.get(1)) - 2 ? new int[] { near, far } : null;
        }

        /**
         * The crossover just before the positive terminal, along {lo, hi}: a train heading for it on the high track
         * crosses to the low one, reverses at the terminal's low platform (beside the depot's siding) and leaves on the
         * low track. Null where the tunnel from the station before is too short.
         */
        public int[] crossPos() {
            if (stations.size() < 2)
                return null;
            int hi = platformStart(positiveTerminal()) - 2, lo = hi - CROSSOVER;
            return lo >= stations.get(stations.size() - 2).endBlock() + 2 ? new int[] { lo, hi } : null;
        }

        /**
         * Whether the line runs as a loop (both crossovers fit, apart): trains head for the negative terminal on the low
         * track and back on the high one, so many can run at once. Otherwise each track is a shuttle of one train.
         */
        public boolean loops() {
            int[] n = crossNeg(), p = crossPos();
            return n != null && p != null && n[1] < p[0];
        }

        /**
         * The track as MTR rails, both tracks, at rail height {@code y} (the world's, lifted): one straight two-way rail
         * between each pair of nodes, a platform where it spans a station, a siding past the positive terminal.
         */
        public List<me.daddychurchill.CityWorld.integration.mtr.MtrRails.Rail> rails(int y) {
            List<me.daddychurchill.CityWorld.integration.mtr.MtrRails.Rail> out = new ArrayList<>();
            List<Integer> nodes = nodes();
            int dir = ew ? 0 : 2, back = (dir + 4) % 8;
            boolean loop = loops();
            for (int c : new int[] { LOW_TRACK, HIGH_TRACK })
                for (int i = 0; i + 1 < nodes.size(); i++) {
                    int b0 = nodes.get(i), b1 = nodes.get(i + 1);
                    boolean platform = false;
                    for (Station s : stations)
                        platform |= b0 == platformStart(s) && b1 == s.endBlock();
                    boolean siding = b0 == sidingStart() && b1 == to * 16 + 14 && (!loop || c == LOW_TRACK);
                    var a = new me.daddychurchill.CityWorld.integration.mtr.MtrRails.Node(blockX(b0, c), y, blockZ(b0, c), dir);
                    var b = new me.daddychurchill.CityWorld.integration.mtr.MtrRails.Node(blockX(b1, c), y, blockZ(b1, c), dir);
                    int speed = siding ? 40 : 80;
                    // a shuttle reverses at its terminals and in its siding: those rails let it turn back
                    boolean terminal = platform && (b0 == platformStart(stations.get(0)) && (!loop || c == HIGH_TRACK)
                            || b1 == positiveTerminal().endBlock() && (!loop || c == LOW_TRACK));
                    out.add(new me.daddychurchill.CityWorld.integration.mtr.MtrRails.Rail(a, dir, b, dir, platform, siding, speed, speed,
                            terminal || siding));
                }
            if (loop) {
                // the crossovers, one way each: low to high before the negative terminal, high to low before the positive
                int[] n = crossNeg(), p = crossPos();
                out.add(new me.daddychurchill.CityWorld.integration.mtr.MtrRails.Rail(node(n[1], LOW_TRACK, y, dir), back, node(n[0], HIGH_TRACK, y, dir), back, false, false,
                        CROSSOVER_SPEED, 0, false));
                out.add(new me.daddychurchill.CityWorld.integration.mtr.MtrRails.Rail(node(p[0], HIGH_TRACK, y, dir), dir, node(p[1], LOW_TRACK, y, dir), dir, false, false,
                        CROSSOVER_SPEED, 0, false));
            }
            return out;
        }

        private me.daddychurchill.CityWorld.integration.mtr.MtrRails.Node node(int b, int c, int y, int dir) {
            return new me.daddychurchill.CityWorld.integration.mtr.MtrRails.Node(blockX(b, c), y, blockZ(b, c), dir);
        }

        /** The middle of station {@code s}'s platform on the track at across {@code c}: world x, z. */
        public double[] platformMiddle(Station s, int c) {
            double b = (platformStart(s) + s.endBlock()) / 2.0 + 0.5;
            return ew ? new double[] { centreX * 16 + b, centreZ * 16 + c + 0.5 } : new double[] { centreX * 16 + c + 0.5, centreZ * 16 + b };
        }

        /** The platform's ends along, for station {@code s} (its first node never before the line's start). */
        public int platformStart(Station s) {
            return Math.max(from * 16 + 1, s.startBlock());
        }

        /**
         * Where the depot's sidings begin along: two blocks past the positive terminal's far end. MTR will not path a
         * train out of a siding that shares its end node with a platform, so a short plain rail links the two.
         */
        public int sidingStart() {
            return positiveTerminal().endBlock() + 2;
        }
    }

    /** A city's two lines (either may be null: too short a run that way). */
    public record Lines(Line ew, Line ns) {
        public boolean interchange() {
            return ew != null && ns != null;
        }

        public boolean any() {
            return ew != null || ns != null;
        }
    }

    private static final Lines NONE = new Lines(null, null);
    private static final Map<CityWorldGenerator, Map<CitySites.Site, Lines>> MEMO = Collections
            .synchronizedMap(new WeakHashMap<>());

    /** Whether this world has a metro: vanilla land made with 7.4 (and the setting on), the overworld. */
    public static boolean planned(CityWorldGenerator generator) {
        return generator.metro && generator.citySites != null && generator.worldEnvironment == Environment.NORMAL;
    }

    /** The lines of the city whose cell holds this chunk (none outside a city). */
    public static Lines linesAt(CityWorldGenerator generator, int chunkX, int chunkZ) {
        if (!planned(generator))
            return NONE;
        CitySites.Site site = generator.citySites.siteAt(chunkX * 16 + 8, chunkZ * 16 + 8);
        return linesOf(generator, site);
    }

    public static Lines linesOf(CityWorldGenerator generator, CitySites.Site site) {
        if (site == null)
            return NONE;
        return MEMO.computeIfAbsent(generator, g -> new ConcurrentHashMap<>()).computeIfAbsent(site,
                s -> new Lines(compute(s, true), compute(s, false)));
    }

    /** Lay out one line of a city, or null where the city is too small that way. */
    static Line compute(CitySites.Site site, boolean ew) {
        int axis = ew ? site.centreX() : site.centreZ();
        int c0 = Math.floorDiv(axis, 16);
        double inner = site.radius() * (1 - WOBBLE) - 8;
        int hPos = 0, hNeg = 0;
        while ((c0 + hPos + 2) * 16 - axis <= inner)
            hPos++;
        while (axis - (c0 - hNeg - 1) * 16 <= inner)
            hNeg++;
        if (hNeg < STATION + 1 || hPos < STATION + DEPOT)
            return null;
        Station neg = new Station(-hNeg, -hNeg + STATION - 1);
        Station centre = new Station(1, STATION);
        Station pos;
        int depotFrom, to;
        if (hPos >= 2 * STATION + DEPOT + GAP + 2) { // room for a terminal of its own past the centre
            pos = new Station(hPos - DEPOT - STATION + 1, hPos - DEPOT);
            depotFrom = hPos - DEPOT + 1;
            to = hPos;
        } else {
            pos = centre;
            depotFrom = centre.to() + 1;
            to = centre.to() + DEPOT;
        }
        List<Station> stations = new ArrayList<>();
        stations.add(neg);
        spread(stations, neg.to() + 1, centre.from() - 1);
        int centreIndex = stations.size();
        stations.add(centre);
        if (pos != centre) {
            spread(stations, centre.to() + 1, pos.from() - 1);
            stations.add(pos);
        }
        int cx = Math.floorDiv(site.centreX(), 16), cz = Math.floorDiv(site.centreZ(), 16);
        return new Line(site, ew, cx, cz, -hNeg, to, List.copyOf(stations), depotFrom, centreIndex);
    }

    /** As many stations as fit in chunks {@code lo..hi} with at least {@link #GAP} of tunnel round each, evenly. */
    private static void spread(List<Station> out, int lo, int hi) {
        int free = hi - lo + 1;
        int n = free >= GAP ? (free - GAP) / (STATION + GAP) : 0;
        if (n <= 0)
            return;
        int spare = free - n * STATION, base = spare / (n + 1), rem = spare % (n + 1);
        int x = lo;
        for (int i = 0; i < n; i++) {
            x += base + (i < rem ? 1 : 0);
            out.add(new Station(x, x + STATION - 1));
            x += STATION;
        }
    }

    /** The interchange: the chunk diagonal to both central stations' first chunks. */
    static boolean isInterchange(Lines lines, int chunkX, int chunkZ) {
        return lines.interchange() && chunkX == lines.ew().centreX() + 1 && chunkZ == lines.ew().centreZ() + 1;
    }

    /** Whether a mine level starting at {@code mineY} would cut a metro tunnel, stair or passage here. */
    public static boolean blocksMines(CityWorldGenerator generator, PlatLot lot, int mineY) {
        if (!planned(generator))
            return false;
        Lines lines = linesAt(generator, lot.getChunkX(), lot.getChunkZ());
        if (!lines.any())
            return false;
        boolean near = false;
        for (Line line : new Line[] { lines.ew(), lines.ns() })
            if (line != null)
                for (int d = -1; d <= 1 && !near; d++)
                    near = line.along(lot.getChunkX() + (line.ew() ? 0 : d), lot.getChunkZ() + (line.ew() ? d : 0)) != Integer.MIN_VALUE;
        int low = generator.streetLevel - NS_DEPTH - 2, high = generator.streetLevel - EW_DEPTH + 14;
        return near && mineY + 9 >= low && mineY - 10 <= high;
    }

    // ---- names ---------------------------------------------------------------------------------------------------

    /**
     * A station's name: the central station is "{@code <city>} Central"; any other takes the name of a street crossing
     * it (the first road chunk along it, its cross street where it has one), else its city and a compass word.
     */
    public static String stationName(CityWorldGenerator generator, Line line, Station s) {
        Lines lines = linesOf(generator, line.site());
        // worked out outside the map (it plans platmaps: other threads must not wait on its bin meanwhile); a race
        // works it out twice, the same both times
        Map<Lines, String[][]> memo = NAMES.computeIfAbsent(generator, g -> new ConcurrentHashMap<>());
        String[][] all = memo.get(lines);
        if (all == null) {
            all = names(generator, lines);
            String[][] raced = memo.putIfAbsent(lines, all);
            if (raced != null)
                all = raced;
        }
        return all[line.ew() ? 0 : 1][line.stations().indexOf(s)];
    }

    private static final Map<CityWorldGenerator, Map<Lines, String[][]>> NAMES = Collections
            .synchronizedMap(new WeakHashMap<>());

    /**
     * Every station name of a city's metro, east-west line first, no two alike: the central station is "{@code <city>}
     * Central"; any other takes the name of a street crossing it or beside it (one not taken already), else its city and
     * a compass word: "West", or "Inner West" and "Outer West" where there are two, and so on.
     */
    private static String[][] names(CityWorldGenerator generator, Lines lines) {
        String city = PlaceNames.ofCity(generator, (lines.ew() != null ? lines.ew() : lines.ns()).site());
        city = city == null ? "City" : city.replace('|', '/');
        String[][] out = new String[2][];
        java.util.Set<String> used = new java.util.HashSet<>();
        used.add(city + " Central");
        Line[] both = { lines.ew(), lines.ns() };
        for (int k = 0; k < 2; k++) {
            Line line = both[k];
            if (line == null)
                continue;
            out[k] = new String[line.stations().size()];
            for (int i = 0; i < out[k].length; i++)
                if (i == line.centre())
                    out[k][i] = city + " Central";
                else if (generator.getSettings().includeNamedRoads)
                    out[k][i] = streetName(generator, line, line.stations().get(i), used);
            // the rest by compass, nearest the centre "Inner", farthest "Outer"
            for (int side = -1; side <= 1; side += 2) {
                List<Integer> unnamed = new ArrayList<>();
                for (int i = 0; i < out[k].length; i++) {
                    Station s = line.stations().get(i);
                    if (out[k][i] == null && Integer.signum(s.from() + s.to()) == side)
                        unnamed.add(i);
                }
                unnamed.sort(java.util.Comparator.comparingInt(i -> Math.abs(line.stations().get(i).from())));
                String way = line.ew() ? (side < 0 ? "West" : "East") : (side < 0 ? "North" : "South");
                for (int n = 0; n < unnamed.size(); n++) {
                    String word = unnamed.size() == 1 ? way
                            : unnamed.size() == 2 ? (n == 0 ? "Inner " : "Outer ") + way
                            : unnamed.size() == 3 ? (n == 0 ? "Inner " : n == 1 ? "" : "Outer ") + way : way + " " + (n + 1);
                    out[k][unnamed.get(n)] = city + " " + word;
                }
            }
        }
        return out;
    }

    /** The name of a street crossing (or beside) this station that no other station has taken, or null. */
    private static String streetName(CityWorldGenerator generator, Line line, Station s, java.util.Set<String> used) {
        for (int d : new int[] { 0, -1, 1, -2, 2 })
            for (int a = s.from() - 1; a <= s.to() + 1; a++) {
                int cx = line.chunkX(a) + (line.ew() ? 0 : d), cz = line.chunkZ(a) + (line.ew() ? d : 0);
                PlatMap platmap = generator.getPlatMap(cx, cz);
                int px = cx - platmap.originX, pz = cz - platmap.originZ;
                if (platmap.getLot(px, pz) instanceof RoadLot road) {
                    boolean[] runs = road.getStreetDirections(platmap, px, pz);
                    // runs = {eastWest, northSouth}: a road across an east-west line runs north-south
                    boolean across = line.ew() ? runs[1] : runs[0];
                    for (boolean eastWest : across ? new boolean[] { !line.ew(), line.ew() } : new boolean[] { line.ew() }) {
                        String name = road.getStreetName(generator, eastWest);
                        if (name != null && !name.isBlank() && used.add(name))
                            return name;
                    }
                }
            }
        return null;
    }

    /**
     * A station's area for MTR, in world blocks {minX, minZ, maxX, maxZ}: its own chunks (so the two central stations,
     * corner to corner, never overlap).
     */
    public static int[] stationBox(Line line, Station s) {
        int ax0 = line.chunkX(s.from()), az0 = line.chunkZ(s.from()), ax1 = line.chunkX(s.to()), az1 = line.chunkZ(s.to());
        return new int[] { Math.min(ax0, ax1) * 16, Math.min(az0, az1) * 16, Math.max(ax0, ax1) * 16 + 15,
                Math.max(az0, az1) * 16 + 15 };
    }

    /** A depot's area for MTR: the depot chunks, the low (across 0..7) or high (8..15) half. */
    public static int[] depotBox(Line line, boolean low) {
        int b0 = line.depotFrom() * 16, b1 = line.to() * 16 + 15;
        int c0 = low ? 0 : 8, c1 = low ? 7 : 15;
        int x0 = line.blockX(b0, c0), z0 = line.blockZ(b0, c0), x1 = line.blockX(b1, c1), z1 = line.blockZ(b1, c1);
        return new int[] { Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1) };
    }

    // ---- planning the entrances ----------------------------------------------------------------------------------

    /**
     * Claim each metro station's street entrance in this platmap: a chunk beside the line along the station, empty,
     * beside a road if one can be, never on the other line or the interchange. The subway station is placed after
     * (with the platmap's buildings), and keeps off the entrances' rows and columns where it can. One per station per platmap, so a station across two platmaps may get two.
     * Runs before the context fills the platmap, on vanilla land only.
     */
    public static void placeEntrances(CityWorldGenerator generator, PlatMap platmap) {
        if (!planned(generator))
            return;
        java.util.Set<CitySites.Site> sites = new java.util.LinkedHashSet<>();
        for (int[] c : new int[][] { { 0, 0 }, { 9, 0 }, { 0, 9 }, { 9, 9 } }) {
            CitySites.Site s = generator.citySites.siteAt((platmap.originX + c[0]) * 16 + 8, (platmap.originZ + c[1]) * 16 + 8);
            if (s != null)
                sites.add(s);
        }
        for (CitySites.Site site : sites) {
            Lines lines = linesOf(generator, site);
            for (Line line : new Line[] { lines.ew(), lines.ns() })
                if (line != null)
                    for (Station s : line.stations())
                        claim(generator, platmap, lines, line, s);
        }
    }

    private static void claim(CityWorldGenerator generator, PlatMap platmap, Lines lines, Line line, Station s) {
        int[] order = { s.from() + 1, s.from() + 2, s.from(), s.to() };
        int[] fallback = null;
        for (int a : order)
            for (int side : new int[] { -1, 1 }) {
                int cx = line.chunkX(a) + (line.ew() ? 0 : side), cz = line.chunkZ(a) + (line.ew() ? side : 0);
                int px = cx - platmap.originX, pz = cz - platmap.originZ;
                if (px < 0 || px >= PlatMap.Width || pz < 0 || pz >= PlatMap.Width)
                    continue;
                if (isInterchange(lines, cx, cz))
                    continue;
                Line other = line.ew() ? lines.ns() : lines.ew();
                if (other != null && (line.ew() ? cx == other.centreX() : cz == other.centreZ()))
                    continue;
                if (!platmap.isEmptyLot(px, pz))
                    continue;
                boolean road = platmap.isExistingRoad(px - 1, pz) || platmap.isExistingRoad(px + 1, pz)
                        || platmap.isExistingRoad(px, pz - 1) || platmap.isExistingRoad(px, pz + 1);
                if (road) {
                    if (platmap.setLot(px, pz, new MetroEntranceLot(platmap, cx, cz, line.ew(), side < 0)))
                        return;
                } else if (fallback == null)
                    fallback = new int[] { px, pz, cx, cz, side };
            }
        if (fallback != null)
            platmap.setLot(fallback[0], fallback[1],
                    new MetroEntranceLot(platmap, fallback[2], fallback[3], line.ew(), fallback[4] < 0));
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    /** Whether the metro draws anything under this chunk. */
    public static boolean touches(CityWorldGenerator generator, int chunkX, int chunkZ) {
        Lines lines = linesAt(generator, chunkX, chunkZ);
        if (!lines.any())
            return false;
        for (Line line : new Line[] { lines.ew(), lines.ns() })
            if (line != null && line.along(chunkX, chunkZ) != Integer.MIN_VALUE)
                return true;
        return isInterchange(lines, chunkX, chunkZ);
    }

    /** The metro under this chunk, if any: its tunnel, station, depot or the interchange stair. */
    public static void generate(CityWorldGenerator generator, PlatLot lot, RealBlocks chunk, Odds odds) {
        Lines lines = linesAt(generator, chunk.sectionX, chunk.sectionZ);
        if (!lines.any())
            return;
        // most of a metro city's chunks have none of it: nothing to draw, and nothing to reshape
        boolean walk = lot != null && MetroWalk.touches(generator, chunk.sectionX, chunk.sectionZ);
        if (!walk && !touches(generator, chunk.sectionX, chunk.sectionZ))
            return;
        for (Line line : new Line[] { lines.ew(), lines.ns() })
            if (line != null) {
                int a = line.along(chunk.sectionX, chunk.sectionZ);
                if (a != Integer.MIN_VALUE)
                    drawLine(generator, lines, line, a, chunk);
            }
        if (isInterchange(lines, chunk.sectionX, chunk.sectionZ))
            drawInterchange(generator, lines, chunk);
        // the walk from the railway station, through a built lot (vanilla land draws it with the station's chunks)
        if (walk)
            MetroWalk.draw(generator, chunk);
        // the bars and panes drawn here joined to their neighbours
        Connections.reconnectChunk(chunk.getWorld(), chunk.sectionX, chunk.sectionZ,
                generator.streetLevel - NS_DEPTH - 8, generator.streetLevel + 12);
    }

    /** A box in a line's frame (along, y, across; all inclusive). */
    private static void box(RealBlocks chunk, boolean ew, int a0, int a1, int y0, int y1, int c0, int c1, Material m) {
        if (ew)
            chunk.setBlocks(a0, a1 + 1, y0, y1 + 1, c0, c1 + 1, m);
        else
            chunk.setBlocks(c0, c1 + 1, y0, y1 + 1, a0, a1 + 1, m);
    }

    private static void put(RealBlocks chunk, boolean ew, int a, int y, int c, Material m) {
        if (ew)
            chunk.setBlock(a, y, c, m);
        else
            chunk.setBlock(c, y, a, m);
    }

    private static void drawLine(CityWorldGenerator generator, Lines lines, Line line, int a, RealBlocks chunk) {
        boolean ew = line.ew();
        int f = line.floor(generator);
        Station s = line.stationAt(a);
        Material wall = s != null ? TILE : SHELL;
        // the box: floor, beds, walls, air, ceiling, with the line's stripe along both walls
        box(chunk, ew, 0, 15, f - 1, f, 0, 15, SHELL);
        box(chunk, ew, 0, 15, f, f, LOW_TRACK - 1, LOW_TRACK + 1, BED);
        box(chunk, ew, 0, 15, f, f, HIGH_TRACK - 1, HIGH_TRACK + 1, BED);
        box(chunk, ew, 0, 15, f + 1, f + 6, 0, 0, wall);
        box(chunk, ew, 0, 15, f + 1, f + 6, 15, 15, wall);
        box(chunk, ew, 0, 15, f + 1, f + 6, 1, 14, Material.AIR);
        box(chunk, ew, 0, 15, f + 3, f + 3, 0, 0, line.stripe());
        box(chunk, ew, 0, 15, f + 3, f + 3, 15, 15, line.stripe());
        box(chunk, ew, 0, 15, f + 7, f + 7, 0, 15, SHELL);
        for (int l : new int[] { 3, 11 })
            for (int c : new int[] { LOW_TRACK, 7, 8, HIGH_TRACK })
                put(chunk, ew, l, f + 7, c, LIGHT);
        if (a == line.from())
            box(chunk, ew, 0, 0, f, f + 7, 0, 15, SHELL);
        if (a == line.to())
            box(chunk, ew, 15, 15, f, f + 7, 0, 15, SHELL);
        if (s != null)
            drawStation(generator, lines, line, s, a, chunk, f);
        else if (a >= line.depotFrom()) // buffer stops at the sidings' ends
            if (a == line.to())
                for (int c : new int[] { LOW_TRACK, HIGH_TRACK })
                    put(chunk, ew, 14, f + 1, c, Material.IRON_BLOCK);
        railNodes(generator, line, a, chunk, f);
    }

    /** MTR's rail node blocks at this chunk's nodes, on both tracks (MTR only). */
    private static void railNodes(CityWorldGenerator generator, Line line, int a, RealBlocks chunk, int f) {
        if (!me.daddychurchill.CityWorld.integration.mtr.MtrStations.enabled())
            return;
        var state = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.railNode(line.ew() ? 0 : 90);
        if (state == null)
            return;
        for (int b : line.nodes())
            if (Math.floorDiv(b, 16) == a)
                for (int c : new int[] { LOW_TRACK, HIGH_TRACK }) {
                    int l = Math.floorMod(b, 16);
                    if (line.ew())
                        chunk.setBlockState(l, f + 1, c, state);
                    else
                        chunk.setBlockState(c, f + 1, l, state);
                }
    }

    private static void drawStation(CityWorldGenerator generator, Lines lines, Line line, Station s, int a,
            RealBlocks chunk, int f) {
        boolean ew = line.ew();
        // the island: platform one over the bed, its edges yellow (MTR's platform blocks where MTR is installed)
        var mtrPlatform = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.platform();
        for (int l = 0; l < 16; l++)
            for (int c = 4; c <= 11; c++) {
                int x = ew ? l : c, z = ew ? c : l;
                if (c == 4 || c == 11) {
                    var dir = ew ? (c == 4 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH)
                            : (c == 4 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST);
                    var edge = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.platformEdge(dir);
                    if (edge != null)
                        chunk.setBlockState(x, f + 1, z, edge);
                    else
                        chunk.setBlock(x, f + 1, z, EDGE);
                } else if (mtrPlatform != null)
                    chunk.setBlockState(x, f + 1, z, mtrPlatform);
                else
                    chunk.setBlock(x, f + 1, z, PLATFORM);
            }
        if (a == s.from())
            box(chunk, ew, 0, 0, f + 1, f + 6, 1, 14, TILE);
        if (a == s.to())
            box(chunk, ew, 15, 15, f + 1, f + 6, 1, 14, TILE);
        // name boards on two pillars in the island's middle, facing each track
        String name = stationName(generator, line, s);
        box(chunk, ew, 7, 8, f + 2, f + 6, 7, 8, PILLAR);
        for (int ll = 7; ll <= 8; ll++) {
            BlockFace low = ew ? BlockFace.NORTH : BlockFace.WEST, high = low.getOppositeFace();
            if (ew) {
                chunk.setWallSign(ll, f + 4, 6, low, "METRO", name, line.name());
                chunk.setWallSign(ll, f + 4, 9, high, "METRO", name, line.name());
            } else {
                chunk.setWallSign(6, f + 4, ll, low, "METRO", name, line.name());
                chunk.setWallSign(9, f + 4, ll, high, "METRO", name, line.name());
            }
        }
        // the concourse over the hall, its length: floor f+9, three high, walls at 5 and 10
        box(chunk, ew, 0, 15, f + 8, f + 9, 5, 10, SHELL);
        box(chunk, ew, 0, 15, f + 9, f + 9, 6, 9, PLATFORM);
        box(chunk, ew, 0, 15, f + 10, f + 12, 5, 5, TILE);
        box(chunk, ew, 0, 15, f + 10, f + 12, 10, 10, TILE);
        box(chunk, ew, 0, 15, f + 11, f + 11, 5, 5, line.stripe());
        box(chunk, ew, 0, 15, f + 11, f + 11, 10, 10, line.stripe());
        box(chunk, ew, 0, 15, f + 10, f + 12, 6, 9, Material.AIR);
        box(chunk, ew, 0, 15, f + 13, f + 13, 5, 10, SHELL);
        for (int l : new int[] { 3, 11 })
            for (int c : new int[] { 7, 8 })
                put(chunk, ew, l, f + 13, c, LIGHT);
        if (a == s.from())
            box(chunk, ew, 0, 0, f + 8, f + 13, 5, 10, SHELL);
        if (a == s.to())
            box(chunk, ew, 15, 15, f + 8, f + 13, 5, 10, SHELL);
        // passages to the entrances beside the line, and to the interchange
        for (int side : new int[] { -1, 1 }) {
            int cx = chunk.sectionX + (ew ? 0 : side), cz = chunk.sectionZ + (ew ? side : 0);
            PlatMap platmap = generator.getPlatMap(cx, cz);
            if (platmap.getLot(cx - platmap.originX, cz - platmap.originZ) instanceof MetroEntranceLot e
                    && e.ew() == ew && e.low() == (side < 0) && e.hasStair(generator))
                passage(chunk, ew, f, 6, 9, side > 0);
        }
        if (lines.interchange() && s == line.stations().get(line.centre()) && a == s.from())
            if (ew)
                passage(chunk, true, f, 8, 11, true); // from the interchange to the south
            else
                passage(chunk, false, f, 6, 7, true); // ...and from it to the east
        // the stairs down to the island at the station's two ends
        if (a == s.from())
            downStair(chunk, ew, 1, true, f);
        if (a == s.to())
            downStair(chunk, ew, 11, false, f);
        if (me.daddychurchill.CityWorld.integration.mtr.MtrStations.enabled())
            fitOut(generator, line, s, a, chunk, f);
        chunk.reconnect(0, 16, f + 1, f + 13, 0, 16);
    }

    private static void putState(RealBlocks chunk, boolean ew, int a, int y, int c, net.minecraft.world.level.block.state.BlockState state) {
        if (state == null)
            return;
        if (ew)
            chunk.setBlockState(a, y, c, state);
        else
            chunk.setBlockState(c, y, a, state);
    }

    /**
     * With MTR, a station's fittings: platform screen doors along both edges of the island (a pane of glass then two
     * pairs of doors, over and over, so a train's door always stands at a door whatever its cars), departure boards and
     * line maps on the island, fare gates across the concourse between each stair and the rest of it, ticket machines
     * outside the gates, a departure board in each half of the concourse, and a few travellers waiting.
     */
    private static void fitOut(CityWorldGenerator generator, Line line, Station s, int a, RealBlocks chunk, int f) {
        boolean ew = line.ew();
        var aPos = ew ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.SOUTH;
        var cPos = ew ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.EAST;
        int n = (s.to() - s.from() + 1) * 16; // the hall's length, its end walls at 0 and n - 1
        // the screen doors: facing the track, as one placing them looks
        for (int edge : new int[] { 4, 11 }) {
            var face = edge == 4 ? cPos.getOpposite() : cPos;
            boolean cwAlong = face.getClockWise() == aPos;
            for (int l = 0; l < 16; l++) {
                int b = (a - s.from()) * 16 + l;
                if (b < 1 || b > n - 2)
                    continue;
                String kind;
                boolean left = false;
                if (b == 1 || b == n - 2)
                    kind = "psd_glass_end";
                else {
                    int k = (b - 2) % 5;
                    int first = k == 0 ? -1 : k <= 2 ? b - k + 1 : b - k + 3;
                    if (k == 0 || first + 1 > n - 3)
                        kind = "psd_glass";
                    else {
                        kind = "psd_door";
                        boolean isFirst = b == first;
                        left = isFirst == cwAlong;
                    }
                }
                for (int h = 0; h < 2; h++) {
                    String half = h == 0 ? "lower" : "upper";
                    var state = kind.equals("psd_door")
                            ? me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.of(kind, "facing", face.getSerializedName(),
                                    "half", half, "side", left ? "left" : "right", "end", "false", "unlocked", "true")
                            : me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.of(kind, "facing", face.getSerializedName(),
                                    "half", half, "side", "single");
                    putState(chunk, ew, l, f + 2 + h, edge, state);
                }
                putState(chunk, ew, l, f + 4, edge, me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.of("psd_top",
                        "facing", face.getSerializedName(), "side", "single"));
            }
        }
        boolean middle = a != s.from() && a != s.to();
        if (middle) {
            // on the island: a departure board on poles back to back (one screen to each track), and a line map
            // showing to each track
            for (int c : new int[] { 7, 8 }) {
                var toward = c == 7 ? cPos : cPos.getOpposite(); // each faces the other; its screen shows outward
                var pole = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.pidsPole(toward);
                var board = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.pids(toward);
                if (pole != null && board != null) {
                    putState(chunk, ew, 3, f + 2, c, pole);
                    putState(chunk, ew, 3, f + 3, c, pole);
                    putState(chunk, ew, 3, f + 4, c, board);
                }
            }
            for (int c : new int[] { 6, 9 }) {
                var shows = c == 6 ? cPos.getOpposite() : cPos;
                putState(chunk, ew, 12, f + 2, c, me.daddychurchill.CityWorld.integration.mtr.MtrBlocks
                        .routeSign("route_sign_standing_light", shows.getOpposite(), false));
                putState(chunk, ew, 12, f + 3, c, me.daddychurchill.CityWorld.integration.mtr.MtrBlocks
                        .routeSign("route_sign_standing_light", shows.getOpposite(), true));
            }
            // travellers waiting on the island
            Odds odds = new Odds(generator.getConnectionKey(chunk.sectionX, chunk.sectionZ) ^ 0x6d6574726fL);
            for (int l : new int[] { 5, 10 }) {
                int c = odds.flipCoin() ? 6 : 9;
                generator.spawnProvider.spawnTraveller(generator, chunk, odds, ew ? l : c, f + 2, ew ? c : l);
            }
        } else {
            // the fare line across the concourse between the stairs (paid) and the rest of the hall
            boolean low = a == s.from();
            int gate = low ? 5 : 10;
            var inward = low ? aPos.getOpposite() : aPos; // toward the stairs
            for (int c = 6; c <= 9; c++) {
                boolean entry = low ? c <= 7 : c >= 8;
                putState(chunk, ew, gate, f + 10, c, me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.of(
                        entry ? "ticket_barrier_entrance_1" : "ticket_barrier_exit_1", "facing",
                        (entry ? inward : inward.getOpposite()).getSerializedName(), "open", "closed"));
                putState(chunk, ew, gate, f + 11, c, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            // ticket machines against the concourse wall outside the gates
            int mc = low ? 6 : 9;
            var looks = low ? cPos.getOpposite() : cPos; // as one standing at them looks
            for (int l : low ? new int[] { 12, 13 } : new int[] { 2, 3 }) {
                putState(chunk, ew, l, f + 10, mc, me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.of("ticket_machine",
                        "facing", looks.getSerializedName(), "half", "lower"));
                putState(chunk, ew, l, f + 11, mc, me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.of("ticket_machine",
                        "facing", looks.getSerializedName(), "half", "upper"));
            }
            // a departure board for the whole station in the concourse, one screen to each end
            int l0 = 7;
            for (int k = 0; k < 2; k++) {
                var toward = k == 0 ? aPos : aPos.getOpposite();
                var pole = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.pidsPole(toward);
                var board = me.daddychurchill.CityWorld.integration.mtr.MtrBlocks.pids(toward);
                if (pole != null && board != null) {
                    putState(chunk, ew, l0 + k, f + 10, low ? 9 : 6, pole);
                    putState(chunk, ew, l0 + k, f + 11, low ? 9 : 6, board);
                }
            }
        }
    }

    /** A passage at concourse level from the concourse wall out to the chunk's edge, along {@code a0..a1}. */
    private static void passage(RealBlocks chunk, boolean ew, int f, int a0, int a1, boolean high) {
        int c0 = high ? 10 : 0, c1 = high ? 15 : 5;
        box(chunk, ew, a0 - 1, a1 + 1, f + 8, f + 9, c0, c1, SHELL);
        box(chunk, ew, a0 - 1, a0 - 1, f + 10, f + 12, c0, c1, TILE);
        box(chunk, ew, a1 + 1, a1 + 1, f + 10, f + 12, c0, c1, TILE);
        box(chunk, ew, a0, a1, f + 9, f + 9, c0, c1, PLATFORM);
        box(chunk, ew, a0, a1, f + 10, f + 12, c0, c1, Material.AIR);
        box(chunk, ew, a0 - 1, a1 + 1, f + 13, f + 13, c0, c1, SHELL);
        put(chunk, ew, (a0 + a1) / 2, f + 13, high ? 13 : 2, LIGHT);
    }

    /** A switchback from the island up to the concourse at one end of a station, and bars at its head. */
    private static void downStair(RealBlocks chunk, boolean ew, int a0, boolean entryPositive, int f) {
        Subway.zigzag(chunk, ew ? a0 : 6, ew ? 6 : a0, ew, entryPositive, f + 2, f + 10, f + 7, Subway.Door.ALONG,
                Subway.Door.ALONG, 2);
        // across the landing lane at the head, beside the exit: the drop into the first flight
        int aIn = entryPositive ? a0 + 3 : a0, head = aIn + (entryPositive ? -1 : 1);
        for (int c = 6; c <= 7; c++)
            put(chunk, ew, head, f + 10, c, Material.IRON_BARS);
    }

    /**
     * The interchange, in the chunk diagonal to both central stations' first chunks: a switchback from the east-west
     * concourse (its passage comes in from the north) down to the north-south one (its passage leaves to the west).
     */
    private static void drawInterchange(CityWorldGenerator generator, Lines lines, RealBlocks chunk) {
        int top = lines.ew().floor(generator) + 10, bottom = lines.ns().floor(generator) + 10;
        // flights along z, x 8..11, z 6..11; in at the north end (z 5) at the top, out west (x 7) at the bottom
        int x0 = 8, z0 = 6, len = 6;
        // the room over the shaft and the passage north, at the top
        chunk.setBlocks(x0 - 1, x0 + 5, top - 2, top, 0, z0 + len + 1, SHELL);
        chunk.setBlocks(x0 - 1, x0 + 5, top, top + 3, 0, z0 + len + 1, TILE);
        chunk.setBlocks(x0, x0 + 4, top, top + 3, 0, z0 + len, Material.AIR);
        chunk.setBlocks(x0 - 1, x0 + 5, top + 3, top + 4, 0, z0 + len + 1, SHELL);
        chunk.setBlocks(x0, x0 + 4, top - 1, top, 0, z0, PLATFORM);
        chunk.setBlock(x0 + 1, top + 3, 2, LIGHT);
        // the passage west at the bottom
        chunk.setBlocks(0, x0, bottom - 2, bottom, z0 - 1, z0 + 3, SHELL);
        chunk.setBlocks(0, x0, bottom, bottom + 3, z0 - 1, z0 + 3, TILE);
        chunk.setBlocks(0, x0, bottom, bottom + 3, z0, z0 + 2, Material.AIR);
        chunk.setBlocks(0, x0, bottom + 3, bottom + 4, z0 - 1, z0 + 3, SHELL);
        chunk.setBlocks(0, x0, bottom - 1, bottom, z0, z0 + 2, PLATFORM);
        chunk.setBlock(3, bottom + 3, z0, LIGHT);
        Subway.zigzag(chunk, x0, z0, false, false, bottom, top, bottom, Subway.Door.ACROSS_LOW, Subway.Door.ALONG, 4);
        // the top door opens the whole end wall, not just the landing lane
        chunk.setBlocks(x0, x0 + 4, top, top + 3, z0 - 1, z0, Material.AIR);
        // the landing lane's ledge beside the exit (flights of four: the exit landing is two deep)
        for (int x = x0; x <= x0 + 1; x++)
            chunk.setBlock(x, top, z0 + 2, Material.IRON_BARS);
        chunk.setWallSign(x0 + 2, top + 1, z0 - 1, BlockFace.NORTH, "INTERCHANGE", lines.ns().name(), "↓ below");
        chunk.setWallSign(1, bottom + 1, z0 + 2, BlockFace.NORTH, "INTERCHANGE", lines.ew().name(), "↑ above");
        chunk.reconnect(0, 16, bottom, top + 3, 0, 16);
    }
}
