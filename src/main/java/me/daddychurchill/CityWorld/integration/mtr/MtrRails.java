package me.daddychurchill.CityWorld.integration.mtr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import me.daddychurchill.CityWorld.worldgen.CitySites;
import me.daddychurchill.CityWorld.worldgen.Highways;
import me.daddychurchill.CityWorld.worldgen.Railways;

/**
 * CityWorld's railway lines as MTR rails: the track a player would otherwise lay by hand, laid as MTR data (its own
 * {@code rails} list, the request the stations go in) with MTR's rail node blocks where the rails meet.
 * <p>
 * A line's rails follow its plan, one set per track: each straight run split into rails at most {@link #SPLIT} blocks
 * long (a node at each split takes the line's grade), a curved rail across each 45° bend (MTR draws the arc from the two
 * nodes' angles), and at a station end the line carries on along the trunk to the fan's end. A station's rails: one
 * platform rail along each track (MTR makes each a platform), and from each platform's ends a rail through the fan to the
 * trunk track on its side, so every platform reaches every line leaving that end (the nodes where they meet are MTR's
 * switches). A line that leaves another line's straight (a corridor) starts at that line's nearest node behind its
 * start on the same track.
 * <p>
 * Every node is a block position on a whole step from its run's first node, so a straight run stays straight on MTR's
 * own 45° lattice. Planning is a pure function of the line, memoised per line.
 */
public final class MtrRails {
    private MtrRails() {
    }

    /** The longest straight rail, in blocks (a diagonal: as near as whole steps make it). */
    static final int SPLIT = 32;
    /** km/h: open line, a bend (a 45° arc of only {@code curveRadius} blocks), platforms and the station throat. */
    static final int LINE_SPEED = 300, CURVE_SPEED = 80, STATION_SPEED = 80;
    private static final String[] ANGLES = { "E", "SEE", "SE", "SSE", "S", "SSW", "SW", "SWW", "W", "NWW", "NW", "NNW",
            "N", "NNE", "NE", "NEE" };

    /** A node: a block position and the compass direction (0-7, as {@code Railways.DIRS}) a train runs through it. */
    public record Node(int x, int y, int z, int dir) {
        /** The axis as MTR's node block reads it, in degrees: 0, 45, 90 or 135. */
        public int axis() {
            return Math.floorMod(dir, 4) * 45;
        }
    }

    /** One rail: from {@code a}, leaving it in {@code dirA}, to {@code b}, arriving in {@code dirB}. */
    public record Rail(Node a, int dirA, Node b, int dirB, boolean platform, int speed) {
        /** An id for CityWorld's sent set: the two ends, either way round. */
        public long id() {
            long ha = pos(a), hb = pos(b);
            long lo = Math.min(ha, hb), hi = Math.max(ha, hb);
            long h = 0x5241_494CL ^ lo * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(hi, 29);
            h ^= h >>> 31;
            return h == 0 ? 1 : h;
        }

        private static long pos(Node n) {
            return ((long) n.x() & 0x3FFFFFF) << 38 | ((long) n.z() & 0x3FFFFFF) << 12 | n.y() & 0xFFF;
        }

        public JsonObject json() {
            JsonObject r = new JsonObject();
            r.add("position1", position(a));
            r.addProperty("angle1", ANGLES[2 * dirA]);
            r.add("position2", position(b));
            r.addProperty("angle2", ANGLES[(2 * dirB + 8) % 16]); // pointing back along the rail
            r.addProperty("shape", "QUADRATIC");
            r.addProperty("verticalRadius", 0.0);
            r.addProperty("tiltPoints", 0);
            for (String k : new String[] { "tiltAngleDegrees1", "tiltAngleDistance1a", "tiltAngleDegrees1a",
                    "tiltAngleDegrees1b", "tiltAngleDistance1b", "tiltAngleDegreesMiddle", "tiltAngleDistance2b",
                    "tiltAngleDegrees2b", "tiltAngleDegrees2a", "tiltAngleDistance2a", "tiltAngleDegrees2" })
                r.addProperty(k, 0.0);
            // MTR draws a rail only through its styles: an empty list is a working but invisible track
            JsonArray styles = new JsonArray();
            styles.add("default");
            r.add("styles", styles);
            r.addProperty("speedLimit1", speed);
            r.addProperty("speedLimit2", speed);
            r.addProperty("isPlatform", platform);
            r.addProperty("isSiding", false);
            r.addProperty("canAccelerate", true);
            r.addProperty("canTurnBack", false);
            r.addProperty("canConnectRemotely", false);
            r.addProperty("canHaveSignal", true);
            r.add("signalColors", new JsonArray());
            r.addProperty("transportMode", "TRAIN");
            r.addProperty("stylesMigratedLegacy", true);
            return r;
        }

