package com.nick.buildcraft.content.block.pump;

import com.mojang.serialization.Codec;
import com.nick.buildcraft.api.engine.EnginePowerAcceptorApi;
import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.energy.BCEnergyStorage;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * PumpBlockEntity
 *
 * - Handles hose extension and sucking source blocks.
 * - Stores pumped fluid in an internal tank.
 * - Greedily clears leftover flowing water.
 * - Syncs hose render state to clients.
 *
 * NEW:
 * - Every tick, tries to push fluid from its tank into ANY adjacent
 *   neighbor that exposes IFluidHandler (pipe, tank, etc.).
 */
public class PumpBlockEntity extends BlockEntity
        implements EnginePulseAcceptorApi, EnginePowerAcceptorApi {

    /* -------------------------------- config -------------------------------- */

    private static final int MAX_SCAN_DEPTH = 256;
    private static final int BFS_MAX_NODES  = 256;

    private static final int HOSE_TICKS_PER_BLOCK = 40;
    private static final double FEED_SPEED_BLOCKS_PER_TICK =
            1.0 / (double) HOSE_TICKS_PER_BLOCK;

    private static final int TANK_CAPACITY_MB = 4000;
    private static final int MAX_FE = 200_000;
    private static final int SYNC_MIN_INTERVAL_TICKS = 8;

    private static final int GREEDY_EVAP_BLOCKS_PER_STEP = 1;
    private static final int GREEDY_EVAP_TICK_DELAY = 5;

    private static final double WHOLE_BLOCK_EPS = 1.0e-3;
    private static final double FINAL_DEPTH_EPS = 1.0e-3;

    /** max mB to try and push out per tick TOTAL across all sides */
    private static final int PUMP_PUSH_PER_TICK = 100;

    /* -------------------------------- runtime state -------------------------------- */

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

    /** internal holding tank for pumped fluid */
    private final FluidTank tank = new FluidTank(TANK_CAPACITY_MB, fs -> true);

    /** staged cleanup state */
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

    /* -------------------------------- Engine power API -------------------------------- */

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

    /** This is what ModCapabilities will expose as the pump's fluid capability. */
    @Nullable
    public IFluidHandler getFluidHandlerForSide(@Nullable Direction side) {
        return tank;
    }

    /* -------------------------------- main tick -------------------------------- */

    public static void serverTick(Level level, BlockPos pos, BlockState state, PumpBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        // 1. track/find water target
        if (be.targetFluidPos == null || !be.isStillValidFluidSource(server, be.targetFluidPos)) {
            be.targetFluidPos = be.findClosestFluidBelow(server, pos);
        }

        // 2. advance hose physics
        be.advanceHose(server, pos);

        // 3. suck from world into internal tank if hose is settled
        be.tryDrainIfTouching(server);

        // 4. collapse flowing leftovers visually
        be.stepGreedyEvaporation(server);

        // 5. NEW: try to output stored fluid into any neighbor with IFluidHandler
        be.tryOutputToNeighbors(server);

        // 6. sync tube render info to client
        be.maybeSyncToClient();

        be.setChanged();
    }

    /* -------------------------------- hose physics -------------------------------- */

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
                return y + 1;
            }
        }
        return minY;
    }

    private void advanceHose(ServerLevel level, BlockPos pumpPos) {
        int pumpY = pumpPos.getY();

        // convert # pulses into "blocks worth" of hose we still owe
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

    private boolean hoseSnappedToWholeBlock() {
        double frac = deployedDepthBlocks - Math.floor(deployedDepthBlocks);
        return (frac < WHOLE_BLOCK_EPS) || (1.0 - frac < WHOLE_BLOCK_EPS);
    }

    private boolean hoseAtFinalDepth(ServerLevel level) {
        if (!poweredYet) return false;
        BlockPos pumpPos = getBlockPos();
        int pumpY = pumpPos.getY();

        int reachLimitY = computeReachLimitY(level, pumpPos);
        double maxDepthAllowedBlocks = Math.max(0.0, pumpY - reachLimitY);

        return Math.abs(deployedDepthBlocks - maxDepthAllowedBlocks) < FINAL_DEPTH_EPS;
    }

    /* -------------------------------- sucking water -------------------------------- */

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

    private static class WaterBlobScan {
        final Set<BlockPos> visited = new HashSet<>();
        BlockPos sourceBlock = null;
        boolean anySourceStillPresent = false;
    }

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
     * Pull 1000 mB from the water blob the hose is touching, if:
     * - hose is fully settled
     * - we found an actual source block in reach
     * Then:
     * - remove that source block
     * - maybe start greedy cleanup
     */
    private void tryDrainIfTouching(ServerLevel level) {
        if (!poweredYet) return;
        if (!hoseSnappedToWholeBlock() || !hoseAtFinalDepth(level)) return;

        BlockPos pumpPos = getBlockPos();
        int pumpY = pumpPos.getY();
        int x = pumpPos.getX();
        int z = pumpPos.getZ();

        int tipFloorY = Mth.floor(this.tubeHeadYExact);
        int scanBottom = tipFloorY;
        int scanTop    = pumpY + 1;

        BlockPos start = findNearestWaterAlongColumn(level, x, z, scanBottom, scanTop);
        if (start == null) return;

        // Look at the blob before we slurp
        WaterBlobScan scan1 = bfsScanWaterBlob(level, start, BFS_MAX_NODES);
        if (scan1.sourceBlock == null) return; // no SOURCE found

        // try to insert ~1 bucket into internal tank
        FluidState fsSrc = level.getFluidState(scan1.sourceBlock);
        if (fsSrc.isEmpty() || !fsSrc.isSource()) return;

        FluidStack bucket = new FluidStack(fsSrc.getType(), 1000);
        int filled = tank.fill(bucket, FluidTank.FluidAction.EXECUTE);
        if (filled <= 0) {
            // tank full, don't break world
            return;
        }

        // delete the source block we slurped
        level.setBlock(scan1.sourceBlock, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        forceSyncSoon();

        // after removing, see what blob is left touching our hose column
        BlockPos start2 = findNearestWaterAlongColumn(level, x, z, scanBottom, scanTop);
        if (start2 == null) {
            cleanupAnchor = null;
            return;
        }

        WaterBlobScan scan2 = bfsScanWaterBlob(level, start2, BFS_MAX_NODES);

        // if there's still ANY source in the blob, don't evaporate
        if (scan2.anySourceStillPresent) {
            cleanupAnchor = null;
            return;
        }

        // otherwise arm evaporation
        cleanupAnchor = start2.immutable();
        cleanupCooldown = 0;
    }

    /* -------------------------------- greedy evaporation -------------------------------- */

    private void stepGreedyEvaporation(ServerLevel level) {
        if (cleanupAnchor == null) return;

        if (cleanupCooldown > 0) {
            cleanupCooldown--;
            return;
        }

        BlockPos pumpPos = getBlockPos();
        int x = pumpPos.getX();
        int z = pumpPos.getZ();
        int pumpY = pumpPos.getY();

        // If anchor isn't water anymore, re-anchor to current hose column water (or give up)
        if (level.getFluidState(cleanupAnchor).isEmpty()) {
            int tipFloorY = Mth.floor(this.tubeHeadYExact);
            BlockPos re = findNearestWaterAlongColumn(level, x, z, tipFloorY, pumpY + 1);
            if (re == null) { cleanupAnchor = null; return; }
            cleanupAnchor = re.immutable();
        }

        WaterBlobScan scan = bfsScanWaterBlob(level, cleanupAnchor, BFS_MAX_NODES);

        // blob refilled with sources? stop cleanup
        if (scan.anySourceStillPresent) {
            cleanupAnchor = null;
            return;
        }

        if (scan.visited.isEmpty()) {
            cleanupAnchor = null;
            return;
        }

        // Pick farthest flowing cells (non-source) to delete first
        List<BlockPos> candidates = new ArrayList<>();
        int bestDist = Integer.MIN_VALUE;

        for (BlockPos p : scan.visited) {
            FluidState fs = level.getFluidState(p);
            if (fs.isEmpty() || fs.isSource()) continue;
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

        int removed = 0;
        for (BlockPos p : candidates) {
            if (removed >= GREEDY_EVAP_BLOCKS_PER_STEP) break;
            FluidState fs = level.getFluidState(p);
            if (!fs.isEmpty() && !fs.isSource()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                removed++;
            }
        }

        cleanupCooldown = GREEDY_EVAP_TICK_DELAY;
        if (removed > 0) forceSyncSoon();
    }

    /* -------------------------------- pushing fluid OUT -------------------------------- */

    /**
     * Try to output up to PUMP_PUSH_PER_TICK mB into neighbors.
     * We iterate all 6 faces. First face that takes anything gets fed.
     * (This means you don't need perfect orientation just to test.)
     */
    private void tryOutputToNeighbors(ServerLevel level) {
        int remaining = Math.min(PUMP_PUSH_PER_TICK, tank.getFluidAmount());
        if (remaining <= 0) return;

        // We’ll try every direction. As soon as we successfully send *some* fluid
        // to someone, we’re happy for this tick.
        for (Direction dir : Direction.values()) {
            if (remaining <= 0) break;
            remaining -= tryOutputOneSide(level, dir, remaining);
        }

        if (remaining < PUMP_PUSH_PER_TICK) {
            // we actually moved fluid
            setChanged();
        }
    }

    /**
     * Attempt to push up to `maxMb` into the neighbor on `dir`.
     * Returns how much actually pushed.
     */
    private int tryOutputOneSide(ServerLevel level, Direction dir, int maxMb) {
        if (maxMb <= 0) return 0;
        if (tank.getFluidAmount() <= 0) return 0;

        BlockPos neighborPos = worldPosition.relative(dir);

        // ask the neighbor for a fluid handler that's facing *us*
        IFluidHandler neighborCap = level.getCapability(
                Capabilities.FluidHandler.BLOCK,
                neighborPos,
                dir.getOpposite()
        );
        if (neighborCap == null) {
            return 0;
        }

        int toSend = Math.min(maxMb, tank.getFluidAmount());
        if (toSend <= 0) return 0;

        // simulate how much they'll take
        FluidStack attempt = tank.getFluid().copyWithAmount(toSend);
        int canAccept = neighborCap.fill(attempt, IFluidHandler.FluidAction.SIMULATE);
        if (canAccept <= 0) {
            return 0;
        }

        // actually drain that amount from us
        FluidStack drained = tank.drain(canAccept, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) {
            return 0;
        }

        int accepted = neighborCap.fill(drained, IFluidHandler.FluidAction.EXECUTE);

        // if there's overflow, put it back into our tank
        int leftover = drained.getAmount() - accepted;
        if (leftover > 0) {
            tank.fill(drained.copyWithAmount(leftover), IFluidHandler.FluidAction.EXECUTE);
        }

        return accepted;
    }

    /* -------------------------------- rendering sync -------------------------------- */

    public Integer getTubeRenderY() { return poweredYet ? tubeHeadY : null; }

    public Double getTubeRenderYExact() { return poweredYet ? tubeHeadYExact : null; }

    public int estimateMidTubeLight() {
        if (level == null) return 0x00F000F0;
        int pumpY = getBlockPos().getY();
        int midY = (pumpY + tubeHeadY) / 2;
        BlockPos sample = new BlockPos(getBlockPos().getX(), midY, getBlockPos().getZ());
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample);
        int sky   = level.getBrightness(net.minecraft.world.level.LightLayer.SKY,   sample);
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

    /* -------------------------------- save/load -------------------------------- */

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
    }

    /* -------------------------------- networking -------------------------------- */

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }
}
