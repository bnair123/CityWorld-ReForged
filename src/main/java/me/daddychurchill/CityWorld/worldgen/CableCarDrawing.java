package me.daddychurchill.CityWorld.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;

import me.daddychurchill.CityWorld.integration.mtr.MtrBlocks;
import me.daddychurchill.CityWorld.worldgen.CableCars.Car;

/**
 * A cable car's part of one chunk ({@link CableCars}): an alpine station at each end (a stone plinth, timber posts
 * and glass, a spruce roof on beams, the cabins' way round a loop sunk a block into the floor, MTR's station nodes
 * hanging from the beams), steps from the base station's back door down toward the city, a viewing terrace past the
 * summit station on stone piers (railings, benches looking out, lanterns, flowers), slim pylons with a cross-arm and
 * MTR's sheaves, and a clear way for the cabins (trees under the cables trimmed, the ground cut where the cable comes
 * down to a station). Drawn after vanilla's decoration, so its trees stand round it and not through it.
 * <p>
 * Local frame: {@code u} along the line from the base node toward the summit, {@code v} across it (positive: the up
 * cable's side). A station's own {@code w} runs from its mouth (toward the other station, w = 0 at the node) back
 * round its loop (negative w).
 */
public final class CableCarDrawing {
    private CableCarDrawing() {
    }

