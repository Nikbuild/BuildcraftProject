package com.nick.buildcraft.content.block.quarry;

import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-authoritative obstacle detection for quarry gantry movement.
 *
 * An obstacle is defined as any block that:
 * - Is above the dig limit Y (typically the current mining layer)
 * - Is within the X/Z perimeter of the quarry frame (interior only)
 * - Is below or equal to the maximum frame height
 * - Is not air, a fluid source, or part of the frame structure
 *
 * This class provides deterministic scanning that is identical across
 * all clients and the server, ensuring no desync.
 */
public final class ObstacleScanner {

    /** Height buffer below ceiling to avoid detecting frame blocks as obstacles */
    private static final int CEILING_GUARD_HEIGHT = 2;

    private ObstacleScanner() {}

    /**
     * Scans a vertical column for the highest obstacle.
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param x Column X coordinate
     * @param z Column Z coordinate
     * @param minY Lowest Y to scan (usually the working/mining level)
     * @return Highest obstacle Y, or -1 if no obstacles found
     */
    public static int findHighestObstacle(Level level, QuarryGeometryHelper.Bounds bounds, int x, int z, int minY) {
        // Validate column is within interior bounds
        if (!isWithinInterior(x, z, bounds)) {
            return -1;
        }

        int highest = -1;
        // Scan from minY up to just below the ceiling guard zone
        int maxScanY = bounds.y1 - CEILING_GUARD_HEIGHT - 1;

        for (int y = minY; y <= maxScanY; y++) {
            if (isObstacle(level, bounds, x, y, z)) {
                highest = y;
            }
        }

        return highest;
    }

    /**
     * Checks if a specific position is an obstacle.
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return true if position contains an obstacle
     */
    public static boolean isObstacle(Level level, QuarryGeometryHelper.Bounds bounds, int x, int y, int z) {
        // Must be within frame interior
        if (!isWithinInterior(x, z, bounds)) {
            return false;
        }

        // Must be within vertical bounds (above floor, below ceiling guard)
        if (y < bounds.y0 || y > bounds.y1 - CEILING_GUARD_HEIGHT) {
            return false;
        }

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);

        // Air is not an obstacle
        if (state.isAir()) {
            return false;
        }

        // Frame blocks are not obstacles
        if (state.is(ModBlocks.FRAME.get())) {
            return false;
        }

        // Quarry controller is not an obstacle
        if (state.is(ModBlocks.QUARRY_CONTROLLER.get())) {
            return false;
        }

        // Source fluids are handled by pump, not obstacles for gantry
        if (!state.getFluidState().isEmpty() && state.getFluidState().isSource()) {
            return false;
        }

