package com.nick.buildcraft.content.block.quarry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Final layer sweep detection and execution.
 * Handles cleanup of remaining blocks at layer boundaries.
 */
public class QuarrySweepManager {

    /**
     * Queue a one-time final sweep for unreachable blocks at the layer's end.
     */
    public static void queueFinalSweepIfNeeded(QuarryBlockEntity qbe, Level level, QuarryGeometryHelper.Bounds b, Direction facing, int y, BlockPos currentCell) {
        qbe.finalSweepCheckedThisLayer = true;  // ensure we compute once per layer
        qbe.finalSweepTargets.clear();

        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;

        List<BlockPos> missing = new ArrayList<>();
        for (int z = zMin; z <= zMax; z++) {
            for (int x = xMin; x <= xMax; x++) {
                if (QuarryGantryManager.columnBlockedAtCeiling(level, b, x, z)) continue;
                BlockPos p = new BlockPos(x, y, z);
                if (QuarryMiningManager.shouldMine(level, p)) missing.add(p);
            }
        }

        if (missing.isEmpty()) {
            qbe.finalSweepPending = false;
            return;
        }

        // Sort by Manhattan distance from where we are now to keep travel cheap
        missing.sort(Comparator.comparingInt(p ->
                Math.abs(p.getX() - currentCell.getX()) + Math.abs(p.getZ() - currentCell.getZ())));

        qbe.finalSweepTargets.addAll(missing);
        qbe.finalSweepPending = true;
    }

    /**
     * Check if we're at the expected end cell for the current layer.
     */
    public static boolean isAtExpectedLayerEndCell(QuarryBlockEntity qbe, QuarryGeometryHelper.Bounds b, Direction facing, BlockPos cell) {
        BlockPos end = expectedEndCellForCurrentLayer(qbe, b, facing);
        return end.getX() == cell.getX() && end.getZ() == cell.getZ();
    }

    /**
     * Calculate the expected end position for the current layer's boustrophedon sweep.
     */
    private static BlockPos expectedEndCellForCurrentLayer(QuarryBlockEntity qbe, QuarryGeometryHelper.Bounds b, Direction facing) {
        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;

        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            int depth = zMax - zMin + 1;
            int startRow = (facing == Direction.NORTH) ? (qbe.layerStartAtTop ? zMin : zMax)
                    : (qbe.layerStartAtTop ? zMax : zMin);
            int stepRow  = (startRow == zMin) ? +1 : -1;
            int endRow   = startRow + stepRow * (depth - 1);

            boolean lastRowLR = ((depth - 1) % 2 == 0) ? qbe.layerLeftToRight : !qbe.layerLeftToRight;
            boolean inc = (facing == Direction.NORTH) ? lastRowLR : !lastRowLR;
            int endCol = inc ? xMax : xMin;
            return new BlockPos(endCol, b.y0, endRow);
        } else {
            int width = xMax - xMin + 1;
            int startRow = (facing == Direction.EAST) ? (qbe.layerStartAtTop ? xMax : xMin)
                    : (qbe.layerStartAtTop ? xMin : xMax);
            int stepRow  = (startRow == xMin) ? +1 : -1;
            int endRow   = startRow + stepRow * (width - 1);

            boolean lastRowLR = ((width - 1) % 2 == 0) ? qbe.layerLeftToRight : !qbe.layerLeftToRight;
            boolean inc = (facing == Direction.EAST) ? lastRowLR : !lastRowLR;
            int endCol = inc ? zMax : zMin;
            return new BlockPos(endRow, b.y0, endCol);
        }
    }
}
