package com.nick.buildcraft.content.block.quarry;

import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;

/**
 * Mining logic and boustrophedon sweep pattern.
 * Handles layer initialization, forward-only scanning, and layer descent.
 */
public class QuarryMiningManager {

    private static final int MINE_TICKS_PER_BLOCK = 10;
    private static final int DRILL_TIME = MINE_TICKS_PER_BLOCK;

    /**
     * Server tick for mining state machine.
     * Handles layer descent, target selection, and block mining.
     */
    public static void stepMining(QuarryBlockEntity qbe, ServerLevel level, BlockPos origin, BlockState controllerState) {
        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(origin, facing);

        final int topY = b.y0 - 1; // just below bottom frame (ceiling band)
        final int minY = level.dimensionType().minY();

        if (qbe.layerY == null) {
            qbe.layerY = topY;
            qbe.layerLeftToRight = false; // start at right edge
            qbe.layerStartAtTop  = false; // start from NEAR edge
            if (qbe.targetCol == null) qbe.targetCol = nearRightInterior(b, facing);
            qbe.atTarget = false;
            qbe.drillTicks = 0;

            qbe.finalSweepPending = false;
            qbe.finalSweepCheckedThisLayer = false;
            qbe.finalSweepTargets.clear();
        }

        // Only mine when carriage is centered on the current target column
        if (!qbe.atTarget) {
            // Reset mining progress if we moved away
            if (qbe.currentlyMining != null) {
                level.destroyBlockProgress(qbe.getBlockPos().hashCode(), qbe.currentlyMining, -1);
                qbe.currentlyMining = null;
                qbe.miningDamage = 0;
            }
            return;
        }

        // BEFORE the dwell, lazily check ONLY THIS column for any grief above the current working layer
        if (qbe.overrideMineY == null && qbe.targetCol != null) {
            Integer gy = griefYInColumnAbove(level, b, qbe.targetCol.getX(), qbe.targetCol.getZ(), topY, qbe.layerY + 1);
            if (gy != null) qbe.overrideMineY = gy;
        }

        // Decide what Y to mine: prefer a grief block in this column if present
        int mineY = (qbe.overrideMineY != null) ? qbe.overrideMineY : qbe.layerY;
        BlockPos p = new BlockPos(qbe.targetCol.getX(), mineY, qbe.targetCol.getZ());

        // If the grief block vanished before we mined, clear override and fall back to layerY
        if (qbe.overrideMineY != null && !shouldMine(level, p)) {
            qbe.overrideMineY = null;
            mineY = qbe.layerY;
            p = new BlockPos(qbe.targetCol.getX(), mineY, qbe.targetCol.getZ());
        }

        if (shouldMine(level, p)) {
            // Track which block we're mining
            if (qbe.currentlyMining == null || !qbe.currentlyMining.equals(p)) {
                // Started mining a new block - clear old damage
                if (qbe.currentlyMining != null) {
                    level.destroyBlockProgress(qbe.getBlockPos().hashCode(), qbe.currentlyMining, -1);
                }
                qbe.currentlyMining = p;
                qbe.miningDamage = 0;
                qbe.drillTicks = 0;
            }

            // Increment drill ticks and show progressive damage
            qbe.drillTicks++;

            // Calculate damage stage (0-10 based on progress)
            int damageStage = (int)((qbe.drillTicks / (float)DRILL_TIME) * 10);
            damageStage = Math.min(9, damageStage); // Max stage is 9 (0-9 = 10 stages)

            // Send block damage to clients
            if (damageStage != qbe.miningDamage) {
                qbe.miningDamage = damageStage;
                level.destroyBlockProgress(qbe.getBlockPos().hashCode(), p, damageStage);
            }

            // Wait for full drill time
            if (qbe.drillTicks < DRILL_TIME) return;
            qbe.drillTicks = 0;

            // Block is fully broken - clear damage overlay and mine it
            level.destroyBlockProgress(qbe.getBlockPos().hashCode(), p, -1);
            qbe.currentlyMining = null;
            qbe.miningDamage = 0;

            if (!QuarryBalancer.tryConsumeToken(level)) { return; }

            final boolean handledOverride = (qbe.overrideMineY != null);

            // Actually mine here (collect to queue only — no spawning here)
            QuarryOutputManager.mineOneBlockToQueue(qbe, level, origin, p);
            qbe.lastMined = p;

            if (handledOverride) {
                // Stay on the column and re-check for more grief next tick
                qbe.overrideMineY = null;
                return;
            }

            // Normal on-layer mining: pick the next target AHEAD on this layer (no backtracking)
            BlockPos nextOnLayer = findNextOnLayerForward(level, b, facing, qbe.targetCol, qbe.layerY, qbe);
            if (nextOnLayer != null) {
                qbe.targetCol = new BlockPos(nextOnLayer.getX(), b.y0, nextOnLayer.getZ());
                qbe.atTarget = false;
                return;
            }

            // No forward cell → maybe one-time final sweep if at the layer's end corner
            if (!qbe.finalSweepCheckedThisLayer && QuarrySweepManager.isAtExpectedLayerEndCell(qbe, b, facing, qbe.targetCol)) {
                QuarrySweepManager.queueFinalSweepIfNeeded(qbe, level, b, facing, qbe.layerY, qbe.targetCol);
                if (qbe.finalSweepPending && !qbe.finalSweepTargets.isEmpty()) {
                    BlockPos nxt = qbe.finalSweepTargets.pollFirst();
                    qbe.targetCol = new BlockPos(nxt.getX(), b.y0, nxt.getZ());
                    qbe.atTarget = false;
                    return;
                }
            }
            // fall through to descend
        }

        // If we're already in final-sweep mode, continue pulling from the queue
        if (qbe.finalSweepPending) {
            if (!qbe.finalSweepTargets.isEmpty()) {
                BlockPos nxt = qbe.finalSweepTargets.pollFirst();
                qbe.targetCol = new BlockPos(nxt.getX(), b.y0, nxt.getZ());
                qbe.atTarget = false;
                return;
            } else {
                // Finished the one-time final sweep for this layer
                qbe.finalSweepPending = false;
                qbe.finalSweepCheckedThisLayer = true;
            }
        }

        // Continue searching forward; if empty, descend to next layer
        BlockPos searchFrom = qbe.targetCol; // search forward only
        BlockPos next;
        while (true) {
            next = findNextOnLayerForward(level, b, facing, searchFrom, qbe.layerY, qbe);
            if (next != null) {
                qbe.targetCol = new BlockPos(next.getX(), b.y0, next.getZ());
                qbe.atTarget = false;
                break;
            }

            // No forward targets left; if we haven't done the end-corner check yet, do it now
            if (!qbe.finalSweepCheckedThisLayer && QuarrySweepManager.isAtExpectedLayerEndCell(qbe, b, facing, qbe.targetCol)) {
                QuarrySweepManager.queueFinalSweepIfNeeded(qbe, level, b, facing, qbe.layerY, qbe.targetCol);
                if (qbe.finalSweepPending && !qbe.finalSweepTargets.isEmpty()) {
                    BlockPos nxt = qbe.finalSweepTargets.pollFirst();
                    qbe.targetCol = new BlockPos(nxt.getX(), b.y0, nxt.getZ());
                    qbe.atTarget = false;
                    break;
                }
            }

            // Descend one layer
            qbe.layerY--;
            qbe.layerLeftToRight = !qbe.layerLeftToRight;
            qbe.layerStartAtTop  = !qbe.layerStartAtTop;
            qbe.tempSkip.clear(); // clear unreachable cache across layers
            qbe.finalSweepPending = false;
            qbe.finalSweepCheckedThisLayer = false;
            qbe.finalSweepTargets.clear();

            if (qbe.layerY < minY) { return; }

            // For the NEW layer we start from the layer's start position
            searchFrom = null;
        }
    }

