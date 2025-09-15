package com.nick.buildcraft.content.block.quarry;

import com.mojang.serialization.Codec;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import com.nick.buildcraft.content.entity.laser.LaserEntity;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import com.nick.buildcraft.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import com.nick.buildcraft.energy.BCEnergyStorage;
import com.nick.buildcraft.energy.Energy;


import java.util.*;

/**
 * Server-authoritative quarry with facing-aware boustrophedon sweep, gantry motion, and
 * drop-safe output via a small queue to prevent duplication and "pop-outs".
 *
 * Output gating: queued items are preserved across power cycles, but they do not flush
 * until at least one new block is mined after power-on.
 */
public class QuarryBlockEntity extends BlockEntity {

    /* ---------- tuning ---------- */

    private static final int FRAME_TICKS_PER_PIECE = 2;

    /** ~0.5s per block at 20 tps */
    private static final int MINE_TICKS_PER_BLOCK = 10;

    /** Gantry step (blocks/tick) */
    private static final double GANTRY_STEP = 0.16;

    /** Footprint (must match renderer/controller) */
    private static final int HALF = 5;    // 11x11 interior including frame
    private static final int HEIGHT = 5;  // gantry height above controller

    /** Throttle for BE -> client syncs */
    private static final int SYNC_MIN_INTERVAL = 8;

    /** Arrival tolerances */
    private static final double EPS = 0.05;
    private static final int DRILL_TIME = MINE_TICKS_PER_BLOCK;

    /** A* / replanning cadence */
    private static final int REPATHER_COOLDOWN_TICKS = 10;

    /** How many vertical blocks above the ceiling band to treat as solid for pathing */
    private static final int CEILING_GUARD_HEIGHT = 5; // y0..y0+4

    /** Auto-skip: milliseconds = ticks in this world; TTL ~10s */
    private static final int UNREACHABLE_TTL = 200;

    /** If true, when NOT connected to any inventory/pipe above, spill queued items upward. */
    private static final boolean DROP_WHEN_DISCONNECTED = true;

    /** How many stacks to attempt per flush tick (prevents long ticks) */
    private static final int FLUSH_TRIES_PER_TICK = 4;

    /* ---------- state ---------- */

    private final ArrayDeque<BlockPos> frameBuildQueue = new ArrayDeque<>();
    private int frameTickCounter = 0;
    private boolean frameComplete = false;

    private BlockPos lastMined = null;

    /** 12 edges (top 4, bottom 4, 4 uprights) */
    private final UUID[] placementLaserIds = new UUID[12];

    /** Gantry simulation (world center at the ceiling band) */
    private Vec3 gantryPos = null;

    // Persisted layer/path state
    private Integer layerY = null;            // current working layer (world Y)
    private boolean layerLeftToRight = false; // LOCAL boustro parity
    private boolean layerStartAtTop  = false; // LOCAL near/far parity

    // Client sync (rate-limited)
    private int     syncCooldown   = 0;
    private Vec3    lastSentGantry = null;
    private Integer lastSentLayer  = null;

    // Mining gates
    private BlockPos targetCol = null;  // goal column at the ceiling band
    private boolean atTarget = false;
    private int drillTicks = 0;

    // If non-null we’re handling a grief block above the current working layer (this column only).
    private Integer overrideMineY = null;

    // --- pathing state ---
    private ArrayDeque<BlockPos> path = new ArrayDeque<>(); // waypoints (world X/Z @ ceiling Y)
    private BlockPos pathTarget = null;                      // which goal the path was planned for
    private int repathCooldown = 0;

    // Unreachable skip cache
    private final Map<BlockPos, Long> tempSkip = new HashMap<>();

    // Reuse a single mutable for cheap vertical probes in the current column
    private final BlockPos.MutableBlockPos tmpPos = new BlockPos.MutableBlockPos();

    // -------- one-time end-of-layer final sweep ----------
    private boolean finalSweepPending = false;           // true while we're working through the queue
    private boolean finalSweepCheckedThisLayer = false;  // prevents repeated scans on the same layer
    private final ArrayDeque<BlockPos> finalSweepTargets = new ArrayDeque<>();

    // -------- output queue (drop-safe) ----------
    private final ArrayDeque<ItemStack> outputQueue = new ArrayDeque<>();

    // -------- gating to avoid "phantom" flush after power-on ----------
    private boolean allowFlushAfterPowerOn = false;

