package com.nick.buildcraft.content.block.pump;

import com.mojang.serialization.Codec;
import com.nick.buildcraft.api.engine.EnginePowerAcceptorApi;
import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.energy.BCEnergyStorage;
import com.nick.buildcraft.energy.Energy;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.*;

/**
 * Pump with smooth payout + greedy "farthest-first" evaporation.
 *
 * Behavior tweaks:
 * - Suction only starts when:
 *      (a) hose tip is snapped to a whole block height (no partial slice still feeding),
 *      AND
 *      (b) hose has fully extended to the deepest point it *can* currently reach
 *          (collision / bedrock limit).
 *
 * - After draining a source and confirming no sources remain feeding that blob,
 *   we don't nuke the blob or evaporate rings from the center. Instead we greedily
 *   delete the farthest flowing-water block(s) from the pump column over time.
 *   This makes distant side branches disappear first and the water at the hose
 *   itself is always last to vanish.
 */
public class PumpBlockEntity extends BlockEntity
        implements EnginePulseAcceptorApi, EnginePowerAcceptorApi {

    /* ---------------- tuning knobs ---------------- */

    private static final int MAX_SCAN_DEPTH = 256;
    private static final int BFS_MAX_NODES  = 256;

    // how many ticks to feed 1 full block of hose (1.0 / 40 = 0.025 blocks/tick)
    private static final int HOSE_TICKS_PER_BLOCK = 40;
    private static final double FEED_SPEED_BLOCKS_PER_TICK =
            1.0 / (double) HOSE_TICKS_PER_BLOCK;

    private static final int TANK_CAPACITY_MB = 4000;
    private static final int MAX_FE = 200_000;
    private static final int SYNC_MIN_INTERVAL_TICKS = 8;

    /** How many non-source water blocks to evaporate per cleanup step. */
    private static final int GREEDY_EVAP_BLOCKS_PER_STEP = 1;

    /** Delay between cleanup steps, to control visual speed. */
    private static final int GREEDY_EVAP_TICK_DELAY = 5;

    /** Tolerance for "has the hose reached a whole block boundary?" */
    private static final double WHOLE_BLOCK_EPS = 1.0e-3;

    /** Tolerance for "hose is basically at final depth." */
    private static final double FINAL_DEPTH_EPS = 1.0e-3;

    /* ---------------- live state ---------------- */

    private final BCEnergyStorage energy =
            new BCEnergyStorage(MAX_FE, MAX_FE, s -> setChanged());

    private int queuedPulses = 0;
    private BlockPos targetFluidPos = null;
    private boolean poweredYet = false;

    private double deployedDepthBlocks = 0.0;
    private double pendingExtendBlocks = 0.0;

    private int tubeHeadY;
    private double tubeHeadYExact;

    private Integer lastSyncedRenderY = null;
    private Double  lastSyncedExactY  = null;
    private int     syncCooldown      = 0;

    private final FluidTank tank = new FluidTank(TANK_CAPACITY_MB, fs -> true);

    /** Greedy cleanup state: anchor column we clean from, and throttle. */
    private BlockPos cleanupAnchor = null;
    private int cleanupCooldown = 0;

    public PumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.PUMP.get(), pos, state);

        this.poweredYet = false;
        this.deployedDepthBlocks = 0.0;
        this.pendingExtendBlocks = 0.0;

        this.tubeHeadY = pos.getY();
        this.tubeHeadYExact = pos.getY();
    }

    /* ------------------------------------------------------ */
    /* Engine APIs                                            */
    /* ------------------------------------------------------ */

    @Override
    public boolean acceptEnginePulse(Direction from) {
        if (level != null && !level.isClientSide) {
            queuedPulses = Mth.clamp(queuedPulses + 1, 0, 64);
            setChanged();
        }
        return true;
    }

    @Override
    public void acceptEnginePower(Direction from, int power) {
        energy.receiveEnergy(Math.max(0, power), false);
        setChanged();
    }

    public BCEnergyStorage getEnergyStorage() { return energy; }
    public FluidTank getFluidTank() { return tank; }

    /* ------------------------------------------------------ */
    /* Tick                                                   */
    /* ------------------------------------------------------ */

    public static void serverTick(Level level, BlockPos pos, BlockState state, PumpBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        // Keep downward-fluid guess updated.
        if (be.targetFluidPos == null || !be.isStillValidFluidSource(server, be.targetFluidPos)) {
            be.targetFluidPos = be.findClosestFluidBelow(server, pos);
        }

        // Advance hose payout and clamp to collision.
        be.advanceHose(server, pos);

        // Try to drink (but only when hose is fully settled).
        be.tryDrainIfTouching(server);

        // Continue staged "farthest-first" evaporation if armed.
        be.stepGreedyEvaporation(server);

        // Sync visual state.
        be.maybeSyncToClient();

        be.setChanged();
    }

    /* ------------------------------------------------------ */
    /* Hose motion / collision                                */
    /* ------------------------------------------------------ */

    private int computeReachLimitY(ServerLevel level, BlockPos pumpPos) {
        int x = pumpPos.getX();
        int z = pumpPos.getZ();
        int minY = level.dimensionType().minY();

        for (int y = pumpPos.getY() - 1; y >= minY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState bs = level.getBlockState(p);
            FluidState fs = level.getFluidState(p);

            boolean fluid = !fs.isEmpty();
            boolean air   = bs.isAir();
            boolean solidHitbox = !bs.getCollisionShape(level, p).isEmpty();

            if (!air && !fluid && solidHitbox) {
                return y + 1; // hose tip must stay above this
            }
        }

        return minY;
    }

    private void advanceHose(ServerLevel level, BlockPos pumpPos) {
        int pumpY = pumpPos.getY();

        // Convert queued engine pulses -> number of whole blocks we "owe" to extend.
        if (queuedPulses > 0) {
            pendingExtendBlocks += queuedPulses;
            queuedPulses = 0;
            if (!poweredYet) poweredYet = true;
        }

        if (!poweredYet) {
            deployedDepthBlocks = 0.0;
            tubeHeadYExact = pumpY;
            tubeHeadY = pumpY;
            return;
        }

        int reachLimitY = computeReachLimitY(level, pumpPos);
        double maxDepthAllowedBlocks = Math.max(0.0, pumpY - reachLimitY);

        if (pendingExtendBlocks > 1e-6 && FEED_SPEED_BLOCKS_PER_TICK > 1e-9) {
            double toSpend = Math.min(FEED_SPEED_BLOCKS_PER_TICK, pendingExtendBlocks);

            if (deployedDepthBlocks + toSpend > maxDepthAllowedBlocks) {
                toSpend = Math.max(0.0, maxDepthAllowedBlocks - deployedDepthBlocks);
            }

            if (toSpend > 1e-9) {
                deployedDepthBlocks += toSpend;
                pendingExtendBlocks -= toSpend;
                if (pendingExtendBlocks < 0) pendingExtendBlocks = 0;
            }
        }

        if (deployedDepthBlocks > maxDepthAllowedBlocks) {
            deployedDepthBlocks = maxDepthAllowedBlocks;
        }

        tubeHeadYExact = pumpY - deployedDepthBlocks;

        int snapY = pumpY - Mth.floor(deployedDepthBlocks);
        if (snapY > pumpY) snapY = pumpY;
        if (snapY < reachLimitY) snapY = reachLimitY;
        tubeHeadY = snapY;
    }

    /* ------------------------------------------------------ */
    /* Hose readiness gates                                   */
    /* ------------------------------------------------------ */

    /** True when the hose tip is aligned to a whole block boundary (no partial slice still feeding). */
    private boolean hoseSnappedToWholeBlock() {
        double frac = deployedDepthBlocks - Math.floor(deployedDepthBlocks);
        return (frac < WHOLE_BLOCK_EPS) || (1.0 - frac < WHOLE_BLOCK_EPS);
    }

    /**
     * True when the hose has extended as deep as it's ALLOWED to go,
     * i.e. deployedDepthBlocks ≈ (pumpY - reachLimitY).
     *
     * This prevents "mid-descent drinking": we won't suck while the hose
     * is still lowering toward its final stop after placement / after a new pulse.
     */
    private boolean hoseAtFinalDepth(ServerLevel level) {
        if (!poweredYet) return false;
        BlockPos pumpPos = getBlockPos();
        int pumpY = pumpPos.getY();

        int reachLimitY = computeReachLimitY(level, pumpPos);
        double maxDepthAllowedBlocks = Math.max(0.0, pumpY - reachLimitY);

        return Math.abs(deployedDepthBlocks - maxDepthAllowedBlocks) < FINAL_DEPTH_EPS;
    }

    /* ------------------------------------------------------ */
    /* Fluid search / suction / greedy evaporation            */
    /* ------------------------------------------------------ */

    /** straight-down scan hint for targetFluidPos */
    private BlockPos findClosestFluidBelow(ServerLevel level, BlockPos origin) {
        int x = origin.getX();
        int z = origin.getZ();
        int minY = level.dimensionType().minY();
        int maxDepth = Math.min(MAX_SCAN_DEPTH, origin.getY() - minY);

        for (int dy = 1; dy <= maxDepth; dy++) {
            BlockPos p = new BlockPos(x, origin.getY() - dy, z);
            FluidState fs = level.getFluidState(p);

            if (!fs.isEmpty() && fs.isSource()) {
                return p.immutable();
            }

            BlockState bs = level.getBlockState(p);
            if (!bs.isAir() && fs.isEmpty()) {
                break;
            }
        }
        return null;
    }

    private boolean isStillValidFluidSource(ServerLevel level, BlockPos p) {
        if (p == null) return false;
        FluidState fs = level.getFluidState(p);
        return !fs.isEmpty() && fs.isSource();
    }

    /**
     * Scan column under the pump (same X/Z) between yLo and yHi inclusive.
     * Return the first BlockPos that currently contains any fluid.
     */
    private BlockPos findNearestWaterAlongColumn(ServerLevel level, int x, int z, int yLo, int yHi) {
        if (yLo > yHi) { int tmp = yLo; yLo = yHi; yHi = tmp; }
        for (int y = yLo; y <= yHi; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getFluidState(p).isEmpty()) {
                return p;
            }
        }
        return null;
    }

    /** Blob scan info. */
    private static class WaterBlobScan {
        final Set<BlockPos> visited = new HashSet<>();
        BlockPos sourceBlock = null;
        boolean anySourceStillPresent = false;
    }

    /** BFS through connected water, up to BFS_MAX_NODES. */
    private WaterBlobScan bfsScanWaterBlob(ServerLevel level, BlockPos start, int maxNodes) {
        WaterBlobScan out = new WaterBlobScan();

        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        out.visited.add(start);

        int scanned = 0;
        while (!q.isEmpty() && scanned < maxNodes) {
            BlockPos cur = q.poll();
            scanned++;

            FluidState fsCur = level.getFluidState(cur);
            if (fsCur.isEmpty()) continue;

            if (fsCur.isSource()) {
                out.anySourceStillPresent = true;
                if (out.sourceBlock == null) {
                    out.sourceBlock = cur.immutable();
                }
            }

            for (Direction dir : Direction.values()) {
                BlockPos nxt = cur.relative(dir);
                if (out.visited.add(nxt)) {
                    FluidState fsNxt = level.getFluidState(nxt);
                    if (!fsNxt.isEmpty()) {
                        q.add(nxt);
                    }
                }
            }
        }

        return out;
    }

    /**
     * Try to drain a real source if we're touching water. If the blob then has no sources,
     * arm greedy cleanup starting from the pump column near the hose tip.
     *
     * NEW GATE:
     *   We return immediately unless BOTH:
     *     - hoseSnappedToWholeBlock() is true, AND
     *     - hoseAtFinalDepth(level) is true.
     * That blocks the "start sucking mid-drop" bug when the pump is first placed.
     */
    private void tryDrainIfTouching(ServerLevel level) {
        if (!poweredYet) return;
        if (!hoseSnappedToWholeBlock() || !hoseAtFinalDepth(level)) return;

        BlockPos pumpPos = getBlockPos();
        int pumpY = pumpPos.getY();
        int x = pumpPos.getX();
        int z = pumpPos.getZ();

        // scan the vertical band from the hose tip (floored) up to just above the pump
        int tipFloorY = Mth.floor(this.tubeHeadYExact);
        int scanBottom = tipFloorY;
        int scanTop    = pumpY + 1;

        BlockPos start = findNearestWaterAlongColumn(level, x, z, scanBottom, scanTop);
        if (start == null) return;

        // scan before draining
        WaterBlobScan scan1 = bfsScanWaterBlob(level, start, BFS_MAX_NODES);
        if (scan1.sourceBlock == null) return; // no real source in reach

        // pull 1000 mB from that source
        FluidState fsSrc = level.getFluidState(scan1.sourceBlock);
        if (fsSrc.isEmpty() || !fsSrc.isSource()) return;

        FluidStack bucket = new FluidStack(fsSrc.getType(), 1000);
        int filled = tank.fill(bucket, FluidTank.FluidAction.EXECUTE);
        if (filled <= 0) {
            // tank full, don't alter world
            return;
        }

        // remove the actual source block
        level.setBlock(scan1.sourceBlock, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        forceSyncSoon();

        // re-scan for the blob touching the pump column (if any)
        BlockPos start2 = findNearestWaterAlongColumn(level, x, z, scanBottom, scanTop);
        if (start2 == null) {
            // nothing left to clean
            cleanupAnchor = null;
            return;
        }

        WaterBlobScan scan2 = bfsScanWaterBlob(level, start2, BFS_MAX_NODES);

        // If there's still ANY source in that connected blob, don't clean it.
        if (scan2.anySourceStillPresent) {
            cleanupAnchor = null;
            return;
        }

        // Arm greedy cleanup from the pump column.
        cleanupAnchor = start2.immutable();
        cleanupCooldown = 0; // start collapsing soon
    }

    /**
     * Greedy staged evaporation:
     * - If armed and delay elapsed, flood-fill from cleanupAnchor,
     *   gather all connected flowing water (no sources should remain),
     *   pick the farthest cells from pump column and remove up to N.
     * - This repeats every GREEDY_EVAP_TICK_DELAY ticks.
     */
    private void stepGreedyEvaporation(ServerLevel level) {
        if (cleanupAnchor == null) return;

        if (cleanupCooldown > 0) {
            cleanupCooldown--;
            return;
        }

        // If anchor itself isn't water anymore, try to re-anchor to nearest water in the hose column.
        BlockPos pumpPos = getBlockPos();
        int x = pumpPos.getX();
        int z = pumpPos.getZ();
        int pumpY = pumpPos.getY();
        if (level.getFluidState(cleanupAnchor).isEmpty()) {
            int tipFloorY = Mth.floor(this.tubeHeadYExact);
            BlockPos re = findNearestWaterAlongColumn(level, x, z, tipFloorY, pumpY + 1);
            if (re == null) { cleanupAnchor = null; return; }
            cleanupAnchor = re.immutable();
        }

        // Scan the current leftover blob from cleanupAnchor.
        WaterBlobScan scan = bfsScanWaterBlob(level, cleanupAnchor, BFS_MAX_NODES);

        // If some new source crept in mid-cleanup, abort (lake refilled).
        if (scan.anySourceStillPresent) {
            cleanupAnchor = null;
            return;
        }

        if (scan.visited.isEmpty()) {
            cleanupAnchor = null;
            return;
        }

        // Pick farthest flowing-water blocks from pump column.
        List<BlockPos> candidates = new ArrayList<>();
        int bestDist = Integer.MIN_VALUE;

        for (BlockPos p : scan.visited) {
            FluidState fs = level.getFluidState(p);
            if (fs.isEmpty() || fs.isSource()) continue; // only flowing water
            int d = Math.abs(p.getX() - x)
                    + Math.abs(p.getY() - pumpY)
                    + Math.abs(p.getZ() - z);
            if (d > bestDist) {
                bestDist = d;
                candidates.clear();
                candidates.add(p);
            } else if (d == bestDist) {
                candidates.add(p);
            }
        }

        if (candidates.isEmpty()) {
            cleanupAnchor = null;
            return;
        }

        // Remove up to GREEDY_EVAP_BLOCKS_PER_STEP of those farthest cells.
        int removed = 0;
        for (BlockPos p : candidates) {
            if (removed >= GREEDY_EVAP_BLOCKS_PER_STEP) break;
            FluidState fs = level.getFluidState(p);
            if (!fs.isEmpty() && !fs.isSource()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                removed++;
            }
        }

        // Pace visual collapse.
        cleanupCooldown = GREEDY_EVAP_TICK_DELAY;
        if (removed > 0) forceSyncSoon();
    }

    /* ------------------------------------------------------ */
    /* Client sync + renderer hooks                           */
    /* ------------------------------------------------------ */

    public Integer getTubeRenderY() {
        return poweredYet ? tubeHeadY : null;
    }

    public Double getTubeRenderYExact() {
        return poweredYet ? tubeHeadYExact : null;
    }

    public int estimateMidTubeLight() {
        if (level == null) return 0x00F000F0;
        int pumpY = getBlockPos().getY();
        int midY = (pumpY + tubeHeadY) / 2;
        BlockPos sample = new BlockPos(getBlockPos().getX(), midY, getBlockPos().getZ());
        int block = level.getBrightness(LightLayer.BLOCK, sample);
        int sky   = level.getBrightness(LightLayer.SKY,   sample);
        return (sky << 20) | (block << 4);
    }

    private void forceSyncSoon() {
        lastSyncedRenderY = null;
        lastSyncedExactY  = null;
        syncCooldown = 0;
    }

    private void maybeSyncToClient() {
        if (level == null || level.isClientSide) return;

        Integer curInt   = poweredYet ? tubeHeadY      : null;
        Double  curExact = poweredYet ? tubeHeadYExact : null;

        boolean intChanged =
                !Objects.equals(lastSyncedRenderY, curInt);

        boolean exactChanged =
                (lastSyncedExactY == null && curExact != null)
                        || (lastSyncedExactY != null && curExact == null)
                        || (lastSyncedExactY != null && curExact != null
                        && Math.abs(lastSyncedExactY - curExact) > 1.0e-4);

        syncCooldown--;

        if (syncCooldown <= 0 || intChanged || exactChanged) {
            syncCooldown = SYNC_MIN_INTERVAL_TICKS;
            lastSyncedRenderY = curInt;
            lastSyncedExactY  = curExact;
            doSyncPacket();
        }
    }

    private void doSyncPacket() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState s = getBlockState();
            level.sendBlockUpdated(worldPosition, s, s, Block.UPDATE_CLIENTS);
        }
    }

    /* ------------------------------------------------------ */
    /* Save / Load                                            */
    /* ------------------------------------------------------ */

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        this.queuedPulses = in.getIntOr("QueuedPulses", this.queuedPulses);

        this.poweredYet  = in.getBooleanOr("PoweredYet", this.poweredYet);
        this.tubeHeadY   = in.getIntOr("TubeHeadY", this.tubeHeadY);

        this.deployedDepthBlocks = in.getDoubleOr("DeployedDepth", this.deployedDepthBlocks);
        this.pendingExtendBlocks = in.getDoubleOr("PendingExtend", this.pendingExtendBlocks);
        this.tubeHeadYExact      = in.getDoubleOr("TubeHeadYExact", this.tubeHeadYExact);

        var tx = in.read("TX", Codec.INT);
        var ty = in.read("TY", Codec.INT);
        var tz = in.read("TZ", Codec.INT);
        if (tx.isPresent() && ty.isPresent() && tz.isPresent()) {
            this.targetFluidPos = new BlockPos(tx.get(), ty.get(), tz.get());
        } else {
            this.targetFluidPos = null;
        }

        in.child("Energy").ifPresent(energy::deserialize);
        tank.deserialize(in.childOrEmpty("Tank"));

        // cleanup state is runtime only; clear on load
        this.cleanupAnchor = null;
        this.cleanupCooldown = 0;

        this.lastSyncedRenderY = null;
        this.lastSyncedExactY  = null;
        this.syncCooldown      = 0;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        out.putInt("QueuedPulses", this.queuedPulses);

        out.putBoolean("PoweredYet", this.poweredYet);
        out.putInt("TubeHeadY", this.tubeHeadY);

        out.putDouble("DeployedDepth", this.deployedDepthBlocks);
        out.putDouble("PendingExtend", this.pendingExtendBlocks);
        out.putDouble("TubeHeadYExact", this.tubeHeadYExact);

        if (this.targetFluidPos != null) {
            out.store("TX", Codec.INT, this.targetFluidPos.getX());
            out.store("TY", Codec.INT, this.targetFluidPos.getY());
            out.store("TZ", Codec.INT, this.targetFluidPos.getZ());
        }

        this.energy.serialize(out.child("Energy"));
        this.tank.serialize(out.child("Tank"));
        // cleanupAnchor / cleanupCooldown are intentionally not persisted
    }

    /* ------------------------------------------------------ */
    /* Networking                                             */
    /* ------------------------------------------------------ */

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }
}
