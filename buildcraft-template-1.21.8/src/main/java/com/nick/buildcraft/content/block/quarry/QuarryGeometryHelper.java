package com.nick.buildcraft.content.block.quarry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Static geometry calculations for the quarry.
 * Handles facing-aware bounds, frame edge generation, and utility math.
 */
public class QuarryGeometryHelper {

    private static final int HALF = 5;    // 11x11 interior including frame
    private static final int HEIGHT = 5;  // gantry height above controller

    public static Bounds boundsForFacing(BlockPos pos, Direction facing) {
        final int size = 2 * HALF + 1;
        int x0, x1, z0, z1;
        int y0 = pos.getY(), y1 = pos.getY() + HEIGHT;

        switch (facing) {
            case NORTH -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() - size; z1 = pos.getZ() - 1; }
            case SOUTH -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() + 1;    z1 = pos.getZ() + size; }
            case WEST  -> { x0 = pos.getX() - size; x1 = pos.getX() - 1;    z0 = pos.getZ() - HALF; z1 = pos.getZ() + HALF; }
            case EAST  -> { x0 = pos.getX() + 1;    x1 = pos.getX() + size; z0 = pos.getZ() - HALF; z1 = pos.getZ() + HALF; }
            default    -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() - size; z1 = pos.getZ() - 1; }
        }
        return new Bounds(x0, y0, z0, x1, y1, z1);
    }

    public static Iterable<BlockPos> frameEdges(BlockPos min, BlockPos max) {
        List<BlockPos> out = new ArrayList<>();
        int x0 = Math.min(min.getX(), max.getX()), x1 = Math.max(min.getX(), max.getX());
        int y0 = Math.min(min.getY(), max.getY()), y1 = Math.max(min.getY(), max.getY());
        int z0 = Math.min(min.getZ(), max.getZ()), z1 = Math.max(min.getZ(), max.getZ());

        for (int y : new int[]{y0, y1})
            for (int z : new int[]{z0, z1})
                for (int x = x0; x <= x1; x++) out.add(new BlockPos(x, y, z));
        for (int y : new int[]{y0, y1})
            for (int x : new int[]{x0, x1})
                for (int z = z0; z <= z1; z++) out.add(new BlockPos(x, y, z));
        for (int x : new int[]{x0, x1})
            for (int z : new int[]{z0, z1})
                for (int y = y0; y <= y1; y++) out.add(new BlockPos(x, y, z));

        return out;
    }

    public static int xMin(Bounds b) { return b.x0 + 1; }
    public static int xMax(Bounds b) { return b.x1 - 1; }
    public static int zMin(Bounds b) { return b.z0 + 1; }
    public static int zMax(Bounds b) { return b.z1 - 1; }

    public static int floor(double d) { return (int) Math.floor(d); }

    /**
     * Tiny bounds record for efficient spatial queries.
     */
    public static final class Bounds {
        public final int x0, y0, z0, x1, y1, z1;
        public Bounds(int x0, int y0, int z0, int x1, int y1, int z1) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
        }
        public BlockPos min() { return new BlockPos(x0, y0, z0); }
        public BlockPos max() { return new BlockPos(x1, y1, z1); }
    }
}