        private static JsonObject position(Node n) {
            JsonObject p = new JsonObject();
            p.addProperty("x", n.x());
            p.addProperty("y", n.y());
            p.addProperty("z", n.z());
            return p;
        }

        boolean touches(int x0, int z0, int x1, int z1) {
            return Math.max(a.x(), b.x()) >= x0 && Math.min(a.x(), b.x()) <= x1 && Math.max(a.z(), b.z()) >= z0
                    && Math.min(a.z(), b.z()) <= z1;
        }
    }

    private static final Map<Railways.Line, List<Rail>> LINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Railways.Hub, List<Rail>> HUBS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Every rail (lines' and stations') with an end or its span in this square of blocks. */
    public static List<Rail> railsTouching(Railways rw, int x0, int z0, int x1, int z1) {
        List<Rail> found = new ArrayList<>();
        for (Railways.Line line : rw.linesTouching(x0, z0, x1, z1))
            for (Rail r : of(rw, line))
                if (r.touches(x0, z0, x1, z1))
                    found.add(r);
        for (Railways.Hub hub : rw.hubsTouching(x0, z0, x1, z1))
            for (Rail r : of(rw, hub))
                if (r.touches(x0, z0, x1, z1))
                    found.add(r);
        return found;
    }

    /** The nodes in this square, one per block (its axis from the first rail that ends there). */
    public static Map<Long, Node> nodesTouching(Railways rw, int x0, int z0, int x1, int z1) {
        Map<Long, Node> nodes = new LinkedHashMap<>();
        for (Rail r : railsTouching(rw, x0, z0, x1, z1))
            for (Node n : new Node[] { r.a(), r.b() })
                if (n.x() >= x0 && n.x() <= x1 && n.z() >= z0 && n.z() <= z1)
                    nodes.putIfAbsent(Rail.pos(n), n);
        return nodes;
    }

    public static List<Rail> of(Railways rw, Railways.Line line) {
        List<Rail> known = LINES.get(line);
        if (known == null) {
            known = Collections.unmodifiableList(plan(rw, line));
            LINES.put(line, known);
        }
        return known;
    }

    public static List<Rail> of(Railways rw, Railways.Hub hub) {
        List<Rail> known = HUBS.get(hub);
        if (known == null) {
            known = Collections.unmodifiableList(plan(rw, hub));
            HUBS.put(hub, known);
        }
        return known;
    }

    // --- geometry ------------------------------------------------------------------------------------------------

    private static double dx(int dir) {
        return Math.round(Math.cos(Math.toRadians(45 * dir)) * 1e9) / 1e9;
    }

    private static double dz(int dir) {
        return Math.round(Math.sin(Math.toRadians(45 * dir)) * 1e9) / 1e9;
    }

    /** A whole step along a direction: one block on each axis it moves on. */
    private static int sx(int dir) {
        return (int) Math.signum(Math.round(dx(dir) * 2));
    }

    private static int sz(int dir) {
        return (int) Math.signum(Math.round(dz(dir) * 2));
    }

    /** Blocks per whole step (1, or √2 on a diagonal). */
    private static double unit(int dir) {
        return dir % 2 == 0 ? 1 : Math.sqrt(2);
    }

