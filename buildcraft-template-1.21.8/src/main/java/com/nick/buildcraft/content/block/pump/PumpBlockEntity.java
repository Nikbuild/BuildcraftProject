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
 * Pump: smooth hose payout with backlog, collision clamp, lake suction.
 *
 * Key behaviors:
 *
 * - Hose starts hidden until first pulse.
 * - Each engine pulse adds +1 block of "credit" (pendingExtendBlocks).
 *   We unspool that credit smoothly over time (constant feed rate),
 *   so motion is continuous and keeps going even if pulses stop.
 *
 * - Collision: hose cannot clip through solid blocks. We scan straight
 *   down and clamp so the hose tip stops just above any solid-with-
 *   collision block (ignoring fluids).
 *
 * - Suction:
 *   We can suck water from ANY connected blob of water that's touching
 *   the hose column, even if the actual source block is sideways.
 *
 *   Logic:
 *   1. We scan the vertical column at (pumpX, pumpZ) from the *real*
 *      hose tip height (tubeHeadYExact floored) up to the pump head,
 *      and grab the first water block in that band. Call that "start".
 *
 *   2. BFS that blob of connected water from start:
 *      - record all positions (visited)
 *      - remember if we saw any source blocks at all
 *      - remember the first source block we saw (sourceBlock)
 *
 *   3. If we found a sourceBlock, try to put 1000 mB of that fluid
 *      into the internal tank. If tank accepted it, we delete that
 *      exact source block (set to air).
 *
 *   4. After deleting that source, we rescan the hose column for
 *      water and BFS that blob again.
 *      - If that new blob still has ANY source blocks feeding it,
 *        we leave all flowing water alone (infinite lake behavior).
 *      - Otherwise (no sources feeding it anymore), we "evaporate"
 *        that blob by deleting any remaining flowing water blocks
 *        in it. This makes a single puddle behave like bucket pickup.
 *
 *   This fixes both:
 *   - The "hose just barely sticks out" case (it still drains),
 *   - The "ghost leftover flowing water" case.
 */