    public QuarryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.QUARRY_CONTROLLER.get(), pos, state);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level instanceof ServerLevel sl) clearPlacementLasers(sl);
    }

    /* ====================================================================== */
    /*  Sync helpers                                                           */
    /* ====================================================================== */

    private void markForSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState s = getBlockState();
            level.sendBlockUpdated(worldPosition, s, s, Block.UPDATE_CLIENTS);
        }
    }

    private void maybeSync() {
        if (level == null || level.isClientSide) return;

        boolean layerChanged = !Objects.equals(layerY, lastSentLayer);
        boolean movedCell = false;

        if (gantryPos != null) {
            if (lastSentGantry == null) movedCell = true;
            else movedCell =
                    (floor(gantryPos.x) != floor(lastSentGantry.x)) ||
                            (floor(gantryPos.z) != floor(lastSentGantry.z));
        }

        if (--syncCooldown <= 0 || movedCell || layerChanged) {
            syncCooldown   = SYNC_MIN_INTERVAL;
            lastSentGantry = gantryPos;
            lastSentLayer  = layerY;
            markForSync();
        }
    }

    /* ====================================================================== */
    /*  Tick (server)                                                          */
    /* ====================================================================== */

    public static void serverTick(Level level, BlockPos pos, BlockState state, QuarryBlockEntity be) {
        if (level.isClientSide) return;

        // Global smoothing hooks
        QuarryBalancer.beginTick((ServerLevel) level);

        // Only show lasers while assembling the frame
        if (!be.frameComplete) be.ensurePlacementLasers((ServerLevel) level);
        else be.clearPlacementLasers((ServerLevel) level);

        if (!be.hasWorkPower()) {
            be.frameTickCounter = 0;
            be.lastMined = null;

            be.layerY = null;
            be.layerLeftToRight = false;
            be.layerStartAtTop  = false;

            be.targetCol = null;
            be.atTarget = false;
            be.drillTicks = 0;

            be.path.clear();
            be.pathTarget = null;
            be.tempSkip.clear();

            be.finalSweepPending = false;
            be.finalSweepCheckedThisLayer = false;
            be.finalSweepTargets.clear();

            // Gate outputs on power-loss so no flush happens on next power-on until we mine.
            be.allowFlushAfterPowerOn = false;

            be.markForSync();
            return;
        }

        // Build frame progressively
        if (!be.frameComplete) {
            if (!be.stepFrameBuild(level, pos, state)) return;
            be.frameComplete = true;
            be.markForSync();
        }

        // Sanity: frame must still exist
        if (!be.verifyEdgesAreFrames()) {
            be.frameComplete = false;
            be.lastMined = null;

            be.layerY = null;
            be.layerLeftToRight = false;
            be.layerStartAtTop  = false;

            be.targetCol = null;
            be.atTarget = false;
            be.drillTicks = 0;

            be.path.clear();
            be.pathTarget = null;
            be.tempSkip.clear();

            be.finalSweepPending = false;
            be.finalSweepCheckedThisLayer = false;
            be.finalSweepTargets.clear();

            be.allowFlushAfterPowerOn = false;

            be.markForSync();
            return;
        }

        // Always move gantry (server-authoritative)
        be.tickGantry((ServerLevel) level, pos, state);

        // Spread mining work across phases
        if (!QuarryBalancer.phaseGate((ServerLevel) level, pos)) {
            // Even if we didn't mine, still try to flush outputs (gated)
            be.flushOutput((ServerLevel) level, pos);
            return;
        }

        // Mine when over target & dwell completed
        be.stepMining((ServerLevel) level, pos, state);

        // After mining phase, attempt to flush queued drops (gated)
        be.flushOutput((ServerLevel) level, pos);
    }

    /* ====================================================================== */
    /*  LASER PREVIEW                                                          */
    /* ====================================================================== */

    private void ensurePlacementLasers(ServerLevel level) {
        Direction facing = getBlockState().getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(getBlockPos(), facing);

        double xL = b.x0 + 0.5, xR = b.x1 + 0.5;
        double zN = b.z0 + 0.5, zS = b.z1 + 0.5;
        double yB = b.y0 + 0.5, yT = b.y1 + 0.5;

        Vec3 b00 = new Vec3(xL, yB, zN), b10 = new Vec3(xR, yB, zN),
                b11 = new Vec3(xR, yB, zS), b01 = new Vec3(xL, yB, zS);
        Vec3 t00 = new Vec3(xL, yT, zN), t10 = new Vec3(xR, yT, zN),
                t11 = new Vec3(xR, yT, zS), t01 = new Vec3(xL, yT, zS);

        ensureLaser(level, 0, t00, t10);
        ensureLaser(level, 1, t10, t11);
        ensureLaser(level, 2, t11, t01);
        ensureLaser(level, 3, t01, t00);

        ensureLaser(level, 4, b00, b10);
        ensureLaser(level, 5, b10, b11);
        ensureLaser(level, 6, b11, b01);
        ensureLaser(level, 7, b01, b00);

        ensureLaser(level, 8,  b00, t00);
        ensureLaser(level, 9,  b10, t10);
        ensureLaser(level, 10, b11, t11);
        ensureLaser(level, 11, b01, t01);
    }

    private void ensureLaser(ServerLevel level, int idx, Vec3 a, Vec3 b) {
        UUID id = placementLaserIds[idx];
        LaserEntity le;

        if (id == null) {
            le = new LaserEntity(ModEntities.LASER.get(), level);
            le.setColor(0xFF0000);
            level.addFreshEntity(le);
            placementLaserIds[idx] = le.getUUID();
        } else {
            Entity e = level.getEntity(id);
            if (!(e instanceof LaserEntity leE) || !e.isAlive()) {
                le = new LaserEntity(ModEntities.LASER.get(), level);
                le.setColor(0xFF0000);
                level.addFreshEntity(le);
                placementLaserIds[idx] = le.getUUID();
            } else {
                le = leE;
            }
        }

        le.setEndpoints(a, b);
    }

    public void clearPlacementLasers(ServerLevel level) {
        for (int i = 0; i < placementLaserIds.length; i++) {
            UUID id = placementLaserIds[i];
            if (id != null) {
                Entity e = level.getEntity(id);
                if (e != null) e.discard();
                placementLaserIds[i] = null;
            }
        }
    }

    /* ====================================================================== */
    /*  FRAME BUILD                                                            */
    /* ====================================================================== */

    private boolean stepFrameBuild(Level level, BlockPos origin, BlockState controllerState) {
        if (frameBuildQueue.isEmpty()) {
            populateFrameQueueFromOwnBounds(level, origin, controllerState);
            if (frameBuildQueue.isEmpty()) return verifyEdgesAreFrames();
        }

        frameTickCounter++;
        if (frameTickCounter < FRAME_TICKS_PER_PIECE) return false;

        BlockPos next = frameBuildQueue.pollFirst();
        frameTickCounter = 0;

        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(origin, facing);

        BlockState state = stateForEdgeBlock(next, b);

        if (!level.getBlockState(next).is(ModBlocks.FRAME.get())) {
            level.setBlock(next, state, Block.UPDATE_CLIENTS);
        } else {
            BlockState cur = level.getBlockState(next);
            if (cur != state) level.setBlock(next, state, Block.UPDATE_CLIENTS);
        }

        return frameBuildQueue.isEmpty() && verifyEdgesAreFrames();
    }

    private void populateFrameQueueFromOwnBounds(Level level, BlockPos origin, BlockState controllerState) {
        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(origin, facing);

        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos p : frameEdges(b.min(), b.max())) {
            if (!level.getBlockState(p).is(ModBlocks.FRAME.get())) todo.add(p);
        }
        if (todo.isEmpty()) return;

        todo.sort(Comparator.comparingInt(Vec3i::getY)
                .thenComparingInt(Vec3i::getZ)
                .thenComparingInt(Vec3i::getX));

        frameBuildQueue.clear();
        frameBuildQueue.addAll(new LinkedHashSet<>(todo));
    }

    private boolean verifyEdgesAreFrames() {
        Level lvl = getLevel();
        if (lvl == null) return false;
        Direction facing = getBlockState().getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(getBlockPos(), facing);
        for (BlockPos p : frameEdges(b.min(), b.max())) {
            if (!lvl.getBlockState(p).is(ModBlocks.FRAME.get())) return false;
        }
        return true;
    }

    /** Decide AXIS/CORNER for a frame block at position p on the shell defined by b. */
    private static BlockState stateForEdgeBlock(BlockPos p, Bounds b) {
        boolean onX0 = p.getX() == b.x0, onX1 = p.getX() == b.x1;
        boolean onZ0 = p.getZ() == b.z0, onZ1 = p.getZ() == b.z1;
        boolean onY0 = p.getY() == b.y0, onY1 = p.getY() == b.y1;

        // Corner piece?
        if ((onX0 || onX1) && (onZ0 || onZ1) && (onY0 || onY1)) {
            FrameBlock.Corner corner;
            boolean bottom = onY0;
            if (onX0 && onZ0) corner = bottom ? FrameBlock.Corner.XMIN_ZMIN_BOTTOM : FrameBlock.Corner.XMIN_ZMIN_TOP;
            else if (onX0 && onZ1) corner = bottom ? FrameBlock.Corner.XMIN_ZMAX_BOTTOM : FrameBlock.Corner.XMIN_ZMAX_TOP;
            else if (onX1 && onZ0) corner = bottom ? FrameBlock.Corner.XMAX_ZMIN_BOTTOM : FrameBlock.Corner.XMAX_ZMIN_TOP;
            else                   corner = bottom ? FrameBlock.Corner.XMAX_ZMAX_BOTTOM : FrameBlock.Corner.XMAX_ZMAX_TOP;

            return ModBlocks.FRAME.get().defaultBlockState()
                    .setValue(FrameBlock.CORNER, corner)
                    .setValue(FrameBlock.AXIS, Direction.Axis.Y);
        }

        // Top/bottom edges along X
        if ((onY0 || onY1) && (onZ0 || onZ1) && !(onX0 || onX1)) {
            return ModBlocks.FRAME.get().defaultBlockState()
                    .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE)
                    .setValue(FrameBlock.AXIS, Direction.Axis.X);
        }

        // Top/bottom edges along Z
        if ((onY0 || onY1) && (onX0 || onX1) && !(onZ0 || onZ1)) {
            return ModBlocks.FRAME.get().defaultBlockState()
                    .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE)
                    .setValue(FrameBlock.AXIS, Direction.Axis.Z);
        }

        // Uprights
        return ModBlocks.FRAME.get().defaultBlockState()
                .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE)
                .setValue(FrameBlock.AXIS, Direction.Axis.Y);
    }

    /* ====================================================================== */
    /*  MINING — forward-only boustrophedon with lazy column cleanup          */
    /* ====================================================================== */

    /** Look only up THIS column for the highest grief block above the current layer. */
    private Integer griefYInColumnAbove(Level level, Bounds b, int x, int z, int topY, int stopYExclusive) {
        for (int y = topY; y >= stopYExclusive; --y) {
            tmpPos.set(x, y, z);
            if (shouldMine(level, tmpPos)) return y;
        }
        return null;
    }

    private void stepMining(ServerLevel level, BlockPos origin, BlockState controllerState) {
        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(origin, facing);

        final int topY = b.y0 - 1; // just below bottom frame (ceiling band)
        final int minY = level.dimensionType().minY();

        if (layerY == null) {
            layerY = topY;
            layerLeftToRight = false; // start at right edge
            layerStartAtTop  = false; // start from NEAR edge
            if (targetCol == null) targetCol = nearRightInterior(b, facing);
            atTarget = false;
            drillTicks = 0;

            finalSweepPending = false;
            finalSweepCheckedThisLayer = false;
            finalSweepTargets.clear();
        }

        // Only mine when carriage is centered on the current target column
        if (!atTarget) return;

        // BEFORE the dwell, lazily check ONLY THIS column for any grief above the current working layer.
        if (overrideMineY == null && targetCol != null) {
            Integer gy = griefYInColumnAbove(level, b, targetCol.getX(), targetCol.getZ(), topY, layerY + 1);
            if (gy != null) overrideMineY = gy;
        }

        // Dwell
        drillTicks++;
        if (drillTicks < DRILL_TIME) return;
        drillTicks = 0;

        // Decide what Y to mine: prefer a grief block in this column if present.
        int mineY = (overrideMineY != null) ? overrideMineY : layerY;
        BlockPos p = new BlockPos(targetCol.getX(), mineY, targetCol.getZ());

        // If the grief block vanished before we mined, clear override and fall back to layerY
        if (overrideMineY != null && !shouldMine(level, p)) {
            overrideMineY = null;
            mineY = layerY;
            p = new BlockPos(targetCol.getX(), mineY, targetCol.getZ());
        }

        if (shouldMine(level, p)) {
            if (!QuarryBalancer.tryConsumeToken(level)) { return; }

            // Energy gate: require FE for this operation
            if (energy.extractEnergy(Energy.QUARRY_ENERGY_PER_OPERATION, true) < Energy.QUARRY_ENERGY_PER_OPERATION) {
                return; // not enough FE yet
            }
            energy.extractEnergy(Energy.QUARRY_ENERGY_PER_OPERATION, false);

            final boolean handledOverride = (overrideMineY != null);

            // Actually mine here (collect to queue only — no spawning here)
            mineOneBlockToQueue(level, origin, p);
            lastMined = p;

            if (handledOverride) {
                // Stay on the column and re-check for more grief next tick
                overrideMineY = null;
                maybeSync();
                return;
            }

            // Normal on-layer mining: pick the next target AHEAD on this layer (no backtracking).
            BlockPos nextOnLayer = findNextOnLayerForward(level, b, facing, targetCol, layerY);
            if (nextOnLayer != null) {
                targetCol = new BlockPos(nextOnLayer.getX(), b.y0, nextOnLayer.getZ());
                atTarget = false;
                maybeSync();
                return;
            }

            // No forward cell → maybe one-time final sweep if at the layer's end corner
            if (!finalSweepCheckedThisLayer && isAtExpectedLayerEndCell(b, facing, targetCol)) {
                queueFinalSweepIfNeeded(level, b, facing, layerY, targetCol);
                if (finalSweepPending && !finalSweepTargets.isEmpty()) {
                    BlockPos nxt = finalSweepTargets.pollFirst();
                    targetCol = new BlockPos(nxt.getX(), b.y0, nxt.getZ());
                    atTarget = false;
                    maybeSync();
                    return;
                }
            }
            // fall through to descend
        }

        // If we're already in final-sweep mode, continue pulling from the queue.
        if (finalSweepPending) {
            if (!finalSweepTargets.isEmpty()) {
                BlockPos nxt = finalSweepTargets.pollFirst();
                targetCol = new BlockPos(nxt.getX(), b.y0, nxt.getZ());
                atTarget = false;
                maybeSync();
                return;
            } else {
                // Finished the one-time final sweep for this layer.
                finalSweepPending = false;
                finalSweepCheckedThisLayer = true;
            }
        }

        // Continue searching forward; if empty, descend to next layer.
        BlockPos searchFrom = targetCol; // search forward only
        BlockPos next;
        while (true) {
            next = findNextOnLayerForward(level, b, facing, searchFrom, layerY);
            if (next != null) {
                targetCol = new BlockPos(next.getX(), b.y0, next.getZ());
                atTarget = false;
                break;
            }

            // No forward targets left; if we haven't done the end-corner check yet, do it now.
            if (!finalSweepCheckedThisLayer && isAtExpectedLayerEndCell(b, facing, targetCol)) {
                queueFinalSweepIfNeeded(level, b, facing, layerY, targetCol);
                if (finalSweepPending && !finalSweepTargets.isEmpty()) {
                    BlockPos nxt = finalSweepTargets.pollFirst();
                    targetCol = new BlockPos(nxt.getX(), b.y0, nxt.getZ());
                    atTarget = false;
                    break;
                }
            }

            // Descend one layer
            layerY--;
            layerLeftToRight = !layerLeftToRight;
            layerStartAtTop  = !layerStartAtTop;
            tempSkip.clear(); // clear unreachable cache across layers
            finalSweepPending = false;
            finalSweepCheckedThisLayer = false;
            finalSweepTargets.clear();

            if (layerY < minY) { maybeSync(); return; }

            // For the NEW layer we start from the layer's start position
            searchFrom = null;
        }

        maybeSync();
    }

    // Scan forward on a single Y layer in LOCAL boustrophedon order (facing-aware).
    // If 'from' is null, start at the layer's start; otherwise begin strictly after 'from'.
    private BlockPos findNextOnLayerForward(Level level, Bounds b, Direction facing, BlockPos from, int y) {
        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;

        boolean rowsAreZ = (facing == Direction.NORTH || facing == Direction.SOUTH);
        int rowMin = rowsAreZ ? zMin : xMin;
        int rowMax = rowsAreZ ? zMax : xMax;
        int colMin = rowsAreZ ? xMin : zMin;
        int colMax = rowsAreZ ? xMax : zMax;

        int startRow = switch (facing) {
            case NORTH -> (layerStartAtTop ? zMin : zMax);
            case SOUTH -> (layerStartAtTop ? zMax : zMin);
            case EAST  -> (layerStartAtTop ? xMax : xMin);
            case WEST  -> (layerStartAtTop ? xMin : xMax);
            default    -> (layerStartAtTop ? zMin : zMax);
        };
        int stepRow = (startRow == rowMin) ? +1 : -1;

        // For NORTH/EAST: lr==true means increasing columns; for SOUTH/WEST it's inverted.
        boolean lrTrueIsInc = (facing == Direction.NORTH || facing == Direction.EAST);

        java.util.function.IntUnaryOperator colForRow = (rowIdxFromStart) -> {
            boolean lrRow = ((rowIdxFromStart & 1) == 0) ? layerLeftToRight : !layerLeftToRight;
            boolean inc = lrTrueIsInc ? lrRow : !lrRow;
            return inc ? colMin : colMax;
        };
        java.util.function.IntUnaryOperator colStepForRow = (rowIdxFromStart) -> {
            boolean lrRow = ((rowIdxFromStart & 1) == 0) ? layerLeftToRight : !layerLeftToRight;
            boolean inc = lrTrueIsInc ? lrRow : !lrRow;
            return inc ? +1 : -1;
        };

        int curRow, curCol;

        if (from == null) {
            curRow = startRow;
            int rowIdx = 0;
            curCol = colForRow.applyAsInt(rowIdx);
        } else {
            int fromRow = rowsAreZ ? from.getZ() : from.getX();
            int fromCol = rowsAreZ ? from.getX() : from.getZ();
            int rowIdx = Math.abs(fromRow - startRow);
            boolean lrRow = ((rowIdx & 1) == 0) ? layerLeftToRight : !layerLeftToRight;
            boolean inc = lrTrueIsInc ? lrRow : !lrRow;

            curRow = fromRow;
            curCol = fromCol + (inc ? +1 : -1); // strictly after 'from'
            if (curCol < colMin || curCol > colMax) {
                curRow = fromRow + stepRow;
                if (curRow < rowMin || curRow > rowMax) return null; // layer exhausted
                int nextRowIdx = Math.abs(curRow - startRow);
                curCol = colForRow.applyAsInt(nextRowIdx);
            }
        }

        while (curRow >= rowMin && curRow <= rowMax) {
            int rowIdx = Math.abs(curRow - startRow);
            int colStep = colStepForRow.applyAsInt(rowIdx);

            for (int c = curCol; c >= colMin && c <= colMax; c += colStep) {
                int x = rowsAreZ ? c : curRow;
                int z = rowsAreZ ? curRow : c;
                if (columnBlockedAtCeiling(level, b, x, z)) continue;
                BlockPos p = new BlockPos(x, y, z);
                if (shouldMine(level, p)) return p;
            }

            curRow += stepRow;
            if (curRow < rowMin || curRow > rowMax) break;
            int nextRowIdx = Math.abs(curRow - startRow);
            curCol = colForRow.applyAsInt(nextRowIdx);
        }

        return null;
    }

    /** True if we should mine this block (solid, not fluid/air, breakable, not our frame). */
    private static boolean shouldMine(Level level, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        if (bs.isAir()) return false;
        FluidState fs = bs.getFluidState();
        if (!fs.isEmpty()) return false;
        if (bs.getDestroySpeed(level, p) < 0) return false; // unbreakable (e.g., bedrock)
        if (bs.is(ModBlocks.FRAME.get())) return false;     // don't mine our own frame
        return true;
    }

    /* -------------------------- OUTPUT LOGIC ------------------------------- */

    /** Returns leftover after trying to insert into the block above (no dropping here). */
    private ItemStack tryOutputUp(Level level, BlockPos quarryPos, ItemStack stackIn) {
        if (!(level instanceof ServerLevel sl) || stackIn.isEmpty()) return stackIn;

        BlockPos up = quarryPos.above();

        // 1) Prefer a normal inventory/pipe that exposes IItemHandler (chest, hopper, mod pipes)
        IItemHandler handler = sl.getCapability(Capabilities.ItemHandler.BLOCK, up, Direction.DOWN);
        if (handler != null) {
            ItemStack remaining = stackIn;
            for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                remaining = handler.insertItem(i, remaining, false);
            }
            if (!remaining.isEmpty()) {
                // 2) If that handler didn’t take it, fall back to our pipe contract (below)
                BlockEntity be = sl.getBlockEntity(up);
                if (be instanceof StonePipeBlockEntity pipe) {
                    remaining = pipe.offer(remaining, Direction.DOWN); // returns leftover
                }
            }
            return remaining;
        }

        // 3) No handler? If it’s our pipe, use its lightweight offer API
        BlockEntity be = sl.getBlockEntity(up);
        if (be instanceof StonePipeBlockEntity pipe) {
            return pipe.offer(stackIn, Direction.DOWN); // returns leftover
        }

        // 4) Nothing to accept it.
        return stackIn;
    }

    /** True if the block above exposes an ItemHandler or is a StonePipeBlockEntity. */
    private boolean hasOutputTarget(ServerLevel level, BlockPos quarryPos) {
        BlockPos up = quarryPos.above();

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, up, Direction.DOWN);
        if (handler != null) return true;

        BlockEntity be = level.getBlockEntity(up);
        return (be instanceof StonePipeBlockEntity);
    }

    /** Try to push queued stacks into the block above; gated to avoid phantom motion on power-on. */
    private void flushOutput(ServerLevel level, BlockPos quarryPos) {
        if (!allowFlushAfterPowerOn) return;     // <-- gate EVERYTHING until we mine again
        if (outputQueue.isEmpty()) return;

        // If there's nowhere to send items and the toggle is on, spill everything upward.
        if (DROP_WHEN_DISCONNECTED && !hasOutputTarget(level, quarryPos)) {
            BlockPos eject = quarryPos.above();
            while (!outputQueue.isEmpty()) {
                ItemStack s = outputQueue.pollFirst();
                if (s != null && !s.isEmpty()) {
                    Block.popResource(level, eject, s);
                }
            }
            return;
        }

        // Otherwise, try to insert a few per tick; requeue leftovers and stop early on backpressure.
        int tries = Math.min(FLUSH_TRIES_PER_TICK, outputQueue.size());
        for (int i = 0; i < tries; i++) {
            ItemStack s = outputQueue.pollFirst();
            if (s == null || s.isEmpty()) continue;

            ItemStack leftover = tryOutputUp(level, quarryPos, s);
            if (!leftover.isEmpty()) {
                // Still blocked: put it back and stop early this tick.
                outputQueue.addFirst(leftover);
                break;
            }
        }
    }

    /**
     * Break one block and queue its drops for output (no immediate spawn).
     * Actual insertion/dropping happens later during flushOutput().
     */
    private void mineOneBlockToQueue(ServerLevel level, BlockPos quarryPos, BlockPos target) {
        BlockState bs = level.getBlockState(target);
        if (bs.isAir()) return;

        // collect drops first, then break without vanilla drop
        BlockEntity beAtTarget = level.getBlockEntity(target);
        List<ItemStack> drops = Block.getDrops(bs, level, target, beAtTarget);
        level.destroyBlock(target, false);

        // queue the drops
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) continue;
            outputQueue.addLast(stack.copy());
        }

        // We mined something after (re)powering on → allow flushing again
        allowFlushAfterPowerOn = true;

        setChanged();
        maybeSync();
    }

    /* ====================================================================== */
    /*  GANTRY: server-authoritative movement + A* pathing                     */
    /* ====================================================================== */

    private void tickGantry(ServerLevel level, BlockPos origin, BlockState controllerState) {
        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(origin, facing);

        final int yCeil = b.y0;

        // initialize start
        if (gantryPos == null) {
            BlockPos start = nearRightInterior(b, facing);
            gantryPos = new Vec3(start.getX() + 0.5, yCeil + 0.5, start.getZ() + 0.5);
            targetCol = start.immutable();
        }
        if (targetCol == null) targetCol = nearRightInterior(b, facing);

        // clamp to band
        gantryPos = new Vec3(
                Mth.clamp(gantryPos.x, xMin(b) + 0.5, xMax(b) + 0.5),
                yCeil + 0.5,
                Mth.clamp(gantryPos.z, zMin(b) + 0.5, zMax(b) + 0.5)
        );

        // current cell (at the ceiling band)
        BlockPos startCell = curCell(b);

        // (re)plan if needed
        boolean needPlan = false;
        boolean alreadyInGoalCell =
                startCell.getX() == targetCol.getX() && startCell.getZ() == targetCol.getZ();

        if (pathTarget == null || !pathTarget.equals(targetCol) || --repathCooldown <= 0) {
            needPlan = true;
        } else if (!path.isEmpty()) {
            BlockPos next = path.peekFirst();
            if (columnBlockedAtCeiling(level, b, next.getX(), next.getZ())) needPlan = true;
        } else if (!alreadyInGoalCell) {
            needPlan = true;
        }

        if (needPlan) {
            path = planPath(level, b, startCell, targetCol);
            pathTarget = targetCol;
            repathCooldown = REPATHER_COOLDOWN_TICKS;

            if (path.isEmpty() && !alreadyInGoalCell) {
                long now = level.getGameTime();
                pruneTempSkip(now);
                tempSkip.put(targetCol.immutable(), now + UNREACHABLE_TTL);

                BlockPos next = findNextOnLayerForward(level, b, facing, targetCol,
                        layerY != null ? layerY : (b.y0 - 1));
                while (next != null && isSkipped(new BlockPos(next.getX(), b.y0, next.getZ()), now)) {
                    next = findNextOnLayerForward(level, b, facing, next, layerY != null ? layerY : (b.y0 - 1));
                }

                if (next != null) {
                    targetCol = new BlockPos(next.getX(), b.y0, next.getZ());
                    path = planPath(level, b, startCell, targetCol);
                    pathTarget = targetCol;
                }
            }
        }

        // follow waypoints
        BlockPos waypoint = path.peekFirst();
        if (waypoint == null) waypoint = targetCol;

        double tx = waypoint.getX() + 0.5;
        double tz = waypoint.getZ() + 0.5;

        double dx = tx - gantryPos.x;
        double dz = tz - gantryPos.z;

        if (Math.abs(dx) > EPS) {
            double stepX = Math.copySign(Math.min(GANTRY_STEP, Math.abs(dx)), dx);
            gantryPos = gantryPos.add(stepX, 0, 0);
        } else if (Math.abs(dz) > EPS) {
            double stepZ = Math.copySign(Math.min(GANTRY_STEP, Math.abs(dz)), dz);
            gantryPos = gantryPos.add(0, 0, stepZ);
        } else {
            if (!path.isEmpty()) path.removeFirst();
        }

        double gx = targetCol.getX() + 0.5, gz = targetCol.getZ() + 0.5;
        atTarget = Math.abs(gx - gantryPos.x) <= EPS && Math.abs(gz - gantryPos.z) <= EPS;

        maybeSync();
    }

    private static BlockPos nearRightInterior(Bounds b, Direction facing) {
        int xMin = b.x0 + 1, xMax = b.x1 - 1;
        int zMin = b.z0 + 1, zMax = b.z1 - 1;
        return switch (facing) {
            case NORTH -> new BlockPos(xMax, b.y0, zMax); // near = zMax, right = xMax
            case SOUTH -> new BlockPos(xMin, b.y0, zMin); // near = zMin, right = xMin
            case EAST  -> new BlockPos(xMin, b.y0, zMax); // near = xMin, right = zMax
            case WEST  -> new BlockPos(xMax, b.y0, zMin); // near = xMax, right = zMin
            default    -> new BlockPos(xMax, b.y0, zMax);
        };
    }

    /** Treat any non-empty collision shape as solid at the ceiling band. Frames are solid too. */
    private static boolean isCeilingObstacle(Level level, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        if (bs.isAir()) return false;
        if (bs.is(ModBlocks.FRAME.get())) return true; // keep other quarries “solid”
        return !bs.getCollisionShape(level, p).isEmpty();
    }

    /** COLUMN keep-out: true iff any of the first CEILING_GUARD_HEIGHT cells above the ceiling are solid. */
    private static boolean columnBlockedAtCeiling(Level level, Bounds b, int x, int z) {
        int y0 = b.y0;
        for (int dy = 0; dy < CEILING_GUARD_HEIGHT; dy++) {
            if (isCeilingObstacle(level, new BlockPos(x, y0 + dy, z))) return true;
        }
        return false;
    }

    /* --------------------------- pathfinding ------------------------------- */

    private static int xMin(Bounds b) { return b.x0 + 1; }
    private static int xMax(Bounds b) { return b.x1 - 1; }
    private static int zMin(Bounds b) { return b.z0 + 1; }
    private static int zMax(Bounds b) { return b.z1 - 1; }

    private BlockPos curCell(Bounds b) {
        int cx = floor(gantryPos.x), cz = floor(gantryPos.z);
        return new BlockPos(
                Mth.clamp(cx, xMin(b), xMax(b)),
                b.y0,
                Mth.clamp(cz, zMin(b), zMax(b))
        );
    }

    private ArrayDeque<BlockPos> planPath(ServerLevel level, Bounds b, BlockPos start, BlockPos goal) {
        ArrayDeque<BlockPos> empty = new ArrayDeque<>();
        if (goal.getX() < xMin(b) || goal.getX() > xMax(b) || goal.getZ() < zMin(b) || goal.getZ() > zMax(b))
            return empty;
        if (columnBlockedAtCeiling(level, b, goal.getX(), goal.getZ())) return empty;

        record Node(int x, int z) {}

        Map<Node, Integer> g = new HashMap<>();
        Map<Node, Integer> f = new HashMap<>();
        Comparator<Node> cmp = Comparator.comparingInt(n -> g.getOrDefault(n, Integer.MAX_VALUE) + f.getOrDefault(n, 0));
        PriorityQueue<Node> open = new PriorityQueue<>(cmp);
        Map<Node, Node> came = new HashMap<>();

        Node s = new Node(start.getX(), start.getZ());
        Node t = new Node(goal.getX(),  goal.getZ());

        g.put(s, 0);
        f.put(s, Math.abs(s.x - t.x) + Math.abs(s.z - t.z));
        open.add(s);

        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.equals(t)) {
                ArrayDeque<BlockPos> out = new ArrayDeque<>();
                Node cur = t;
                while (!cur.equals(s)) {
                    out.addFirst(new BlockPos(cur.x, b.y0, cur.z));
                    cur = came.get(cur);
                    if (cur == null) { out.clear(); break; }
                }
                return out;
            }
            int base = g.get(n);
            int[][] d4 = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int[] dv : d4) {
                int nx = n.x + dv[0], nz = n.z + dv[1];
                if (nx < xMin(b) || nx > xMax(b) || nz < zMin(b) || nz > zMax(b)) continue;
                if (columnBlockedAtCeiling(level, b, nx, nz)) continue;
                Node m = new Node(nx, nz);
                int ng = base + 1;
                if (ng < g.getOrDefault(m, Integer.MAX_VALUE)) {
                    came.put(m, n);
                    g.put(m, ng);
                    f.put(m, ng + Math.abs(nx - t.x) + Math.abs(nz - t.z));
                    open.remove(m);
                    open.add(m);
                }
            }
        }
        return empty; // no path
    }

    private void pruneTempSkip(long now) { tempSkip.entrySet().removeIf(e -> e.getValue() <= now); }
    private boolean isSkipped(BlockPos p, long now) { Long until = tempSkip.get(p); return until != null && until > now; }

    /* ====================== final-sweep helpers ============================ */

    private boolean isAtExpectedLayerEndCell(Bounds b, Direction facing, BlockPos cell) {
        BlockPos end = expectedEndCellForCurrentLayer(b, facing);
        return end.getX() == cell.getX() && end.getZ() == cell.getZ();
    }

    private BlockPos expectedEndCellForCurrentLayer(Bounds b, Direction facing) {
        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;

        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            int depth = zMax - zMin + 1;
            int startRow = (facing == Direction.NORTH) ? (layerStartAtTop ? zMin : zMax)
                    : (layerStartAtTop ? zMax : zMin);
            int stepRow  = (startRow == zMin) ? +1 : -1;
            int endRow   = startRow + stepRow * (depth - 1);

            boolean lastRowLR = ((depth - 1) % 2 == 0) ? layerLeftToRight : !layerLeftToRight;
            boolean inc = (facing == Direction.NORTH) ? lastRowLR : !lastRowLR;
            int endCol = inc ? xMax : xMin;
            return new BlockPos(endCol, b.y0, endRow);
        } else {
            int width = xMax - xMin + 1;
            int startRow = (facing == Direction.EAST) ? (layerStartAtTop ? xMax : xMin)
                    : (layerStartAtTop ? xMin : xMax);
            int stepRow  = (startRow == xMin) ? +1 : -1;
            int endRow   = startRow + stepRow * (width - 1);

            boolean lastRowLR = ((width - 1) % 2 == 0) ? layerLeftToRight : !layerLeftToRight;
            boolean inc = (facing == Direction.EAST) ? lastRowLR : !lastRowLR;
            int endCol = inc ? zMax : zMin;
            return new BlockPos(endRow, b.y0, endCol);
        }
    }

    private void queueFinalSweepIfNeeded(Level level, Bounds b, Direction facing, int y, BlockPos currentCell) {
        finalSweepCheckedThisLayer = true;  // ensure we compute once per layer
        finalSweepTargets.clear();

        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;

        List<BlockPos> missing = new ArrayList<>();
        for (int z = zMin; z <= zMax; z++) {
            for (int x = xMin; x <= xMax; x++) {
                if (columnBlockedAtCeiling(level, b, x, z)) continue;
                BlockPos p = new BlockPos(x, y, z);
                if (shouldMine(level, p)) missing.add(p);
            }
        }

        if (missing.isEmpty()) {
            finalSweepPending = false;
            return;
        }

        // Sort by Manhattan distance from where we are now to keep travel cheap
        missing.sort(Comparator.comparingInt(p ->
                Math.abs(p.getX() - currentCell.getX()) + Math.abs(p.getZ() - currentCell.getZ())));

        finalSweepTargets.addAll(missing);
        finalSweepPending = true;
    }

    /* ====================================================================== */
    /*  UTIL                                                                   */
    /* ====================================================================== */

    // --- energy buffer (engines push FE here) ---
    private final BCEnergyStorage energy =
            new BCEnergyStorage(Energy.ENGINE_BUFFER, Energy.ENGINE_MAX_IO, s -> setChanged());

    /** Exposed via ModCapabilities.EnergyStorage so engines can push FE. */
    public BCEnergyStorage getEnergyStorage() { return energy; }


    /** True if we have enough FE buffered to perform one mining action. */
    private boolean hasWorkPower() {
        return energy.getEnergyStored() >= Energy.QUARRY_ENERGY_PER_OPERATION;
    }




    private boolean isPowered(Level level, BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.POWERED)) return state.getValue(BlockStateProperties.POWERED);
        return level.hasNeighborSignal(pos);
    }

    public static int getMiningPeriodTicks() { return MINE_TICKS_PER_BLOCK; }

    private static int floor(double d) { return (int) Math.floor(d); }

    /* ---------- geometry shared with the block ---------- */

    static Bounds boundsForFacing(BlockPos pos, Direction facing) {
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

    static Iterable<BlockPos> frameEdges(BlockPos min, BlockPos max) {
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

    /* ---------- tiny bounds record ---------- */
    static final class Bounds {
        final int x0, y0, z0, x1, y1, z1;
        Bounds(int x0, int y0, int z0, int x1, int y1, int z1) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0; this.x1 = x1; this.y1 = y1; this.z1 = z1;
        }
        BlockPos min() { return new BlockPos(x0, y0, z0); }
        BlockPos max() { return new BlockPos(x1, y1, z1); }
    }

    /* ====================================================================== */
    /*  PUBLIC HOOKS FOR RENDER/PREDICTION (read-only)                         */
    /* ====================================================================== */

    public Vec3 getGantryPos() { return gantryPos; }

    public CeilingMaskSnapshot snapshotCeilingMask() {
        Level lvl = getLevel();
        if (lvl == null) return new CeilingMaskSnapshot(0, 0, 0, 0, BitSet.valueOf(new byte[0]));

        Direction facing = getBlockState().getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(getBlockPos(), facing);

        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;
        final int width = Math.max(0, xMax - xMin + 1);
        final int depth = Math.max(0, zMax - zMin + 1);

        BitSet bits = new BitSet(width * depth);
        for (int zi = 0; zi < depth; zi++) {
            int z = zMin + zi;
            for (int xi = 0; xi < width; xi++) {
                int x = xMin + xi;
                boolean blocked = columnBlockedAtCeiling(lvl, b, x, z);
                if (blocked) bits.set(zi * width + xi);
            }
        }
        return new CeilingMaskSnapshot(xMin, zMin, width, depth, bits);
    }

    public static final class CeilingMaskSnapshot {
        public final int xMin, zMin;
        public final int width, depth;
        public final BitSet mask;

        public CeilingMaskSnapshot(int xMin, int zMin, int width, int depth, BitSet mask) {
            this.xMin = xMin;
            this.zMin = zMin;
            this.width = width;
            this.depth = depth;
            this.mask = (BitSet) mask.clone();
        }

        public boolean isBlocked(int x, int z) {
            int xi = x - xMin, zi = z - zMin;
            if (xi < 0 || zi < 0 || xi >= width || zi >= depth) return false;
            return mask.get(zi * width + xi);
        }
    }

    public Set<BlockPos> getBlockedPositions() { return new HashSet<>(); }

    /* ====================================================================== */
    /*  Persist + vanilla sync (ValueInput/ValueOutput, 1.21+)                */
    /* ====================================================================== */

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        // gantry position (optional)
        var gx = in.read("GX", Codec.DOUBLE);
        var gy = in.read("GY", Codec.DOUBLE);
        var gz = in.read("GZ", Codec.DOUBLE);
        gx.ifPresent(x -> this.gantryPos = new Vec3(x, gy.orElse(0.0), gz.orElse(0.0)));

        // layer state (optionals)
        in.read("LayerY", Codec.INT).ifPresent(v -> this.layerY = v);
        this.layerLeftToRight = in.read("LayerLR", Codec.BOOL).orElse(this.layerLeftToRight);
        this.layerStartAtTop  = in.read("LayerTop", Codec.BOOL).orElse(this.layerStartAtTop);

        // output queue — best-effort shallow load (not critical if empty)
        int qn = in.getInt("OutQn").orElse(0);
        outputQueue.clear();
        for (int i = 0; i < qn; i++) {
            var st = in.read("OutQ_" + i, ItemStack.CODEC);
            st.ifPresent(outputQueue::addLast);
        }

        // energy buffer
        in.child("Energy").ifPresent(child -> this.energy.deserialize(child));

        // On world load, keep the gate closed until we mine something.
        allowFlushAfterPowerOn = false;
    }


    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        if (this.gantryPos != null) {
            out.store("GX", Codec.DOUBLE, this.gantryPos.x);
            out.store("GY", Codec.DOUBLE, this.gantryPos.y);
            out.store("GZ", Codec.DOUBLE, this.gantryPos.z);
        }
        out.storeNullable("LayerY", Codec.INT, this.layerY);
        out.store("LayerLR", Codec.BOOL, this.layerLeftToRight);
        out.store("LayerTop", Codec.BOOL, this.layerStartAtTop);

        // output queue — shallow save
        out.putInt("OutQn", outputQueue.size());
        int i = 0;
        for (ItemStack s : outputQueue) {
            out.store("OutQ_" + (i++), ItemStack.CODEC, s);
        }

        // energy buffer
        this.energy.serialize(out.child("Energy"));
    }

    /** Packet the server sends when you call sendBlockUpdated in markForSync(). */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    /** What tags to send in that packet. Uses our saveAdditional(ValueOutput). */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return this.saveCustomOnly(registries); }
}
