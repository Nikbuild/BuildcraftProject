package com.nick.buildcraft.content.block.quarry;

import com.mojang.serialization.Codec;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Server-authoritative quarry with facing-aware boustrophedon sweep, gantry motion, and
 * drop-safe output via a small queue to prevent duplication and "pop-outs".
 *
 * Output gating: queued items are preserved across power cycles, but they do not flush
 * until at least one new block is mined after power-on.
 *
 * Delegates logic to manager classes for focused responsibilities:
 * - QuarryFrameManager: Frame construction and visualization
 * - QuarryMiningManager: Mining sweep and layer traversal
 * - QuarryGantryManager: Gantry positioning and A* pathfinding
 * - QuarryOutputManager: Item queue and safe output delivery
 * - QuarrySweepManager: Final layer sweep detection
 * - QuarryEnergyManager: Energy buffer and power validation
 * - QuarryGeometryHelper: Static geometry calculations
 */
public class QuarryBlockEntity extends BlockEntity {

    /* ---------- Sync Throttle ---------- */
    private static final int SYNC_MIN_INTERVAL = 1; // Sync every tick for smooth gantry movement

    /* ---------- State Fields (Public for manager access) ---------- */

    // Frame building
    public final ArrayDeque<BlockPos> frameBuildQueue = new ArrayDeque<>();
    public int frameTickCounter = 0;
    public boolean frameComplete = false;
    public final UUID[] placementLaserIds = new UUID[12];

    /**
     * Gantry movement state machine with deterministic transitions.
     *
     * State flow:
     * IDLE -> SCANNING (when target assigned)
     * SCANNING -> RETRACTING (if obstacle detected)
     * SCANNING -> TRAVERSING (if path clear)
     * RETRACTING -> CLEARANCE_CHECK (when lift complete)
     * CLEARANCE_CHECK -> TRAVERSING (if confirmed clear)
     * CLEARANCE_CHECK -> RETRACTING (if new obstacle appeared)
     * TRAVERSING -> SCANNING (after each horizontal move)
     * TRAVERSING -> DEPLOYING (when at target column)
     * DEPLOYING -> MINING (when fully lowered)
     * MINING -> SCANNING (after block mined, new target)
     */
    public enum GantryMovementState {
        /** Evaluating next horizontal move, scanning for obstacles */
        SCANNING,
        /** Raising gantry bar incrementally to clear obstacles */
        RETRACTING,
        /** Verifying clearance after retraction (catches dynamic obstacles) */
        CLEARANCE_CHECK,
        /** Horizontal movement after clearance confirmed */
        TRAVERSING,
        /** Fine-tuning XZ position to exact block center */
        CENTERING,
        /** Smooth descent back toward working Y after clearing obstacle zone */
        DEPLOYING,
        /** At target position, executing mining operation */
        MINING,
        /** Quarry complete, paused, or awaiting target */
        IDLE;

        /** @return true if gantry is actively moving */
        public boolean isMoving() {
            return this == RETRACTING || this == TRAVERSING || this == DEPLOYING || this == CENTERING;
        }

        /** @return true if gantry is moving vertically */
        public boolean isVertical() {
            return this == RETRACTING || this == DEPLOYING;
        }
    }

    // Gantry positioning
    public Vec3 gantryPos = null;
    public Vec3 prevGantryPos = null; // For client-side interpolation (Calen's technique)
    public BlockPos targetCol = null;
    public boolean atTarget = false;
    public BlockPos lastMined = null;
    public double gantryLiftY = 0.0; // Current vertical lift (smooth sub-block crane movement)
    public double prevGantryLiftY = 0.0; // Previous vertical lift for client-side interpolation
    public double targetLiftY = 0.0; // Target vertical lift for obstacle clearance
    public GantryMovementState movementState = GantryMovementState.IDLE; // Current movement state
    public int ticksInState = 0; // Ticks spent in current state (for timeout detection)
    public static final int MAX_TICKS_PER_STATE = 1200; // 60 seconds failsafe timeout

    // Layer/mining state
    public Integer layerY = null;
    public boolean layerLeftToRight = false;
    public boolean layerStartAtTop = false;
    public int drillTicks = 0;
    public Integer overrideMineY = null;
    public BlockPos currentlyMining = null;  // Track which block is being mined for damage display
    public int miningDamage = 0;              // Progressive damage 0-10 for visual breaking

