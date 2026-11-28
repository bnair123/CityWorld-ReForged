package me.daddychurchill.CityWorld.integration.mtr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

import me.daddychurchill.CityWorld.CityWorldMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Ties each MTR departure board CityWorld drew to its own platform. A board with no platforms set shows every
 * platform of the station it stands in, so a board on platform 3 listed the trains for all of them. Once MTR has the
 * station's platforms, a board whose platform list is still empty gets the platforms whose track runs within
 * {@link #REACH} blocks of it (an island's board: both its tracks). A board further from the tracks (a concourse's)
 * keeps the whole station, and a board a player has set is left as it is. Only CityWorld's platforms (in a station it
 * offered) are ever given to a board, so a player's own station's boards are never touched.
 */
public final class MtrPids {
    private MtrPids() {
    }

    /** How near a board a platform's track must run to be the board's (blocks, across). */
    static final double REACH = 5.0;
    /** How far above or below the board the platform's rail may be (blocks). */
    static final int RISE = 8;
    /** How far round each player boards are looked for (chunks). */
    static final int RADIUS = 8;

    record Plat(long id, double x1, double y1, double z1, double x2, double y2, double z2) {
    }

    /** MTR's platforms per dimension, as last listed. */
    static final Map<ResourceKey<Level>, List<Plat>> PLATFORMS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Boards already settled (linked, or left to their player). */
    private static final Set<Long> settled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static Class<?> signClass;
    private static Method getSign, setSign;
    private static Class<?> baseClass, setClass;
    private static Method getIds, setData, isEmpty, add;
    private static Field maxArrivals, canStore;
    static boolean broken;

    static void remember(ResourceKey<Level> dimension, List<JsonObject> platforms) {
        List<Plat> list = new ArrayList<>();
        for (JsonObject o : platforms) {
            if (!MtrStations.ours(o))
                continue;
            JsonObject p1 = o.getAsJsonObject("position1"), p2 = o.getAsJsonObject("position2");
            if (p1 == null || p2 == null || !o.has("id"))
                continue;
            list.add(new Plat(o.get("id").getAsLong(), p1.get("x").getAsDouble() + 0.5, p1.get("y").getAsDouble(),
                    p1.get("z").getAsDouble() + 0.5, p2.get("x").getAsDouble() + 0.5, p2.get("y").getAsDouble(),
                    p2.get("z").getAsDouble() + 0.5));
        }
        PLATFORMS.put(dimension, list);
    }

    static void clear() {
        PLATFORMS.clear();
        settled.clear();
    }

    private static boolean bind() {
        if (baseClass != null)
            return true;
        if (broken)
            return false;
        try {
            baseClass = Class.forName("org.mtr.block.BlockPIDSBase$BlockEntityBase");
            setClass = Class.forName("org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet");
            getIds = baseClass.getMethod("getPlatformIds");
            setData = baseClass.getMethod("setData", String[].class, boolean[].class, setClass, int.class);
            maxArrivals = baseClass.getField("maxArrivals");
            canStore = baseClass.getField("canStoreData");
            isEmpty = setClass.getMethod("isEmpty");
            add = setClass.getMethod("add", long.class);
            try {
                signClass = Class.forName("org.mtr.block.BlockRouteSignBase$BlockEntityBase");
                getSign = signClass.getMethod("getPlatformId");
                setSign = signClass.getMethod("setPlatformId", long.class);
            } catch (Throwable t) {
                signClass = null;
            }
            return true;
        } catch (Throwable t) {
            broken = true;
            baseClass = null;
            CityWorldMod.LOGGER.warn("CityWorld: MTR departure boards can't be linked: {}", t.toString());
            return false;
        }
    }

