package me.daddychurchill.CityWorld.integration.mtr;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.daddychurchill.CityWorld.CityWorldMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;

/**
 * CityWorld's railway stations as MTR (Minecraft Transit Railway) stations: each station CityWorld draws becomes a
 * named MTR station area over its yard, so a player lays platform rail along the tracks (the "Platform N" signs say
 * which number is which) and builds routes from stations that already have their names.
 * <p>
 * Optional and reflective: nothing here names an MTR class at compile time, so CityWorld builds and runs without MTR,
 * and an MTR beta that moves a class costs a log line, not a crash. The path is MTR's own public message queue
 * ({@code MTR.sendMessageC2S}), never its files or its private simulators.
 * <p>
 * Worldgen only queues ({@link #offer}, any thread). The server thread flushes the queue once a second: it lists what
 * MTR already has in that dimension, leaves out any station whose id exists or whose middle a station (a player's)
 * already covers, and sends the rest in one update. Ids are a pure function of the station's yard, and every id sent
 * is written to {@code data/cityworld_mtr_offered.txt}, so a station the player renamed keeps its name and one they
 * deleted stays deleted.
 */
public final class MtrStations {
    private MtrStations() {
    }

    /** A station waiting for the server thread. */
    private record Offer(ResourceKey<Level> dimension, long id, String name, int minX, int minZ, int maxX, int maxZ,
            boolean depot, String mode) {
        Offer(ResourceKey<Level> dimension, long id, String name, int minX, int minZ, int maxX, int maxZ, boolean depot) {
            this(dimension, id, name, minX, minZ, maxX, maxZ, depot, "TRAIN");
        }

        int midX() {
            return Math.floorDiv(minX + maxX, 2);
        }

        int midZ() {
            return Math.floorDiv(minZ + maxZ, 2);
        }
    }

    private static final int BATCH = 32;
    private static final int TIMEOUT_TICKS = 200;
    private static final int[] COLOURS = { 0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0x00897B, 0xFDD835,
            0x6D4C41, 0x3949AB, 0xD81B60 };

    private static final ConcurrentLinkedQueue<Offer> QUEUE = new ConcurrentLinkedQueue<>();
    /** Rails waiting for the server thread ({@link MtrRails}), and the ids queued (so a scan adds each once). */
    private record RailOffer(ResourceKey<Level> dimension, MtrRails.Rail rail) {
    }
    private static final ConcurrentLinkedQueue<RailOffer> RAILS = new ConcurrentLinkedQueue<>();
    private static final Set<Long> railsQueued = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int RAIL_BATCH = 256;
    /**
     * A depot waiting for its routes: the services it is home to (see {@link #homeDepot}), each with its stations'
     * names. Once MTR has platforms at every station a service calls at and sidings in the yard, each service gets a
     * route each way and its own share of the sidings.
     */
    private record RouteJob(ResourceKey<Level> dimension, long depotId, me.daddychurchill.CityWorld.worldgen.Railways.Depot depot, String city, List<Run> runs,
            int headway, me.daddychurchill.CityWorld.worldgen.CityWorldSettingsData.Trains trains) {
        long key() {
            return routeKey(depotId);
        }
    }

    /** A service and its calls' station names (in call order). */
    private record Run(MtrServices.Service service, List<String> names) {
        String first() {
            return names.get(0);
        }

        String last() {
            return names.get(names.size() - 1);
        }

        String label() {
            return (service.kind() == MtrServices.Kind.LOCAL ? "Local" : "Intercity") + ": " + first() + " - " + last();
        }
    }

    /** The MTR depot of share {@code share} of a yard (the yard is split between its services); 0 is the yard's own. */
    private static long splitDepot(long depotId, int share) {
        if (share == 0)
            return depotId;
        long k = (depotId ^ 0x53_50_4C_49_54L ^ share * 0x9E3779B97F4A7C15L) * 0xD6E8FEB86659FD93L;
        k ^= k >>> 32;
        return k == 0 ? share + 1 : k;
    }

    /** The id under which "this depot's routes were made" is remembered (never a station's, depot's or rail's). */
    private static long routeKey(long depotId) {
        long k = depotId * 0x9E3779B97F4A7C15L ^ 0x52_4F_55_54_45L;
        return k == 0 ? 1 : k;
    }

    /** The id of a depot's route {@code n} (1 there, 2 back): a pure function of the depot, so CityWorld knows its own. */
    private static long routeId(long depotId, int n) {
        long k = (routeKey(depotId) + n) * 0xBF58476D1CE4E5B9L;
        return k == 0 ? n : k;
    }

    // --- the cable cars --------------------------------------------------------------------------------

    /** A cable car waiting for MTR: its two platforms and its depot's siding. */
    private record CableJob(ResourceKey<Level> dimension, me.daddychurchill.CityWorld.worldgen.CableCars.Car car, long depotId, String base, String summit,
            String city) {
        long key() {
            return routeKey(depotId);
        }
    }

    private static final Map<Long, CableJob> CABLE_JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<Long> cablePlanned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * MTR's Ngong Ping 360 cabin: one two-block car. A cable car is MTR's "continuous movement": its siding must have
     * unlimited vehicles (0; MTR's own screen forces it) and then sends a cabin every 8 s whatever the frequency; with a
     * cap MTR makes it a single departure, one cabin on the whole line (measured, 2026-11-28).
     */
    private static final String CABLE_CABIN = "ngong_ping_360_normal_red_rht";
    private static final int CABLE_FREQUENCY = 10, CABLE_CABINS = 0, CABLE_COLOUR = 0xC0392B;

    /** A box {minX, minZ, maxX, maxZ} over a cable car's frame, u0..u1 along the line, v0..v1 across. */
    private static int[] cableBox(me.daddychurchill.CityWorld.worldgen.CableCars.Car car, int u0, int u1, int v0, int v1) {
        int xa = car.x(u0, v0), xb = car.x(u1, v1), za = car.z(u0, v0), zb = car.z(u1, v1);
        return new int[] { Math.min(xa, xb), Math.min(za, zb), Math.max(xa, xb), Math.max(za, zb) };
    }

    private static int[] cableStation(me.daddychurchill.CityWorld.worldgen.CableCars.Car car, boolean base) {
        return base ? cableBox(car, -me.daddychurchill.CityWorld.worldgen.CableCars.STATION_BACK - 1, 2, -7, 7)
                : cableBox(car, car.length() - 2, car.length() + 14, -7, 7);
    }

    /** The base station's siding, the cable car's depot. */
    private static int[] cableDepot(me.daddychurchill.CityWorld.worldgen.CableCars.Car car) {
        return cableBox(car, me.daddychurchill.CityWorld.worldgen.CableCarDrawing.SIDING_W - 1, me.daddychurchill.CityWorld.worldgen.CableCarDrawing.LOOP_W + 1, -2, 2);
    }

    /** A city's cable car, from a chunk of it as it is drawn (once per car): stations, depot, cables; its route queued. */
    public static void offerCableCar(WorldGenLevel level, me.daddychurchill.CityWorld.CityWorldGenerator context,
            me.daddychurchill.CityWorld.worldgen.CableCars.Car car) {
        if (!enabled())
            return;
        ResourceKey<Level> dimension = level.getLevel().dimension();
        if (!cablePlanned.add(car.id() ^ dimension.location().hashCode()))
            return;
        String city = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, car.site());
        city = city == null ? "City" : city.replace('|', '/');
        String base = city + " Cable Car", summit = city + " Summit";
        int[] b = cableStation(car, true), t = cableStation(car, false), d = cableDepot(car);
        offer(dimension, base, b[0], b[1], b[2], b[3], false, "CABLE_CAR");
        offer(dimension, summit, t[0], t[1], t[2], t[3], false, "CABLE_CAR");
        offer(dimension, base + " Depot", d[0], d[1], d[2], d[3], true, "CABLE_CAR");
        long depotId = asDepot(idOf(dimension, d[0], d[1], d[2], d[3]));
        offerRails(dimension, MtrRails.cableRails(car));
        CableJob job = new CableJob(dimension, car, depotId, base, summit, city);
        CABLE_JOBS.putIfAbsent(car.id(), job); // one already made is dropped by routes(), on the server thread
        CityWorldMod.LOGGER.info("CityWorld: cable car {} to {} offered to MTR", base, summit);
    }

    // --- the metro -------------------------------------------------------------------------------------

    /**
     * A metro line waiting for MTR: its stations' platforms and its depots' sidings. A line that loops has one depot
     * whose trains run to the negative terminal on the low track and back on the high one, every {@code headway}
     * seconds; otherwise each track is a shuttle, one train from its own depot (A the low track, B the high).
     */
    private record MetroJob(ResourceKey<Level> dimension, me.daddychurchill.CityWorld.Support.Metro.Line line, long[] depotIds, List<String> names, String city,
            int headway, me.daddychurchill.CityWorld.worldgen.CityWorldSettingsData.Trains trains) {
        long key() {
            return routeKey(depotIds[0]);
        }
    }