        // Blocks without collision shape (tall grass, flowers, etc.) are not obstacles
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }

        return true;
    }

    /**
     * Checks if a column is completely blocked (obstacle extends to ceiling).
     * These columns are unreachable and should be avoided by pathfinding.
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param x Column X coordinate
     * @param z Column Z coordinate
     * @return true if column is blocked at ceiling level
     */
    public static boolean isColumnBlockedAtCeiling(Level level, QuarryGeometryHelper.Bounds bounds, int x, int z) {
        int y0 = bounds.y1 - CEILING_GUARD_HEIGHT;
        for (int dy = 0; dy < CEILING_GUARD_HEIGHT; dy++) {
            if (isObstacle(level, bounds, x, y0 + dy, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a column has an obstacle that cannot be cleared by maximum lift.
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param x Column X coordinate
     * @param z Column Z coordinate
     * @param minY Minimum Y to scan from
     * @return true if column has an impassable obstacle
     */
    public static boolean isColumnImpassable(Level level, QuarryGeometryHelper.Bounds bounds, int x, int z, int minY) {
        int obstacleY = findHighestObstacle(level, bounds, x, z, minY);
        if (obstacleY < 0) {
            return false; // No obstacle
        }

        // Maximum possible lift is from floor to ceiling
        int maxLift = bounds.y1 - bounds.y0 - CEILING_GUARD_HEIGHT;

        // Clearance needed: obstacle Y - floor Y + safety margin
        double clearanceNeeded = (obstacleY - bounds.y0) + 1.5;

        return clearanceNeeded > maxLift;
    }

    /**
     * Scans a corridor (path) for the highest obstacle encountered.
     * Used for pre-calculating required lift before movement.
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param path List of positions to check
     * @param minY Minimum Y to scan from
     * @return Highest obstacle Y across entire path, or -1 if clear
     */
    public static int scanPathForHighestObstacle(Level level, QuarryGeometryHelper.Bounds bounds,
                                                   Iterable<BlockPos> path, int minY) {
        int highest = -1;
        for (BlockPos pos : path) {
            int obstacleY = findHighestObstacle(level, bounds, pos.getX(), pos.getZ(), minY);
            if (obstacleY > highest) {
                highest = obstacleY;
            }
        }
        return highest;
    }

    /**
     * Calculate the required lift height to clear all obstacles in a path.
     *
     * gantryLiftY represents how many blocks the drill tip is raised ABOVE bounds.y0 (frame floor).
     * If obstacle is at world Y=65 and bounds.y0=60, obstacle is at relative height 5.
     * To clear it, drill tip needs to be at relative height 7 (obstacle + 2 block clearance).
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param currentX Current gantry X
     * @param currentZ Current gantry Z
     * @param targetX Target X
     * @param targetZ Target Z
     * @param currentDrillBase Unused, kept for API compatibility
     * @return Required lift in blocks (gantryLiftY value), 0.0 if path is clear
     */
    public static double calculateRequiredLift(Level level, QuarryGeometryHelper.Bounds bounds,
                                                int currentX, int currentZ, int targetX, int targetZ,
                                                int currentDrillBase) {
        // Scan along the movement corridor for highest obstacle
        int highestObstacleWorldY = -1;

        // Bresenham-style line scan from current to target (inclusive of both endpoints)
        int dx = Math.abs(targetX - currentX);
        int dz = Math.abs(targetZ - currentZ);
        int sx = currentX < targetX ? 1 : -1;
        int sz = currentZ < targetZ ? 1 : -1;
        int err = dx - dz;

        int x = currentX;
        int z = currentZ;

        while (true) {
            // Check this column from frame floor up to ceiling
            int obstacleY = findHighestObstacle(level, bounds, x, z, bounds.y0);
            if (obstacleY > highestObstacleWorldY) {
                highestObstacleWorldY = obstacleY;
            }

            if (x == targetX && z == targetZ) break;

            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }

        if (highestObstacleWorldY < 0) {
            return 0.0; // Path is clear, no lift needed
        }

        // Convert obstacle world Y to relative height above frame floor
        int obstacleRelativeY = highestObstacleWorldY - bounds.y0;

        // Required lift = obstacle height + 3 blocks clearance
        // (1 block above obstacle top + 1 for drill tip height + 1 safety margin)
        // This ensures the drill tip clears the obstacle during horizontal movement
        double requiredLift = obstacleRelativeY + 3.0;

        // Clamp to maximum possible lift (can't go above frame ceiling)
        int maxLift = bounds.y1 - bounds.y0 - CEILING_GUARD_HEIGHT;
        return Math.min(requiredLift, maxLift);
    }

    /**
     * Check if the given coordinates are within the quarry interior.
     */
    private static boolean isWithinInterior(int x, int z, QuarryGeometryHelper.Bounds bounds) {
        int xMin = bounds.x0 + 1;
        int xMax = bounds.x1 - 1;
        int zMin = bounds.z0 + 1;
        int zMax = bounds.z1 - 1;
        return x >= xMin && x <= xMax && z >= zMin && z <= zMax;
    }

    /**
     * Find the top of any solid blocks in a column (for determining current drill base).
     *
     * @param level The world level
     * @param bounds The quarry bounds
     * @param x Column X
     * @param z Column Z
     * @return Top Y of solid blocks in column, or bounds.y0 - 1 if column is empty
     */
    public static int findColumnTop(Level level, QuarryGeometryHelper.Bounds bounds, int x, int z) {
        int maxScanY = bounds.y1 - CEILING_GUARD_HEIGHT - 1;

        for (int y = maxScanY; y >= bounds.y0 - 1; y--) {
            if (y < bounds.y0 - 1) {
                return bounds.y0 - 1; // Column is empty down to bedrock area
            }

            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);

            if (!state.isAir() && !state.getFluidState().isSource() &&
                !state.getCollisionShape(level, pos).isEmpty()) {
                return y;
            }
        }

        return bounds.y0 - 1;
    }
}