    /** On the server thread: link the boards round each player. */
    @SuppressWarnings("unchecked")
    static void link(MinecraftServer server) {
        if (PLATFORMS.isEmpty() || !bind())
            return;
        int linked = 0;
        for (var player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            List<Plat> plats = PLATFORMS.get(level.dimension());
            if (plats == null || plats.isEmpty())
                continue;
            int pcx = player.getBlockX() >> 4, pcz = player.getBlockZ() >> 4;
            for (int cx = pcx - RADIUS; cx <= pcx + RADIUS; cx++)
                for (int cz = pcz - RADIUS; cz <= pcz + RADIUS; cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null)
                        continue;
                    for (BlockEntity be : List.copyOf(chunk.getBlockEntities().values())) {
                        if (signClass != null && signClass.isInstance(be)) {
                            if (linkSign(level, be, plats))
                                linked++;
                            continue;
                        }
                        if (!baseClass.isInstance(be))
                            continue;
                        BlockPos pos = be.getBlockPos();
                        long key = pos.asLong() ^ ((long) level.dimension().location().hashCode() << 1);
                        if (settled.contains(key))
                            continue;
                        try {
                            var store = (java.util.function.BiPredicate<Level, BlockPos>) canStore.get(be);
                            if (!store.test(level, pos)) {
                                settled.add(key);
                                continue;
                            }
                            if (!(boolean) isEmpty.invoke(getIds.invoke(be))) {
                                settled.add(key); // a player's choice
                                continue;
                            }
                            Object ids = setClass.getConstructor().newInstance();
                            int found = 0;
                            double bx = pos.getX() + 0.5, bz = pos.getZ() + 0.5;
                            for (Plat p : plats) {
                                if (Math.abs(p.y1() - pos.getY()) > RISE)
                                    continue;
                                if (distance(bx, bz, p) <= REACH) {
                                    add.invoke(ids, p.id());
                                    found++;
                                }
                            }
                            if (found == 0)
                                continue; // a concourse's board, or MTR has not got the platform yet: look again later
                            int n = maxArrivals.getInt(be);
                            String[] messages = new String[n];
                            java.util.Arrays.fill(messages, "");
                            setData.invoke(be, messages, new boolean[n], ids, 0);
                            be.setChanged();
                            var state = level.getBlockState(pos);
                            level.sendBlockUpdated(pos, state, state, 3);
                            settled.add(key);
                            linked++;
                        } catch (Throwable t) {
                            settled.add(key);
                            failed(t);
                        }
                    }
                }
        }
        if (linked > 0)
            CityWorldMod.LOGGER.info("CityWorld: {} MTR departure board(s) and line map(s) linked to their platforms", linked);
    }

    /**
     * A line map (MTR's route sign) with no platform: the platform on the side it shows to, the one nearest a point a
     * few blocks out in front of it.
     */
    private static boolean linkSign(ServerLevel level, BlockEntity be, List<Plat> plats) {
        BlockPos pos = be.getBlockPos();
        long key = pos.asLong() ^ ((long) level.dimension().location().hashCode() << 1);
        if (settled.contains(key))
            return false;
        try {
            if ((long) getSign.invoke(be) != 0) {
                settled.add(key);
                return false;
            }
            var state = level.getBlockState(pos);
            if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                settled.add(key);
                return false;
            }
            var shows = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    .getOpposite();
            double fx = pos.getX() + 0.5 + shows.getStepX() * 4, fz = pos.getZ() + 0.5 + shows.getStepZ() * 4;
            Plat best = null;
            double bestD = REACH + 4;
            for (Plat p : plats) {
                if (Math.abs(p.y1() - pos.getY()) > RISE)
                    continue;
                double d = distance(fx, fz, p);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best == null)
                return false;
            setSign.invoke(be, best.id());
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
            settled.add(key);
            return true;
        } catch (Throwable t) {
            settled.add(key);
            failed(t);
            return false;
        }
    }

    private static boolean warned;

    /** A board that could not be linked: said once (MTR changed under us, most likely), then quietly settled. */
    private static void failed(Throwable t) {
        if (!warned) {
            warned = true;
            CityWorldMod.LOGGER.warn("CityWorld: linking an MTR departure board or line map failed: {}", t.toString());
        }
    }

    /** Horizontal distance from a point to a platform's rail (a segment). */
    static double distance(double x, double z, Plat p) {
        double dx = p.x2() - p.x1(), dz = p.z2() - p.z1();
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - p.x1()) * dx + (z - p.z1()) * dz) / len2));
        double ex = p.x1() + t * dx - x, ez = p.z1() + t * dz - z;
        return Math.sqrt(ex * ex + ez * ez);
    }
}