    private static final Map<Long, MetroJob> METRO_JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<Long> metroPlanned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** MTR's London Underground 1996 stock, its compressed cars: three fit a metro platform. */
    private static final String METRO_TRAIN = "london_underground_1996";
    private static final double METRO_CAR = 19.5, METRO_BOGIE = 5.75;
    /** The most trains a service may have out at once: a metro line, a Local, an Intercity. */
    private static final int METRO_TRAINS = 12, LOCAL_TRAINS = 20, INTERCITY_TRAINS = 16;

    /**
     * MTR's depot frequency for a train every {@code seconds}: its departures come 4 game hours / frequency apart, 200
     * real seconds / frequency in a 20-minute day. 0 seconds: the old one-train timetable.
     */
    static int frequencyFor(int seconds) {
        return seconds <= 0 ? ROUTE_FREQUENCY : Math.max(1, Math.min(48, Math.round(200f / seconds)));
    }

    /** Trains per siding for {@code trains} over {@code sidings} (1 when the headway is 0). */
    static int perSiding(int headway, int trains, int sidings) {
        return headway <= 0 ? 1 : Math.max(1, (trains + Math.max(1, sidings) - 1) / Math.max(1, sidings));
    }

    /**
     * A city's metro, from any chunk of the city as it is drawn (once per line): its stations, its two depots and its
     * rails offered to MTR, and its services queued for when MTR has them. {@code shift}: the city's lift.
     */
    public static void offerMetro(WorldGenLevel level, me.daddychurchill.CityWorld.CityWorldGenerator context,
            me.daddychurchill.CityWorld.worldgen.CitySites.Site site, int shift, int headway,
            me.daddychurchill.CityWorld.worldgen.CityWorldSettingsData.Trains trains) {
        if (!enabled())
            return;
        ResourceKey<Level> dimension = level.getLevel().dimension();
        me.daddychurchill.CityWorld.Support.Metro.Lines lines = me.daddychurchill.CityWorld.Support.Metro.linesOf(context, site);
        for (me.daddychurchill.CityWorld.Support.Metro.Line line : new me.daddychurchill.CityWorld.Support.Metro.Line[] { lines.ew(), lines.ns() })
            if (line != null && metroPlanned.add(line.id() ^ dimension.location().hashCode()))
                plan(() -> {
                    try {
                        String city = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, site);
                        city = city == null ? "City" : city.replace('|', '/');
                        List<String> names = new ArrayList<>();
                        for (me.daddychurchill.CityWorld.Support.Metro.Station s : line.stations()) {
                            String name = me.daddychurchill.CityWorld.Support.Metro.stationName(context, line, s);
                            names.add(name);
                            int[] box = me.daddychurchill.CityWorld.Support.Metro.stationBox(line, s);
                            // the station the walk from the railway station reaches: its area grown along the walk
                            // to meet the railway station's (MTR's interchange), its id still its own box's
                            int[] reach = me.daddychurchill.CityWorld.Support.MetroWalk.reach(context, site, line.name(), name);
                            if (lines.interchange() && s == line.stations().get(line.centre())) {
                                // the two lines' central stations: their areas grown a chunk each way so they overlap
                                // (MTR's interchange); otherwise they only touch at a corner
                                int g = 16;
                                reach = reach == null ? new int[] { box[0] - g, box[1] - g, box[2] + g, box[3] + g }
                                        : new int[] { Math.min(reach[0], box[0] - g), Math.min(reach[1], box[1] - g),
                                                Math.max(reach[2], box[2] + g), Math.max(reach[3], box[3] + g) };
                            }
                            if (reach != null && !stale() && QUEUE.add(new Offer(dimension, nonZero(idOf(dimension, box[0], box[1], box[2], box[3])),
                                    name.replace('|', '/'), Math.min(box[0], reach[0]), Math.min(box[1], reach[1]),
                                    Math.max(box[2], reach[2]), Math.max(box[3], reach[3]), false))) {
                                AREAS.put(nonZero(idOf(dimension, box[0], box[1], box[2], box[3])), box);
                                CityWorldMod.LOGGER.info("CityWorld: metro station {} reaches its interchange (area {},{} to {},{})", name,
                                        Math.min(box[0], reach[0]), Math.min(box[1], reach[1]), Math.max(box[2], reach[2]), Math.max(box[3], reach[3]));
                                continue;
                            }
                            offer(dimension, name, box[0], box[1], box[2], box[3], false);
                        }
                        long[] depotIds = new long[line.loops() ? 1 : 2];
                        for (int t = 0; t < depotIds.length; t++) {
                            int[] box = me.daddychurchill.CityWorld.Support.Metro.depotBox(line, t == 0);
                            String suffix = depotIds.length == 1 ? "" : t == 0 ? " A" : " B";
                            offer(dimension, city + " " + line.name() + " Depot" + suffix, box[0], box[1], box[2], box[3], true);
                            depotIds[t] = asDepot(idOf(dimension, box[0], box[1], box[2], box[3]));
                        }
                        offerRails(dimension, line.rails(line.floor(context) + 1 + shift));
                        MetroJob job = new MetroJob(dimension, line, depotIds, List.copyOf(names), city, headway, trains);
                        if (!stale())
                            METRO_JOBS.putIfAbsent(line.id(), job); // one already made is dropped by routes()
                    } catch (Throwable t) {
                        CityWorldMod.LOGGER.warn("CityWorld: offering a metro line to MTR failed: {}", t.toString());
                    }
                });
    }

    /** The platform whose middle is at {@code at} (within a block and a half): a metro track's platform at a station. */
    private static JsonObject platformAt(List<JsonObject> platforms, double[] at) {
        for (JsonObject p : platforms) {
            double[] m = mid(p);
            if (Math.abs(m[0] - at[0]) <= 1.5 && Math.abs(m[1] - at[1]) <= 1.5)
                return p;
        }
        return null;
    }

    /** How often each of CityWorld's depots has been asked to work out its paths again (a broken one stops). */
    private static final Map<Long, Integer> RETRIES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 10;

    /** Dimensions that have had rails sent since their depots were last looked at (a failed path may now be found). */
    private static final Set<ResourceKey<Level>> railsSince = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<Long, RouteJob> ROUTE_JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    /** MTR's depot frequency for every hour: trains = this / 4 per (game) hour. */
    private static final int ROUTE_FREQUENCY = 8;
    /** Stations whose platforms are still to be numbered (by station id), from the scan round players. */
    private static final Map<Long, NumberJob> NUMBER_JOBS = new java.util.concurrent.ConcurrentHashMap<>();

    private record NumberJob(ResourceKey<Level> dimension, me.daddychurchill.CityWorld.worldgen.Railways.Hub hub) {
    }

    /** The trains: a Local is Class 377 (four or eight 17-block cars), an Intercity Class 802 (five or nine 25-block cars). */
    private static final String LOCAL_TRAIN = "class_377_southern", INTERCITY_TRAIN = "class_802_gwr";
    private static final double LOCAL_CAR = 17, INTERCITY_CAR = 25;
    private static final int INTERCITY_COLOUR = 0xC62828;
    private static volatile Boolean loaded;

    // server thread only
    private static final Map<ResourceKey<Level>, Long> inFlight = new HashMap<>();
    private static Set<Long> offered;
    private static Path offeredFile;
    private static Api api;
    private static boolean broken;
    private static int ticks;

    /** True when MTR is installed (asked once). */
    public static boolean enabled() {
        Boolean l = loaded;
        if (l == null) {
            var mods = net.neoforged.fml.ModList.get();
            loaded = l = mods != null && mods.isLoaded("mtr");
        }
        return l;
    }

    /** Called by the mod constructor: the tick and stop listeners, only when MTR is installed. */
    public static void register() {
        if (!enabled())
            return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MtrStations::onTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MtrStations::onStopped);
    }

    /**
     * A station CityWorld has drawn (any thread; called once per station, from the chunk that holds its name board).
     * The yard's block bounds become the station's area, every height.
     */
    public static void offer(WorldGenLevel level, String name, int minX, int minZ, int maxX, int maxZ) {
        offer(level.getLevel().dimension(), name, minX, minZ, maxX, maxZ);
    }

    private static void offer(ResourceKey<Level> dimension, String name, int minX, int minZ, int maxX, int maxZ) {
        offer(dimension, name, minX, minZ, maxX, maxZ, false);
    }

    /**
     * A station's depot (its stabling yard) as an MTR depot named "{@code <city>} Depot", and its line's routes queued
     * for when both stations have platforms in MTR; any thread.
     */
    public static void offerDepot(WorldGenLevel level, me.daddychurchill.CityWorld.CityWorldGenerator context, me.daddychurchill.CityWorld.worldgen.Railways railways, me.daddychurchill.CityWorld.worldgen.Railways.Depot depot) {
        if (!enabled())
            return;
        String name = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, depot.city());
        int[] box = depot.box();
        if (name == null)
            return;
        offer(level.getLevel().dimension(), name + " Depot", box[0], box[1], box[2], box[3], true);
        queueRoutes(level.getLevel().dimension(), context, railways, depot);
    }

    /**
     * Queue a depot for the services it is home to (once per depot), on a planner thread: a service's stations reach
     * far past what is generated, so their stations and every rail of the service are offered now, and the routes are
     * made when MTR has them all.
     */
    private static void queueRoutes(ResourceKey<Level> dimension, me.daddychurchill.CityWorld.CityWorldGenerator context, me.daddychurchill.CityWorld.worldgen.Railways railways, me.daddychurchill.CityWorld.worldgen.Railways.Depot depot) {
        int[] box = depot.box();
        long id = asDepot(idOf(dimension, box[0], box[1], box[2], box[3]));
        if (ROUTE_JOBS.containsKey(id) || !planned.add(id))
            return;
        plan(() -> {
            try {
                String city = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, depot.city());
                var line = depot.line();
                List<MtrServices.Service> home = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                List<me.daddychurchill.CityWorld.worldgen.CitySites.Site[]> near = new ArrayList<>();
                near.add(new me.daddychurchill.CityWorld.worldgen.CitySites.Site[] { line.link().a(), line.link().b() });
                for (var end : new me.daddychurchill.CityWorld.worldgen.CitySites.Site[] { line.link().a(), line.link().b() })
                    for (var partner : railways.partnersOf(end))
                        near.add(new me.daddychurchill.CityWorld.worldgen.CitySites.Site[] { end, partner });
                for (MtrServices.Kind kind : MtrServices.Kind.values())
                    for (var pair : near) {
                        MtrServices.Service service = MtrServices.serviceOn(railways, pair[0], pair[1], kind);
                        if (service != null && seen.add(service.id()) && homeDepot(railways, service) == depot)
                            home.add(service);
                    }
                List<Run> runs = new ArrayList<>();
                for (MtrServices.Service service : home) {
                    List<String> names = new ArrayList<>();
                    for (MtrServices.Call call : service.calls()) {
                        var hub = call.hub();
                        String name = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, hub.site());
                        names.add(name == null ? "?" : name.replace('|', '/'));
                        offer(dimension, name, hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ());
                        numberLater(dimension, hub);
                        offerRails(dimension, MtrRails.of(railways, hub));
                        // every line at the station, not just the service's: two lines leaving the same end share
                        // their first stretch, which belongs to one of them
                        for (var partner : railways.partnersOf(hub.site())) {
                            var l = railways.lineBetween(hub.site(), partner);
                            if (l != null)
                                offerRails(dimension, MtrRails.of(railways, l));
                        }
                    }
                    for (int i = 0; i + 1 < service.calls().size(); i++) {
                        var l = railways.lineBetween(service.calls().get(i).hub().site(), service.calls().get(i + 1).hub().site());
                        if (l != null)
                            offerRails(dimension, MtrRails.of(railways, l));
                    }
                    runs.add(new Run(service, List.copyOf(names)));
                    if (runs.size() >= MAX_SHARES)
                        break;
                }
                offerRails(dimension, MtrRails.of(railways, depot));
                if (!runs.isEmpty() && city != null && !stale())
                    ROUTE_JOBS.putIfAbsent(id, new RouteJob(dimension, id, depot, city.replace('|', '/'), List.copyOf(runs),
                            railways.settings().headway(), railways.settings().trains()));
            } catch (Throwable t) {
                CityWorldMod.LOGGER.warn("CityWorld: planning MTR services failed: {}", t.toString());
            }
        });
    }

    /** Depots whose services have been worked out this session. */
    private static final Set<Long> planned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A yard is split between at most this many services (it has seven sidings). */
    private static final int MAX_SHARES = 4;

    /**
     * The depot a service runs from: the depot of the first of its lines (in call order) that has one; else, at the
     * first of its stops that has one, a depot on another line from there (its lines in key order); null if none.
     */
    public static me.daddychurchill.CityWorld.worldgen.Railways.Depot homeDepot(me.daddychurchill.CityWorld.worldgen.Railways railways, MtrServices.Service service) {
        var calls = service.calls();
        for (int i = 0; i + 1 < calls.size(); i++) {
            var l = railways.lineBetween(calls.get(i).hub().site(), calls.get(i + 1).hub().site());
            var d = l == null ? null : railways.depotOf(l);
            if (d != null)
                return d;
        }
        for (MtrServices.Call call : service.stops()) {
            var site = call.hub().site();
            List<me.daddychurchill.CityWorld.worldgen.CitySites.Site> partners = new ArrayList<>(railways.partnersOf(site));
            partners.sort(java.util.Comparator.comparingLong(me.daddychurchill.CityWorld.worldgen.Railways::key));
            for (var partner : partners) {
                var l = railways.lineBetween(site, partner);
                var d = l == null ? null : railways.depotOf(l);
                if (d != null)
                    return d;
            }
        }
        return null;
    }

    private static void offerRails(ResourceKey<Level> dimension, java.util.Collection<MtrRails.Rail> rails) {
        if (stale())
            return;
        for (MtrRails.Rail rail : rails)
            if (railsQueued.add(rail.id()))
                RAILS.add(new RailOffer(dimension, rail));
    }

    /** Rails CityWorld has laid nodes for (any thread; from each chunk as it is drawn). */
    public static void offerRails(WorldGenLevel level, java.util.Collection<MtrRails.Rail> rails) {
        if (!enabled())
            return;
        ResourceKey<Level> dimension = level.getLevel().dimension();
        for (MtrRails.Rail rail : rails)
            if (railsQueued.add(rail.id()))
                RAILS.add(new RailOffer(dimension, rail));
    }

    private static void offer(ResourceKey<Level> dimension, String name, int minX, int minZ, int maxX, int maxZ,
            boolean depot) {
        offer(dimension, name, minX, minZ, maxX, maxZ, depot, "TRAIN");
    }

    private static void offer(ResourceKey<Level> dimension, String name, int minX, int minZ, int maxX, int maxZ,
            boolean depot, String mode) {
        if (!enabled() || name == null || name.isBlank() || stale())
            return;
        long id = idOf(dimension, minX, minZ, maxX, maxZ);
        if (depot)
            id = asDepot(id);
        else
            AREAS.put(id, new int[] { minX, minZ, maxX, maxZ });
        QUEUE.add(new Offer(dimension, id == 0 ? 1 : id, name.replace('|', '/'), minX, minZ, maxX, maxZ, depot, mode));
    }

    private static long nonZero(long id) {
        return id == 0 ? 1 : id;
    }

    /** A depot's id from {@link #idOf} its box: never a station's. */
    private static long asDepot(long id) {
        long d = id * 0x9E3779B97F4A7C15L ^ 0x44_45_50_4FL;
        return d == 0 ? 1 : d;
    }

    /** A station's id: a mix of its yard and dimension, never 0. */
    static long idOf(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ) {
        long h = 0x43_57_4D_54_52_00_00_00L ^ dimension.location().toString().hashCode();
        for (int v : new int[] { minX, minZ, maxX, maxZ }) {
            h ^= v;
            h *= 0x9E3779B97F4A7C15L;
            h ^= h >>> 31;
        }
        return h == 0 ? 1 : h;
    }

    /**
     * The server's session: bumped when it stops, so a planner task still running from the last world (singleplayer:
     * quit, then open another) drops what it finds instead of offering it to the next world's MTR.
     */
    private static volatile int session;
    private static final ThreadLocal<Integer> TASK_SESSION = new ThreadLocal<>();

    /** Runs a planning task on a planner thread, tagged with this session. */
    private static void plan(Runnable task) {
        int s = session;
        me.daddychurchill.CityWorld.Support.Planners.run(() -> {
            if (s != session)
                return;
            TASK_SESSION.set(s);
            try {
                task.run();
            } finally {
                TASK_SESSION.remove();
            }
        });
    }

    /** Whether this thread is a planner task from a session that has since stopped (its finds are dropped). */
    private static boolean stale() {
        Integer s = TASK_SESSION.get();
        return s != null && s != session;
    }

    /** A station's platforms to be numbered, unless they were already this session. */
    private static void numberLater(ResourceKey<Level> dimension, me.daddychurchill.CityWorld.worldgen.Railways.Hub hub) {
        long id = idOf(dimension, hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ());
        if (!stale() && !numbered.contains(id))
            NUMBER_JOBS.putIfAbsent(id, new NumberJob(dimension, hub));
    }

    /** The areas of the stations CityWorld offered this session, by id: whose boards are CityWorld's to link. */
    private static final Map<Long, int[]> AREAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether a platform (MTR's JSON) lies in a station CityWorld offered this session. */
    static boolean ours(JsonObject platform) {
        double[] m = mid(platform);
        if (m == null)
            return false;
        for (int[] b : AREAS.values())
            if (m[0] >= b[0] && m[0] <= b[2] + 1 && m[1] >= b[1] && m[1] <= b[3] + 1)
                return true;
        return false;
    }

    /** The stations whose platforms were numbered this session (not asked for again every half minute). */
    private static final Set<Long> numbered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        session++;
        broken = false;
        MtrPids.broken = false;
        ticks = 0;
        numbered.clear();
        AREAS.clear();
        QUEUE.clear();
        RAILS.clear();
        railsQueued.clear();
        ROUTE_JOBS.clear();
        NUMBER_JOBS.clear();
        METRO_JOBS.clear();
        CABLE_JOBS.clear();
        cablePlanned.clear();
        metroPlanned.clear();
        RETRIES.clear();
        planned.clear();
        railsSince.clear();
        inFlight.clear();
        MtrPids.clear();
        offered = null;
        offeredFile = null;
    }

    /** How far round a player the stations of land generated before this build are found (blocks). */
    private static final int NEAR = 768;
    private static final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The stations round each player, offered from a planner thread: a world explored before MTR (or before this
     * build) drew its boards already, so the board's offer never came. Planning is a pure function of the seed, so
     * this finds the same stations, with the same ids, as the boards would.
     */
    private static void nearPlayers(MinecraftServer server) {
        record Spot(ServerLevel level, me.daddychurchill.CityWorld.worldgen.CityWorldChunkGenerator gen, int x, int z) {
        }
        List<Spot> spots = new ArrayList<>();
        for (var player : server.getPlayerList().getPlayers())
            if (player.serverLevel().getChunkSource().getGenerator() instanceof me.daddychurchill.CityWorld.worldgen.CityWorldChunkGenerator gen)
                spots.add(new Spot(player.serverLevel(), gen, player.getBlockX(), player.getBlockZ()));
        if (spots.isEmpty() || !scanning.compareAndSet(false, true))
            return;
        plan(() -> {
            try {
                for (Spot spot : spots) {
                    var railways = spot.gen().railways();
                    if (railways == null)
                        continue;
                    var context = spot.gen().getContext(spot.level());
                    for (var hub : railways.hubsTouching(spot.x() - NEAR, spot.z() - NEAR, spot.x() + NEAR, spot.z() + NEAR)) {
                        String name = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, hub.site());
                        offer(spot.level().dimension(), name, hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ());
                        numberLater(spot.level().dimension(), hub);
                    }
                    for (var depot : railways.depotsTouching(spot.x() - NEAR, spot.z() - NEAR, spot.x() + NEAR, spot.z() + NEAR)) {
                        String name = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, depot.city());
                        int[] box = depot.box();
                        if (name != null)
                            offer(spot.level().dimension(), name + " Depot", box[0], box[1], box[2], box[3], true);
                        queueRoutes(spot.level().dimension(), context, railways, depot);
                    }
                    // the track: every rail round the player, once
                    for (var rail : MtrRails.railsTouching(railways, spot.x() - NEAR, spot.z() - NEAR, spot.x() + NEAR,
                            spot.z() + NEAR))
                        if (!stale() && railsQueued.add(rail.id()))
                            RAILS.add(new RailOffer(spot.level().dimension(), rail));
                }
            } catch (Throwable t) {
                CityWorldMod.LOGGER.warn("CityWorld: finding stations for MTR failed: {}", t.toString());
            } finally {
                scanning.set(false);
            }
        });
    }

    private static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (broken || ++ticks % 20 != 0)
            return;
        MinecraftServer server = event.getServer();
        if (ticks % 600 == 20 || ticks == 100)
            nearPlayers(server);
        if (ticks % 200 == 140)
            MtrPids.link(server);
        boolean routesDue = ticks % 600 == 320 && (!ROUTE_JOBS.isEmpty() || !railsSince.isEmpty() || !NUMBER_JOBS.isEmpty()
                || !METRO_JOBS.isEmpty() || !CABLE_JOBS.isEmpty());
        if (QUEUE.isEmpty() && RAILS.isEmpty() && !routesDue)
            return;
        try {
            if (api == null)
                api = new Api();
            if (!api.ready())
                return; // MTR builds its simulators on ServerStarted; a message before that is dropped
            loadOffered(server);
            // what is waiting, by dimension (an offer for a dimension already in flight waits its turn)
            Map<ResourceKey<Level>, List<Offer>> byDimension = new HashMap<>();
            List<Offer> later = new ArrayList<>();
            Offer o;
            while ((o = QUEUE.poll()) != null) {
                if (offered.contains(o.id()))
                    continue;
                Long since = inFlight.get(o.dimension());
                if (since != null && ticks - since < TIMEOUT_TICKS) {
                    later.add(o);
                    continue;
                }
                List<Offer> list = byDimension.computeIfAbsent(o.dimension(), k -> new ArrayList<>());
                if (list.size() < BATCH)
                    list.add(o);
                else
                    later.add(o);
            }
            QUEUE.addAll(later);
            for (var entry : byDimension.entrySet()) {
                ServerLevel level = server.getLevel(entry.getKey());
                if (level == null)
                    continue;
                inFlight.put(entry.getKey(), (long) ticks);
                send(server, level, dedupe(entry.getValue()));
            }
            // then the rails, a batch per dimension not busy with stations
            Map<ResourceKey<Level>, List<MtrRails.Rail>> rails = new HashMap<>();
            List<RailOffer> railsLater = new ArrayList<>();
            RailOffer r;
            while ((r = RAILS.poll()) != null) {
                if (offered.contains(r.rail().id()))
                    continue;
                Long since = inFlight.get(r.dimension());
                List<MtrRails.Rail> list = rails.computeIfAbsent(r.dimension(), k -> new ArrayList<>());
                if (since != null && ticks - since < TIMEOUT_TICKS || list.size() >= RAIL_BATCH)
                    railsLater.add(r);
                else
                    list.add(r.rail());
            }
            RAILS.addAll(railsLater);
            for (var entry : rails.entrySet()) {
                ServerLevel level = server.getLevel(entry.getKey());
                if (level == null || entry.getValue().isEmpty())
                    continue;
                inFlight.put(entry.getKey(), (long) ticks);
                sendRails(server, level, entry.getValue());
            }
            if (routesDue)
                routes(server);
        } catch (Throwable t) {
            broken = true;
            CityWorldMod.LOGGER.warn("CityWorld: MTR stations are off for this session: {}", t.toString());
        }
    }

    private static List<Offer> dedupe(List<Offer> offers) {
        Map<Long, Offer> byId = new HashMap<>();
        for (Offer o : offers)
            byId.putIfAbsent(o.id(), o);
        return new ArrayList<>(byId.values());
    }

    /** Ask MTR what this dimension has, then add what it lacks (both callbacks on the server thread). */
    private static void send(MinecraftServer server, ServerLevel level, List<Offer> offers) throws Exception {
        ResourceKey<Level> dimension = level.dimension();
        api.send(api.listData, server, level, api.newListRequest(), listed -> {
            try {
                JsonObject have = JsonParser.parseString(api.json(listed)).getAsJsonObject();
                Set<Long> ids = new HashSet<>();
                List<long[]> areas = new ArrayList<>(), depotAreas = new ArrayList<>();
                for (String kind : new String[] { "stations", "depots" })
                    if (have.has(kind))
                    for (var e : have.getAsJsonArray(kind)) {
                        JsonObject s = e.getAsJsonObject();
                        ids.add(s.get("id").getAsLong());
                        JsonObject p1 = s.getAsJsonObject("position1"), p2 = s.getAsJsonObject("position2");
                        if (p1 != null && p2 != null)
                            (kind.equals("depots") ? depotAreas : areas).add(new long[] { Math.min(p1.get("x").getAsLong(), p2.get("x").getAsLong()),
                                    Math.min(p1.get("z").getAsLong(), p2.get("z").getAsLong()),
                                    Math.max(p1.get("x").getAsLong(), p2.get("x").getAsLong()),
                                    Math.max(p1.get("z").getAsLong(), p2.get("z").getAsLong()) });
                    }
                JsonArray stations = new JsonArray(), depots = new JsonArray();
                List<Offer> sent = new ArrayList<>();
                for (Offer o : offers) {
                    if (ids.contains(o.id()) || covered(o.depot() ? depotAreas : areas, o.midX(), o.midZ())) {
                        offered.add(o.id()); // there already: never ours to send again
                        continue;
                    }
                    (o.depot() ? depots : stations).add(o.depot() ? depotJson(o) : stationJson(o));
                    sent.add(o);
                }
                if (stations.isEmpty() && depots.isEmpty()) {
                    inFlight.remove(dimension);
                    saveOffered();
                    return;
                }
                JsonObject request = new JsonObject();
                request.add("stations", stations);
                request.add("depots", depots);
                for (String k : new String[] { "platforms", "sidings", "routes", "lifts", "rails",
                        "signalModifications", "homes", "landmarks" })
                    request.add(k, new JsonArray());
                api.send(api.updateData, server, level, api.newUpdateRequest(request.toString()), updated -> {
                    inFlight.remove(dimension);
                    StringBuilder names = new StringBuilder();
                    for (Offer o : sent) {
                        offered.add(o.id());
                        names.append(names.length() == 0 ? "" : ", ").append(o.name());
                    }
                    saveOffered();
                    CityWorldMod.LOGGER.info("CityWorld: {} MTR station(s)/depot(s) in {}: {}", sent.size(),
                            dimension.location(), names);
                });
            } catch (Throwable t) {
                inFlight.remove(dimension);
                broken = true;
                CityWorldMod.LOGGER.warn("CityWorld: MTR stations are off for this session: {}", t.toString());
            }
        });
    }

    /** Add a batch of rails (MTR replaces a rail between the same two nodes, so a resend is harmless). */
    private static void sendRails(MinecraftServer server, ServerLevel level, List<MtrRails.Rail> rails) throws Exception {
        ResourceKey<Level> dimension = level.dimension();
        JsonObject request = new JsonObject();
        JsonArray list = new JsonArray();
        for (MtrRails.Rail rail : rails)
            list.add(rail.json());
        request.add("rails", list);
        for (String k : new String[] { "stations", "depots", "platforms", "sidings", "routes", "lifts",
                "signalModifications", "homes", "landmarks" })
            request.add(k, new JsonArray());
        api.send(api.updateData, server, level, api.newUpdateRequest(request.toString()), updated -> {
            inFlight.remove(dimension);
            for (MtrRails.Rail rail : rails)
                offered.add(rail.id());
            saveOffered();
            railsSince.add(dimension);
            CityWorldMod.LOGGER.info("CityWorld: {} MTR rail(s) in {}", rails.size(), dimension.location());
        });
    }

    // --- routes ---------------------------------------------------------------------------------------

    /** Each dimension's waiting depots, a list request for each dimension not busy. */
    private static void routes(MinecraftServer server) throws Exception {
        Map<ResourceKey<Level>, List<RouteJob>> byDimension = new HashMap<>();
        for (RouteJob job : ROUTE_JOBS.values())
            if (offered.contains(job.key()))
                ROUTE_JOBS.remove(job.depotId());
            else
                byDimension.computeIfAbsent(job.dimension(), k -> new ArrayList<>()).add(job);
        for (var dimension : railsSince)
            byDimension.computeIfAbsent(dimension, k -> new ArrayList<>());
        for (NumberJob job : NUMBER_JOBS.values())
            byDimension.computeIfAbsent(job.dimension(), k -> new ArrayList<>());
        for (MetroJob job : METRO_JOBS.values())
            if (offered.contains(job.key()))
                METRO_JOBS.remove(job.line().id());
            else
                byDimension.computeIfAbsent(job.dimension(), k -> new ArrayList<>());
        for (CableJob job : CABLE_JOBS.values())
            if (offered.contains(job.key()))
                CABLE_JOBS.remove(job.car().id());
            else
                byDimension.computeIfAbsent(job.dimension(), k -> new ArrayList<>());
        for (var entry : byDimension.entrySet()) {
            Long since = inFlight.get(entry.getKey());
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null || since != null && ticks - since < TIMEOUT_TICKS)
                continue;
            inFlight.put(entry.getKey(), (long) ticks);
            railsSince.remove(entry.getKey());
            sendRoutes(server, level, entry.getValue());
        }
    }

    /**
     * Lists what MTR has; numbers the platforms of the stations waiting for it; then for each depot whose services have
     * platforms at every stop and whose yard has sidings: each service a route each way, the yard split between its
     * services (a second service runs from a second MTR depot over its share of the sidings), a train in each siding;
     * then asks MTR to work out the depots' paths. A depot that already has routes (the player's) is left alone, and
     * each is done once.
     */
    private static void sendRoutes(MinecraftServer server, ServerLevel level, List<RouteJob> jobs) throws Exception {
        ResourceKey<Level> dimension = level.dimension();
        api.send(api.listData, server, level, api.newListRequest(), listed -> {
            try {
                JsonObject have = JsonParser.parseString(api.json(listed)).getAsJsonObject();
                List<JsonObject> platforms = objects(have, "platforms"), sidings = objects(have, "sidings"),
                        depots = objects(have, "depots");
                MtrPids.remember(dimension, platforms);
                JsonArray routes = new JsonArray(), depotOut = new JsonArray(), sidingOut = new JsonArray(),
                        platformOut = new JsonArray();
                List<RouteJob> done = new ArrayList<>(), sent = new ArrayList<>();
                // platform numbers: 1 on the city side, counting across
                for (var entry : NUMBER_JOBS.entrySet()) {
                    NumberJob job = entry.getValue();
                    if (job.dimension() != dimension)
                        continue;
                    var hub = job.hub();
                    List<JsonObject> mine = inBox(platforms, new int[] { hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ() });
                    if (mine.isEmpty())
                        continue; // not in MTR yet
                    mine.sort(java.util.Comparator.comparingDouble(p -> hub.across(mid(p)[0], mid(p)[1])));
                    for (int i = 0; i < mine.size(); i++) {
                        JsonObject p = mine.get(i);
                        String name = p.has("name") ? p.get("name").getAsString() : "";
                        String number = Integer.toString(i + 1);
                        if ((name.isEmpty() || name.equals("1")) && !name.equals(number)) {
                            p.addProperty("name", number); // MTR's default; a player's own name stays
                            platformOut.add(p);
                        }
                    }
                    NUMBER_JOBS.remove(entry.getKey());
                    numbered.add(entry.getKey());
                }
                for (RouteJob job : jobs) {
                    JsonObject depot = byId(depots, job.depotId());
                    if (depot == null)
                        continue; // not in MTR yet
                    if (depot.has("routeIds") && depot.getAsJsonArray("routeIds").size() > 0) {
                        done.add(job); // routes of the player's own
                        continue;
                    }
                    List<JsonObject> yard = inBox(sidings, job.depot().box());
                    if (yard.size() < job.runs().size())
                        continue; // the yard not in MTR yet: next time
                    // every stop's platform, both ways
                    List<List<JsonObject>> outStops = new ArrayList<>(), backStops = new ArrayList<>();
                    boolean ready = true;
                    for (Run run : job.runs()) {
                        List<JsonObject> out = new ArrayList<>(), back = new ArrayList<>();
                        for (MtrServices.Call call : run.service().stops()) {
                            JsonObject[] pair = stopPlatforms(platforms, call, run.service().id());
                            if (pair == null) {
                                ready = false;
                                break;
                            }
                            out.add(pair[0]);
                            back.add(0, pair[1]);
                        }
                        outStops.add(out);
                        backStops.add(back);
                    }
                    if (!ready)
                        continue; // a station not in MTR yet: next time
                    var d = job.depot();
                    // sidings ordered along the world axis across the yard; share r takes the r-th even slice
                    int n = job.runs().size();
                    int[] box = d.box();
                    boolean acrossX = Math.abs(d.nx()) > 0.5;
                    yard.sort(java.util.Comparator.comparingDouble(o -> acrossX ? mid(o)[0] : mid(o)[1]));
                    int[] from = new int[n + 1];
                    for (int r = 0; r <= n; r++)
                        from[r] = Math.round(r * yard.size() / (float) n);
                    for (int r = 0; r < n; r++) {
                        Run run = job.runs().get(r);
                        List<JsonObject> share = yard.subList(from[r], from[r + 1]);
                        long depotId = splitDepot(job.depotId(), r);
                        JsonObject dep = r == 0 ? depot : depot.deepCopy();
                        if (r > 0) {
                            dep.addProperty("id", depotId);
                            dep.addProperty("name", job.city() + " Depot " + (r + 1));
                            dep.addProperty("color", run.service().kind() == MtrServices.Kind.LOCAL ? COLOURS[r % COLOURS.length] : INTERCITY_COLOUR);
                        }
                        if (n > 1) {
                            // this share's slice of the yard: cut halfway between neighbouring shares' sidings
                            int lo = acrossX ? box[0] : box[1], hi = acrossX ? box[2] : box[3];
                            if (r > 0)
                                lo = (int) Math.floor((axis(yard.get(from[r] - 1), acrossX) + axis(yard.get(from[r]), acrossX)) / 2) + 1;
                            if (r < n - 1)
                                hi = (int) Math.floor((axis(yard.get(from[r + 1] - 1), acrossX) + axis(yard.get(from[r + 1]), acrossX)) / 2);
                            int x0 = acrossX ? lo : box[0], x1 = acrossX ? hi : box[2];
                            int z0 = acrossX ? box[1] : lo, z1 = acrossX ? box[3] : hi;
                            dep.add("position1", position(x0, Long.MIN_VALUE, z0));
                            dep.add("position2", position(x1, Long.MAX_VALUE, z1));
                        }
                        boolean local = run.service().kind() == MtrServices.Kind.LOCAL;
                        int colour = local ? COLOURS[(int) Math.floorMod(run.service().id(), (long) COLOURS.length)] : INTERCITY_COLOUR;
                        long there = routeId(depotId, 1), back = routeId(depotId, 2);
                        String type = local ? "NORMAL" : "HIGH_SPEED";
                        String kind = local ? "Local" : "Intercity";
                        routes.add(routeJson(there, kind + ": " + run.first() + " - " + run.last(), colour, type, outStops.get(r)));
                        routes.add(routeJson(back, kind + ": " + run.last() + " - " + run.first(), colour, type, backStops.get(r)));
                        JsonArray ids = new JsonArray();
                        ids.add(there);
                        ids.add(back);
                        dep.add("routeIds", ids);
                        // a Local every headway, an Intercity every two
                        int headway = job.headway() <= 0 ? 0 : local ? job.headway() : 2 * job.headway();
                        JsonArray frequencies = new JsonArray();
                        for (int h = 0; h < 24; h++)
                            frequencies.add(frequencyFor(headway));
                        dep.add("frequencies", frequencies);
                        dep.addProperty("useRealTime", false);
                        depotOut.add(dep);
                        // the train: as long as the shortest platform it calls at holds
                        int platform = Integer.MAX_VALUE;
                        for (MtrServices.Call call : run.service().stops())
                            platform = Math.min(platform, call.hub().length());
                        for (JsonObject siding : share) {
                            double length = siding.has("railLength") ? siding.get("railLength").getAsDouble() : platform;
                            siding.add("vehicleCars", train(local ? job.trains().local() : job.trains().intercity(),
                                    Math.min(platform, length), 4));
                            siding.addProperty("maxVehicles", perSiding(headway, local ? LOCAL_TRAINS : INTERCITY_TRAINS, share.size()));
                            sidingOut.add(siding);
                        }
                    }
                    sent.add(job);
                }
                for (RouteJob job : done)
                    offered.add(job.key());
                // the metro: a loop from one depot, or each track a shuttle from its own
                List<MetroJob> metroSent = new ArrayList<>();
                for (MetroJob job : METRO_JOBS.values()) {
                    if (job.dimension() != dimension)
                        continue;
                    var line = job.line();
                    int nd = job.depotIds().length, n = line.stations().size();
                    boolean ready = true, theirs = false;
                    JsonObject[] deps = new JsonObject[nd];
                    List<List<JsonObject>> yards = new ArrayList<>();
                    for (int t = 0; t < nd && ready; t++) {
                        deps[t] = byId(depots, job.depotIds()[t]);
                        if (deps[t] == null) {
                            ready = false;
                            break;
                        }
                        theirs |= deps[t].has("routeIds") && deps[t].getAsJsonArray("routeIds").size() > 0;
                        List<JsonObject> yard = inBox(sidings, me.daddychurchill.CityWorld.Support.Metro.depotBox(line, t == 0));
                        if (yard.isEmpty())
                            ready = false;
                        yards.add(yard);
                    }
                    // each track's platforms, from the positive terminal to the negative one
                    List<List<JsonObject>> stopsDown = new ArrayList<>();
                    for (int c : new int[] { me.daddychurchill.CityWorld.Support.Metro.LOW_TRACK, me.daddychurchill.CityWorld.Support.Metro.HIGH_TRACK }) {
                        List<JsonObject> down = new ArrayList<>();
                        for (int i = n - 1; i >= 0 && ready; i--) {
                            JsonObject p = platformAt(platforms, line.platformMiddle(line.stations().get(i), c));
                            if (p == null)
                                ready = false;
                            down.add(p);
                        }
                        stopsDown.add(down);
                    }
                    if (theirs) {
                        offered.add(job.key()); // a player's own routes: left alone
                        METRO_JOBS.remove(line.id());
                        continue;
                    }
                    if (!ready)
                        continue; // not all in MTR yet: next time
                    String first = job.names().get(n - 1), last = job.names().get(0);
                    int metroHeadway = job.headway() <= 0 ? 0 : Math.max(1, job.headway() / 2);
                    for (int t = 0; t < nd; t++) {
                        JsonObject dep = deps[t];
                        long depotId = job.depotIds()[t];
                        List<JsonObject> there, back;
                        String track;
                        if (nd == 1) {
                            // the loop: down the low track to the negative terminal's high platform, back up the high
                            // track to the positive terminal's low platform (by the depot)
                            List<JsonObject> low = stopsDown.get(0), high = stopsDown.get(1);
                            there = new ArrayList<>(low.subList(0, n - 1));
                            there.add(high.get(n - 1));
                            List<JsonObject> up = new ArrayList<>(high);
                            java.util.Collections.reverse(up);
                            back = new ArrayList<>(up.subList(0, n - 1));
                            back.add(low.get(0));
                            track = "";
                        } else {
                            there = stopsDown.get(t);
                            back = new ArrayList<>(there);
                            java.util.Collections.reverse(back);
                            track = t == 0 ? " (A)" : " (B)";
                        }
                        long thereId = routeId(depotId, 1), backId = routeId(depotId, 2);
                        routes.add(routeJson(thereId, line.name() + track + ": " + first + " - " + last, line.colour(), "NORMAL", there));
                        routes.add(routeJson(backId, line.name() + track + ": " + last + " - " + first, line.colour(), "NORMAL", back));
                        JsonArray ids = new JsonArray();
                        ids.add(thereId);
                        ids.add(backId);
                        dep.add("routeIds", ids);
                        int frequency = nd == 1 ? frequencyFor(metroHeadway) : ROUTE_FREQUENCY;
                        JsonArray frequencies = new JsonArray();
                        for (int h = 0; h < 24; h++)
                            frequencies.add(frequency);
                        dep.add("frequencies", frequencies);
                        dep.addProperty("useRealTime", false);
                        dep.addProperty("color", line.colour());
                        depotOut.add(dep);
                        for (JsonObject siding : yards.get(t)) {
                            double length = siding.has("railLength") ? siding.get("railLength").getAsDouble() : 62;
                            siding.add("vehicleCars", train(job.trains().metro(), Math.min(62, length), 2));
                            siding.addProperty("maxVehicles", nd == 1 ? perSiding(metroHeadway, METRO_TRAINS, yards.get(t).size()) : 1);
                            sidingOut.add(siding);
                        }
                    }
                    // platform numbers on the island: 1 the low track, 2 the high (a player's own name stays)
                    for (int t = 0; t < 2; t++) {
                        String number = t == 0 ? "1" : "2";
                        for (JsonObject p : stopsDown.get(t)) {
                            String name = p.has("name") ? p.get("name").getAsString() : "";
                            if ((name.isEmpty() || name.equals("1")) && !name.equals(number)) {
                                p.addProperty("name", number);
                                platformOut.add(p);
                            }
                        }
                    }
                    metroSent.add(job);
                }
                // the cable cars: one route up and round, cabins from the base's siding
                List<CableJob> cableSent = new ArrayList<>();
                for (CableJob job : CABLE_JOBS.values()) {
                    if (job.dimension() != dimension)
                        continue;
                    JsonObject dep = byId(depots, job.depotId());
                    List<JsonObject> yard = inBox(sidings, cableDepot(job.car()));
                    JsonObject up = null, down = null;
                    for (MtrRails.Rail r : MtrRails.cableRails(job.car()))
                        if (r.platform()) {
                            JsonObject p = platformAt(platforms, new double[] { (r.a().x() + r.b().x()) / 2.0 + 0.5,
                                    (r.a().z() + r.b().z()) / 2.0 + 0.5 });
                            if (up == null)
                                up = p;
                            else
                                down = p;
                        }
                    if (dep == null || yard.isEmpty() || up == null || down == null)
                        continue; // not all in MTR yet
                    if (dep.has("routeIds") && dep.getAsJsonArray("routeIds").size() > 0) {
                        offered.add(job.key()); // a player's own: left alone
                        CABLE_JOBS.remove(job.car().id());
                        continue;
                    }
                    long routeId = routeId(job.depotId(), 1);
                    // up and back down again: a cabin carries riders both ways round the loop
                    routes.add(routeJson(routeId, "Cable Car: " + job.city() + " - " + job.summit(), CABLE_COLOUR, "NORMAL",
                            List.of(up, down, up), "CABLE_CAR"));
                    JsonArray ids = new JsonArray();
                    ids.add(routeId);
                    dep.add("routeIds", ids);
                    JsonArray frequencies = new JsonArray();
                    for (int h = 0; h < 24; h++)
                        frequencies.add(CABLE_FREQUENCY);
                    dep.add("frequencies", frequencies);
                    dep.addProperty("useRealTime", false);
                    dep.addProperty("color", CABLE_COLOUR);
                    depotOut.add(dep);
                    for (JsonObject siding : yard) {
                        JsonArray cars = new JsonArray();
                        JsonObject cabin = new JsonObject();
                        cabin.addProperty("vehicleId", CABLE_CABIN);
                        cabin.addProperty("length", 2.0);
                        cabin.addProperty("width", 2.0);
                        cabin.addProperty("bogie1Position", 0.0);
                        cabin.addProperty("bogie2Position", 0.0);
                        cabin.addProperty("couplingPadding1", 0.0);
                        cabin.addProperty("couplingPadding2", 0.0);
                        cars.add(cabin);
                        siding.add("vehicleCars", cars);
                        siding.addProperty("maxVehicles", CABLE_CABINS);
                        sidingOut.add(siding);
                    }
                    for (JsonObject p : new JsonObject[] { up, down })
                        if (!p.has("name") || p.get("name").getAsString().isEmpty()) {
                            p.addProperty("name", "1");
                            platformOut.add(p);
                        }
                    cableSent.add(job);
                }
                // CityWorld's own depots whose paths were not found (the line was not all there yet): try again
                List<Long> retry = new ArrayList<>();
                for (JsonObject depot : depots) {
                    long id = depot.get("id").getAsLong();
                    JsonArray routeIds = depot.has("routeIds") ? depot.getAsJsonArray("routeIds") : null;
                    String status = depot.has("lastGeneratedStatus") ? depot.get("lastGeneratedStatus").getAsString() : "";
                    if (routeIds != null && routeIds.size() > 0 && routeIds.get(0).getAsLong() == routeId(id, 1)
                            && !status.equals("SUCCESSFUL") && !status.equals("NONE")
                            && RETRIES.merge(id, 1, Integer::sum) <= MAX_RETRIES) {
                        retry.add(id);
                        railsSince.add(dimension); // and look again next time, in case this one fails too
                    }
                }
                if (sent.isEmpty() && platformOut.isEmpty() && metroSent.isEmpty() && cableSent.isEmpty()) {
                    inFlight.remove(dimension);
                    saveOffered();
                    if (!retry.isEmpty())
                        generate(server, level, retry);
                    return;
                }
                JsonObject request = new JsonObject();
                request.add("routes", routes);
                request.add("depots", depotOut);
                request.add("sidings", sidingOut);
                request.add("platforms", platformOut);
                for (String k : new String[] { "stations", "lifts", "rails", "signalModifications", "homes", "landmarks" })
                    request.add(k, new JsonArray());
                int numbered = platformOut.size();
                api.send(api.updateData, server, level, api.newUpdateRequest(request.toString()), updated -> {
                    inFlight.remove(dimension);
                    railsSince.add(dimension); // a path worked out before MTR has the routes fails: look again
                    StringBuilder names = new StringBuilder();
                    for (RouteJob job : sent) {
                        offered.add(job.key());
                        for (int r = 0; r < job.runs().size(); r++)
                            retry.add(splitDepot(job.depotId(), r));
                        for (Run run : job.runs())
                            names.append(names.length() == 0 ? "" : ", ").append(run.label());
                    }
                    for (MetroJob job : metroSent) {
                        offered.add(job.key());
                        METRO_JOBS.remove(job.line().id());
                        for (long id : job.depotIds())
                            retry.add(id);
                        names.append(names.length() == 0 ? "" : ", ").append(job.city()).append(' ').append(job.line().name());
                    }
                    for (CableJob job : cableSent) {
                        offered.add(job.key());
                        CABLE_JOBS.remove(job.car().id());
                        retry.add(job.depotId());
                        names.append(names.length() == 0 ? "" : ", ").append(job.base());
                    }
                    saveOffered();
                    if (numbered > 0)
                        CityWorldMod.LOGGER.info("CityWorld: {} MTR platform(s) numbered in {}", numbered, dimension.location());
                    if (!sent.isEmpty() || !metroSent.isEmpty() || !cableSent.isEmpty())
                        CityWorldMod.LOGGER.info("CityWorld: MTR services from {} depot(s) in {}: {}", sent.size(),
                                dimension.location(), names);
                    if (!retry.isEmpty())
                        generate(server, level, retry);
                });
            } catch (Throwable t) {
                inFlight.remove(dimension);
                CityWorldMod.LOGGER.warn("CityWorld: MTR routes failed: {}", t.toString());
            }
        });
    }

    /**
     * A stop's platform going out (the service's own direction) and coming back: through a station, the platform on the
     * side the train leaves by (right-hand running: across x end x side > 0 leaving by that end); where it starts, ends
     * or turns back, the platform it arrives at, both ways (it leaves again from where it came in). Null while the
     * station has no platforms in MTR. A train that starts, ends or turns back here uses the innermost platform on its
     * side (only those reach the other track: MtrRails' throat); one passing through takes any platform on its side,
     * chosen from the service's id, so the services at a big station spread over its platforms.
     */
    private static JsonObject[] stopPlatforms(List<JsonObject> platforms, MtrServices.Call call, long service) {
        var hub = call.hub();
        int s = hub.side();
        double outSide, backSide;
        boolean through = false;
        if (call.in() == 0)
            outSide = backSide = -call.out() * s; // the first stop: where the train coming back arrives
        else if (call.out() == 0 || call.turnsBack())
            outSide = backSide = -call.in() * s;
        else {
            outSide = call.out() * s;
            backSide = call.in() * s;
            through = true;
        }
        long h = service * 0x9E3779B97F4A7C15L ^ (long) hub.x(0, 0) * 31 ^ (long) hub.z(0, 0);
        int rank = through ? (int) Math.floorMod(h ^ h >>> 29, 1L << 20) : 0;
        JsonObject out = pickPlatform(platforms, hub, outSide, rank), back = pickPlatform(platforms, hub, backSide, rank);
        return out == null || back == null ? null : new JsonObject[] { out, back };
    }

    private static double axis(JsonObject o, boolean x) {
        return mid(o)[x ? 0 : 1];
    }

    /** Ask MTR to work out these depots' paths (and so their departures). */
    private static void generate(MinecraftServer server, ServerLevel level, List<Long> depotIds) {
        try {
            long[] ids = new long[depotIds.size()];
            for (int i = 0; i < ids.length; i++)
                ids[i] = depotIds.get(i);
            api.send(api.generateByDepotIds, server, level, api.newDepotIds(ids), generated -> {
            });
        } catch (Throwable t) {
            CityWorldMod.LOGGER.warn("CityWorld: could not ask MTR to generate depot paths: {}", t.toString());
        }
    }

    private static List<JsonObject> objects(JsonObject have, String key) {
        List<JsonObject> list = new ArrayList<>();
        if (have.has(key))
            for (var e : have.getAsJsonArray(key))
                list.add(e.getAsJsonObject());
        return list;
    }

    private static JsonObject byId(List<JsonObject> list, long id) {
        for (JsonObject o : list)
            if (o.has("id") && o.get("id").getAsLong() == id)
                return o;
        return null;
    }

    /** The middle of a platform or siding (between its two rail nodes), or null. */
    private static double[] mid(JsonObject o) {
        JsonObject p1 = o.getAsJsonObject("position1"), p2 = o.getAsJsonObject("position2");
        if (p1 == null || p2 == null)
            return null;
        return new double[] { (p1.get("x").getAsDouble() + p2.get("x").getAsDouble()) / 2 + 0.5,
                (p1.get("z").getAsDouble() + p2.get("z").getAsDouble()) / 2 + 0.5 };
    }

    private static List<JsonObject> inBox(List<JsonObject> list, int[] box) {
        List<JsonObject> in = new ArrayList<>();
        for (JsonObject o : list) {
            double[] m = mid(o);
            if (m != null && m[0] >= box[0] && m[0] <= box[2] + 1 && m[1] >= box[1] && m[1] <= box[3] + 1)
                in.add(o);
        }
        return in;
    }

    /**
     * The station's platform nearest its middle on the wanted side ({@code side} > 0: the positive side of the
     * station's across axis; < 0 the other; 0 either), any platform of the station when that side has none.
     */
    private static JsonObject pickPlatform(List<JsonObject> platforms, me.daddychurchill.CityWorld.worldgen.Railways.Hub hub, double side,
            int rank) {
        if (side != 0 && rank > 0) {
            // the platforms on that side, innermost first; the rank picks one
            List<JsonObject> onSide = new ArrayList<>();
            for (JsonObject p : inBox(platforms, new int[] { hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ() })) {
                double[] m = mid(p);
                if (hub.across(m[0], m[1]) * side > 0)
                    onSide.add(p);
            }
            if (!onSide.isEmpty()) {
                onSide.sort(java.util.Comparator.comparingDouble(p -> Math.abs(hub.across(mid(p)[0], mid(p)[1]))));
                return onSide.get(rank % onSide.size());
            }
        }
        JsonObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (JsonObject p : inBox(platforms, new int[] { hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ() })) {
            double[] m = mid(p);
            double c = hub.across(m[0], m[1]);
            double score = Math.abs(c) + (side != 0 && c * side <= 0 ? 1000 : 0);
            if (score < bestScore) {
                best = p;
                bestScore = score;
            }
        }
        return best;
    }

    private static JsonObject routeJson(long id, String name, int colour, String type, List<JsonObject> stops) {
        return routeJson(id, name, colour, type, stops, "TRAIN");
    }

    private static JsonObject routeJson(long id, String name, int colour, String type, List<JsonObject> stops, String mode) {
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("transportMode", mode);
        r.addProperty("name", name);
        r.addProperty("color", colour);
        r.addProperty("routeType", type);
        r.addProperty("routeNumber", "");
        r.addProperty("hidden", false);
        r.addProperty("circularState", "NONE");
        JsonArray list = new JsonArray();
        for (JsonObject platform : stops) {
            JsonObject stop = new JsonObject();
            stop.addProperty("platformId", platform.get("id").getAsLong());
            stop.addProperty("customDestination", "");
            list.add(stop);
        }
        r.add("routePlatformData", list);
        return r;
    }

    /**
     * A train of {@code family}'s cars for a platform this long: {@code small} cars, or {@code big} where it fits (cab,
     * trailers, cab), fewer where even {@code small} does not.
     */
    private static JsonArray train(me.daddychurchill.CityWorld.worldgen.CityWorldSettingsData.Train t, double platform, int margin) {
        var consist = t.consistFor(platform - margin);
        if (consist != null) {
            JsonArray list = new JsonArray();
            for (var k : consist) {
                JsonObject car = new JsonObject();
                car.addProperty("vehicleId", k.id());
                car.addProperty("length", k.length());
                car.addProperty("width", 2.0);
                car.addProperty("capacity", 80);
                car.addProperty("bogie1Position", k.bogie1());
                car.addProperty("bogie2Position", k.bogie2());
                car.addProperty("couplingPadding1", k.padding1());
                car.addProperty("couplingPadding2", k.padding2());
                list.add(car);
            }
            return list;
        }
        int fits = (int) Math.floor((platform - margin) / t.length());
        int small = Math.min(t.count(), t.most()), big = t.most();
        int cars = Math.max(1, fits >= big ? big : Math.min(fits, small));
        JsonArray list = new JsonArray();
        for (int i = 0; i < cars; i++) {
            JsonObject car = new JsonObject();
            car.addProperty("vehicleId", t.carId(i, cars));
            car.addProperty("length", t.length());
            car.addProperty("width", 2.0);
            car.addProperty("capacity", 80);
            car.addProperty("bogie1Position", -t.bogie());
            car.addProperty("bogie2Position", t.bogie());
            // a cab that keeps a gap at its coupled end (MTR's Class 377 cab_1/cab_3 keep a block)
            car.addProperty("couplingPadding1", i == 0 ? t.frontPadding() : 0.0);
            car.addProperty("couplingPadding2", 0.0);
            list.add(car);
        }
        return list;
    }

    private static boolean covered(List<long[]> areas, int x, int z) {
        for (long[] a : areas)
            if (x >= a[0] && x <= a[2] && z >= a[1] && z <= a[3])
                return true;
        return false;
    }

    /** One station in MTR's own JSON (keys read from a serialized MTR 4.1 station). */
    private static JsonObject stationJson(Offer o) {
        JsonObject s = new JsonObject();
        s.addProperty("id", o.id());
        s.addProperty("transportMode", o.mode());
        s.addProperty("name", o.name());
        s.addProperty("color", COLOURS[Math.floorMod(o.name().hashCode(), COLOURS.length)]);
        s.add("position1", position(o.minX(), Long.MIN_VALUE, o.minZ()));
        s.add("position2", position(o.maxX(), Long.MAX_VALUE, o.maxZ()));
        s.addProperty("zone1", 0);
        s.addProperty("zone2", 0);
        s.addProperty("zone3", 0);
        s.add("exits", new JsonArray());
        return s;
    }

    /** One depot in MTR's own JSON (its schema's keys; MTR fills in the rest: no routes, no departures yet). */
    private static JsonObject depotJson(Offer o) {
        JsonObject s = new JsonObject();
        s.addProperty("id", o.id());
        s.addProperty("transportMode", o.mode());
        s.addProperty("name", o.name());
        s.addProperty("color", COLOURS[Math.floorMod(o.name().hashCode(), COLOURS.length)]);
        s.add("position1", position(o.minX(), Long.MIN_VALUE, o.minZ()));
        s.add("position2", position(o.maxX(), Long.MAX_VALUE, o.maxZ()));
        s.add("routeIds", new JsonArray());
        s.addProperty("useRealTime", false);
        s.addProperty("repeatInfinitely", false);
        s.addProperty("cruisingAltitude", 256);
        return s;
    }

    private static JsonObject position(long x, long y, long z) {
        JsonObject p = new JsonObject();
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("z", z);
        return p;
    }

    // --- the offered set ---------------------------------------------------------------------------

    private static void loadOffered(MinecraftServer server) {
        if (offered != null)
            return;
        offered = new HashSet<>();
        offeredFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data")
                .resolve("cityworld_mtr_offered.txt");
        try {
            if (Files.exists(offeredFile))
                for (String line : Files.readAllLines(offeredFile, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (!line.isEmpty())
                        offered.add(Long.parseLong(line));
                }
        } catch (Exception e) {
            CityWorldMod.LOGGER.warn("CityWorld: could not read {}: {}", offeredFile, e.toString());
        }
    }

    private static void saveOffered() {
        if (offeredFile == null)
            return;
        try {
            Files.createDirectories(offeredFile.getParent());
            StringBuilder b = new StringBuilder();
            for (long id : offered)
                b.append(id).append('\n');
            Files.writeString(offeredFile, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CityWorldMod.LOGGER.warn("CityWorld: could not write {}: {}", offeredFile, e.toString());
        }
    }

    // --- MTR, by reflection --------------------------------------------------------------------------

    /** The few MTR members used, found once. */
    private static final class Api {
        final Field main;
        final Method sendMessage, parseJson, toJson;
        final Constructor<?> clientData, jsonReader, updateRequest, listRequest, depotIds;
        final Class<?> serialized;
        final String updateData, listData, generateByDepotIds;

        Api() throws Exception {
            ClassLoader cl = MtrStations.class.getClassLoader();
            Class<?> mtr = Class.forName("org.mtr.MTR", false, cl);
            Class<?> utilities = Class.forName("org.mtr.core.tool.Utilities", false, cl);
            Class<?> processor = Class.forName("org.mtr.core.servlet.OperationProcessor", false, cl);
            Class<?> data = Class.forName("org.mtr.core.data.Data", false, cl);
            Class<?> reader = Class.forName("org.mtr.core.serializer.ReaderBase", false, cl);
            Class<?> element = Class.forName("org.mtr.libraries.com.google.gson.JsonElement", false, cl);
            serialized = Class.forName("org.mtr.core.serializer.SerializedDataBase", false, cl);
            main = mtr.getDeclaredField("main");
            main.setAccessible(true);
            sendMessage = mtr.getMethod("sendMessageC2S", String.class, MinecraftServer.class, Level.class, serialized,
                    Consumer.class, Class.class);
            parseJson = utilities.getMethod("parseJson", String.class);
            toJson = utilities.getMethod("getJsonObjectFromData", serialized);
            clientData = Class.forName("org.mtr.core.data.ClientData", false, cl).getConstructor();
            jsonReader = Class.forName("org.mtr.core.serializer.JsonReader", false, cl).getConstructor(element);
            updateRequest = Class.forName("org.mtr.core.operation.UpdateDataRequest", false, cl).getConstructor(reader,
                    data);
            listRequest = Class.forName("org.mtr.core.operation.ListDataResponse", false, cl).getConstructor(data);
            updateData = (String) processor.getField("UPDATE_DATA").get(null);
            listData = (String) processor.getField("LIST_DATA").get(null);
            generateByDepotIds = (String) processor.getField("GENERATE_BY_DEPOT_IDS").get(null);
            depotIds = Class.forName("org.mtr.core.operation.DepotOperationByIds", false, cl).getConstructor(reader);
        }

        boolean ready() throws Exception {
            return main.get(null) != null;
        }

        Object newListRequest() throws Exception {
            return listRequest.newInstance(clientData.newInstance());
        }

        Object newUpdateRequest(String json) throws Exception {
            return updateRequest.newInstance(jsonReader.newInstance(parseJson.invoke(null, json)),
                    clientData.newInstance());
        }

        Object newDepotIds(long[] ids) throws Exception {
            JsonObject o = new JsonObject();
            JsonArray list = new JsonArray();
            for (long id : ids)
                list.add(id);
            o.add("depotIds", list);
            return depotIds.newInstance(jsonReader.newInstance(parseJson.invoke(null, o.toString())));
        }

        String json(Object serializedData) throws Exception {
            return toJson.invoke(null, serializedData).toString();
        }

        void send(String key, MinecraftServer server, ServerLevel level, Object request, Consumer<Object> callback)
                throws Exception {
            sendMessage.invoke(null, key, server, level, request, callback, serialized);
        }
    }
}