public class PumpBlockEntity extends BlockEntity
        implements EnginePulseAcceptorApi, EnginePowerAcceptorApi {

    /* ---------------- tuning knobs ---------------- */

    /** how deep we bother scanning for targetFluidPos hint (not critical anymore) */
    private static final int MAX_SCAN_DEPTH = 256;

    /** BFS cap so we don't explore entire oceans per tick */
    private static final int BFS_MAX_NODES = 256;

    /** how many ticks it should take to feed 1 full block of hose */
    private static final int HOSE_TICKS_PER_BLOCK = 40;
    private static final double FEED_SPEED_BLOCKS_PER_TICK =
            1.0 / (double) HOSE_TICKS_PER_BLOCK;

    /** internal tank size */
    private static final int TANK_CAPACITY_MB = 4000;

    /** FE buffer capacity (for future cost logic) */
    private static final int MAX_FE = 200_000;

    /** min ticks between sync packets unless something visibly changed */
    private static final int SYNC_MIN_INTERVAL_TICKS = 8;

    /* ---------------- live state ---------------- */

    /** FE battery fed by engines */
    private final BCEnergyStorage energy =
            new BCEnergyStorage(MAX_FE, MAX_FE, s -> setChanged());

    /** pulses queued from engines this tick; each pulse is +1 block credit */
    private int queuedPulses = 0;

    /** downward "best guess" fluid target under pump (just a hint, not required) */
    private BlockPos targetFluidPos = null;

    /** true after the first powered extension; before that, render no hose */
    private boolean poweredYet = false;

    /**
     * Total depth of hose already physically unspooled below pump, in blocks (can be fractional).
     * 0.0 = fully retracted.
     */
    private double deployedDepthBlocks = 0.0;

    /**
     * Remaining owed hose in blocks. Each pulse adds +1.0.
     * We feed this out over time at FEED_SPEED_BLOCKS_PER_TICK.
     */
    private double pendingExtendBlocks = 0.0;

    /** discrete tip Y (integer snap, clamped for collision/light helpers) */
    private int tubeHeadY;

    /** fractional world Y of the actual hose tip (pumpY - deployedDepthBlocks, post-clamp) */
    private double tubeHeadYExact;

    // client sync throttling
    private Integer lastSyncedRenderY = null; // last int Y sent
    private Double  lastSyncedExactY  = null; // last exact Y sent
    private int     syncCooldown      = 0;

    /** internal 4-bucket tank */
    private final FluidTank tank = new FluidTank(TANK_CAPACITY_MB, fs -> true);

    public PumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.PUMP.get(), pos, state);

        this.poweredYet = false;
        this.deployedDepthBlocks = 0.0;
        this.pendingExtendBlocks = 0.0;

        // "tip is at pump" initially
        this.tubeHeadY = pos.getY();
        this.tubeHeadYExact = pos.getY();
    }

    /* ------------------------------------------------------ */
    /* Engine APIs                                            */
    /* ------------------------------------------------------ */

    @Override
    public boolean acceptEnginePulse(Direction from) {
        if (level != null && !level.isClientSide) {
            // accumulate up to 64 pulses so spam doesn't overflow ints
            queuedPulses = Mth.clamp(queuedPulses + 1, 0, 64);
            setChanged();
        }
        return true;
    }

    @Override
    public void acceptEnginePower(Direction from, int power) {
        // just buffer FE; hose extension currently driven by pulses, not FE consumption
        energy.receiveEnergy(Math.max(0, power), false);
        setChanged();
    }

    public BCEnergyStorage getEnergyStorage() { return energy; }
    public FluidTank getFluidTank() { return tank; }

    /* ------------------------------------------------------ */
    /* Tick (server only)                                     */
    /* ------------------------------------------------------ */

    public static void serverTick(Level level, BlockPos pos, BlockState state, PumpBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        // Maintain "hint" target under pump.
        if (be.targetFluidPos == null || !be.isStillValidFluidSource(server, be.targetFluidPos)) {
            be.targetFluidPos = be.findClosestFluidBelow(server, pos);
        }

        // Smooth hose payout / collision clamp / update head Y
        be.advanceHose(server, pos);

        // Suction + post-drain cleanup
        be.tryDrainIfTouching(server);

        // Sync to client if changed enough
        be.maybeSyncToClient();

        be.setChanged();
    }

    /* ------------------------------------------------------ */
    /* Hose motion / collision                                */
    /* ------------------------------------------------------ */

    /**
     * Find the first "hard blocker" straight down from the pump:
     *
     *  - not air
     *  - not fluid
     *  - has non-empty collision shape
     *
     * Hose is not allowed to exist at or below that Y. If such a block is at Y=b,
     * our allowed min hose tip Y is b+1.
     *
     * If we never find one, we can go all the way to world minY.
     */
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
                // can't enter this block, so hose tip must stay ABOVE it
                return y + 1;
            }
        }

        // no collision until we hit the dimension floor
        return minY;
    }

    /**
     * Core hose motion:
     * - queuedPulses -> pendingExtendBlocks (+1 block each pulse)
     * - gradually spend pendingExtendBlocks at FEED_SPEED_BLOCKS_PER_TICK
     * - clamp so we don't clip through solids
     * - update tubeHeadYExact and integer tubeHeadY
     */
    private void advanceHose(ServerLevel level, BlockPos pumpPos) {
        int pumpY = pumpPos.getY();

        // convert queued pulses into extension backlog
        if (queuedPulses > 0) {
            pendingExtendBlocks += queuedPulses;
            queuedPulses = 0;
            if (!poweredYet) {
                poweredYet = true; // first time we actually deploy hose
            }
        }

        // not powered yet? hose is invisible/up in pump
        if (!poweredYet) {
            deployedDepthBlocks = 0.0;
            tubeHeadYExact = pumpY;
            tubeHeadY = pumpY;
            return;
        }

        // how far down we're allowed (collision clamp)
        int reachLimitY = computeReachLimitY(level, pumpPos);
        double maxDepthAllowedBlocks = Math.max(0.0, pumpY - reachLimitY);

        // feed out hose smoothly this tick
        if (pendingExtendBlocks > 1e-6 && FEED_SPEED_BLOCKS_PER_TICK > 1e-9) {
            double toSpend = Math.min(FEED_SPEED_BLOCKS_PER_TICK, pendingExtendBlocks);

            // don't exceed collision limit
            if (deployedDepthBlocks + toSpend > maxDepthAllowedBlocks) {
                toSpend = Math.max(0.0, maxDepthAllowedBlocks - deployedDepthBlocks);
            }

            if (toSpend > 1e-9) {
                deployedDepthBlocks += toSpend;
                pendingExtendBlocks -= toSpend;
                if (pendingExtendBlocks < 0) pendingExtendBlocks = 0;
            }
        }

        // in case environment changed (someone placed a block under us)
        if (deployedDepthBlocks > maxDepthAllowedBlocks) {
            deployedDepthBlocks = maxDepthAllowedBlocks;
        }

        // compute real fractional tip Y
        tubeHeadYExact = pumpY - deployedDepthBlocks;

        // integer snap Y for lighting/etc. (clamped)
        int snapY = pumpY - Mth.floor(deployedDepthBlocks);
        if (snapY > pumpY) snapY = pumpY;
        if (snapY < reachLimitY) snapY = reachLimitY;
        tubeHeadY = snapY;
    }

    /* ------------------------------------------------------ */
    /* Fluid search / suction / cleanup                       */
    /* ------------------------------------------------------ */

    /** quick straight-down scan below the pump; used as a "hint" for UI/logic */
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

            // hit a solid dry block => assume can't see past this
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
     * Scan upward along the pump's X/Z column, from yLo..yHi inclusive,
     * return first block position that actually contains fluid.
     */
    private BlockPos findNearestWaterAlongColumn(ServerLevel level, int x, int z, int yLo, int yHi) {
        if (yLo > yHi) { int t = yLo; yLo = yHi; yHi = t; }
        for (int y = yLo; y <= yHi; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getFluidState(p).isEmpty()) {
                return p;
            }
        }
        return null;
    }

    /**
     * BFS result wrapper: connected water blob.
     */
    private static class WaterBlobScan {
        final Set<BlockPos> visited = new HashSet<>();
        BlockPos sourceBlock = null;           // first source block we saw
        boolean anySourceStillPresent = false; // true if we saw ANY source at all
    }

    /**
     * BFS flood fill over connected water cells starting at 'start'.
     * We gather every reachable water block, track whether we saw any
     * sources, and remember the first source for draining.
     *
     * We cap exploration at BFS_MAX_NODES for safety.
     */
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
     * Suction routine:
     *
     * 1. Figure out the vertical scan band: from the REAL hose tip height
     *    (tubeHeadYExact floored) up to pumpY+1.
     *
     *    This fixes the "pump placed low, hose barely extended" case:
     *    even if the hose is only 0.2 blocks long we still include the
     *    right Y range where water is actually touching.
     *
     * 2. Find the first water block in that band → start.
     *
     * 3. BFS that blob (scan1). If no sourceBlock in scan1, bail.
     *
     * 4. If we do find a source:
     *      - try to insert 1000 mB of that fluid into our tank
     *      - if the tank accepted it, remove exactly that source block.
     *
     * 5. After removing that source, re-scan the column to get a new start
     *    (water may have moved). BFS again (scan2).
     *
     *    If scan2 still has ANY source feeding it, we leave all flowing
     *    water as-is (infinite pond behavior).
     *
     *    If scan2 has NO sources, we "evaporate" any flowing water in scan2,
     *    so shallow puddles disappear like a bucket pickup rather than
     *    leaving stranded non-source water.
     */
    private void tryDrainIfTouching(ServerLevel level) {
        if (!poweredYet) return;

        BlockPos pumpPos = getBlockPos();
        int pumpY = pumpPos.getY();
        int x = pumpPos.getX();
        int z = pumpPos.getZ();

        // scan band bottom is REAL hose tip, not the snapped int Y
        int tipFloorY = Mth.floor(this.tubeHeadYExact);
        int scanBottom = tipFloorY;
        int scanTop    = pumpY + 1; // include just-above-pump so short hoses still count

        // find first water cell touching this column in [scanBottom..scanTop]
        BlockPos start = findNearestWaterAlongColumn(level, x, z, scanBottom, scanTop);
        if (start == null) return; // no water visible to hose at all

        // pass 1: scan BEFORE draining
        WaterBlobScan scan1 = bfsScanWaterBlob(level, start, BFS_MAX_NODES);
        if (scan1.sourceBlock == null) return; // there is water but no source in it

        // try to pump 1 bucket from that source
        FluidState fsSrc = level.getFluidState(scan1.sourceBlock);
        if (fsSrc.isEmpty() || !fsSrc.isSource()) return;

        FluidStack bucket = new FluidStack(fsSrc.getType(), 1000);
        int filled = tank.fill(bucket, FluidTank.FluidAction.EXECUTE);
        if (filled <= 0) {
            // tank is full, so don't remove world fluid
            return;
        }

        // remove the source block we drained
        level.setBlock(scan1.sourceBlock, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        // we changed tank/world, make sure client updates animation + tank state ASAP
        forceSyncSoon();

        // pass 2: AFTER removing that source, re-scan the column
        BlockPos start2 = findNearestWaterAlongColumn(level, x, z, scanBottom, scanTop);
        if (start2 == null) {
            // nothing left in the column, done
            return;
        }

        WaterBlobScan scan2 = bfsScanWaterBlob(level, start2, BFS_MAX_NODES);

        // if there's still ANY remaining source in that blob, lake still lives, do nothing
        if (scan2.anySourceStillPresent) return;

        // otherwise, no more source -> evaporate leftover flowing water in that blob
        for (BlockPos p : scan2.visited) {
            FluidState fs = level.getFluidState(p);
            if (!fs.isEmpty() && !fs.isSource()) {
                // kill only flowing water (don't nuke lava or something else that somehow slipped in)
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /* ------------------------------------------------------ */
    /* Client sync + renderer hooks                           */
    /* ------------------------------------------------------ */

    /** old-style int Y for hose tip; renderer can still use it for lighting if needed */
    public Integer getTubeRenderY() {
        return poweredYet ? tubeHeadY : null;
    }

    /** new-style fractional hose tip world Y for perfect smooth rendering */
    public Double getTubeRenderYExact() {
        return poweredYet ? tubeHeadYExact : null;
    }

    /** lighting helper if renderer wants an approximate combined light value mid-column */
    public int estimateMidTubeLight() {
        if (level == null) return 0x00F000F0;
        int pumpY = getBlockPos().getY();
        int midY = (pumpY + tubeHeadY) / 2;
        BlockPos sample = new BlockPos(getBlockPos().getX(), midY, getBlockPos().getZ());
        int block = level.getBrightness(LightLayer.BLOCK, sample);
        int sky   = level.getBrightness(LightLayer.SKY,   sample);
        return (sky << 20) | (block << 4);
    }

    /** force next maybeSyncToClient() to send, even if cooldown not elapsed */
    private void forceSyncSoon() {
        lastSyncedRenderY = null;
        lastSyncedExactY  = null;
        syncCooldown = 0;
    }

    /**
     * Sync throttling:
     * We sync both tubeHeadY (int) and tubeHeadYExact (double).
     *
     * We send an update if:
     *  - cooldown expired, OR
     *  - int Y changed, OR
     *  - exact Y changed by > ~1e-4 blocks.
     */
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

        // reset sync throttle so client gets fresh data right after load
        this.lastSyncedRenderY = null;
        this.lastSyncedExactY  = null;
        this.syncCooldown      = 0;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        out.putInt("QueuedPulses", this.queuedPulses);

        out.putBoolean("PoweredYet", this.poweredYet);
        out.putInt("TubeHeadY",     this.tubeHeadY);

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