    // Pathfinding
    public ArrayDeque<BlockPos> path = new ArrayDeque<>();
    public BlockPos pathTarget = null;
    public int repathCooldown = 0;
    public final Map<BlockPos, Long> tempSkip = new HashMap<>();

    // Final sweep
    public boolean finalSweepPending = false;
    public boolean finalSweepCheckedThisLayer = false;
    public final ArrayDeque<BlockPos> finalSweepTargets = new ArrayDeque<>();

    // Output
    public final ArrayDeque<ItemStack> outputQueue = new ArrayDeque<>();
    public boolean allowFlushAfterPowerOn = false;

    // Energy buffer: 500 FE capacity, drains 50 FE/tick when running
    public final com.nick.buildcraft.energy.BCEnergyStorage energy =
            new com.nick.buildcraft.energy.BCEnergyStorage(
                    com.nick.buildcraft.energy.Energy.QUARRY_BUFFER,
                    500, // max receive per tick (10 pumps worth)
                    s -> setChanged()
            );

    // Speed system: tracks energy inflow for dynamic gantry speed
    public int energyLastTick = 0;           // Energy stored last tick (to calculate inflow rate)
    public float currentSpeed = 0.0f;        // Current gantry speed multiplier (0.0 = stopped, 1.0 = base, 4.0 = max)
    public float targetSpeed = 0.0f;         // Target speed based on energy inflow

    // Client sync (private)
    private int syncCooldown = 0;
    private Vec3 lastSentGantry = null;
    private Double lastSentGantryLiftY = null;
    private Integer lastSentLayer = null;
    private int lastSentEnergy = 0;

    // Client-side energy cache for renderer (synced from server)
    private int clientEnergy = 0;

