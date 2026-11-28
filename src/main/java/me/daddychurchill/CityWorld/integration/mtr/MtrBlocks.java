package me.daddychurchill.CityWorld.integration.mtr;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * MTR's station furniture, looked up by name (null when MTR is not installed, or an MTR version renamed it), so a
 * station draws MTR's own platform edges, departure boards, bins and name posts where it can and plain blocks where it
 * cannot. The states' properties are read from each block's own definition: no MTR class is named.
 * <p>
 * Orientation, read from MTR 4.1's blocks: a platform block's {@code facing} is the way its yellow edge faces (toward
 * the track); a horizontal PIDS is a back-to-back pair (the second one {@code facing} away from the first, a block
 * further along {@code facing}), each showing its board on its outer side.
 */
public final class MtrBlocks {
    private MtrBlocks() {
    }

    private static Block block(String name) {
        if (!MtrStations.enabled())
            return null;
        var b = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("mtr", name));
        return b.filter(x -> x != Blocks.AIR).orElse(null);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BlockState with(BlockState state, String property, String value) {
        Property p = state.getBlock().getStateDefinition().getProperty(property);
        if (p == null)
            return state;
        var v = p.getValue(value);
        return v.isPresent() ? state.setValue(p, (Comparable) v.get()) : state;
    }

    private static BlockState state(String name, String... properties) {
        Block b = block(name);
        if (b == null)
            return null;
        BlockState s = b.defaultBlockState();
        for (int i = 0; i + 1 < properties.length; i += 2)
            s = with(s, properties[i], properties[i + 1]);
        return s;
    }

    /** Any MTR block by its name, with these properties (name, value, ...); null without MTR or without the block. */
    public static BlockState of(String name, String... properties) {
        return state(name, properties);
    }

    /** MTR's UK platform block, its edge toward the track; null without MTR. */
    public static BlockState platformEdge(Direction toTrack) {
        return state("platform_uk_1", "facing", toTrack.getSerializedName(), "door_type", "none");
    }

    /** MTR's plain platform block (the rest of the platform's top); null without MTR. */
    public static BlockState platform() {
        return state("platform", "door_type", "none");
    }

    /** A rubbish bin; null without MTR. */
    public static BlockState bin(Direction facing) {
        return state("rubbish_bin_1", "facing", facing.getSerializedName());
    }

    /** One of a back-to-back pair of departure boards (see the class note); null without MTR. */
    public static BlockState pids(Direction facing) {
        return state("pids_1", "facing", facing.getSerializedName());
    }

    /** The pole under a departure board; null without MTR. */
    public static BlockState pidsPole(Direction facing) {
        return state("pids_pole", "facing", facing.getSerializedName());
    }

    /**
     * One half of MTR's route sign, the line map (the routes calling at its platform, every stop along them): {@code kind}
     * {@code route_sign_wall_light} or {@code route_sign_standing_light}; {@code facing} as a player placing it looks,
     * so the map shows the other way. Null without MTR.
     */
    public static BlockState routeSign(String kind, Direction facing, boolean upper) {
        return state(kind, "facing", facing.getSerializedName(), "half", upper ? "upper" : "lower");
    }

    /**
     * One block of MTR's escalator: a step ({@code step}) or the handrail over it, {@code facing} uphill, the {@code left}
     * or right half of the pair (as one looks uphill), its {@code orientation} (landing_top, transition_top, slope,
     * transition_bottom, landing_bottom, flat: what MTR works out from its neighbours), running uphill. Null without MTR.
     */
    public static BlockState escalator(boolean step, Direction facing, boolean left, String orientation) {
        return escalator(step, facing, left, orientation, true);
    }

    /** As above, running uphill ({@code upward}) or down. */
    public static BlockState escalator(boolean step, Direction facing, boolean left, String orientation, boolean upward) {
        if (step)
            return state("escalator_step", "facing", facing.getSerializedName(), "side", left ? "left" : "right",
                    "orientation", orientation, "direction", Boolean.toString(upward), "status", "true");
        return state("escalator_side", "facing", facing.getSerializedName(), "side", left ? "left" : "right",
                "orientation", orientation);
    }

    /** A third ({@code lower}, {@code middle}, {@code upper}) of MTR's standing station-name post; null without MTR. */
    public static BlockState namePost(Direction facing, String third) {
        return state("station_name_tall_standing", "facing", facing.getSerializedName(), "third", third);
    }

    /**
     * MTR's rail node, connected, on an axis in degrees (0, 45, 90 or 135; MTR reads {@code facing} true as 0, false as
     * 90, and adds 45 for {@code is_45}); null without MTR.
     */
    public static BlockState railNode(int axis) {
        boolean along = axis == 0 || axis == 45;
        return state("rail", "facing", Boolean.toString(along), "is_22_5", "false", "is_45",
                Boolean.toString(axis == 45 || axis == 135), "is_connected", "true");
    }
}