    /**
     * Scan forward on a single Y layer in LOCAL boustrophedon order (facing-aware).
     * If 'from' is null, start at the layer's start; otherwise begin strictly after 'from'.
     */
    public static BlockPos findNextOnLayerForward(Level level, QuarryGeometryHelper.Bounds b, Direction facing, BlockPos from, int y, QuarryBlockEntity qbe) {
        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;

        boolean rowsAreZ = (facing == Direction.NORTH || facing == Direction.SOUTH);
        int rowMin = rowsAreZ ? zMin : xMin;
        int rowMax = rowsAreZ ? zMax : xMax;
        int colMin = rowsAreZ ? xMin : zMin;
        int colMax = rowsAreZ ? xMax : zMax;

        // Determine row iteration order based on layer start position
        int startRow = switch (facing) {
            case NORTH -> (qbe.layerStartAtTop ? zMin : zMax);
            case SOUTH -> (qbe.layerStartAtTop ? zMax : zMin);
            case EAST  -> (qbe.layerStartAtTop ? xMax : xMin);
            case WEST  -> (qbe.layerStartAtTop ? xMin : xMax);
            default    -> (qbe.layerStartAtTop ? zMin : zMax);
        };

        int stepRow = (startRow == rowMin) ? +1 : -1;

        // For NORTH/EAST: lr==true means increasing columns; for SOUTH/WEST it's inverted
        boolean lrTrueIsInc = (facing == Direction.NORTH || facing == Direction.EAST);

        int curRow, curCol;

        if (from == null) {
            curRow = startRow;
            int rowIdx = 0;
            curCol = getColForRow(qbe, rowIdx, rowMin, colMin, colMax, lrTrueIsInc);
        } else {
            int fromRow = rowsAreZ ? from.getZ() : from.getX();
            int fromCol = rowsAreZ ? from.getX() : from.getZ();
            int rowIdx = Math.abs(fromRow - startRow);
            boolean lrRow = ((rowIdx & 1) == 0) ? qbe.layerLeftToRight : !qbe.layerLeftToRight;
            boolean inc = lrTrueIsInc ? lrRow : !lrRow;

            curRow = fromRow;
            curCol = fromCol + (inc ? +1 : -1); // strictly after 'from'
            if (curCol < colMin || curCol > colMax) {
                curRow = fromRow + stepRow;
                if (curRow < rowMin || curRow > rowMax) return null; // layer exhausted
                int nextRowIdx = Math.abs(curRow - startRow);
                curCol = getColForRow(qbe, nextRowIdx, rowMin, colMin, colMax, lrTrueIsInc);
            }
        }

        while (curRow >= rowMin && curRow <= rowMax) {
            int rowIdx = Math.abs(curRow - startRow);
            int colStep = getColStepForRow(qbe, rowIdx, lrTrueIsInc);

            for (int c = curCol; c >= colMin && c <= colMax; c += colStep) {
                int x = rowsAreZ ? c : curRow;
                int z = rowsAreZ ? curRow : c;
                if (QuarryGantryManager.columnBlockedAtCeiling(level, b, x, z)) continue;
                BlockPos p = new BlockPos(x, y, z);
                if (shouldMine(level, p)) return p;
            }

            curRow += stepRow;
            if (curRow < rowMin || curRow > rowMax) break;
            int nextRowIdx = Math.abs(curRow - startRow);
            curCol = getColForRow(qbe, nextRowIdx, rowMin, colMin, colMax, lrTrueIsInc);
        }

        return null;
    }