    private static int block(double v) {
        return (int) Math.floor(v + 1e-6);
    }

    /** The signs of the tracks across a bed: two tracks, or one on the centre line. */
    private static int[] tracks(Railways rw) {
        return rw.trackOffset() > 0 ? new int[] { -1, 1 } : new int[] { 0 };
    }

    // --- a line ------------------------------------------------------------------------------------------------

    /** One straight run of a line's centre: from {@code (x0, z0)} to {@code (x1, z1)}, heading {@code dir}. */
    private record Run(double x0, double z0, double x1, double z1, int dir) {
    }

    private static List<Rail> plan(Railways rw, Railways.Line line) {
        Highways.Link link = line.link();
        int[] headings = line.headings();
        List<double[]> corners = line.corners();
        if (headings.length == 0 || corners.size() != headings.length)
            return List.of();
        double t = rw.tangent();
        // the centre's runs: from the start to the first bend's tangent point, between bends, to the end
        List<Run> runs = new ArrayList<>();
        double[] cursor = { link.x()[0], link.z()[0] };
        for (int i = 0; i < headings.length; i++) {
            int d = headings[i];
            double[] end = corners.get(i);
            boolean bend = i < headings.length - 1;
            double ex = bend ? end[0] - dx(d) * t : end[0], ez = bend ? end[1] - dz(d) * t : end[1];
            runs.add(new Run(cursor[0], cursor[1], ex, ez, d));
            if (bend) {
                int next = headings[i + 1];
                cursor = new double[] { end[0] + dx(next) * t, end[1] + dz(next) * t };
            }
        }
        // a station end: the first (last) run carries on back along the trunk to the fan's end
        int levelA = levelAt(link, 0), levelB = levelAt(link, link.x().length - 1);
        if (line.offA() == null) {
            double[] fan = fanEnd(rw, link.a(), link.b());
            Run r = runs.get(0);
            if (fan != null && onLine(r, fan, -1)) {
                runs.set(0, new Run(fan[0], fan[1], r.x1(), r.z1(), r.dir()));
                levelA = (int) fan[2];
            }
        }
        if (line.offB() == null) {
            double[] fan = fanEnd(rw, link.b(), link.a());
            Run r = runs.get(runs.size() - 1);
            if (fan != null && onLine(r, fan, 1)) {
                runs.set(runs.size() - 1, new Run(r.x0(), r.z0(), fan[0], fan[1], r.dir()));
                levelB = (int) fan[2];
            }
        }
        List<Rail> rails = new ArrayList<>();
        for (int s : tracks(rw)) {
            double off = s * rw.trackOffset();
            List<List<Node>> nodes = new ArrayList<>();
            for (int i = 0; i < runs.size(); i++)
                nodes.add(runNodes(runs.get(i), off, link, levelA, levelB, i == runs.size() - 1 && runs.size() > 1));
            // a corridor's end: from (to) the line it leaves, at that line's nearest node behind it
            if (line.offA() != null) {
                Node from = branchNode(rw, line.offA(), nodes.get(0).get(0), runs.get(0).dir(), true, s);
                if (from != null)
                    nodes.get(0).set(0, new Node(from.x(), from.y(), from.z(), runs.get(0).dir()));
            }
            if (line.offB() != null) {
                List<Node> last = nodes.get(nodes.size() - 1);
                Node to = branchNode(rw, line.offB(), last.get(last.size() - 1), runs.get(runs.size() - 1).dir(), false, s);
                if (to != null)
                    last.set(last.size() - 1, new Node(to.x(), to.y(), to.z(), runs.get(runs.size() - 1).dir()));
            }
            for (int i = 0; i < nodes.size(); i++) {
                List<Node> run = nodes.get(i);
                int d = runs.get(i).dir();
                for (int k = 0; k + 1 < run.size(); k++)
                    if (!same(run.get(k), run.get(k + 1)))
                        rails.add(new Rail(run.get(k), d, run.get(k + 1), d, false, LINE_SPEED));
                if (i + 1 < nodes.size()) {
                    Node a = run.get(run.size() - 1), b = nodes.get(i + 1).get(0);
                    if (!same(a, b))
                        rails.add(new Rail(a, d, b, runs.get(i + 1).dir(), false, CURVE_SPEED));
                }
            }
        }
        return rails;
    }