    /* ---------- Constructor ---------- */
    public QuarryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.QUARRY_CONTROLLER.get(), pos, state);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level instanceof ServerLevel sl) QuarryFrameManager.clearPlacementLasers(this, sl);
    }

    /* ====================================================================== */
    /*  Sync Helpers                                                           */
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
        boolean energyChanged = (energy.getEnergyStored() != lastSentEnergy);

        // Check if gantry position changed at all (not just cell change)
        boolean gantryMoved = false;
        if (gantryPos != null) {
            if (lastSentGantry == null) {
                gantryMoved = true;
            } else {
                double dx = Math.abs(gantryPos.x - lastSentGantry.x);
                double dz = Math.abs(gantryPos.z - lastSentGantry.z);
                gantryMoved = (dx > 0.001 || dz > 0.001); // Any movement
            }
        }

        // Check if vertical lift changed (critical for smooth crane rendering)
        boolean gantryLiftChanged;
        if (lastSentGantryLiftY == null) {
            gantryLiftChanged = (gantryLiftY > 0.001);
        } else {
            gantryLiftChanged = (Math.abs(gantryLiftY - lastSentGantryLiftY) > 0.001);
        }

        if (--syncCooldown <= 0 || gantryMoved || gantryLiftChanged || layerChanged || energyChanged) {
            syncCooldown = SYNC_MIN_INTERVAL;
            lastSentGantry = gantryPos;
            lastSentGantryLiftY = gantryLiftY;
            lastSentLayer = layerY;
            lastSentEnergy = energy.getEnergyStored();

            markForSync();
        }
    }

    /* ====================================================================== */
    /*  Client Tick (For smooth interpolation)                                */
    /* ====================================================================== */

    public static void clientTick(Level level, BlockPos pos, BlockState state, QuarryBlockEntity be) {
        if (!level.isClientSide) return;

        // Calen's technique: Store previous position, then copy current position
        // This creates a sliding window for interpolation
        be.prevGantryPos = be.gantryPos;
        be.prevGantryLiftY = be.gantryLiftY;
    }

    /* ====================================================================== */
    /*  Server Tick (Main Orchestration)                                      */
    /* ====================================================================== */

    public static void serverTick(Level level, BlockPos pos, BlockState state, QuarryBlockEntity be) {
        if (level.isClientSide) return;

        ServerLevel sl = (ServerLevel) level;
        QuarryBalancer.beginTick(sl);

        // Frame visualization
        if (!be.frameComplete) QuarryFrameManager.ensurePlacementLasers(be, sl);
        else QuarryFrameManager.clearPlacementLasers(be, sl);

        // Update speed based on energy inflow rate (must be before power check)
        QuarryEnergyManager.updateSpeed(be);

        // Power check
        if (!QuarryEnergyManager.hasWorkPower(be)) {
            be.frameTickCounter = 0;
            be.lastMined = null;
            be.layerY = null;
            be.layerLeftToRight = false;
            be.layerStartAtTop = false;
            be.targetCol = null;
            be.atTarget = false;
            be.drillTicks = 0;
            be.path.clear();
            be.pathTarget = null;
            be.tempSkip.clear();
            be.finalSweepPending = false;
            be.finalSweepCheckedThisLayer = false;
            be.finalSweepTargets.clear();
            QuarryOutputManager.resetFlushGate(be);
            be.markForSync();
            return;
        }

        // Frame building
        if (!be.frameComplete) {
            if (!QuarryFrameManager.stepFrameBuild(be, level, pos, state)) return;
            be.frameComplete = true;
            be.markForSync();
        }

        // Sanity check
        if (!QuarryFrameManager.verifyEdgesAreFrames(be)) {
            be.frameComplete = false;
            be.lastMined = null;
            be.layerY = null;
            be.layerLeftToRight = false;
            be.layerStartAtTop = false;
            be.targetCol = null;
            be.atTarget = false;
            be.drillTicks = 0;
            be.path.clear();
            be.pathTarget = null;
            be.tempSkip.clear();
            be.finalSweepPending = false;
            be.finalSweepCheckedThisLayer = false;
            be.finalSweepTargets.clear();
            QuarryOutputManager.resetFlushGate(be);
            be.markForSync();
            return;
        }

        // Drain energy per tick while running
        be.energy.extractEnergy(com.nick.buildcraft.energy.Energy.QUARRY_DRAIN_PER_TICK, false);

        // Gantry movement
        QuarryGantryManager.tickGantry(be, sl, pos, state);
        be.maybeSync(); // Sync gantry position to client for smooth rendering

        // Mining phase gating
        if (!QuarryBalancer.phaseGate(sl, pos)) {
            QuarryOutputManager.flushOutput(be, sl, pos);
            return;
        }

        // Mining
        QuarryMiningManager.stepMining(be, sl, pos, state);

        // Output flushing
        QuarryOutputManager.flushOutput(be, sl, pos);
    }

    /* ====================================================================== */
    /*  Public Getters                                                        */
    /* ====================================================================== */

    public Integer getLayerY() {
        return layerY;
    }

    public boolean isFrameComplete() {
        return frameComplete;
    }

    public com.nick.buildcraft.energy.BCEnergyStorage getEnergyStorage() {
        return energy;
    }

    public Vec3 getGantryPos() {
        return gantryPos;
    }

    public int getClientEnergy() {
        return level != null && level.isClientSide ? clientEnergy : energy.getEnergyStored();
    }

    public static int getMiningPeriodTicks() {
        return 10; // MINE_TICKS_PER_BLOCK
    }

    /**
     * Get the quarry bounds based on facing direction.
     * @return The computed bounds, or null if no level available
     */
    public QuarryGeometryHelper.Bounds getBounds() {
        if (level == null) return null;
        return QuarryGeometryHelper.boundsForFacing(getBlockPos(), getFacing());
    }

    /**
     * Get the facing direction of the quarry.
     * @return The facing direction from blockstate, or NORTH as fallback
     */
    public Direction getFacing() {
        try {
            return getBlockState().getValue(QuarryBlock.FACING);
        } catch (IllegalArgumentException e) {
            return Direction.NORTH;
        }
    }

    /* ====================================================================== */
    /*  Client Rendering Hooks                                               */
    /* ====================================================================== */

    public CeilingMaskSnapshot snapshotCeilingMask() {
        Level lvl = getLevel();
        if (lvl == null) return new CeilingMaskSnapshot(0, 0, 0, 0, BitSet.valueOf(new byte[0]));

        Direction facing = getBlockState().getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(getBlockPos(), facing);

        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;
        final int width = Math.max(0, xMax - xMin + 1);
        final int depth = Math.max(0, zMax - zMin + 1);

        BitSet bits = new BitSet(width * depth);
        for (int zi = 0; zi < depth; zi++) {
            int z = zMin + zi;
            for (int xi = 0; xi < width; xi++) {
                int x = xMin + xi;
                boolean blocked = QuarryGantryManager.columnBlockedAtCeiling(lvl, b, x, z);
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

    public Set<BlockPos> getBlockedPositions() {
        return new HashSet<>();
    }

    /* ====================================================================== */
    /*  Persistence (Save/Load)                                              */
    /* ====================================================================== */

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        // Gantry position (current and previous for smooth interpolation)
        var gx = in.read("GX", Codec.DOUBLE);
        var gy = in.read("GY", Codec.DOUBLE);
        var gz = in.read("GZ", Codec.DOUBLE);
        gx.ifPresent(x -> this.gantryPos = new Vec3(x, gy.orElse(0.0), gz.orElse(0.0)));

        var pgx = in.read("PrevGX", Codec.DOUBLE);
        var pgy = in.read("PrevGY", Codec.DOUBLE);
        var pgz = in.read("PrevGZ", Codec.DOUBLE);
        pgx.ifPresent(x -> this.prevGantryPos = new Vec3(x, pgy.orElse(0.0), pgz.orElse(0.0)));

        // CLIENT-SIDE: Preserve current value as previous before loading new value (for smooth interpolation)
        // SERVER-SIDE: Load both values from disk
        if (this.level != null && this.level.isClientSide) {
            // On client, preserve current as previous for interpolation across sync packets
            this.prevGantryLiftY = this.gantryLiftY;
            this.gantryLiftY = in.read("GantryLiftY", Codec.DOUBLE).orElse(0.0);
        } else {
            // On server, load both from disk
            this.gantryLiftY = in.read("GantryLiftY", Codec.DOUBLE).orElse(0.0);
            this.prevGantryLiftY = in.read("PrevGantryLiftY", Codec.DOUBLE).orElse(0.0);
        }
        this.targetLiftY = in.read("TargetLiftY", Codec.DOUBLE).orElse(0.0);

        // Movement state
        String stateStr = in.getStringOr("MovementState", "IDLE");
        try {
            this.movementState = GantryMovementState.valueOf(stateStr);
        } catch (IllegalArgumentException e) {
            // Handle legacy states (MOVING -> TRAVERSING migration)
            if ("MOVING".equals(stateStr)) {
                this.movementState = GantryMovementState.TRAVERSING;
            } else {
                this.movementState = GantryMovementState.IDLE;
            }
        }
        this.ticksInState = in.getIntOr("TicksInState", 0);

        // Target column position
        var tcx = in.read("TargetX", Codec.INT);
        var tcy = in.read("TargetY", Codec.INT);
        var tcz = in.read("TargetZ", Codec.INT);
        if (tcx.isPresent() && tcy.isPresent() && tcz.isPresent()) {
            this.targetCol = new BlockPos(tcx.get(), tcy.get(), tcz.get());
        }

        this.atTarget = in.read("AtTarget", Codec.BOOL).orElse(false);

        // Mining state
        in.read("LayerY", Codec.INT).ifPresent(v -> this.layerY = v);
        this.layerLeftToRight = in.read("LayerLR", Codec.BOOL).orElse(this.layerLeftToRight);
        this.layerStartAtTop = in.read("LayerTop", Codec.BOOL).orElse(this.layerStartAtTop);
        this.drillTicks = in.getIntOr("DrillTicks", 0);

        // Currently mining block
        var cmx = in.read("MiningX", Codec.INT);
        var cmy = in.read("MiningY", Codec.INT);
        var cmz = in.read("MiningZ", Codec.INT);
        if (cmx.isPresent() && cmy.isPresent() && cmz.isPresent()) {
            this.currentlyMining = new BlockPos(cmx.get(), cmy.get(), cmz.get());
        }
        this.miningDamage = in.getIntOr("MiningDamage", 0);

        // Pathfinding state
        var ptx = in.read("PathTargetX", Codec.INT);
        var pty = in.read("PathTargetY", Codec.INT);
        var ptz = in.read("PathTargetZ", Codec.INT);
        if (ptx.isPresent() && pty.isPresent() && ptz.isPresent()) {
            this.pathTarget = new BlockPos(ptx.get(), pty.get(), ptz.get());
        }
        this.repathCooldown = in.getIntOr("RepathCooldown", 0);

        // Path waypoints
        int pathSize = in.getIntOr("PathSize", 0);
        this.path.clear();
        for (int i = 0; i < pathSize; i++) {
            var pwx = in.read("Path" + i + "X", Codec.INT);
            var pwy = in.read("Path" + i + "Y", Codec.INT);
            var pwz = in.read("Path" + i + "Z", Codec.INT);
            if (pwx.isPresent() && pwy.isPresent() && pwz.isPresent()) {
                this.path.addLast(new BlockPos(pwx.get(), pwy.get(), pwz.get()));
            }
        }

        // Output queue
        int qn = in.getInt("OutQn").orElse(0);
        outputQueue.clear();
        for (int i = 0; i < qn; i++) {
            var st = in.read("OutQ_" + i, ItemStack.CODEC);
            st.ifPresent(outputQueue::addLast);
        }

        // Energy buffer
        in.child("Energy").ifPresent(this.energy::deserialize);

        // Client energy (for renderer smoothness)
        this.clientEnergy = in.getIntOr("ClientEnergy", 0);

        // Speed system
        this.energyLastTick = in.getIntOr("EnergyLastTick", 0);
        this.currentSpeed = in.getFloatOr("CurrentSpeed", 0.0f);
        this.targetSpeed = in.getFloatOr("TargetSpeed", 0.0f);

        // Reset power gate on world load
        allowFlushAfterPowerOn = false;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        // Gantry position (current and previous for smooth interpolation)
        if (this.gantryPos != null) {
            out.store("GX", Codec.DOUBLE, this.gantryPos.x);
            out.store("GY", Codec.DOUBLE, this.gantryPos.y);
            out.store("GZ", Codec.DOUBLE, this.gantryPos.z);
        }
        if (this.prevGantryPos != null) {
            out.store("PrevGX", Codec.DOUBLE, this.prevGantryPos.x);
            out.store("PrevGY", Codec.DOUBLE, this.prevGantryPos.y);
            out.store("PrevGZ", Codec.DOUBLE, this.prevGantryPos.z);
        }

        out.store("GantryLiftY", Codec.DOUBLE, this.gantryLiftY);
        out.store("PrevGantryLiftY", Codec.DOUBLE, this.prevGantryLiftY);
        out.store("TargetLiftY", Codec.DOUBLE, this.targetLiftY);

        // Movement state
        out.putString("MovementState", this.movementState.name());
        out.putInt("TicksInState", this.ticksInState);

        // Target column position
        if (this.targetCol != null) {
            out.store("TargetX", Codec.INT, this.targetCol.getX());
            out.store("TargetY", Codec.INT, this.targetCol.getY());
            out.store("TargetZ", Codec.INT, this.targetCol.getZ());
        }
        out.store("AtTarget", Codec.BOOL, this.atTarget);

        // Mining state
        out.storeNullable("LayerY", Codec.INT, this.layerY);
        out.store("LayerLR", Codec.BOOL, this.layerLeftToRight);
        out.store("LayerTop", Codec.BOOL, this.layerStartAtTop);
        out.putInt("DrillTicks", this.drillTicks);

        // Currently mining block
        if (this.currentlyMining != null) {
            out.store("MiningX", Codec.INT, this.currentlyMining.getX());
            out.store("MiningY", Codec.INT, this.currentlyMining.getY());
            out.store("MiningZ", Codec.INT, this.currentlyMining.getZ());
        }
        out.putInt("MiningDamage", this.miningDamage);

        // Pathfinding state
        if (this.pathTarget != null) {
            out.store("PathTargetX", Codec.INT, this.pathTarget.getX());
            out.store("PathTargetY", Codec.INT, this.pathTarget.getY());
            out.store("PathTargetZ", Codec.INT, this.pathTarget.getZ());
        }
        out.putInt("RepathCooldown", this.repathCooldown);

        // Path waypoints
        out.putInt("PathSize", this.path.size());
        int pathIdx = 0;
        for (BlockPos waypoint : this.path) {
            out.store("Path" + pathIdx + "X", Codec.INT, waypoint.getX());
            out.store("Path" + pathIdx + "Y", Codec.INT, waypoint.getY());
            out.store("Path" + pathIdx + "Z", Codec.INT, waypoint.getZ());
            pathIdx++;
        }

        // Output queue
        out.putInt("OutQn", outputQueue.size());
        int i = 0;
        for (ItemStack s : outputQueue) {
            out.store("OutQ_" + (i++), ItemStack.CODEC, s);
        }

        // Energy buffer
        this.energy.serialize(out.child("Energy"));

        // Client energy (for renderer smoothness)
        out.putInt("ClientEnergy", energy.getEnergyStored());

        // Speed system
        out.putInt("EnergyLastTick", this.energyLastTick);
        out.putFloat("CurrentSpeed", this.currentSpeed);
        out.putFloat("TargetSpeed", this.targetSpeed);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }
}
