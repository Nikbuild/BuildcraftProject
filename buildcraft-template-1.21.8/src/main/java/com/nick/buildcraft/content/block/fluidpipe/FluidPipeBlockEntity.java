package com.nick.buildcraft.content.block.fluidpipe;

import com.mojang.serialization.Codec;
import com.nick.buildcraft.content.block.tank.TankBlockEntity;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

/**
 * ULTIMATE DUAL-SYSTEM PIPE: Wave Propagation + Checkpoint Storage
 *
 * 🔥 SMART WAVE RECOVERY: Network topology changes now use intelligent backward/forward scanning
 * instead of full resets. When a pipe breaks, the wave moves back to the last filled pipe.
 * When a pipe is replaced, the wave skips over already-filled pipes with checkpoints.
 *
 * 🔥 GAS PEDAL SYSTEM: THREE-PART approval for pipe filling:
 * 1. Pump must have injected enough units (cumulative counter)
 * 2. Wave must have physically reached the pipe position
 * 3. 🆕 Pump must be ACTIVELY pushing fluid RIGHT NOW (prevents stale checkpoint refills)
 *
 * Benefits:
 * - No more full network resets
 * - Broken pipes don't cause entire system to empty
 * - Replacing pipes intelligently resumes from where wave left off
 * - Checkpoints preserve fluid during disconnections
 * - 🆕 Pipes can ONLY fill with FRESH fluid from active pump pushes, not stale checkpoints
 */
public class FluidPipeBlockEntity extends BlockEntity {

    // ============ WAVE CONFIGURATION ============

    /** How many pipes per bucket - CHANGED TO 1 for simpler behavior */
    public static final int REACH_PER_UNIT = 1;  // 1 pipe per 1000mB

    /** How many mB per unit */
    private static final int MB_PER_UNIT = 1000;

    /** Wave animation speed */
    private static final float WAVE_SPEED = 0.0f;  // Animation disabled, controlled by pump injection

    // ============ CHECKPOINT CONFIGURATION ============

    /** Each pipe checkpoint holds 1000mB = 1 bucket per pipe */
    private static final int CHECKPOINT_CAPACITY_MB = 1000;

    /** Transfer rate between checkpoints - DISABLED, we don't want equalization */
    private static final int TRANSFER_RATE = 0;  // Set to 0 to disable equalization

    // ============ 🔥 ANTI-REFILL CONFIGURATION ============

    /** How many ticks a new pipe stays "virgin" (cannot inherit wave state) */
    private static final int VIRGIN_GRACE_PERIOD_TICKS = 40;  // 2 seconds

    /** How many ticks the gas pedal signal remains valid */
    private static final int GAS_PEDAL_GRACE_TICKS = 2;

    // ============ WAVE STATE (for visual flow) ============

    /** True if this is a wave root */
    private boolean isRoot = false;

    /** Units injected at root */
    private int unitsInjected = 0;

    /** Units consumed (delivered to tanks) */
    private int unitsConsumed = 0;

    /** Wave front position (in pipes from root) */
    private float frontPos = 0f;

    /** Accumulator for small pump packets */
    private int injectedMbAccum = 0;

    /** Distance from root (for wave propagation) */
    private int distanceFromRoot = Integer.MAX_VALUE;

    /** Previous distance (detect changes) */
    private int prevDistanceFromRoot = Integer.MAX_VALUE;

    /** Fluid type (propagated from root) */
    private FluidStack displayedFluid = FluidStack.EMPTY;

    /** Units delivered to adjacent tank */
    private int unitsDeliveredToTank = 0;

    // ============ 🔥 GAS PEDAL STATE ============

    /** True if pump is actively pushing fluid THIS tick (gas pedal pressed) */
    private boolean pumpPushingThisTick = false;

    /** Last game tick when pump pushed fluid */
    private long lastPumpPushTick = 0L;

    // ============ CHECKPOINT STATE (for persistence) ============

    /** ACTUAL fluid stored in this pipe's checkpoint */
    private FluidStack checkpointFluid = FluidStack.EMPTY;

    /** ACTUAL amount in checkpoint (0-1000mB) */
    private int checkpointAmount = 0;

    /** Has this pipe been "checkpointed" by the wave? */
    private boolean hasCheckpoint = false;

    /** Is this checkpoint orphaned (disconnected from network)? */
    private boolean isOrphaned = false;

    /** 🔥 DUPLICATION FIX: Track if checkpoint fluid was already delivered via wave system */
    private boolean checkpointWasDeliveredByWave = false;

    // ============ 🔥 ANTI-REFILL STATE ============

    /** True if this pipe was just placed and should not inherit wave state yet */
    private boolean isVirginPipe = true;

    /** Remaining ticks in virgin grace period */
    private int virginTicksRemaining = VIRGIN_GRACE_PERIOD_TICKS;

