package me.daddychurchill.CityWorld.integration.mtr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import me.daddychurchill.CityWorld.worldgen.CitySites;
import me.daddychurchill.CityWorld.worldgen.Railways;

/**
 * The train services over CityWorld's railway lines: multi-station routes, a stopping train and a fast one.
 * <p>
 * At each station the lines are paired: first across it (a line leaving one end with a line leaving the other,
 * straightest first), so a train runs on through; then the lines left over with each other, a train calling there and
 * turning back at its platform. Following the pairs from station to station makes long runs ("strokes"), each line on
 * exactly one. A stroke is cut evenly into {@link Kind#LOCAL} services of about {@link #LOCAL_LINES} lines, calling at
 * every station, and into {@link Kind#INTERCITY} services of up to {@link #INTERCITY_LINES} lines, calling only at their
 * ends, the big cities and where they turn back. Every rule reads the plan alone, so any thread planning any chunk, in
 * any order, gets the same services.
 */
public final class MtrServices {
    private MtrServices() {
    }

    public enum Kind {
        /** Calls everywhere ("Local", the stopping train). */
        LOCAL,
        /** Calls at its ends, the big cities and where it turns back ("Intercity"). */
        INTERCITY
    }

    /**
     * One station on a service: the end of the station the train arrives by and leaves by (0: it starts or ends here;
     * the same end both ways: it turns back here), and whether it calls.
     */
    public record Call(Railways.Hub hub, int in, int out, boolean stops) {
        public boolean turnsBack() {
            return in != 0 && in == out;
        }
    }

    /** A service, its calls in its canonical direction. */
    public record Service(Kind kind, List<Call> calls, long id) {
        public Railways.Hub first() {
            return calls.get(0).hub();
        }

        public Railways.Hub last() {
            return calls.get(calls.size() - 1).hub();
        }

        public List<Call> stops() {
            List<Call> s = new ArrayList<>();
            for (Call c : calls)
                if (c.stops())
                    s.add(c);
            return s;
        }
    }

    /** A stopping service runs over about this many lines (a stroke is cut evenly). */
    static final int LOCAL_LINES = 5;
    /** An Intercity runs over at most this many. */
    static final int INTERCITY_LINES = 14;
    /** The Intercity calls at cities at least this big, or this big with at least this many lines. */
    static final int BIG_RADIUS = 480, JUNCTION_RADIUS = 400, JUNCTION_LINES = 4;
    /** No stroke runs through more stations than this. */
    static final int MAX_STATIONS = 64;

    private static final Map<Railways.Hub, Map<Long, Long>> PAIRS = Collections.synchronizedMap(new WeakHashMap<>());

