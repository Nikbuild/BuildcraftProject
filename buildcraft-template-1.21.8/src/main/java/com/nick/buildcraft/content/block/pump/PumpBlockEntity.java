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

import java.util.Objects;

/**
 * Behaves like classic BuildCraft pump logic:
 *
 * - Needs engine pulses / FE to extend.
 * - Tube lowers over time toward the nearest fluid source under the pump.
 * - When tube head reaches fluid, THEN we start draining.
 * - Renderer shows tube down to tubeHeadY, NOT instantly to the source.
 *
 * Notes / simplifications right now:
 * - 1 pulse moves the head 1 block downward, max one block per tick.
 * - Draining is still instant/1 bucket per tick once we are touching the source.
 * - No retract yet.
 * - No per-bucket FE cost yet.
 */
public class PumpBlockEntity extends BlockEntity
        implements EnginePulseAcceptorApi, EnginePowerAcceptorApi {

    /* -------------------- tuning knobs -------------------- */

    /** how deep we are allowed to scan for liquid candidates */
    private static final int MAX_SCAN_DEPTH = 256;

    /** internal tank size */
    private static final int TANK_CAPACITY_MB = 4000;

    /** energy buffer capacity (same numbers you had) */
    private static final int MAX_FE = 200_000;

    /** we only sync to client at most every N ticks unless something changed */
    private static final int SYNC_MIN_INTERVAL_TICKS = 8;

    /* -------------------- server state -------------------- */

    /** FE battery fed by engines */
    private final BCEnergyStorage energy =
            new BCEnergyStorage(MAX_FE, MAX_FE, s -> setChanged());

    /** pulses from engines; we "spend" these to extend */
    private int queuedPulses = 0;

    /** where we THINK the next fluid source of interest is (first valid downward source) */
    private BlockPos targetFluidPos = null;

    /**
     * The current Y position of the tube head tip in world coords.
     * Starts at pumpY-1 when placed.
     * Moves downward 1 block per pulse.
     *
     * The renderer should draw tube down to this.
     */
    private int tubeHeadY;

    /**
     * Client render hint. The renderer calls getTubeRenderY() which just returns tubeHeadY.
     * Kept as Integer to match old signature (null = no tube yet).
     */
    private Integer lastSyncedRenderY = null;

    /** rate-limit sync spam */
    private int syncCooldown = 0;

    /** internal tank of pumped fluid */
    private final FluidTank tank = new FluidTank(TANK_CAPACITY_MB, stack -> true);

    public PumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.PUMP.get(), pos, state);

        // default tube head starts just under the pump body
        this.tubeHeadY = pos.getY() - 1;
    }

    /* ------------------------------------------------------ */
    /* Engine interfaces                                      */
    /* ------------------------------------------------------ */

    @Override
    public boolean acceptEnginePulse(Direction from) {
        // server only
        if (level != null && !level.isClientSide) {
            // store up to 64 pulses so spam doesn't explode
            queuedPulses = Mth.clamp(queuedPulses + 1, 0, 64);
            setChanged();
        }
        return true;
    }

    @Override
    public void acceptEnginePower(Direction from, int power) {
        // just buffer FE for future balancing, we won't spend it yet
        energy.receiveEnergy(Math.max(0, power), false);
        setChanged();
    }

    public BCEnergyStorage getEnergyStorage() { return energy; }
    public FluidTank getFluidTank() { return tank; }

    /* ------------------------------------------------------ */
    /* Tick logic (server only)                               */
    /* ------------------------------------------------------ */

    public static void serverTick(Level level, BlockPos pos, BlockState state, PumpBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        // Step 1: if we don't currently have a known fluid target or it's invalid,
        // rescan straight down to find the closest source.
        if (be.targetFluidPos == null || !be.isStillValidFluidSource(server, be.targetFluidPos)) {
            be.targetFluidPos = be.findClosestFluidBelow(server, pos);
        }

        // Step 2: try to extend tube downward TOWARD that target, but only if:
        // - we actually have pulses to spend
        // - there IS a target
        be.extendTubeTowardTarget(server, pos);

        // Step 3: if tube head is touching source fluid now, drain it into tank.
        be.tryDrainIfTouching(server);

        // Step 4: sync to clients if needed (tube moved or periodic refresh)
        be.maybeSyncToClient();

        be.setChanged();
    }

    /* ------------------------------------------------------ */
    /* Core behaviors                                         */
    /* ------------------------------------------------------ */

    /** Scan down same X/Z as pump, stop at first source fluid. */
    private BlockPos findClosestFluidBelow(ServerLevel level, BlockPos origin) {
        int x = origin.getX();
        int z = origin.getZ();

        int minY = level.dimensionType().minY();
        int maxDepth = Math.min(MAX_SCAN_DEPTH, origin.getY() - minY);

        for (int dy = 1; dy <= maxDepth; dy++) {
            BlockPos check = new BlockPos(x, origin.getY() - dy, z);
            FluidState fs = level.getFluidState(check);
            if (!fs.isEmpty() && fs.isSource()) {
                return check.immutable();
            }

            // hit solid dry block? we assume we can't reach past it
            BlockState bs = level.getBlockState(check);
            if (!bs.isAir() && fs.isEmpty()) {
                break;
            }
        }

        return null;
    }

    /** still a valid fluid block? */
    private boolean isStillValidFluidSource(ServerLevel level, BlockPos p) {
        if (p == null) return false;
        FluidState fs = level.getFluidState(p);
        return !fs.isEmpty() && fs.isSource();
    }

    /**
     * Spend pulses to move tubeHeadY downward one block at a time
     * until either we run out of pulses OR we've reached/passed targetFluidPos.y.
     *
     * We never move below targetFluidPos.y (no point).
     */
    private void extendTubeTowardTarget(ServerLevel level, BlockPos pumpPos) {
        if (targetFluidPos == null) return;
        if (queuedPulses <= 0) return;

        int targetY = targetFluidPos.getY();

        // already at or below the fluid height? stop extending.
        if (tubeHeadY <= targetY) return;

        // We are above the target, and we have at least one pulse.
        // Move down exactly 1 block this tick.
        tubeHeadY -= 1;
        queuedPulses -= 1;

        // rate-limit but force flag for sync because visual length changed
        forceSyncSoon();
    }

    /**
     * If the tube head tip is now at/below the fluid source y,
     * and we're actually sitting in a source fluid block,
     * suck up 1 bucket (1000 mB) per tick into tank and kill that source block.
     *
     * NOTE: we DON'T auto-re-scan + "infinite source lake spreading"
     * logic or anything fancy yet. This is fine for now.
     */
    private void tryDrainIfTouching(ServerLevel level) {
        // must have a target
        if (targetFluidPos == null) return;

        // is our tip at or below the target source height?
        if (tubeHeadY > targetFluidPos.getY()) return;

        // check the actual block at targetFluidPos
        FluidState fs = level.getFluidState(targetFluidPos);
        if (fs.isEmpty() || !fs.isSource()) return;

        // try insert 1000 mB
        FluidStack stack = new FluidStack(fs.getType(), 1000);
        int filled = tank.fill(stack, FluidTank.FluidAction.EXECUTE);
        if (filled <= 0) {
            // tank full, can't drain
            return;
        }

        // remove that source block
        level.setBlock(targetFluidPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        // after draining, our known target may no longer be valid next tick,
        // serverTick() will rescan next loop
        forceSyncSoon();
    }

    /* ------------------------------------------------------ */
    /* Sync / rendering helpers                               */
    /* ------------------------------------------------------ */

    /**
     * This is what the renderer should use as the “bottom Y of the hose”.
     * We keep the same signature (Integer) that PumpRenderer expects.
     */
    public Integer getTubeRenderY() {
        // we always have a hose once placed, it starts pumpY-1.
        // if you ever want "no hose yet", return null until first pulse.
        return tubeHeadY;
    }

    /** Called whenever we changed tubeHeadY or drained something significant. */
    private void forceSyncSoon() {
        // make sure next maybeSyncToClient() sees "changed"
        lastSyncedRenderY = null;
        syncCooldown = 0;
    }

    /**
     * We sync two cases:
     *  - periodic heartbeat every SYNC_MIN_INTERVAL_TICKS
     *  - OR tubeHeadY changed since the last sync
     */
    private void maybeSyncToClient() {
        if (level == null || level.isClientSide) return;

        boolean renderChanged = !Objects.equals(lastSyncedRenderY, Integer.valueOf(tubeHeadY));

        syncCooldown--;
        if (syncCooldown <= 0 || renderChanged) {
            syncCooldown = SYNC_MIN_INTERVAL_TICKS;
            lastSyncedRenderY = tubeHeadY;
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

    /**
     * Lighting helper for renderer, same idea you already had.
     * We pick a sample halfway between pump Y and tubeHeadY.
     */
    public int estimateMidTubeLight() {
        if (level == null) return 0x00F000F0;

        int pumpY = getBlockPos().getY();
        int midY = (pumpY + tubeHeadY) / 2;

        BlockPos sample = new BlockPos(getBlockPos().getX(), midY, getBlockPos().getZ());
        int block = level.getBrightness(LightLayer.BLOCK, sample);
        int sky   = level.getBrightness(LightLayer.SKY,   sample);
        return (sky << 20) | (block << 4);
    }

    /* ------------------------------------------------------ */
    /* Save / load                                            */
    /* ------------------------------------------------------ */

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        this.queuedPulses = in.getIntOr("QueuedPulses", this.queuedPulses);

        // tube head Y
        this.tubeHeadY = in.getIntOr("TubeHeadY", this.tubeHeadY);

        // target fluid pos
        var tx = in.read("TX", Codec.INT);
        var ty = in.read("TY", Codec.INT);
        var tz = in.read("TZ", Codec.INT);
        if (tx.isPresent() && ty.isPresent() && tz.isPresent()) {
            this.targetFluidPos = new BlockPos(tx.get(), ty.get(), tz.get());
        } else {
            this.targetFluidPos = null;
        }

        // energy + tank
        in.child("Energy").ifPresent(energy::deserialize);
        tank.deserialize(in.childOrEmpty("Tank"));

        // reset sync throttle so client updates right after load
        this.lastSyncedRenderY = null;
        this.syncCooldown = 0;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        out.putInt("QueuedPulses", this.queuedPulses);
        out.putInt("TubeHeadY",    this.tubeHeadY);

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
