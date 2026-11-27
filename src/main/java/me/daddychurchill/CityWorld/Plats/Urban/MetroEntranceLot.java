package me.daddychurchill.CityWorld.Plats.Urban;

import me.daddychurchill.CityWorld.CityWorldGenerator;
import me.daddychurchill.CityWorld.Context.DataContext;
import me.daddychurchill.CityWorld.Plats.BuildingLot;
import me.daddychurchill.CityWorld.Plats.PlatLot;
import me.daddychurchill.CityWorld.Support.AbstractCachedYs;
import me.daddychurchill.CityWorld.Support.InitialBlocks;
import me.daddychurchill.CityWorld.Support.Metro;
import me.daddychurchill.CityWorld.Support.PlatMap;
import me.daddychurchill.CityWorld.Support.RealBlocks;
import me.daddychurchill.CityWorld.Support.Subway;
import me.daddychurchill.CityWorld.compat.BiomeGrid;
import me.daddychurchill.CityWorld.compat.BlockFace;
import me.daddychurchill.CityWorld.compat.Material;

/**
 * A metro station's street entrance: a small pavilion beside the line, its stair going down to the station's
 * concourse (a switchback, four steps a flight), and a passage at the bottom through the chunk edge into the
 * concourse's side. Claimed by {@link Metro#placeEntrances} while the platmap is planned; the station draws its end
 * of the passage when it finds this lot beside it.
 */
public class MetroEntranceLot extends BuildingLot {

    private final boolean ew, low;

    /** {@code ew}: the line is east-west; {@code low}: this lot is on its north (or west) side. */
    public MetroEntranceLot(PlatMap platmap, int chunkX, int chunkZ, boolean ew, boolean low) {
        super(platmap, chunkX, chunkZ);
        this.ew = ew;
        this.low = low;
        height = 1;
        depth = 0;
    }

    public boolean ew() {
        return ew;
    }

    public boolean low() {
        return low;
    }

    @Override
    public PlatLot newLike(PlatMap platmap, int chunkX, int chunkZ) {
        return new MetroEntranceLot(platmap, chunkX, chunkZ, ew, low);
    }

    @Override
    public boolean isConnectable(PlatLot relative) {
        return false;
    }

    @Override
    public boolean makeConnected(PlatLot relative) {
        return false;
    }

    @Override
    public boolean isConnected(PlatLot relative) {
        return false;
    }

    @Override
    protected boolean isShaftableLevel(CityWorldGenerator generator, int blockY) {
        return blockY < generator.streetLevel - Metro.NS_DEPTH - 4 && super.isShaftableLevel(generator, blockY);
    }

    @Override
    public int getBottomY(CityWorldGenerator generator) {
        return generator.streetLevel;
    }

    @Override
    public int getTopY(CityWorldGenerator generator, AbstractCachedYs blockYs, int x, int z) {
        return generator.streetLevel + 9;
    }

    /** Whether the stair can go down here: no subway tunnel crosses this chunk on its way. */
    public boolean hasStair(CityWorldGenerator generator) {
        return !Subway.drawsIn(generator, this) || Subway.at(generator, chunkX, chunkZ) == Subway.Piece.NONE;
    }

    @Override
    protected void generateActualChunk(CityWorldGenerator generator, PlatMap platmap, InitialBlocks chunk,
            BiomeGrid biomes, DataContext context, int platX, int platZ) {
        chunk.setLayer(generator.streetLevel, 2, Material.SMOOTH_STONE);
    }