    private static int getColForRow(QuarryBlockEntity qbe, int rowIdxFromStart, int rowMin, int colMin, int colMax, boolean lrTrueIsInc) {
        boolean lrRow = ((rowIdxFromStart & 1) == 0) ? qbe.layerLeftToRight : !qbe.layerLeftToRight;
        boolean inc = lrTrueIsInc ? lrRow : !lrRow;
        return inc ? colMin : colMax;
    }

    private static int getColStepForRow(QuarryBlockEntity qbe, int rowIdxFromStart, boolean lrTrueIsInc) {
        boolean lrRow = ((rowIdxFromStart & 1) == 0) ? qbe.layerLeftToRight : !qbe.layerLeftToRight;
        boolean inc = lrTrueIsInc ? lrRow : !lrRow;
        return inc ? +1 : -1;
    }

    /**
     * Look only up THIS column for the highest grief block above the current layer.
     */
    private static Integer griefYInColumnAbove(Level level, QuarryGeometryHelper.Bounds b, int x, int z, int topY, int stopYExclusive) {
        BlockPos.MutableBlockPos tmpPos = new BlockPos.MutableBlockPos();
        for (int y = topY; y >= stopYExclusive; --y) {
            tmpPos.set(x, y, z);
            if (shouldMine(level, tmpPos)) return y;
        }
        return null;
    }

    /**
     * True if we should mine this block.
     */
    public static boolean shouldMine(Level level, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        if (bs.isAir()) return false;
        FluidState fs = bs.getFluidState();
        if (!fs.isEmpty()) return false;
        if (bs.getDestroySpeed(level, p) < 0) return false; // unbreakable
        if (bs.is(ModBlocks.FRAME.get())) return false;     // don't mine our own frame
        return true;
    }

    private static BlockPos nearRightInterior(QuarryGeometryHelper.Bounds b, Direction facing) {
        int xMin = b.x0 + 1, xMax = b.x1 - 1;
        int zMin = b.z0 + 1, zMax = b.z1 - 1;
        return switch (facing) {
            case NORTH -> new BlockPos(xMax, b.y0, zMax);
            case SOUTH -> new BlockPos(xMin, b.y0, zMin);
            case EAST  -> new BlockPos(xMin, b.y0, zMax);
            case WEST  -> new BlockPos(xMax, b.y0, zMin);
            default    -> new BlockPos(xMax, b.y0, zMax);
        };
    }
}
