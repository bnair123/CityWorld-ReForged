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