    @Override
    protected void generateActualBlocks(CityWorldGenerator generator, PlatMap platmap, RealBlocks chunk,
            DataContext context, int platX, int platZ) {
        int floor = generator.streetLevel + 1, stand = floor + 1;
        int f = generator.streetLevel - (ew ? Metro.EW_DEPTH : Metro.NS_DEPTH);
        Metro.Lines lines = Metro.linesAt(generator, chunkX, chunkZ);
        Metro.Line line = ew ? lines.ew() : lines.ns();
        String name = "Metro";
        if (line != null) {
            int a = line.along(chunkX + (ew ? 0 : low ? 1 : -1), chunkZ + (ew ? (low ? 1 : -1) : 0));
            Metro.Station s = a == Integer.MIN_VALUE ? null : line.stationAt(a);
            if (s != null)
                name = Metro.stationName(generator, line, s);
        }
        boolean stair = line != null && hasStair(generator);
        // the shaft's corner and which way it runs: flights across the line's axis, the bottom door facing it
        boolean alongX = !ew, entryPositive = low;
        int x0, z0;
        if (ew) {
            x0 = 6;
            z0 = low ? 8 : 2;
        } else {
            x0 = low ? 8 : 2;
            z0 = 6;
        }
        // the pavilion; its door on the line's side moves clear of the stair head
        pavilion(chunk, platmap, platX, platZ, floor, stand, name, line == null ? Material.GRAY_CONCRETE : line.stripe(), stair);
        if (stair) {
            Subway.zigzag(chunk, x0, z0, alongX, entryPositive, f + 10, stand, f + 9, Subway.Door.ALONG,
                    Subway.Door.ACROSS_HIGH, 4);
            // the passage at the bottom, from the shaft's end through the chunk edge (the station opens the other side)
            int y = f + 10;
            if (ew) {
                int za = low ? 14 : 0, zb = low ? 15 : 1;
                chunk.setBlocks(5, 11, y - 2, y, za, zb + 1, Metro.SHELL);
                chunk.setBlocks(5, 11, y, y + 3, za, zb + 1, Metro.TILE);
                chunk.setBlocks(6, 10, y - 1, y, za, zb + 1, Metro.PLATFORM);
                chunk.setBlocks(6, 10, y, y + 3, za, zb + 1, Material.AIR);
                chunk.setBlocks(5, 11, y + 3, y + 4, za, zb + 1, Metro.SHELL);
            } else {
                int xa = low ? 14 : 0, xb = low ? 15 : 1;
                chunk.setBlocks(xa, xb + 1, y - 2, y, 5, 11, Metro.SHELL);
                chunk.setBlocks(xa, xb + 1, y, y + 3, 5, 11, Metro.TILE);
                chunk.setBlocks(xa, xb + 1, y - 1, y, 6, 10, Metro.PLATFORM);
                chunk.setBlocks(xa, xb + 1, y, y + 3, 6, 10, Material.AIR);
                chunk.setBlocks(xa, xb + 1, y + 3, y + 4, 5, 11, Metro.SHELL);
            }
            // bars wherever the floor drops away more than a step at the stair head
            for (int x = x0 - 1; x <= x0 + (alongX ? 6 : 4); x++)
                for (int z = z0 - 1; z <= z0 + (alongX ? 4 : 6); z++)
                    if (x >= 0 && x <= 15 && z >= 0 && z <= 15 && chunk.isEmpty(x, stand, z)
                            && chunk.isEmpty(x, stand - 1, z) && chunk.isEmpty(x, stand - 2, z))
                        chunk.setBlock(x, stand, z, Material.IRON_BARS);
            chunk.reconnect(0, 16, f + 9, f + 13, 0, 16);
        }
        chunk.reconnect(0, 16, stand, stand + 6, 0, 16);
        generator.reportLocation("metro", name + " Metro", chunk);
        if (buildingsDecay(generator))
            destroyLot(generator, stand, stand + 5);
    }

    /** Which sides face a road: {N, S, E, W}. */
    private static boolean[] roadSides(PlatMap platmap, int platX, int platZ) {
        return new boolean[] { platmap.isExistingRoad(platX, platZ - 1), platmap.isExistingRoad(platX, platZ + 1),
                platmap.isExistingRoad(platX + 1, platZ), platmap.isExistingRoad(platX - 1, platZ) };
    }

    /** The pavilion: the subway's ticket hall without its booth, an "M" roundel by every door. */
    private void pavilion(RealBlocks chunk, PlatMap platmap, int platX, int platZ, int floor, int stand, String name,
            Material stripe, boolean open) {
        chunk.setLayer(floor, Subway.PLATFORM);
        chunk.setBlocks(0, 16, stand, stand + 7, 0, 16, Material.AIR);
        for (int i = 1; i <= 14; i++)
            for (int[] w : new int[][] { { i, 1 }, { i, 14 }, { 1, i }, { 14, i } }) {
                int x = w[0], z = w[1];
                boolean pillar = i == 1 || i == 14 || i % 4 == 2;
                chunk.setBlock(x, stand, z, pillar ? Subway.RING : Subway.HALL_WALL);
                chunk.setBlocks(x, stand + 1, stand + 3, z, pillar ? Subway.RING : Material.GLASS_PANE);
                chunk.setBlock(x, stand + 3, z, pillar ? Subway.RING : stripe);
                chunk.setBlock(x, stand + 4, z, pillar ? Subway.RING : Subway.HALL_WALL);
            }
        chunk.setBlocks(1, 15, stand + 5, stand + 6, 1, 15, Subway.SHELL);
        for (int i = 1; i <= 14; i++)
            for (int[] w : new int[][] { { i, 1 }, { i, 14 }, { 1, i }, { 14, i } })
                chunk.setBlock(w[0], stand + 6, w[1], Subway.RING);
        boolean[] roads = roadSides(platmap, platX, platZ);
        // the wall on the line's side: the stair's head is against it, so its door moves to 11..13
        int lineSide = ew ? (low ? 1 : 0) : (low ? 2 : 3);
        if (!roads[0] && !roads[1] && !roads[2] && !roads[3])
            roads[lineSide ^ 1] = true;
        for (int side = 0; side < 4; side++) {
            if (!roads[side])
                continue;
            BlockFace into = switch (side) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.NORTH;
            case 2 -> BlockFace.WEST;
            default -> BlockFace.EAST;
            };
            int wall = side == 0 || side == 3 ? 1 : 14;
            int from = side == lineSide ? 11 : 7;
            for (int along = from; along <= from + 2; along++) {
                int x = side < 2 ? along : wall, z = side < 2 ? wall : along;
                chunk.setBlocks(x, stand, stand + 3, z, Material.AIR);
            }
            int d = -1; // outside, facing the street
            int sx = side < 2 ? from : wall + (side == 2 ? -d : d), sz = side < 2 ? wall + (side == 0 ? d : -d) : from;
            chunk.setWallSign(sx, stand + 3, sz, into.getOppositeFace(), "METRO", name, open ? "↓ trains" : "(closed)");
        }
        for (int[] p : new int[][] { { 4, 4 }, { 11, 4 }, { 4, 11 }, { 11, 11 } })
            chunk.setBlock(p[0], stand + 5, p[1], Subway.LIGHT);
    }
}