    /** A station's half-width; its back wall {@code w}; the loop's far node and the siding's end (base only). */
    public static final int HALF = 6, LOOP_W = -11, SIDING_W = -17;
    static final int BASE_BACK = -CableCars.STATION_BACK - 1, TOP_BACK = -14;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), PLINTH = Blocks.STONE_BRICKS.defaultBlockState(),
            FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState(), TROUGH = Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
            POST = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), PLANKS = Blocks.SPRUCE_PLANKS.defaultBlockState(),
            GLASS = Blocks.GLASS_PANE.defaultBlockState(), EDGE = Blocks.SMOOTH_STONE.defaultBlockState(),
            SHAFT = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), ARM = Blocks.POLISHED_ANDESITE.defaultBlockState(),
            FENCE = Blocks.SPRUCE_FENCE.defaultBlockState(), PIER = Blocks.STONE_BRICK_WALL.defaultBlockState();

    /** Draws the car's part of this chunk; {@code city}: the city's name, for the station boards. */
    public static void draw(Car car, WorldGenLevel level, ChunkAccess chunk, String city) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        Ctx c = new Ctx(car, level, p, x0, z0);
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int ox = x - car.baseX(), oz = z - car.baseZ();
                int u = ox * car.dx() + oz * car.dz(), v = ox * car.cx() + oz * car.cz();
                if (Math.abs(v) > CableCars.TERRACE / 2 + 2)
                    continue;
                // the base station: w = u
                if (u <= 2 && u >= BASE_BACK - 1)
                    station(c, x, z, u, v, car.baseDeck(), true);
                // the summit station: w = length - u, and the terrace past it
                int w = car.length() - u;
                if (w <= 2 && w >= TOP_BACK - CableCars.TERRACE - 1) {
                    if (w >= TOP_BACK)
                        station(c, x, z, w, v, car.topDeck(), false);
                    else
                        terrace(c, x, z, w - TOP_BACK, v, car.topDeck());
                }
                // the way for the cabins between the stations, and the pylons
                if (u > 2 && u < car.length() - 2 && Math.abs(v) <= CableCars.GAUGE + 1)
                    clearWay(c, x, z, u, v);
            }
        for (CableCars.Pylon pylon : car.pylons())
            for (int v = -3; v <= 3; v++) {
                int x = car.x(pylon.u(), v), z = car.z(pylon.u(), v);
                if (x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16)
                    pylon(c, x, z, v, pylon);
            }
        // the base station's steps down toward the city, off its back door
        steps(c, car, x0, z0);
        // the boards, on the chunk holding each door
        board(c, car, true, city);
        board(c, car, false, city);
    }

    private record Ctx(Car car, WorldGenLevel level, BlockPos.MutableBlockPos p, int x0, int z0) {
        void set(int x, int y, int z, BlockState s) {
            if (s == null || x < x0 || x >= x0 + 16 || z < z0 || z >= z0 + 16)
                return;
            level.setBlock(p.set(x, y, z), s, 2);
        }

        BlockState get(int x, int y, int z) {
            return level.getBlockState(p.set(x, y, z));
        }

        /** The top block of the ground here (not leaves, logs or plants), searching down from {@code from}. */
        int ground(int x, int z, int from) {
            for (int y = from; y > level.getMinBuildHeight(); y--) {
                BlockState s = get(x, y, z);
                if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.getFluidState().isEmpty()
                        || s.canBeReplaced() || !s.blocksMotion())
                    continue;
                return y;
            }
            return level.getMinBuildHeight();
        }

        boolean inChunk(int x, int z) {
            return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
        }

        /** The raw terrain's top solid block anywhere (no trees): the same answer from whichever chunk asks. */
        int terrain(int x, int z) {
            var cache = level.getLevel().getChunkSource();
            return cache.getGenerator().getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                    level, cache.randomState()) - 1;
        }

        Direction along() {
            return Direction.fromDelta(car.dx(), 0, car.dz());
        }

        Direction across() {
            return Direction.fromDelta(car.cx(), 0, car.cz());
        }
    }

    /** The node block's axis: {@code facing} true for a line along x. */
    private static BlockState node(Car car, String kind) {
        return MtrBlocks.of("cable_car_node_" + kind, "facing", car.dx() != 0 ? "true" : "false", "is_45", "false");
    }

    /** Whether a cell of a station is on the cabins' way (the two straights, the loop, the base's siding). */
    private static boolean onWay(int w, int v, boolean base) {
        int a = Math.abs(v);
        if (w <= 2 && w >= -8 && a >= 1 && a <= 3)
            return true;
        if (w < -8 && w >= LOOP_W - 1 && a <= 3)
            return true;
        return base && w < LOOP_W - 1 && w >= SIDING_W - 1 && a <= 1;
    }

    /** One column of a station, its deck at {@code deck}; {@code w} from its mouth, negative inward. */
    private static void station(Ctx c, int x, int z, int w, int v, int deck, boolean base) {
        int back = base ? BASE_BACK : TOP_BACK;
        int a = Math.abs(v);
        if (a > HALF || w > 2 || w < back)
            return;
        // the plinth from the ground up to the deck, the hill cut away above it
        int g = c.ground(x, z, deck + 24);
        for (int y = Math.max(g - 2, deck - 40); y < deck; y++)
            c.set(x, y, z, PLINTH);
        for (int y = deck + 1; y <= deck + 12; y++)
            c.set(x, y, z, AIR);
        boolean wall = a == HALF || w == back;
        boolean way = onWay(w, v, base);
        c.set(x, deck, z, wall ? PLINTH : way ? TROUGH : FLOOR);
        if (way)
            c.set(x, deck, z, TROUGH);
        // the walls: a stone sill, timber posts every fourth block, glass between, planks under the eaves
        int top = deck + CableCars.NODE_RISE + 1; // the beams
        if (wall) {
            boolean post = a == HALF && Math.floorMod(w, 4) == 2 || w == back && (a == HALF || a % 4 == 2);
            boolean door = w == back && a <= 0;
            for (int y = deck + 1; y < top; y++) {
                BlockState s = post ? POST : y == deck + 1 ? PLINTH : y >= top - 1 ? PLANKS : GLASS;
                if (door && y <= deck + 2)
                    s = AIR;
                c.set(x, y, z, s);
            }
            if (door) {
                Direction out = base ? c.along().getOpposite() : c.along();
                c.set(x, deck + 1, z, Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, out)
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                c.set(x, deck + 2, z, Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, out)
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
        } else if (w == 2 && a != 0 && !(a >= 1 && a <= 3)) {
            // the mouth: posts each side of the two openings, open to the sky's cables
            for (int y = deck + 1; y < top; y++)
                c.set(x, y, z, a == HALF - 1 || a == 4 ? (y >= top - 1 ? PLANKS : POST) : AIR);
        }
        // the roof: beams across at the nodes, planks over, slabs on top with an overhang
        int beamW = w == 0 || w == -8 || w == LOOP_W || base && w == SIDING_W ? 1 : 0;
        if (beamW == 1 || wall || w == 2)
            c.set(x, top, z, PLANKS);
        c.set(x, top + 1, z, PLANKS);
        c.set(x, top + 2, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        if (a == 0 && w <= 0 && w > back)
            c.set(x, top + 2, z, Blocks.SPRUCE_PLANKS.defaultBlockState()); // a ridge
        // the cable nodes hanging from the beams (y = top - 1 = deck + NODE_RISE)
        int n = deck + CableCars.NODE_RISE;
        if ((w == 0 || w == -8) && a == CableCars.GAUGE || w == LOOP_W && a == 0 || base && w == SIDING_W && a == 0)
            c.set(x, n, z, node(c.car(), "station"));
        // lanterns hanging from the beams over the walkways, benches against the walls
        if (beamW == 1 && a == 5)
            c.set(x, top - 1, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        if (a == HALF - 1 && (w == -3 || w == -5) && !way) {
            Direction in = v > 0 ? c.across() : c.across().getOpposite(); // a bench's back to the wall
            c.set(x, deck + 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, in.getOpposite())
                    .setValue(StairBlock.HALF, Half.BOTTOM));
        }
        if (a == HALF - 1 && w == -1 && !way)
            c.set(x, deck + 1, z, Blocks.POTTED_AZALEA.defaultBlockState());
    }

    /** One column of the summit's terrace, {@code t} blocks past the station's back (negative), deck at {@code deck}. */
    private static void terrace(Ctx c, int x, int z, int t, int v, int deck) {
        int a = Math.abs(v), far = -CableCars.TERRACE;
        int half = CableCars.TERRACE / 2;
        if (a > half || t < far)
            return;
        // a rounded end: the corners cut back
        if (t <= far + 2 && a >= half - (far + 3 - t))
            return;
        int g = c.ground(x, z, deck + 24);
        for (int y = deck + 1; y <= deck + 8; y++)
            c.set(x, y, z, AIR);
        boolean rim = a == half || t == far || t <= far + 2 && a >= half - (far + 4 - t);
        c.set(x, deck, z, rim ? EDGE : (Math.floorMod(t + v, 2) == 0 ? FLOOR : Blocks.STONE_BRICKS.defaultBlockState()));
        // stone piers down to the ground every fourth block, and the rim; solid where the ground is close
        boolean pier = rim && (Math.floorMod(t, 4) == 0 || a == half && t == far) || a == 0 && Math.floorMod(t, 4) == 0;
        if (g >= deck - 2)
            for (int y = g + 1; y < deck; y++)
                c.set(x, y, z, PLINTH);
        else if (pier)
            for (int y = Math.max(g + 1, deck - 48); y < deck; y++)
                c.set(x, y, z, y == deck - 1 ? PLINTH : PIER);
        if (rim) {
            boolean lamp = a == half && Math.floorMod(t, 6) == 0 || t == far && a == 0;
            c.set(x, deck + 1, z, FENCE);
            if (lamp)
                c.set(x, deck + 2, z, Blocks.LANTERN.defaultBlockState());
        } else if (a == half - 1 && Math.floorMod(t, 4) == 2) {
            // benches looking out over the rail
            Direction out = v > 0 ? c.across() : c.across().getOpposite();
            c.set(x, deck + 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, out.getOpposite())
                    .setValue(StairBlock.HALF, Half.BOTTOM));
        } else if (a == 0 && t == far + 1) {
            c.set(x, deck + 1, z, Blocks.POTTED_RED_TULIP.defaultBlockState());
        } else if (a == 2 && t == -2) {
            c.set(x, deck + 1, z, Blocks.POTTED_BLUE_ORCHID.defaultBlockState());
        }
    }

    /** One column of the cabins' way: trees under the cable trimmed; near a station, the ground cut too. */
    private static void clearWay(Ctx c, int x, int z, int u, int v) {
        Car car = c.car();
        int cable = (int) Math.floor(car.cableAt(u));
        int d = Math.min(u, car.length() - u);
        boolean cut = d <= 28;
        for (int y = cable - 7; y <= cable + 1; y++) {
            BlockState s = c.get(x, y, z);
            if (s.isAir())
                continue;
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.canBeReplaced() && s.getFluidState().isEmpty()
                    || cut && s.getFluidState().isEmpty())
                c.set(x, y, z, AIR);
        }
    }

    /** One column of a pylon: a slim shaft between the cables on a stone footing, a cross-arm, the sheaves. */
    private static void pylon(Ctx c, int x, int z, int v, CableCars.Pylon pylon) {
        int cable = pylon.cable();
        int g = c.ground(x, z, cable);
        if (v == 0) {
            for (int y = g + 1; y <= cable; y++)
                c.set(x, y, z, y <= g + 2 ? PLINTH : SHAFT);
            c.set(x, cable + 1, z, ARM);
            c.set(x, cable + 2, z, Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState());
        } else {
            if (Math.abs(v) == 1)
                for (int y = g + 1; y <= g + 2; y++)
                    c.set(x, y, z, PLINTH); // the footing's sides
            c.set(x, cable + 1, z, ARM);
            if (Math.abs(v) == CableCars.GAUGE)
                c.set(x, cable, z, node(c.car(), "lower"));
            else if (Math.abs(v) == 3)
                c.set(x, cable, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            else
                c.set(x, cable, z, AIR);
        }
        // the footing's ends, along the line
        if (v == 0)
            for (int du : new int[] { -1, 1 }) {
                int fx = x + c.car().dx() * du, fz = z + c.car().dz() * du;
                if (!c.inChunk(fx, fz))
                    continue;
                int fg = c.ground(fx, fz, cable);
                for (int y = fg + 1; y <= g + 2; y++)
                    c.set(fx, y, fz, PLINTH);
            }
    }

    /** Steps from the base station's back door down the slope toward the city, till they meet the ground. */
    private static void steps(Ctx c, Car car, int x0, int z0) {
        int y = car.baseDeck();
        Direction down = c.along().getOpposite();
        for (int k = 1; k <= 40; k++) {
            int u = BASE_BACK - k;
            // where they land is decided from the raw terrain on the middle line, so every chunk agrees
            boolean landed = k > 1 && c.terrain(car.x(u, 0), car.z(u, 0)) >= y - 1;
            for (int v = -1; v <= 1; v++) {
                int x = car.x(u, v), z = car.z(u, v);
                if (!c.inChunk(x, z))
                    continue;
                int g = c.ground(x, z, y + 16);
                for (int yy = y + 1; yy <= y + 4; yy++)
                    c.set(x, yy, z, AIR);
                for (int yy = Math.max(g + 1, y - 30); yy < y; yy++)
                    c.set(x, yy, z, PLINTH);
                c.set(x, y, z, k == 1 ? FLOOR
                        : Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, down.getOpposite())
                                .setValue(StairBlock.HALF, Half.BOTTOM));
                if (Math.abs(v) == 1 && k % 4 == 1)
                    c.set(x, y + 1, z, k == 1 ? Blocks.LANTERN.defaultBlockState() : AIR);
            }
            if (landed)
                break;
            if (k > 1)
                y--;
        }
    }

    /** A board by each station's door: the line's name and where it goes. */
    private static void board(Ctx c, Car car, boolean base, String city) {
        int w = base ? BASE_BACK : TOP_BACK;
        int u = base ? w : car.length() - w;
        int deck = base ? car.baseDeck() : car.topDeck();
        int x = car.x(u, 2), z = car.z(u, 2);
        if (x < c.x0() || x >= c.x0() + 16 || z < c.z0() || z >= c.z0() + 16)
            return;
        Direction out = base ? c.along().getOpposite() : c.along();
        int bx = x + out.getStepX(), bz = z + out.getStepZ();
        if (bx < c.x0() || bx >= c.x0() + 16 || bz < c.z0() || bz >= c.z0() + 16)
            return;
        BlockState sign = Blocks.SPRUCE_WALL_SIGN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallSignBlock.FACING, out);
        c.set(bx, deck + 3, bz, sign);
        if (c.level().getBlockEntity(c.p().set(bx, deck + 3, bz)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity s) {
            String name = city == null ? "City" : city;
            var front = s.getFrontText()
                    .setMessage(0, net.minecraft.network.chat.Component.literal("CABLE CAR"))
                    .setMessage(1, net.minecraft.network.chat.Component.literal(base ? "to the summit" : "to " + name))
                    .setMessage(2, net.minecraft.network.chat.Component.literal(base ? name + " Peak" : ""))
                    .setMessage(3, net.minecraft.network.chat.Component.literal(base ? "" : "and the city"));
            // straight to the field: setText marks the sign updated through its level, null while generating
            s.frontText = front;
        }
    }
}