    private static boolean same(Node a, Node b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    /** Whether a point lies on a run's line, before ({@code -1}) its start or after ({@code 1}) its end. */
    private static boolean onLine(Run r, double[] p, int where) {
        double ux = dx(r.dir()), uz = dz(r.dir());
        double px = p[0] - r.x0(), pz = p[1] - r.z0();
        double across = -px * uz + pz * ux, along = px * ux + pz * uz;
        double len = (r.x1() - r.x0()) * ux + (r.z1() - r.z0()) * uz;
        return Math.abs(across) < 0.75 && (where < 0 ? along <= 0.5 : along >= len - 0.5);
    }

    /**
     * The nodes of one track along a run: the first at the run's start (offset across to the track), then whole steps
     * along it, a node at most every {@link #SPLIT} blocks and one at its end; each at the line's height there. The last
     * run is laid from its end back ({@code fromEnd}), so a line ending at a station's fan ends on the fan's own node.
     */
    private static List<Node> runNodes(Run r, double off, Highways.Link link, int levelA, int levelB, boolean fromEnd) {
        int d = r.dir();
        double px = -dz(d), pz = dx(d);
        double len = Math.hypot(r.x1() - r.x0(), r.z1() - r.z0());
        int steps = (int) Math.round(len / unit(d));
        int x0 = block(r.x0() + px * off), z0 = block(r.z0() + pz * off);
        if (fromEnd) {
            x0 = block(r.x1() + px * off) - sx(d) * steps;
            z0 = block(r.z1() + pz * off) - sz(d) * steps;
        }
        int per = Math.max(1, (int) Math.floor(SPLIT / unit(d)));
        int pieces = Math.max(1, (int) Math.ceil(steps / (double) per));
        List<Node> nodes = new ArrayList<>();
        for (int k = 0; k <= pieces; k++) {
            int m = (int) Math.round(steps * (double) k / pieces);
            double cx = r.x0() + dx(d) * unit(d) * m, cz = r.z0() + dz(d) * unit(d) * m;
            int y = heightAt(link, cx, cz, levelA, levelB) + 1;
            nodes.add(new Node(x0 + sx(d) * m, y, z0 + sz(d) * m, d));
        }
        return nodes;
    }

    /** The line's deck at a point of its centre (a station's level past its ends). */
    private static int heightAt(Highways.Link link, double x, double z, int levelA, int levelB) {
        var near = Highways.nearest(List.of(link), x, z, 3);
        if (near != null)
            return (int) Math.floor(near.height());
        int n = link.x().length;
        double da = Math.hypot(x - link.x()[0], z - link.z()[0]), db = Math.hypot(x - link.x()[n - 1], z - link.z()[n - 1]);
        return da <= db ? levelA : levelB;
    }

    private static int levelAt(Highways.Link link, int i) {
        return (int) Math.floor(link.h()[i]);
    }

    /** Where this city's station's fan meets its trunk, toward the line to {@code other}: {x, z, level}; null if none. */
    private static double[] fanEnd(Railways rw, CitySites.Site self, CitySites.Site other) {
        Railways.Hub hub = rw.hubOf(self);
        if (hub == null)
            return null;
        Railways.Attach at = hub.attach().get(Railways.key(other));
        if (at == null)
            return null;
        double a = at.end() * hub.fanEnd();
        return new double[] { hub.x(a, 0), hub.z(a, 0), hub.level() };
    }

    /**
     * The node on the same track of the line a corridor leaves, nearest to the corridor's own end node and not past it
     * (behind its start, or beyond its end, along the corridor's heading); null where that line is not found.
     */
    private static Node branchNode(Railways rw, Highways.Link off, Node own, int dir, boolean start, int s) {
        Railways.Line parent = null;
        for (Railways.Line l : rw.linesTouching(own.x() - 4, own.z() - 4, own.x() + 4, own.z() + 4))
            if (Railways.key(l.link().a()) == Railways.key(off.a()) && Railways.key(l.link().b()) == Railways.key(off.b()))
                parent = l;
        if (parent == null)
            return null;
        double ux = dx(dir), uz = dz(dir);
        Node best = null;
        double bestD = Double.MAX_VALUE;
        for (Rail r : of(rw, parent))
            for (Node n : new Node[] { r.a(), r.b() }) {
                double along = (n.x() - own.x()) * ux + (n.z() - own.z()) * uz;
                if (start ? along > 0.5 : along < -0.5)
                    continue;
                double d = Math.hypot(n.x() - own.x(), n.z() - own.z());
                // the same track: across, the node is where the corridor's own track is
                double across = Math.abs(-(n.x() - own.x()) * uz + (n.z() - own.z()) * ux);
                if (across <= 1.5 && d < bestD) {
                    best = n;
                    bestD = d;
                }
            }
        return bestD <= SPLIT + 4 ? best : null;
    }

    // --- a station ---------------------------------------------------------------------------------------------

    private static List<Rail> plan(Railways rw, Railways.Hub hub) {
        List<Rail> rails = new ArrayList<>();
        int y = hub.level() + 1, d = hub.dir();
        double pe = hub.platformEnd(), off = rw.trackOffset();
        for (int j = 0; j < hub.pairs(); j++)
            for (int s : tracks(rw)) {
                double c = hub.pairCentre(j) + s * off;
                // the platform rail, whole steps from its first end
                int ax = block(hub.x(-pe, c)), az = block(hub.z(-pe, c));
                int steps = (int) Math.round(2 * pe / unit(d));
                Node minus = new Node(ax, y, az, d), plus = new Node(ax + sx(d) * steps, y, az + sz(d) * steps, d);
                rails.add(new Rail(minus, d, plus, d, true, STATION_SPEED));
                // through the fan to the trunk's track on this side, at each end a line leaves
                for (int end : new int[] { 1, -1 }) {
                    if (hub.trunk(end) <= 0)
                        continue;
                    // platforms right of the station's centre line join the main line's right track, those left
                    // of it the left: directional platforms, and no track crosses another in the throat
                    int side = s == 0 ? 0 : c > 0.5 ? 1 : c < -0.5 ? -1 : s;
                    double a = end * hub.fanEnd(), tc = side * off;
                    int tx = block(hub.x(a, tc)), tz = block(hub.z(a, tc));
                    // the trunk node is the line's: on the line's own lattice from its run's start, which is this point
                    Node trunk = new Node(tx, y, tz, d);
                    Node from = end > 0 ? plus : minus;
                    int out = end > 0 ? d : (d + 4) % 8;
                    if (!same(from, trunk))
                        rails.add(new Rail(from, out, trunk, out, false, STATION_SPEED));
                }
            }
        return rails;
    }

    // --- in the world --------------------------------------------------------------------------------------------

    /**
     * After decoration: MTR's node blocks in this chunk where rails meet, each on its rail's axis, in an empty cell
     * just over the bed (a node where something stands is left out: the rail is MTR's data either way).
     */
    public static void placeNodes(Railways rw, net.minecraft.world.level.WorldGenLevel level,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (!MtrStations.enabled())
            return;
        var pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ();
        MtrStations.offerRails(level, railsTouching(rw, x0, z0, x0 + 15, z0 + 15));
        var cur = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (Node n : nodesTouching(rw, x0, z0, x0 + 15, z0 + 15).values()) {
            var state = MtrBlocks.railNode(n.axis());
            if (state == null)
                return;
            cur.set(n.x(), n.y(), n.z());
            var there = level.getBlockState(cur);
            if (there.isAir() || there.canBeReplaced() && there.getFluidState().isEmpty())
                level.setBlock(cur, state, 2);
        }
    }
}
