package me.daddychurchill.CityWorld.Support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Glass panes, iron bars, fences and walls joined (and stairs turned into corners) to what stands beside them. Generation places each one in its
 * default, unjoined state, and nothing reshapes it until a neighbour changes, so a station's windows stood as thin
 * posts until a player placed a block beside them. After a part of the world is drawn, this asks each such block what
 * shape its neighbours give it, as a player's placement would.
 */
public final class Connections {
    private Connections() {
    }

    /** Joins every pane, bar, fence and wall in one chunk's columns from {@code yLo} to {@code yHi}. */
    public static int reconnectChunk(LevelAccessor level, int chunkX, int chunkZ, int yLo, int yHi) {
        int x0 = chunkX << 4, z0 = chunkZ << 4, changed = 0;
        yLo = Math.max(yLo, level.getMinBuildHeight());
        yHi = Math.min(yHi, level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = x0; x < x0 + 16; x++)
            for (int z = z0; z < z0 + 16; z++)
                for (int y = yLo; y <= yHi; y++) {
                    BlockState state = level.getBlockState(cur.set(x, y, z));
                    Block b = state.getBlock();
                    if (!(b instanceof IronBarsBlock || b instanceof FenceBlock || b instanceof WallBlock
                            || b instanceof StairBlock || isScreenDoor(b)))
                        continue;
                    BlockPos pos = cur.immutable();
                    BlockState joined = Block.updateFromNeighbourShapes(state, level, pos);
                    if (joined != state && !joined.isAir()) {
                        // known shape: the neighbours are reshaped by their own turn in this pass, not by this one
                        level.setBlock(pos, joined, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        changed++;
                    }
                }
        return changed;
    }

    /** MTR's platform screen doors and their glass, ends and tops, which shape themselves to their neighbours. */
    private static boolean isScreenDoor(Block b) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        return "mtr".equals(key.getNamespace()) && key.getPath().startsWith("psd_");
    }
}
