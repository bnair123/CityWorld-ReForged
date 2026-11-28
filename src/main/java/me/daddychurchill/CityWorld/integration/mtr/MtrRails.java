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
    static final int LINE_SPEED = 300, CURVE_SPEED = 80, STATION_SPEED = 80, DEPOT_SPEED = 40;
    private static final String[] ANGLES = { "E", "SEE", "SE", "SSE", "S", "SSW", "SW", "SWW", "W", "NWW", "NW", "NNW",
            "N", "NNE", "NE", "NEE" };

    /** A node: a block position and the compass direction (0-7, as {@code Railways.DIRS}) a train runs through it. */
    public record Node(int x, int y, int z, int dir) {
        /** The axis as MTR's node block reads it, in degrees: 0, 45, 90 or 135. */
        public int axis() {
            return Math.floorMod(dir, 4) * 45;
        }
    }

    /**
     * One rail: from {@code a}, leaving it in {@code dirA}, to {@code b}, arriving in {@code dirB}; {@code speed} km/h
     * from a to b, {@code back} from b to a (0: one way, MTR finds no path against it). {@code turnBack}: a train may
     * reverse on it (MTR's turn-back rail: the metro's terminal platforms and sidings, where its shuttles reverse).
     */
    public record Rail(Node a, int dirA, Node b, int dirB, boolean platform, boolean siding, int speed, int back,
            boolean turnBack, boolean cable) {
        public Rail(Node a, int dirA, Node b, int dirB, boolean platform, boolean siding, int speed, int back,
                boolean turnBack) {
            this(a, dirA, b, dirB, platform, siding, speed, back, turnBack, false);
        }

        public Rail(Node a, int dirA, Node b, int dirB, boolean platform, boolean siding, int speed, int back) {
            this(a, dirA, b, dirB, platform, siding, speed, back, false);
        }

        public Rail(Node a, int dirA, Node b, int dirB, boolean platform, boolean siding, int speed) {
            this(a, dirA, b, dirB, platform, siding, speed, speed);
        }

        public Rail(Node a, int dirA, Node b, int dirB, boolean platform, int speed) {
            this(a, dirA, b, dirB, platform, false, speed, speed);
        }

        /** A rail only a to b. */
        static Rail oneWay(Node a, int dirA, Node b, int dirB, int speed) {
            return new Rail(a, dirA, b, dirB, false, false, speed, 0);
        }

        /** A rail only b to a. */
        static Rail oneWayBack(Node a, int dirA, Node b, int dirB, int speed) {
            return new Rail(a, dirA, b, dirB, false, false, 0, speed);
        }

        /** Whether a train may run from a to b. */
        public boolean forward() {
            return speed > 0;
        }

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
            // a cable car's cable: MTR draws it straight between its nodes, whatever their angles say
            r.addProperty("shape", cable ? "CABLE" : "QUADRATIC");
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
            r.addProperty("speedLimit2", back);
            r.addProperty("isPlatform", platform);
            r.addProperty("isSiding", siding);
            r.addProperty("canAccelerate", true);
            r.addProperty("canTurnBack", turnBack);
            r.addProperty("canConnectRemotely", false);
            r.addProperty("canHaveSignal", true);
            r.add("signalColors", new JsonArray());
            r.addProperty("transportMode", cable ? "CABLE_CAR" : "TRAIN");
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

        public boolean touches(int x0, int z0, int x1, int z1) {
            return Math.max(a.x(), b.x()) >= x0 && Math.min(a.x(), b.x()) <= x1 && Math.max(a.z(), b.z()) >= z0
                    && Math.min(a.z(), b.z()) <= z1;
        }
    }

    private static final Map<Railways.Line, List<Rail>> LINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Railways.Hub, List<Rail>> HUBS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Railways.Depot, List<Rail>> DEPOTS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * How far past its own samples a line's rails may reach: at a station end it carries on along the trunk from its
     * junction back to the fan's end, which its link (and so {@code linesTouching}) does not cover.
     */
    private static final int LINE_REACH = 192;

    /** Every rail (lines' and stations') with an end or its span in this square of blocks. */
    public static List<Rail> railsTouching(Railways rw, int x0, int z0, int x1, int z1) {
        List<Rail> found = new ArrayList<>();
        for (Railways.Line line : rw.linesTouching(x0 - LINE_REACH, z0 - LINE_REACH, x1 + LINE_REACH, z1 + LINE_REACH))
            for (Rail r : of(rw, line))
                if (r.touches(x0, z0, x1, z1))
                    found.add(r);
        for (Railways.Hub hub : rw.hubsTouching(x0, z0, x1, z1))
            for (Rail r : of(rw, hub))
                if (r.touches(x0, z0, x1, z1))
                    found.add(r);
        for (Railways.Depot depot : rw.depotsTouching(x0, z0, x1, z1))
            for (Rail r : of(rw, depot))
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

    public static List<Rail> of(Railways rw, Railways.Depot depot) {
        List<Rail> known = DEPOTS.get(depot);
        if (known == null) {
            known = Collections.unmodifiableList(plan(rw, depot));
            DEPOTS.put(depot, known);
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
            // right-hand running: the track right of A-to-B travel (s = 1) carries A to B only, the other B to A
            // only, so no train runs against the traffic (one track: both ways)
            int fwd = s >= 0 ? 1 : 0, bwd = s <= 0 ? 1 : 0;
            for (int i = 0; i < nodes.size(); i++) {
                List<Node> run = nodes.get(i);
                int d = runs.get(i).dir();
                for (int k = 0; k + 1 < run.size(); k++)
                    if (!same(run.get(k), run.get(k + 1)))
                        rails.add(new Rail(run.get(k), d, run.get(k + 1), d, false, false, LINE_SPEED * fwd, LINE_SPEED * bwd));
                if (i + 1 < nodes.size()) {
                    Node a = run.get(run.size() - 1), b = nodes.get(i + 1).get(0);
                    if (!same(a, b))
                        rails.add(new Rail(a, d, b, runs.get(i + 1).dir(), false, false, CURVE_SPEED * fwd, CURVE_SPEED * bwd));
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
        // the innermost platform each side of the centre line: the one every service stops at (pickPlatform)
        double innerRight = Double.MAX_VALUE, innerLeft = -Double.MAX_VALUE;
        for (int j = 0; j < hub.pairs(); j++)
            for (int s : tracks(rw)) {
                double c = hub.pairCentre(j) + s * off;
                if (c > 0)
                    innerRight = Math.min(innerRight, c);
                else if (c < 0)
                    innerLeft = Math.max(innerLeft, c);
            }
        for (int j = 0; j < hub.pairs(); j++)
            for (int s : tracks(rw)) {
                double c = hub.pairCentre(j) + s * off;
                // the platform rail, whole steps from its first end
                int ax = block(hub.x(-pe, c)), az = block(hub.z(-pe, c));
                int steps = (int) Math.round(2 * pe / unit(d));
                Node minus = new Node(ax, y, az, d), plus = new Node(ax + sx(d) * steps, y, az + sz(d) * steps, d);
                rails.add(new Rail(minus, d, plus, d, true, STATION_SPEED));
                // through the fan, at each end a line leaves: each platform joins the main line's track on its own side
                // of the centre line (owner: right platforms to the right track, left to the left; nothing crosses in
                // the throat). Right-hand running: leaving by end e a train runs e * u, on the track whose
                // across * side * e > 0, so a side's platforms are one direction's. A train that starts, ends or
                // turns back here stops at the innermost platform on its arrival side (pickPlatform) and leaves the
                // way it came: only those two platforms also get a rail out onto the other track, one X per end.
                boolean inner = c == innerRight || c == innerLeft;
                int sideOf = c > 0 ? 1 : -1;
                for (int end : new int[] { 1, -1 }) {
                    if (hub.trunk(end) <= 0)
                        continue;
                    double a = end * hub.fanEnd();
                    Node from = end > 0 ? plus : minus;
                    int out = end > 0 ? d : (d + 4) % 8;
                    if (off <= 0) {
                        // one track: the trunk is the centre line, both ways
                        Node trunk = new Node(block(hub.x(a, 0)), y, block(hub.z(a, 0)), d);
                        if (!same(from, trunk))
                            rails.add(new Rail(from, out, trunk, out, false, STATION_SPEED));
                        continue;
                    }
                    for (int way : new int[] { 1, -1 }) {
                        // way 1: outbound (platform to trunk), -1: inbound (trunk to platform)
                        double tc = way * end * hub.side() * off;
                        boolean ownSide = tc * sideOf > 0;
                        // the other side's track: only the inner platforms, and only outbound (a turn-back leaving)
                        if (!ownSide && !(inner && way > 0))
                            continue;
                        // the trunk node is the line's: on the line's own lattice from its run's start, this point
                        Node trunk = new Node(block(hub.x(a, tc)), y, block(hub.z(a, tc)), d);
                        if (same(from, trunk))
                            continue;
                        rails.add(way > 0 ? Rail.oneWay(from, out, trunk, out, STATION_SPEED)
                                : Rail.oneWayBack(from, out, trunk, out, STATION_SPEED));
                    }
                }
            }
        return rails;
    }

    // --- a depot ------------------------------------------------------------------------------------------------

    /**
     * A depot beside a line: a siding along each stabling road the length of the yard, and at each end a rail from
     * every siding through the lead to the main line's track nearest the yard, at that track's first node past the
     * lead (so the depot joins the line where its rails already meet: a switch with nothing to lay).
     */
    private static List<Rail> plan(Railways rw, Railways.Depot depot) {
        List<Rail> rails = new ArrayList<>();
        int d = depot.dir(), y = depot.level() + 1;
        double body = Railways.DEPOT_LENGTH / 2.0, lead = body + Railways.DEPOT_LEAD, off = rw.trackOffset();
        // each track's nodes past each end's lead ([track: 0 near, 1 far][end: 0 plus, 1 minus]), and which way along
        // the yard each track runs (+1, -1; 0 both ways)
        Node[][] junction = new Node[2][2];
        double[][] best = { { Double.MAX_VALUE, Double.MAX_VALUE }, { Double.MAX_VALUE, Double.MAX_VALUE } };
        int[] runs = new int[2];
        for (Rail r : of(rw, depot.line()))
            for (int t = 0; t < 2; t++) {
                double want = t == 0 ? off : -off;
                double ca = depot.across(r.a().x() + 0.5, r.a().z() + 0.5), cb = depot.across(r.b().x() + 0.5, r.b().z() + 0.5);
                if (Math.abs(ca - want) > 1.5 || Math.abs(cb - want) > 1.5)
                    continue;
                if (r.speed() > 0 != r.back() > 0) {
                    double way = depot.along(r.b().x() + 0.5, r.b().z() + 0.5) - depot.along(r.a().x() + 0.5, r.a().z() + 0.5);
                    if (Math.abs(way) > 0.5)
                        runs[t] = (way > 0) == r.forward() ? 1 : -1;
                }
                for (Node n : new Node[] { r.a(), r.b() }) {
                    if (Math.floorMod(n.dir() - d, 4) != 0)
                        continue;
                    double a = depot.along(n.x() + 0.5, n.z() + 0.5);
                    for (int e = 0; e < 2; e++) {
                        double past = (e == 0 ? a : -a) - lead;
                        if (past >= 0 && past < best[t][e]) {
                            best[t][e] = past;
                            junction[t][e] = n;
                        }
                    }
                }
            }
        if (off <= 0)
            runs[0] = runs[1] = 0;
        for (double roadC = depot.inner() + 3; roadC <= depot.outer() - 3; roadC += Railways.DEPOT_ROAD) {
            Node plus = new Node(block(depot.x(body, roadC)), y, block(depot.z(body, roadC)), d);
            Node minus = new Node(block(depot.x(-body, roadC)), y, block(depot.z(-body, roadC)), d);
            rails.add(new Rail(minus, d, plus, d, false, true, DEPOT_SPEED));
            for (int e = 0; e < 2; e++) {
                Node from = e == 0 ? plus : minus;
                int out = e == 0 ? d : (d + 4) % 8, away = e == 0 ? 1 : -1;
                for (int t = 0; t < (off > 0 ? 2 : 1); t++) {
                    Node to = junction[t][e];
                    if (to == null)
                        continue;
                    Node node = new Node(to.x(), to.y(), to.z(), d);
                    if (runs[t] == 0)
                        rails.add(new Rail(from, out, node, out, false, DEPOT_SPEED));
                    else if (runs[t] == away) // out of the yard onto a track running away from it
                        rails.add(Rail.oneWay(from, out, node, out, DEPOT_SPEED));
                    else // in from a track running toward it
                        rails.add(Rail.oneWayBack(from, out, node, out, DEPOT_SPEED));
                }
            }
        }
        return rails;
    }

    // --- in the world --------------------------------------------------------------------------------------------

    /** km/h: a cable car's cabins, all the way round (they move with the cable). */
    static final int CABLE_SPEED = 20;
    /** The longest rail along a cable (blocks): a cabin's signal block. */
    static final int CABLE_PIECE = 32;

    /**
     * A cable car's cables as MTR rails, all one way round: the up cable from the base station's platform over each
     * pylon to the summit, round the summit's loop past its platform, down the other cable and round the base's loop to
     * its platform again; the base's siding (where the cabins are kept) off the far node of its loop. A cable is drawn
     * straight whatever its nodes' angles, so every rail carries the line's own heading and MTR's path finder (which
     * only follows a rail whose start angle meets the last one's end) goes round the loops as it would a straight.
     */
    public static List<Rail> cableRails(me.daddychurchill.CityWorld.worldgen.CableCars.Car car) {
        int d = car.dx() == 1 ? 0 : car.dz() == 1 ? 2 : car.dx() == -1 ? 4 : 6;
        int g = me.daddychurchill.CityWorld.worldgen.CableCars.GAUGE, len = car.length();
        int yb = car.baseNode(), yt = car.topNode();
        List<int[]> path = new ArrayList<>();
        path.add(new int[] { -8, g, yb });
        path.add(new int[] { 0, g, yb });
        for (var p : car.pylons())
            path.add(new int[] { p.u(), g, p.cable() });
        path.add(new int[] { len, g, yt });
        path.add(new int[] { len + 8, g, yt });
        path.add(new int[] { len + 11, 0, yt });
        int summitPlatform = path.size(); // (len + 8, -g) -> (len, -g)
        path.add(new int[] { len + 8, -g, yt });
        path.add(new int[] { len, -g, yt });
        for (int i = car.pylons().size() - 1; i >= 0; i--) {
            var p = car.pylons().get(i);
            path.add(new int[] { p.u(), -g, p.cable() });
        }
        path.add(new int[] { 0, -g, yb });
        path.add(new int[] { -8, -g, yb });
        path.add(new int[] { me.daddychurchill.CityWorld.worldgen.CableCarDrawing.LOOP_W, 0, yb });
        // a long span cut into short rails between nodes in the air: MTR lets a cabin onto a rail only when it is clear,
        // so one rail per span would have just one cabin on the line at a time
        List<Node> ring = new ArrayList<>();
        List<Boolean> platforms = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            int[] q = path.get(i), r = path.get((i + 1) % path.size());
            ring.add(new Node(car.x(q[0], q[1]), q[2], car.z(q[0], q[1]), d));
            platforms.add(i == 0 || i == summitPlatform);
            int run = Math.abs(r[0] - q[0]);
            int pieces = r[1] == q[1] ? (run + CABLE_PIECE - 1) / CABLE_PIECE : 1;
            for (int k = 1; k < pieces; k++) {
                int u = q[0] + (r[0] - q[0]) * k / pieces;
                int y = (int) Math.round(q[2] + (r[2] - q[2]) * k / (double) pieces);
                ring.add(new Node(car.x(u, q[1]), y, car.z(u, q[1]), d));
                platforms.add(false);
            }
        }
        List<Rail> rails = new ArrayList<>();
        for (int i = 0; i < ring.size(); i++) {
            Node a = ring.get(i), b = ring.get((i + 1) % ring.size());
            rails.add(new Rail(a, d, b, d, platforms.get(i), false, CABLE_SPEED, 0, false, true));
        }
        // the siding: from the loop's far node to its end, its angle at the loop the other way (a cabin leaving it
        // meets the loop head on; one entering turns back onto it)
        Node far = ring.get(ring.size() - 1);
        Node end = new Node(car.x(me.daddychurchill.CityWorld.worldgen.CableCarDrawing.SIDING_W, 0), yb, car.z(me.daddychurchill.CityWorld.worldgen.CableCarDrawing.SIDING_W, 0), d);
        rails.add(new Rail(far, (d + 4) % 8, end, (d + 4) % 8, false, true, CABLE_SPEED, CABLE_SPEED, true, true));
        return rails;
    }

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
            // a slab is the deck's half-block ramp on a grade: the node takes its place
            if (there.isAir() || there.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                    || there.canBeReplaced() && there.getFluidState().isEmpty())
                level.setBlock(cur, state, 2);
        }
    }
}