    /** A partner's key -> the partner a train arriving from it carries on to, at this station. */
    static Map<Long, Long> pairing(Railways rw, Railways.Hub hub) {
        Map<Long, Long> known = PAIRS.get(hub);
        if (known != null)
            return known;
        CitySites.Site site = hub.site();
        List<CitySites.Site> partners = rw.partnersOf(site);
        record Option(long p, long m, boolean through, double bend) {
        }
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < partners.size(); i++)
            for (int j = i + 1; j < partners.size(); j++) {
                CitySites.Site p = partners.get(i), m = partners.get(j);
                Railways.Attach ap = hub.attach().get(Railways.key(p)), am = hub.attach().get(Railways.key(m));
                if (ap == null || am == null)
                    continue;
                double[] bp = bearing(site, p), bm = bearing(site, m);
                // 0 for dead straight on, up to 180 for a train sent back the way it came
                double bend = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -(bp[0] * bm[0] + bp[1] * bm[1])))));
                long kp = Railways.key(p), km = Railways.key(m);
                options.add(new Option(Math.min(kp, km), Math.max(kp, km), ap.end() == -am.end(), bend));
            }
        // through pairs first, straightest first; then the rest (a train turning back), straightest first
        options.sort(java.util.Comparator.comparing((Option o) -> !o.through()).thenComparingDouble(Option::bend)
                .thenComparingLong(Option::p).thenComparingLong(Option::m));
        Map<Long, Long> pairs = new HashMap<>();
        for (Option o : options)
            if (!pairs.containsKey(o.p()) && !pairs.containsKey(o.m())) {
                pairs.put(o.p(), o.m());
                pairs.put(o.m(), o.p());
            }
        Map<Long, Long> made = Collections.unmodifiableMap(pairs);
        PAIRS.put(hub, made);
        return made;
    }

    private static double[] bearing(CitySites.Site from, CitySites.Site to) {
        double x = to.centreX() - from.centreX(), z = to.centreZ() - from.centreZ(), l = Math.max(1, Math.hypot(x, z));
        return new double[] { x / l, z / l };
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /** Whether the Intercity calls here when passing through. */
    static boolean big(Railways rw, Railways.Hub hub) {
        int r = hub.site().radius();
        return r >= BIG_RADIUS || r >= JUNCTION_RADIUS && rw.partnersOf(hub.site()).size() >= JUNCTION_LINES;
    }

    private static CitySites.Site partner(Railways rw, CitySites.Site at, long key) {
        for (CitySites.Site p : rw.partnersOf(at))
            if (Railways.key(p) == key)
                return p;
        return null;
    }

    /**
     * The stations from {@code at} onward, arriving from {@code from}, following the pairs until a station with no
     * partner for the line or one already in {@code seen} (a ring: the walk ends where it began).
     */
    private static List<CitySites.Site> walk(Railways rw, CitySites.Site from, CitySites.Site at, Set<Long> seen) {
        List<CitySites.Site> out = new ArrayList<>();
        CitySites.Site prev = from, cur = at;
        while (out.size() < MAX_STATIONS) {
            if (!seen.add(Railways.key(cur)))
                break;
            out.add(cur);
            Railways.Hub hub = rw.hubOf(cur);
            if (hub == null)
                break;
            Long next = pairing(rw, hub).get(Railways.key(prev));
            CitySites.Site nextSite = next == null ? null : partner(rw, cur, next);
            if (nextSite == null)
                break;
            prev = cur;
            cur = nextSite;
        }
        return out;
    }

    /** The stroke through the line between a and b: its stations in canonical order (the smaller end key first). */
    static List<CitySites.Site> stroke(Railways rw, CitySites.Site a, CitySites.Site b) {
        Set<Long> seen = new HashSet<>();
        List<CitySites.Site> back = walk(rw, b, a, seen);
        List<CitySites.Site> fwd = walk(rw, a, b, seen);
        List<CitySites.Site> sites = new ArrayList<>(back);
        Collections.reverse(sites);
        sites.addAll(fwd);
        int n = sites.size();
        if (n >= 3 && next(rw, sites.get(n - 2), sites.get(n - 1)) == Railways.key(sites.get(0))
                && next(rw, sites.get(1), sites.get(0)) == Railways.key(sites.get(n - 1))) {
            // a ring: from its smallest city, toward the smaller of that city's two neighbours on it, round to it again
            int m = 0;
            for (int i = 1; i < n; i++)
                if (Railways.key(sites.get(i)) < Railways.key(sites.get(m)))
                    m = i;
            List<CitySites.Site> ring = new ArrayList<>();
            for (int i = 0; i < n; i++)
                ring.add(sites.get((m + i) % n));
            if (Railways.key(ring.get(1)) > Railways.key(ring.get(n - 1))) {
                Collections.reverse(ring);
                ring.add(0, ring.remove(n - 1));
            }
            ring.add(ring.get(0));
            return ring;
        }
        if (n >= 2 && Railways.key(sites.get(0)) > Railways.key(sites.get(n - 1)))
            Collections.reverse(sites);
        return sites;
    }

    /** The partner key a train arriving at {@code at} from {@code from} carries on to; 0 for none. */
    private static long next(Railways rw, CitySites.Site from, CitySites.Site at) {
        Railways.Hub hub = rw.hubOf(at);
        Long k = hub == null ? null : pairing(rw, hub).get(Railways.key(from));
        return k == null ? 0 : k;
    }

    /**
     * The service of this kind over the line between {@code a} and {@code b}: null for an Intercity that would call at
     * fewer than three stations, or everywhere (that stretch has only its stopping trains).
     */
    public static Service serviceOn(Railways rw, CitySites.Site a, CitySites.Site b, Kind kind) {
        List<CitySites.Site> sites = stroke(rw, a, b);
        int n = sites.size() - 1; // lines
        if (n < 1)
            return null;
        int at = -1;
        for (int i = 0; i < n; i++) {
            long k0 = Railways.key(sites.get(i)), k1 = Railways.key(sites.get(i + 1));
            if (k0 == Railways.key(a) && k1 == Railways.key(b) || k0 == Railways.key(b) && k1 == Railways.key(a))
                at = i;
        }
        if (at < 0)
            return null;
        // cut evenly: k pieces, piece p over lines [round(p n / k), round((p + 1) n / k))
        int k = (int) Math.ceil(n / (double) (kind == Kind.LOCAL ? LOCAL_LINES : INTERCITY_LINES));
        int from = 0, to = n;
        for (int p = 0; p < k; p++) {
            int s = (int) Math.round(p * (double) n / k), e = (int) Math.round((p + 1) * (double) n / k);
            if (at >= s && at < e) {
                from = s;
                to = e;
            }
        }
        List<Call> calls = new ArrayList<>();
        int stops = 0;
        for (int i = from; i <= to; i++) {
            Railways.Hub hub = rw.hubOf(sites.get(i));
            if (hub == null)
                return null;
            int in = i == from ? 0 : endTo(hub, sites.get(i - 1)), out = i == to ? 0 : endTo(hub, sites.get(i + 1));
            boolean stop = kind == Kind.LOCAL || i == from || i == to || (in != 0 && in == out) || big(rw, hub);
            calls.add(new Call(hub, in, out, stop));
            if (stop)
                stops++;
        }
        if (kind == Kind.INTERCITY && (stops < 3 || stops == calls.size()))
            return null;
        long id = 0x5357_4943L ^ (kind.ordinal() + 1) * 0x9E3779B97F4A7C15L;
        for (Call c : calls)
            id = mix(id ^ Railways.key(c.hub().site()));
        return new Service(kind, Collections.unmodifiableList(calls), id == 0 ? 1 : id);
    }

    private static int endTo(Railways.Hub hub, CitySites.Site partner) {
        Railways.Attach at = hub.attach().get(Railways.key(partner));
        return at == null ? 0 : at.end();
    }

    /** Every service (both kinds) running over any of these lines, each once, in a stable order. */
    public static List<Service> servicesOn(Railways rw, List<Railways.Line> lines) {
        Map<Long, Service> found = new LinkedHashMap<>();
        for (Railways.Line line : lines)
            for (Kind kind : Kind.values()) {
                Service s = serviceOn(rw, line.link().a(), line.link().b(), kind);
                if (s != null)
                    found.putIfAbsent(s.id(), s);
            }
        List<Service> list = new ArrayList<>(found.values());
        list.sort(java.util.Comparator.comparingLong(Service::id));
        return list;
    }
}
