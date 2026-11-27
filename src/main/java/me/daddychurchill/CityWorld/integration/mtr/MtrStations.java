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
            boolean depot) {
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

    /** A station's depot (its stabling yard) as an MTR depot named "{@code name} Depot"; any thread. */
    public static void offerDepot(WorldGenLevel level, String name, int[] box) {
        if (box != null && name != null)
            offer(level.getLevel().dimension(), name + " Depot", box[0], box[1], box[2], box[3], true);
    }

    private static void offer(ResourceKey<Level> dimension, String name, int minX, int minZ, int maxX, int maxZ,
            boolean depot) {
        if (!enabled() || name == null || name.isBlank())
            return;
        long id = idOf(dimension, minX, minZ, maxX, maxZ);
        if (depot)
            id = id * 0x9E3779B97F4A7C15L ^ 0x44_45_50_4FL; // never a station's
        QUEUE.add(new Offer(dimension, id == 0 ? 1 : id, name.replace('|', '/'), minX, minZ, maxX, maxZ, depot));
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

    private static void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        QUEUE.clear();
        inFlight.clear();
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
        me.daddychurchill.CityWorld.Support.Planners.run(() -> {
            try {
                for (Spot spot : spots) {
                    var railways = spot.gen().railways();
                    if (railways == null)
                        continue;
                    var context = spot.gen().getContext(spot.level());
                    for (var hub : railways.hubsTouching(spot.x() - NEAR, spot.z() - NEAR, spot.x() + NEAR, spot.z() + NEAR)) {
                        String name = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, hub.site());
                        offer(spot.level().dimension(), name, hub.minX(), hub.minZ(), hub.maxX(), hub.maxZ());
                    }
                    for (var depot : railways.depotsTouching(spot.x() - NEAR, spot.z() - NEAR, spot.x() + NEAR, spot.z() + NEAR)) {
                        String name = me.daddychurchill.CityWorld.Support.PlaceNames.ofCity(context, depot.city());
                        int[] box = depot.box();
                        if (name != null)
                            offer(spot.level().dimension(), name + " Depot", box[0], box[1], box[2], box[3], true);
                    }
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
        if (ticks % 600 == 20)
            nearPlayers(server);
        if (QUEUE.isEmpty())
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
        s.addProperty("transportMode", "TRAIN");
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
        s.addProperty("transportMode", "TRAIN");
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
        final Constructor<?> clientData, jsonReader, updateRequest, listRequest;
        final Class<?> serialized;
        final String updateData, listData;

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

        String json(Object serializedData) throws Exception {
            return toJson.invoke(null, serializedData).toString();
        }

        void send(String key, MinecraftServer server, ServerLevel level, Object request, Consumer<Object> callback)
                throws Exception {
            sendMessage.invoke(null, key, server, level, request, callback, serialized);
        }
    }
}