    /** Game time when this pipe was placed (for additional safety checks) */
    private long placementTime = 0L;

    // ============ SHARED STATE ============

    /** Tick counter */
    private int tickCounter = 0;

    /** Cached root pipe reference (cleared when network changes) */
    private FluidPipeBlockEntity cachedRoot = null;

    /** Per-side handlers */
    private final EnumMap<Direction, SideFluidHandler> sideHandlers = new EnumMap<>(Direction.class);

    public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.FLUID_PIPE.get(), pos, state);
        for (Direction d : Direction.values()) {
            sideHandlers.put(d, new SideFluidHandler(d));
        }

        // Ensure new pipes start completely empty
        clearAllFluidState();
    }

    /**
     * 🔥 ENHANCED: Clear all fluid state - used when pipe is first placed or broken.
     * Now also resets virgin pipe state to prevent duplication bugs.
     */
    public void clearAllFluidState() {
        // Wave state
        isRoot = false;
        unitsInjected = 0;
        unitsConsumed = 0;
        frontPos = 0f;
        injectedMbAccum = 0;
        distanceFromRoot = Integer.MAX_VALUE;
        prevDistanceFromRoot = Integer.MAX_VALUE;
        displayedFluid = FluidStack.EMPTY;
        unitsDeliveredToTank = 0;

        // Gas pedal state
        pumpPushingThisTick = false;
        lastPumpPushTick = 0L;

        // Checkpoint state
        checkpointFluid = FluidStack.EMPTY;
        checkpointAmount = 0;
        hasCheckpoint = false;
        isOrphaned = false;
        checkpointWasDeliveredByWave = false;

        // 🔥 ANTI-REFILL: Reset virgin pipe state
        isVirginPipe = true;
        virginTicksRemaining = VIRGIN_GRACE_PERIOD_TICKS;
        // Set placement time immediately (will be overwritten on first tick if level exists)
        if (level != null) {
            placementTime = level.getGameTime();
        } else {
            placementTime = 0L; // Will be set on first tick
        }

        tickCounter = 0;
    }

    /* ---------------------------------------------------------------------- */
    /* Public API for Renderer                                                */
    /* ---------------------------------------------------------------------- */

    /**
     * Returns fluid to display.
     * Prefers wave fluid, falls back to checkpoint fluid.
     */
    public FluidStack getDisplayedFluid() {
        if (!displayedFluid.isEmpty()) return displayedFluid;
        if (!checkpointFluid.isEmpty()) return checkpointFluid;
        return FluidStack.EMPTY;
    }

    /**
     * Returns fill fraction for rendering.
     * Uses wave fill if connected, checkpoint fill if orphaned.
     */
    public float getFillFraction() {
        // PRIORITY 1: If has checkpoint, show it (whether orphaned or not)
        if (hasCheckpoint && checkpointAmount > 0) {
            return (float) checkpointAmount / (float) CHECKPOINT_CAPACITY_MB;
        }

        // PRIORITY 2: If in network, use wave fill
        if (distanceFromRoot == Integer.MAX_VALUE) return 0f;

        float head = getFrontPos();
        if (head <= 0f) return 0f;

        float segmentStart = distanceFromRoot - 1;
        float segmentEnd = distanceFromRoot;

        if (head <= segmentStart) return 0f;
        if (head >= segmentEnd) return 1f;

        return head - segmentStart;
    }

    /**
     * 🔥 REWRITTEN: Calculate wave position based on root's unitsInjected.
     * No inheritance - pure calculation.
     */
    public float getFrontPos() {
        if (isRoot) {
            return frontPos; // Root has the authoritative position
        }

        if (level == null || distanceFromRoot == Integer.MAX_VALUE) {
            return 0f;
        }

        // Find root and use its unitsInjected
        FluidPipeBlockEntity root = findExistingRoot();
        if (root == null) return 0f;

        // Wave position is simply how many units have been injected
        // If root injected 5 units, wave is at position 5.0
        float wavePosition = (float) root.unitsInjected;

        // If wave hasn't reached us yet, return 0
        // Our segment starts at (distanceFromRoot - 1)
        float ourSegmentStart = distanceFromRoot - 1;
        if (wavePosition < ourSegmentStart) {
            return 0f;
        }

        // Wave has reached or passed us
        return wavePosition;
    }

    /**
     * Legacy compat.
     */
    public int getDistanceFromRoot() {
        return distanceFromRoot;
    }

    /**
     * Check if this pipe is a wave root.
     */
    public boolean isRoot() {
        return isRoot;
    }

    /**
     * Check if on active flow path.
     */
    private boolean isOnActiveFlowPath() {
        if (isRoot) return true;
        if (level == null) return false;
        if (distanceFromRoot == Integer.MAX_VALUE) return false;

        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                if (other.distanceFromRoot < this.distanceFromRoot &&
                        other.distanceFromRoot != Integer.MAX_VALUE) {
                    return true;
                }
            }
        }

        return false;
    }

    /* ---------------------------------------------------------------------- */
    /* Connection Helper                                                       */
    /* ---------------------------------------------------------------------- */

    private boolean isConnectedInDirection(Direction dir) {
        BlockState state = getBlockState();
        BooleanProperty prop = switch (dir) {
            case NORTH -> BaseFluidPipeBlock.NORTH;
            case SOUTH -> BaseFluidPipeBlock.SOUTH;
            case EAST -> BaseFluidPipeBlock.EAST;
            case WEST -> BaseFluidPipeBlock.WEST;
            case UP -> BaseFluidPipeBlock.UP;
            case DOWN -> BaseFluidPipeBlock.DOWN;
        };
        return state.hasProperty(prop) && state.getValue(prop);
    }

    /* ---------------------------------------------------------------------- */
    /* Main Tick Logic                                                         */
    /* ---------------------------------------------------------------------- */

    public void tickFluids() {
        if (level == null) return;

        // 🔥 GAS PEDAL: Reset push flag at start of each tick (only at root)
        if (isRoot && !level.isClientSide) {
            pumpPushingThisTick = false;
        }

        // 🔥 ANTI-REFILL: Tick down virgin grace period
        if (isVirginPipe && virginTicksRemaining > 0) {
            virginTicksRemaining--;
            if (virginTicksRemaining <= 0) {
                isVirginPipe = false;
            }
        }

        // WAVE SYSTEM: Update network topology
        updateDistanceFromRoot();
        propagateFluidType();

        if (!level.isClientSide) {
            // WAVE SYSTEM: Advance wave front at root
            if (isRoot) {
                advanceWaveFront();
                syncConsumedUnits();
            }

            // WAVE SYSTEM: Propagate wave data from neighbors
            propagateFrontPos();

            // CHECKPOINT SYSTEM: Create checkpoints as wave passes
            updateCheckpoint();

            // BOTH SYSTEMS: Deliver to tanks
            deliverToTanks();
        }

        // Periodic sync
        tickCounter++;
        if (tickCounter >= 10) {
            tickCounter = 0;
            syncToClient();
        }
    }

    /* ---------------------------------------------------------------------- */
    /* WAVE SYSTEM: Network Topology                                          */
    /* ---------------------------------------------------------------------- */

    private void updateDistanceFromRoot() {
        if (level == null) return;

        int best = Integer.MAX_VALUE;

        if (isRoot) {
            best = 1;
        }

        // Find minimum distance from connected neighbors
        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                int od = other.distanceFromRoot;
                if (od != Integer.MAX_VALUE) {
                    best = Math.min(best, od + 1);
                }
            }
        }

        if (best != distanceFromRoot) {
            prevDistanceFromRoot = distanceFromRoot;
            distanceFromRoot = best;

            // 🔥 SMART WAVE RECOVERY: Instead of full reset, use intelligent recalculation
            if (!isRoot && !level.isClientSide) {
                FluidPipeBlockEntity root = findExistingRoot();
                if (root != null) {
                    // Network changed - use smart wave recovery
                    root.smartWaveRecovery();
                }
            }

            // 🔥 PRESERVE CHECKPOINTS: When disconnecting, keep checkpoint but clear wave state
            if (distanceFromRoot == Integer.MAX_VALUE && !isRoot) {
                isOrphaned = true;

                // Clear cached root
                cachedRoot = null;

                // Create checkpoint from current wave state BEFORE clearing it
                if (unitsInjected > 0 && displayedFluid != null && !displayedFluid.isEmpty()) {
                    if (!hasCheckpoint) {
                        // Calculate how much fluid this pipe should hold
                        float ourSegmentStart = prevDistanceFromRoot - 1;
                        float wavePosition = frontPos;
                        float passed = Math.max(0, wavePosition - ourSegmentStart);

                        // Create checkpoint with appropriate amount
                        if (passed > 0) {
                            hasCheckpoint = true;
                            checkpointFluid = displayedFluid.copy();
                            checkpointAmount = (int)(Math.min(1f, passed) * CHECKPOINT_CAPACITY_MB);
                            checkpointFluid.setAmount(checkpointAmount);
                        }
                    }
                }

                // Clear WAVE state but KEEP checkpoint
                displayedFluid = FluidStack.EMPTY;
                frontPos = 0f;
                unitsInjected = 0;
                unitsConsumed = 0;
                unitsDeliveredToTank = 0;
                // Note: hasCheckpoint, checkpointFluid, checkpointAmount are PRESERVED
            } else {
                isOrphaned = false;
            }

            // Stop being root if no non-pipe neighbors
            if (isRoot && !hasNonPipeNeighbor()) {
                isRoot = false;
                isOrphaned = true;

                // Also destroy state when losing root
                hasCheckpoint = false;
                checkpointFluid = FluidStack.EMPTY;
                checkpointAmount = 0;
            }

            setChanged();
            if (!level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    /**
     * 🔥 REWRITTEN: Get fluid type from root only when wave has reached us.
     */
    private void propagateFluidType() {
        if (level == null) return;
        if (isRoot) return; // Root already has its fluid type

        // Get fluid type directly from root
        FluidPipeBlockEntity root = findExistingRoot();
        if (root == null) {
            if (!displayedFluid.isEmpty()) {
                displayedFluid = FluidStack.EMPTY;
                setChanged();
            }
            return;
        }

        // Only show fluid if wave has reached us (checked via unitsInjected)
        // If we have units, we can show fluid
        if (unitsInjected > 0) {
            // Copy fluid type from root
            if (!FluidStack.isSameFluidSameComponents(root.displayedFluid, this.displayedFluid)) {
                this.displayedFluid = root.displayedFluid.isEmpty() ? FluidStack.EMPTY : root.displayedFluid.copy();
                setChanged();
                if (!level.isClientSide) {
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                }
            }
        } else {
            // No units reached us yet - clear fluid display
            if (!displayedFluid.isEmpty()) {
                displayedFluid = FluidStack.EMPTY;
                setChanged();
                if (!level.isClientSide) {
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                }
            }
        }
    }

    /**
     * 🔥 COMPLETE REWRITE: Copy ONLY units that have reached this pipe.
     * THREE-PART condition (THE GAS PEDAL FIX):
     * 1. Pump must have injected enough units (root.unitsInjected >= our distance)
     * 2. Wave must have physically reached our position (root.frontPos >= our start)
     * 3. 🆕 Pump must be ACTIVELY pushing fluid RIGHT NOW (gas pedal pressed)
     * 4. 🔥 Virgin pipe grace period (cannot inherit during first 2 seconds)
     */
    private void propagateFrontPos() {
        if (level == null) return;
        if (isRoot) return; // Root manages its own frontPos
        if (distanceFromRoot == Integer.MAX_VALUE) return; // Not connected

        // 🔥 ANTI-REFILL: Virgin pipes don't inherit wave state
        if (isVirginPipe && virginTicksRemaining > 0) {
            // Clear any inherited state
            if (this.unitsInjected > 0) {
                this.unitsInjected = 0;
                this.unitsConsumed = 0;
                setChanged();
            }
            return;
        }

        // Find the root pipe
        FluidPipeBlockEntity root = findExistingRoot();
        if (root == null) return;

        // Calculate our position in the pipe network
        float ourSegmentStart = distanceFromRoot - 1;

        // THREE-PART CONDITION:
        // 1. Has the pump injected enough units to reach us?
        boolean pumpApproves = root.unitsInjected >= distanceFromRoot;

        // 2. Has the wave actually reached our position?
        boolean waveApproves = root.frontPos >= ourSegmentStart;

        // 🔥 3. Is the pump ACTIVELY pushing fluid RIGHT NOW? (gas pedal)
        long currentTick = level.getGameTime();
        long ticksSinceLastPush = currentTick - root.lastPumpPushTick;
        boolean gasPedalPressed = root.pumpPushingThisTick ||
                (ticksSinceLastPush <= GAS_PEDAL_GRACE_TICKS);

        // ALL THREE must be true
        if (pumpApproves && waveApproves && gasPedalPressed) {
            // Calculate how many units have actually reached us
            // The wave is at root.frontPos, our segment starts at ourSegmentStart
            float waveProgressIntoOurSegment = root.frontPos - ourSegmentStart;

            // Only consider units that have passed our segment start
            int unitsReachedUs = Math.max(0, (int)Math.floor(root.frontPos));

            // Don't exceed what the root has
            unitsReachedUs = Math.min(unitsReachedUs, root.unitsInjected);

            if (unitsReachedUs != this.unitsInjected) {
                this.unitsInjected = unitsReachedUs;
                this.unitsConsumed = root.unitsConsumed;
                setChanged();
            }
        } else {
            // Wave hasn't reached us yet OR pump not actively pushing - we should have 0 units
            if (this.unitsInjected > 0) {
                this.unitsInjected = 0;
                this.unitsConsumed = 0;
                setChanged();
            }
        }
    }

    private boolean hasNonPipeNeighbor() {
        if (level == null) return false;

        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(np);

            if (!(be instanceof FluidPipeBlockEntity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 🔥 NEW SMART WAVE RECOVERY SYSTEM
     * Instead of resetting entire network, intelligently recalculate wave position.
     *
     * Process:
     * 1. Scan network to find furthest pipe that still has fluid (checkpoint or wave)
     * 2. Move wave backward to that position
     * 3. Don't touch unitsConsumed (tanks already got that fluid)
     * 4. Clear cache so next pump injection will do smart forward scan
     * 5. 🆕 Reset gas pedal - network change requires new pump push
     */
    private void smartWaveRecovery() {
        if (!isRoot) return;
        if (level == null) return;

        // Clear cache in entire network
        clearCacheInNetwork();

        // Scan network to find furthest filled pipe
        int newWavePosition = scanFurthestFilledPipe();

        // Move wave backward to last confirmed filled position
        // This makes broken pipe fluid LOST, but preserves downstream checkpoints
        frontPos = Math.max(1f, newWavePosition); // At minimum, root is at position 1
        unitsInjected = Math.max(1, newWavePosition);

        // 🔥 GAS PEDAL: Reset pump push state - network changed, need fresh pump signal
        pumpPushingThisTick = false;
        lastPumpPushTick = 0L;

        // DON'T reset unitsConsumed - tanks already received that fluid
        // DON'T reset displayedFluid - keep fluid type

        setChanged();
        if (!level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * 🔥 NEW: Scan network to find furthest pipe that has fluid
     * Returns the distance of the furthest pipe with checkpoint or wave units
     */
    private int scanFurthestFilledPipe() {
        if (level == null) return 1;

        int maxPosition = 1; // Root is always at position 1

        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                // Does this pipe have fluid? (checkpoint OR wave units)
                boolean hasFluids = (pipe.hasCheckpoint && pipe.checkpointAmount > 0) ||
                        pipe.unitsInjected > 0;

                if (hasFluids && pipe.distanceFromRoot != Integer.MAX_VALUE) {
                    // Track furthest position that has fluid
                    maxPosition = Math.max(maxPosition, pipe.distanceFromRoot);
                }

                // Continue scanning connected pipes
                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnectedInDirection(dir)) continue;

                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        return maxPosition;
    }

    /**
     * Clear cached root references throughout the network
     */
    private void clearCacheInNetwork() {
        if (level == null) return;

        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                pipe.cachedRoot = null;

                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnectedInDirection(dir)) continue;

                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* WAVE SYSTEM: Wave Front Advancement                                    */
    /* ---------------------------------------------------------------------- */

    /**
     * 🔥 REWRITTEN: Wave advancement is now PURELY driven by actual fluid injection.
     * No automatic animation - wave only moves when pump adds units.
     */
    private void advanceWaveFront() {
        if (!isRoot || level == null) return;

        // Calculate how far the wave SHOULD be based on units actually injected
        float targetFrontPos = (float) unitsInjected;

        // Smoothly advance toward target (for visual interpolation)
        if (frontPos < targetFrontPos) {
            float diff = targetFrontPos - frontPos;
            // Move 20% of the difference per tick for smooth animation
            frontPos += Math.min(diff, diff * 0.2f);

            // Snap to target if very close
            if (Math.abs(frontPos - targetFrontPos) < 0.01f) {
                frontPos = targetFrontPos;
            }

            setChanged();
        }
    }

    private void syncConsumedUnits() {
        if (level == null || !isRoot) return;

        int maxConsumed = 0;

        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                maxConsumed = Math.max(maxConsumed, pipe.unitsDeliveredToTank);

                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnectedInDirection(dir)) continue;

                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        if (maxConsumed != unitsConsumed) {
            unitsConsumed = maxConsumed;
            setChanged();
        }
    }

    /**
     * CHECKPOINT SYSTEM: Create and Manage Checkpoints
     * Checkpoints preserve fluid when pipes are disconnected
     */
    private void updateCheckpoint() {
        if (level == null) return;
        if (isOrphaned) return; // Don't update orphaned checkpoints (they already have their fluid)
        if (displayedFluid.isEmpty()) return;

        // Calculate how much wave has passed through this pipe
        float head = getFrontPos();
        if (head <= 0f) return;

        float segmentStart = distanceFromRoot - 1;
        float passed = head - segmentStart;

        // If wave has reached us, create/update checkpoint
        if (passed > 0f) {
            if (!hasCheckpoint) {
                // Create new checkpoint
                hasCheckpoint = true;
                checkpointFluid = displayedFluid.copy();
                checkpointWasDeliveredByWave = false;
            }

            // Calculate how much should be in checkpoint based on wave progress
            float fillFraction = Math.min(1f, passed);
            int targetAmount = (int)(fillFraction * CHECKPOINT_CAPACITY_MB);

            if (targetAmount > checkpointAmount) {
                int toAdd = targetAmount - checkpointAmount;
                checkpointAmount += toAdd;
                checkpointFluid.setAmount(checkpointAmount);
                setChanged();
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Tank Delivery                                                           */
    /* ---------------------------------------------------------------------- */

    private void deliverToTanks() {
        if (level == null) return;

        // Find adjacent tank
        TankBlockEntity tankBe = null;
        for (Direction dir : Direction.values()) {
            BlockPos np = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof TankBlockEntity t) {
                tankBe = t;
                break;
            }
        }
        if (tankBe == null) return;

        // Try wave delivery first (if in network)
        if (distanceFromRoot != Integer.MAX_VALUE && !displayedFluid.isEmpty() && isOnActiveFlowPath()) {
            deliverWaveToTank(tankBe);
        }
        // Then try checkpoint delivery (if orphaned or has checkpoint)
        else if (hasCheckpoint && checkpointAmount > 0) {
            deliverCheckpointToTank(tankBe);
        }
    }

    private void deliverWaveToTank(TankBlockEntity tankBe) {
        float head = getFrontPos();
        if (head <= 0f) return;

        float segmentStart = distanceFromRoot - 1;
        float passed = head - segmentStart;

        int unitsPassedHere = (int) Math.floor(passed);
        if (unitsPassedHere <= unitsDeliveredToTank) return;

        int newUnits = unitsPassedHere - unitsDeliveredToTank;
        int amountMb = newUnits * MB_PER_UNIT;

        IFluidHandler tankHandler = tankBe.getColumnHandler();
        FluidStack toInsert = new FluidStack(displayedFluid.getFluid(), amountMb);

        int filled = tankHandler.fill(toInsert, IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0) return;

        int actualUnits = filled / MB_PER_UNIT;
        if (actualUnits <= 0) return;

        unitsDeliveredToTank += actualUnits;

        // 🔥 DUPLICATION FIX: Mark checkpoint as delivered by wave
        if (hasCheckpoint) {
            checkpointWasDeliveredByWave = true;
        }

        tankBe.setChanged();
        level.sendBlockUpdated(tankBe.getBlockPos(), tankBe.getBlockState(), tankBe.getBlockState(), 3);
    }

    private void deliverCheckpointToTank(TankBlockEntity tankBe) {
        IFluidHandler tankHandler = tankBe.getColumnHandler();
        if (tankHandler == null) return;

        // 🔥 KEY FIX: Don't deliver checkpoint if it was already delivered via wave system
        if (checkpointWasDeliveredByWave) {
            checkpointFluid = FluidStack.EMPTY;
            checkpointAmount = 0;
            hasCheckpoint = false;
            checkpointWasDeliveredByWave = false;
            setChanged();
            return;
        }

        int toTransfer = Math.min(100, checkpointAmount);
        FluidStack toInsert = checkpointFluid.copyWithAmount(toTransfer);

        int filled = tankHandler.fill(toInsert, IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            checkpointAmount -= filled;

            if (checkpointAmount <= 0) {
                checkpointFluid = FluidStack.EMPTY;
                checkpointAmount = 0;
                hasCheckpoint = false;
            } else {
                checkpointFluid.setAmount(checkpointAmount);
            }

            tankBe.setChanged();
            level.sendBlockUpdated(tankBe.getBlockPos(), tankBe.getBlockState(), tankBe.getBlockState(), 3);
            setChanged();
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Fluid Injection from Pump                                              */
    /* ---------------------------------------------------------------------- */

    /**
     * 🔥 ENHANCED: Now includes smart forward scan to skip already-filled pipes
     * AND sets the gas pedal flag when pump pushes fluid
     */
    public FluidStack offer(FluidStack stack, Direction from) {
        if (level == null || stack.isEmpty()) return stack;

        BlockPos srcPos = worldPosition.relative(from);
        BlockEntity srcBe = level.getBlockEntity(srcPos);
        boolean fromPipe = srcBe instanceof FluidPipeBlockEntity;

        if (!fromPipe) {
            // Receiving from pump/machine - start wave
            FluidPipeBlockEntity existingRoot = findExistingRoot();

            if (existingRoot != null && existingRoot != this) {
                return existingRoot.offer(stack, from);
            }

            isRoot = true;
            isOrphaned = false;

            // 🔥 ANTI-REFILL: Receiving fluid makes us no longer virgin
            isVirginPipe = false;
            virginTicksRemaining = 0;

            if (displayedFluid.isEmpty()) {
                displayedFluid = new FluidStack(stack.getFluid(), MB_PER_UNIT);
            }

            int networkCapacity = getNetworkCapacity();
            int unitsInSystem = unitsInjected - unitsConsumed;

            if (unitsInSystem >= networkCapacity) {
                return stack;
            }

            int incomingMb = stack.getAmount();
            injectedMbAccum += incomingMb;

            int newUnits = injectedMbAccum / MB_PER_UNIT;

            if (newUnits > 0) {
                // 🔥 SMART FORWARD SCAN: Check if downstream pipes already have checkpoints
                int effectivePosition = smartCalculateWavePosition();

                // If downstream is already filled, jump wave to that position
                if (effectivePosition > unitsInjected) {
                    unitsInjected = effectivePosition;
                    frontPos = effectivePosition;
                }

                int unitsToAdd = Math.min(newUnits, networkCapacity - unitsInSystem);

                if (unitsToAdd > 0) {
                    unitsInjected += unitsToAdd;

                    // 🔥 GAS PEDAL: Mark that pump is ACTIVELY pushing fluid RIGHT NOW
                    pumpPushingThisTick = true;
                    lastPumpPushTick = level.getGameTime();

                    int mbConsumed = unitsToAdd * MB_PER_UNIT;
                    injectedMbAccum -= mbConsumed;

                    int mbRejected = incomingMb - mbConsumed;
                    if (mbRejected > 0) {
                        if (frontPos < 0f) frontPos = 0f;
                        setChanged();
                        if (!level.isClientSide) {
                            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                        }
                        return new FluidStack(stack.getFluid(), mbRejected);
                    }
                } else {
                    injectedMbAccum -= incomingMb;
                    return stack;
                }
            }

            if (frontPos < 0f) frontPos = 0f;
            setChanged();
            if (!level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }

            return FluidStack.EMPTY;
        }

        return FluidStack.EMPTY;
    }

    /**
     * 🔥 NEW: Smart wave position calculator
     * Scans forward from root to find pipes with checkpoints and skips over them
     *
     * Example: If pipes D and E have full checkpoints, but C is empty:
     * - Wave should be at position matching last filled pipe
     * - This allows pump to "jump over" already-filled sections
     */
    private int smartCalculateWavePosition() {
        if (level == null) return unitsInjected;

        // Start from current position
        int position = unitsInjected;

        // Use BFS to scan network and find consecutive filled pipes
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Map<Integer, FluidPipeBlockEntity> pipesByDistance = new java.util.HashMap<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

        // Collect all pipes by distance
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                if (pipe.distanceFromRoot != Integer.MAX_VALUE) {
                    pipesByDistance.put(pipe.distanceFromRoot, pipe);
                }

                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnectedInDirection(dir)) continue;

                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        // Find consecutive filled pipes starting from position 1 (root)
        int consecutiveFilled = 1; // Root is always filled
        for (int dist = 2; dist <= pipesByDistance.size() + 1; dist++) {
            FluidPipeBlockEntity pipe = pipesByDistance.get(dist);
            if (pipe == null) break;

            // Is this pipe filled? (has full checkpoint)
            boolean isFilled = pipe.hasCheckpoint &&
                    pipe.checkpointAmount >= CHECKPOINT_CAPACITY_MB * 0.95; // 95% = close enough

            if (isFilled) {
                consecutiveFilled = dist;
            } else {
                // Hit an empty pipe - stop scanning
                break;
            }
        }

        return Math.max(position, consecutiveFilled);
    }

    @Nullable
    private FluidPipeBlockEntity findExistingRoot() {
        if (level == null) return null;

        // Use cached root if we have it and it's still valid
        if (cachedRoot != null && cachedRoot.isRoot() && !cachedRoot.isRemoved()) {
            return cachedRoot;
        }

        // Search for root
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                if (pipe.isRoot() && pipe != this) {
                    cachedRoot = pipe; // Cache it
                    return pipe;
                }

                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnectedInDirection(dir)) continue;

                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        cachedRoot = null;
        return null;
    }

    private int getNetworkCapacity() {
        if (level == null) return 1;

        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            for (Direction dir : Direction.values()) {
                BlockState state = level.getBlockState(current);
                BooleanProperty prop = switch (dir) {
                    case NORTH -> BaseFluidPipeBlock.NORTH;
                    case SOUTH -> BaseFluidPipeBlock.SOUTH;
                    case EAST -> BaseFluidPipeBlock.EAST;
                    case WEST -> BaseFluidPipeBlock.WEST;
                    case UP -> BaseFluidPipeBlock.UP;
                    case DOWN -> BaseFluidPipeBlock.DOWN;
                };
                if (!state.hasProperty(prop) || !state.getValue(prop)) continue;

                BlockPos next = current.relative(dir);
                if (visited.contains(next)) continue;

                BlockEntity be = level.getBlockEntity(next);
                if (be instanceof FluidPipeBlockEntity) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        return visited.size();
    }

    /* ---------------------------------------------------------------------- */
    /* Capability                                                              */
    /* ---------------------------------------------------------------------- */

    private class SideFluidHandler implements IFluidHandler {
        private final Direction side;

        SideFluidHandler(Direction side) {
            this.side = side;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return MB_PER_UNIT;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return stack != null && !stack.isEmpty();
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) return 0;

            if (action.simulate()) {
                if (level != null && isRoot) {
                    int networkCapacity = getNetworkCapacity();
                    int unitsInSystem = unitsInjected - unitsConsumed;
                    int availableUnits = networkCapacity - unitsInSystem;
                    int availableMb = availableUnits * MB_PER_UNIT;
                    return Math.min(resource.getAmount(), Math.max(0, availableMb));
                }
                return resource.getAmount();
            }

            FluidStack copy = resource.copy();
            FluidStack leftover = offer(copy, side);
            return resource.getAmount() - leftover.getAmount();
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    @Nullable
    public IFluidHandler getFluidHandlerForSide(Direction side) {
        return sideHandlers.get(side);
    }

    /* ---------------------------------------------------------------------- */
    /* Sync                                                                    */
    /* ---------------------------------------------------------------------- */

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return this.saveWithoutMetadata(provider);
    }

    /* ---------------------------------------------------------------------- */
    /* Save/Load - 🔥 ENHANCED with virgin pipe state AND gas pedal          */
    /* ---------------------------------------------------------------------- */

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        // WAVE STATE
        out.putInt("DistanceFromRoot", distanceFromRoot);
        out.putInt("IsRoot", isRoot ? 1 : 0);
        out.putInt("UnitsInjected", unitsInjected);
        out.putInt("UnitsConsumed", unitsConsumed);
        out.putInt("InjectedMbAccum", injectedMbAccum);
        out.store("FrontPos", Codec.FLOAT, frontPos);
        out.putInt("UnitsDeliveredToTank", unitsDeliveredToTank);

        if (!displayedFluid.isEmpty()) {
            out.store("DisplayedFluid", FluidStack.OPTIONAL_CODEC, displayedFluid);
        }

        // 🔥 GAS PEDAL STATE
        out.putInt("PumpPushingThisTick", pumpPushingThisTick ? 1 : 0);
        out.putLong("LastPumpPushTick", lastPumpPushTick);

        // CHECKPOINT STATE
        out.putInt("HasCheckpoint", hasCheckpoint ? 1 : 0);
        out.putInt("IsOrphaned", isOrphaned ? 1 : 0);
        out.putInt("CheckpointAmount", checkpointAmount);
        out.putInt("CheckpointWasDeliveredByWave", checkpointWasDeliveredByWave ? 1 : 0);

        if (!checkpointFluid.isEmpty()) {
            out.store("CheckpointFluid", FluidStack.OPTIONAL_CODEC, checkpointFluid);
        }

        // 🔥 ANTI-REFILL STATE
        out.putInt("IsVirginPipe", isVirginPipe ? 1 : 0);
        out.putInt("VirginTicksRemaining", virginTicksRemaining);
        out.putLong("PlacementTime", placementTime);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        // WAVE STATE
        distanceFromRoot = in.getInt("DistanceFromRoot").orElse(Integer.MAX_VALUE);
        prevDistanceFromRoot = distanceFromRoot;
        isRoot = in.getInt("IsRoot").orElse(0) != 0;
        unitsInjected = in.getInt("UnitsInjected").orElse(0);
        unitsConsumed = in.getInt("UnitsConsumed").orElse(0);
        injectedMbAccum = in.getInt("InjectedMbAccum").orElse(0);
        frontPos = in.read("FrontPos", Codec.FLOAT).orElse(0f);
        unitsDeliveredToTank = in.getInt("UnitsDeliveredToTank").orElse(0);

        displayedFluid = in.read("DisplayedFluid", FluidStack.OPTIONAL_CODEC)
                .orElse(FluidStack.EMPTY);

        // 🔥 GAS PEDAL STATE
        pumpPushingThisTick = in.getInt("PumpPushingThisTick").orElse(0) != 0;
        lastPumpPushTick = in.getLong("LastPumpPushTick").orElse(0L);

        // CHECKPOINT STATE
        hasCheckpoint = in.getInt("HasCheckpoint").orElse(0) != 0;
        isOrphaned = in.getInt("IsOrphaned").orElse(0) != 0;
        checkpointAmount = in.getInt("CheckpointAmount").orElse(0);
        checkpointWasDeliveredByWave = in.getInt("CheckpointWasDeliveredByWave").orElse(0) != 0;

        checkpointFluid = in.read("CheckpointFluid", FluidStack.OPTIONAL_CODEC)
                .orElse(FluidStack.EMPTY);

        // 🔥 ANTI-REFILL STATE
        // Default to virgin for backward compatibility (old pipes become virgin on load)
        isVirginPipe = in.getInt("IsVirginPipe").orElse(1) != 0;
        virginTicksRemaining = in.getInt("VirginTicksRemaining").orElse(VIRGIN_GRACE_PERIOD_TICKS);
        placementTime = in.getLong("PlacementTime").orElse(0L);
    }
}