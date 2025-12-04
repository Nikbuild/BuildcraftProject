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
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

/**
 * Simplified fluid pipe with "wave" behaviour + tank handoff:
 *
 * - Pump still just calls fill/offer on the first pipe.
 * - Pipes own the visual logic:
 *   - When FIRST pipe next to a non-pipe neighbour receives fluid, it
 *     becomes an injection root and increments unitsInjected.
 *   - For that root, fluid front distance = unitsInjected * REACH_PER_UNIT pipes.
 *   - A smooth head position (frontPos) animates toward that distance.
 *   - Each pipe knows its integer distanceFromRoot in pipe steps.
 *   - Renderer uses distanceFromRoot + frontPos to draw a "snake" inside
 *     each relevant pipe (partial fill when head is inside that block).
 *
 * - NEW: Proper corner/junction detection to prevent fluid rendering outside pipe bounds.
 *   The entity now tracks which directions have actual pipe connections to guide rendering.
 *
 * - Respects endpoint-only connection logic - only considers neighbors
 *   that are actually connected via the blockstate connection properties.
 *
 * - If a pipe in the chain touches a TankBlockEntity, then as the
 *   wave passes through that pipe it will transfer real fluid into the tank
 *   via the tank's ColumnFluidHandler.
 *
 * - CAPACITY LIMITS: Each pipe segment can hold a maximum amount of fluid.
 */
public class FluidPipeBlockEntity extends BlockEntity {

    // How many pipes per "unit" of water (you set this to 1)
    public static final int REACH_PER_UNIT = 1;

    // how many mB of fluid counts as "one unit" for extending REACH_PER_UNIT pipes
    private static final int MB_PER_UNIT = 1000;

    // Maximum units each pipe segment can hold (1 bucket per pipe)
    private static final int MAX_UNITS_PER_PIPE = 1;

    // accumulator to handle many small pump packets
    private int injectedMbAccum = 0;

    // Visual speed: pipes per tick that the head moves
    private static final float FRONT_SPEED = 0.12f; // ~1 full pipe every ~8 ticks

    // ---- per-network (root-based) state ----

    /** True if this pipe is a root (directly receiving from a pump/tank/etc). */
    private boolean isRoot = false;

    /** Total "units" of water injected at this root (each ~= MB_PER_UNIT mB). */
    private int unitsInjected = 0;

    /** Total units that have been delivered/consumed from the network */
    private int unitsConsumed = 0;

    /** Animated head position measured in pipes from root (0 at first pipe). */
    private float frontPos = 0f;

    // ---- per-pipe state ----

    /** Distance (in pipe steps) from the root. 1 = first pipe, 2 = next, etc. */
    private int distanceFromRoot = Integer.MAX_VALUE;

    /** Previous distanceFromRoot to detect topology changes */
    private int prevDistanceFromRoot = Integer.MAX_VALUE;

    /** Fluid type to show (propagated from root). */
    private FluidStack displayedFluid = FluidStack.EMPTY;

    /**
     * For endpoint pipes that sit next to a tank: how many "units" of the wave
     * have already been delivered into the tank so we don't double-count.
     */
    private int unitsDeliveredToTank = 0;

    /** Per-side capability handlers. */
    private final EnumMap<Direction, SideFluidHandler> sideHandlers = new EnumMap<>(Direction.class);

    public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.FLUID_PIPE.get(), pos, state);
        for (Direction d : Direction.values()) {
            sideHandlers.put(d, new SideFluidHandler(d));
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Public API for renderer                                                */
    /* ---------------------------------------------------------------------- */

    /** Distance from root (pipe steps), Integer.MAX_VALUE = not in a rooted network. */
    public int getDistanceFromRoot() {
        return distanceFromRoot;
    }

    /**
     * Head position (in pipes) measured from the root pipe, using the root's data.
     * Pipes that are not in any rooted network return 0.
     */
    public float getFrontPos() {
        // Root stores its own frontPos.
        if (isRoot) return frontPos;

        if (level == null || distanceFromRoot == Integer.MAX_VALUE) return 0f;

        float bestFront = 0f;
        int bestDist = Integer.MAX_VALUE;

        // Look for a neighbour that is closer to the root than we are,
        // and recursively pull its frontPos. This lets the head value
        // propagate down the whole chain, not just the first pipe.
        for (Direction dir : Direction.values()) {
            // Only check directions where we actually have a connection
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                // Only consider neighbors that are actually part of the flow path
                if (other.distanceFromRoot < this.distanceFromRoot && other.distanceFromRoot != Integer.MAX_VALUE) {
                    float candidate = other.getFrontPos();
                    if (other.distanceFromRoot < bestDist) {
                        bestDist = other.distanceFromRoot;
                        bestFront = candidate;
                    }
                }
            }
        }

        return bestFront;
    }

    /** Which fluid to draw. */
    public FluidStack getDisplayedFluid() {
        return displayedFluid;
    }

    /** True if there is any amount of fluid in this pipe visually. */
    public boolean hasAnyFluid() {
        return getFillFraction() > 0f && !displayedFluid.isEmpty();
    }

    /**
     * Returns how full this pipe is (0..1) based on distanceFromRoot and the front position.
     *
     *  - If front hasn't reached this pipe yet -> 0
     *  - If front has passed completely       -> 1
     *  - If front is inside this pipe         -> fraction in [0,1]
     *
     * NEW: Returns 0 if this pipe is not actually part of the active flow path.
     */
    public float getFillFraction() {
        if (distanceFromRoot == Integer.MAX_VALUE) return 0f;

        // NEW: Verify this pipe is actually on the flow path by checking if we have
        // a valid upstream neighbor
        if (!isOnActiveFlowPath()) return 0f;

        float head = getFrontPos(); // pipes from root
        if (head <= 0f) return 0f;

        // pipes are indexed [0..), distanceFromRoot=1 means segment [0,1)
        float segmentStart = distanceFromRoot - 1;
        float segmentEnd   = distanceFromRoot;

        if (head <= segmentStart) return 0f;
        if (head >= segmentEnd)   return 1f;

        return head - segmentStart; // [0..1)
    }

    /**
     * NEW: Check if this pipe is actually on the active flow path.
     * A pipe is on the flow path if:
     * - It's the root, OR
     * - It has at least one connected neighbor with a lower distanceFromRoot
     */
    private boolean isOnActiveFlowPath() {
        if (isRoot) return true;
        if (level == null) return false;
        if (distanceFromRoot == Integer.MAX_VALUE) return false;

        // Check if we have any upstream neighbor (closer to root)
        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = worldPosition.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                if (other.distanceFromRoot < this.distanceFromRoot &&
                        other.distanceFromRoot != Integer.MAX_VALUE) {
                    return true; // Found an upstream connection
                }
            }
        }

        return false; // No upstream connection = not on active path
    }

    /* ---------------------------------------------------------------------- */
    /* Connection helpers                                                      */
    /* ---------------------------------------------------------------------- */

    /**
     * Check if this pipe has a valid connection in the given direction
     * based on the blockstate connection properties.
     */
    private boolean isConnectedInDirection(Direction dir) {
        BlockState state = getBlockState();
        BooleanProperty prop = getPropertyForDirection(dir);
        return state.hasProperty(prop) && state.getValue(prop);
    }

    /**
     * Get the BooleanProperty for a given direction.
     */
    private static BooleanProperty getPropertyForDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> BaseFluidPipeBlock.NORTH;
            case SOUTH -> BaseFluidPipeBlock.SOUTH;
            case EAST  -> BaseFluidPipeBlock.EAST;
            case WEST  -> BaseFluidPipeBlock.WEST;
            case UP    -> BaseFluidPipeBlock.UP;
            case DOWN  -> BaseFluidPipeBlock.DOWN;
        };
    }

    /* ---------------------------------------------------------------------- */
    /* Ticking                                                                 */
    /* ---------------------------------------------------------------------- */

    /** Old hook used by block. */
    public void tickFluids() {
        tick();
    }

    /** Tick: server owns the simulation; client just renders synced state. */
    public void tick() {
        if (level == null) return;

        // Both sides keep topology / fluid type up to date
        updateDistanceFromRoot(level, worldPosition);
        propagateFluidType(level, worldPosition);

        // 🔑 Only the SERVER advances the wave and does tank IO.
        if (!level.isClientSide) {
            if (isRoot) {
                float target = unitsInjected * REACH_PER_UNIT;
                if (frontPos < target) {
                    frontPos = Math.min(target, frontPos + FRONT_SPEED);
                    setChanged();
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                }

                // Propagate unitsConsumed from the network
                syncConsumedUnits(level, worldPosition);
            }

            // Propagate frontPos from neighbors even when not root
            // This allows orphaned pipes to continue the wave animation
            propagateFrontPos(level, worldPosition);

            // If this pipe touches a tank, deliver any units whose wave
            // front has already passed through this block.
            tryDeliverToAdjacentTank();
        }
    }

    /**
     * Compute minimal distanceFromRoot based on neighbours.
     * Root has distance 1 by definition.
     *
     * Only considers neighbors where we have an actual blockstate connection.
     */
    private void updateDistanceFromRoot(Level level, BlockPos pos) {
        int best = Integer.MAX_VALUE;

        if (isRoot) {
            best = 1;
        }

        // Only check connected directions
        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                int od = other.distanceFromRoot;
                if (od != Integer.MAX_VALUE) {
                    best = Math.min(best, od + 1);
                }
            }
        }

        if (best != distanceFromRoot) {
            // Topology changed - reset delivery counter to allow re-delivery
            if (prevDistanceFromRoot != Integer.MAX_VALUE && best != prevDistanceFromRoot) {
                unitsDeliveredToTank = 0;
            }

            prevDistanceFromRoot = distanceFromRoot;
            distanceFromRoot = best;
            setChanged();

            // Sync new distance to the client so rendering updates immediately
            if (!level.isClientSide) {
                level.sendBlockUpdated(pos, getBlockState(), getBlockState(), 3);
            }
        }
    }

    /**
     * Sync consumed units from downstream pipes back to root.
     * This allows the root to know how much fluid has left the system.
     *
     * Only traverses pipes with actual blockstate connections.
     */
    private void syncConsumedUnits(Level level, BlockPos pos) {
        if (!isRoot) return;

        int maxConsumed = 0;

        // Find the maximum unitsDeliveredToTank from any pipe in the network
        // Only traverse via actual blockstate connections
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(pos);
        visited.add(pos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                maxConsumed = Math.max(maxConsumed, pipe.unitsDeliveredToTank);

                BlockState currentState = level.getBlockState(current);

                // Only traverse connected directions
                for (Direction dir : Direction.values()) {
                    BooleanProperty prop = getPropertyForDirection(dir);
                    if (!currentState.hasProperty(prop) || !currentState.getValue(prop)) {
                        continue; // Not connected in this direction
                    }

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
     * Propagate frontPos and unitsInjected from neighbors.
     * This ensures that when the root is destroyed, the wave continues
     * to animate and deliver fluid based on what was already injected.
     *
     * Only considers neighbors with actual blockstate connections.
     */
    private void propagateFrontPos(Level level, BlockPos pos) {
        if (isRoot) return; // Root manages its own frontPos

        float bestFrontPos = frontPos;
        int bestUnitsInjected = unitsInjected;
        int bestUnitsConsumed = unitsConsumed;
        boolean foundBetter = false;

        // Only check connected directions
        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                if (other.distanceFromRoot < this.distanceFromRoot && other.distanceFromRoot != Integer.MAX_VALUE) {
                    // Inherit from closer neighbor
                    if (other.frontPos > bestFrontPos || other.unitsInjected > bestUnitsInjected) {
                        bestFrontPos = other.frontPos;
                        bestUnitsInjected = other.unitsInjected;
                        bestUnitsConsumed = other.unitsConsumed;
                        foundBetter = true;
                    }
                }
            }
        }

        if (foundBetter) {
            if (frontPos != bestFrontPos || unitsInjected != bestUnitsInjected || unitsConsumed != bestUnitsConsumed) {
                frontPos = bestFrontPos;
                unitsInjected = bestUnitsInjected;
                unitsConsumed = bestUnitsConsumed;
                setChanged();
                level.sendBlockUpdated(pos, getBlockState(), getBlockState(), 3);
            }
        }
    }

    /**
     * Propagate displayedFluid from root outward.
     *
     * Only considers neighbors with actual blockstate connections.
     */
    private void propagateFluidType(Level level, BlockPos pos) {
        FluidStack best = displayedFluid;

        if (isRoot && !displayedFluid.isEmpty()) {
            best = displayedFluid;
        }

        // Only check connected directions
        for (Direction dir : Direction.values()) {
            if (!isConnectedInDirection(dir)) continue;

            BlockPos np = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                if (other.distanceFromRoot + 1 == this.distanceFromRoot && !other.displayedFluid.isEmpty()) {
                    // Prefer fluid type from the neighbour closer to the root
                    best = other.displayedFluid;
                    break;
                }
            }
        }

        if (!FluidStack.isSameFluidSameComponents(best, this.displayedFluid)) {
            this.displayedFluid = best.isEmpty() ? FluidStack.EMPTY : best.copy();
            // If the fluid type changed, reset local delivery tracking so we don't
            // leak old units into a tank.
            unitsDeliveredToTank = 0;

            setChanged();
            if (!level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Capacity calculation helpers                                           */
    /* ---------------------------------------------------------------------- */

    /**
     * Count how many pipe segments are reachable from this root.
     * Used to determine total network capacity.
     *
     * Only traverses via actual blockstate connections.
     */
    private int countNetworkSize(Level level, BlockPos startPos) {
        if (level == null) return 1;

        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(startPos);
        visited.add(startPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockState currentState = level.getBlockState(current);

            // Only traverse connected directions
            for (Direction dir : Direction.values()) {
                BooleanProperty prop = getPropertyForDirection(dir);
                if (!currentState.hasProperty(prop) || !currentState.getValue(prop)) {
                    continue; // Not connected in this direction
                }

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

    /**
     * Calculate the maximum units this network can hold based on pipe count.
     */
    private int getNetworkCapacity(Level level, BlockPos rootPos) {
        int pipeCount = countNetworkSize(level, rootPos);
        return pipeCount * MAX_UNITS_PER_PIPE;
    }

    /* ---------------------------------------------------------------------- */
    /* Fluid injection (from neighbours)                                      */
    /* ---------------------------------------------------------------------- */

    /**
     * Pump / tank / etc. calls fill on this pipe. If the source is NOT another
     * pipe, we treat this pipe as a root and consider that "one unit of water"
     * has entered the system, extending the target length by REACH_PER_UNIT pipes.
     *
     * Respects network capacity limits based on (unitsInjected - unitsConsumed).
     */
    public FluidStack offer(FluidStack stack, Direction from) {
        if (level == null || stack.isEmpty()) return stack;

        BlockPos srcPos = worldPosition.relative(from);
        BlockEntity srcBe = level.getBlockEntity(srcPos);
        boolean fromPipe = srcBe instanceof FluidPipeBlockEntity;

        if (!fromPipe) {
            // This pipe is the root where fluid enters
            boolean wasRoot = isRoot;
            isRoot = true;

            // First time any fluid arrives, define which fluid the column renders
            if (displayedFluid.isEmpty()) {
                displayedFluid = new FluidStack(stack.getFluid(), MB_PER_UNIT);
                unitsDeliveredToTank = 0; // new fluid type → reset local delivery
            }

            // If we just became a root, reset delivery tracking for fresh start
            if (!wasRoot) {
                unitsDeliveredToTank = 0;
            }

            // Check capacity before accepting fluid
            // Available capacity = total capacity - (units in system)
            // Units in system = unitsInjected - unitsConsumed
            int networkCapacity = getNetworkCapacity(level, worldPosition);
            int unitsInSystem = unitsInjected - unitsConsumed;

            if (unitsInSystem >= networkCapacity) {
                // Network is full - reject the fluid
                return stack;
            }

            // accumulate millibuckets from the pump
            int incomingMb = stack.getAmount();
            injectedMbAccum += incomingMb;

            // how many NEW full "units" (buckets) did we just cross?
            int newUnits = injectedMbAccum / MB_PER_UNIT;

            if (newUnits > 0) {
                // Check if adding these units would exceed capacity
                int unitsToAdd = Math.min(newUnits, networkCapacity - unitsInSystem);

                if (unitsToAdd > 0) {
                    unitsInjected += unitsToAdd;
                    int mbConsumed = unitsToAdd * MB_PER_UNIT;
                    injectedMbAccum -= mbConsumed;

                    // Calculate leftover
                    int mbRejected = incomingMb - mbConsumed;
                    if (mbRejected > 0) {
                        // Ensure animation head starts correctly
                        if (frontPos < 0f) {
                            frontPos = 0f;
                        }

                        setChanged();
                        if (!level.isClientSide) {
                            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                        }

                        return new FluidStack(stack.getFluid(), mbRejected);
                    }
                } else {
                    // Can't accept any more units - reject all
                    injectedMbAccum -= incomingMb; // undo the accumulation
                    return stack;
                }
            }

            // Ensure animation head starts correctly
            if (frontPos < 0f) {
                frontPos = 0f;
            }

            setChanged();
            if (!level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }

            return FluidStack.EMPTY; // accepted all remaining fluid
        }

        // From another pipe - just pass through
        return FluidStack.EMPTY;
    }

    /* ---------------------------------------------------------------------- */
    /* Tank delivery logic                                                    */
    /* ---------------------------------------------------------------------- */

    /**
     * If this pipe is adjacent to a TankBlockEntity, transfer real fluid
     * into the tank as the wave passes through this block.
     *
     * We count how many full "units" of the wave have traversed this block:
     *
     *   unitsPassedHere = floor( head - (distanceFromRoot - 1) )
     *
     * and compare that to unitsDeliveredToTank. Whenever unitsPassedHere grows,
     * we push (deltaUnits * MB_PER_UNIT) mB into the tank.
     */
    private void tryDeliverToAdjacentTank() {
        if (level == null) return;
        if (distanceFromRoot == Integer.MAX_VALUE) return;
        if (displayedFluid.isEmpty()) return;

        // NEW: Only deliver if we're actually on the active flow path
        if (!isOnActiveFlowPath()) return;

        // Find a neighbouring tank block entity, if any.
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

        float head = getFrontPos();
        if (head <= 0f) return;

        float segmentStart = distanceFromRoot - 1;
        float passed = head - segmentStart;

        // Wait for actual wave passage (no early delivery)
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

        tankBe.setChanged();
        level.sendBlockUpdated(tankBe.getBlockPos(), tankBe.getBlockState(), tankBe.getBlockState(), 3);
        level.invalidateCapabilities(tankBe.getBlockPos());
    }


    /* ---------------------------------------------------------------------- */
    /* Capabilities                                                            */
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
            // Report actual network capacity
            if (level != null && isRoot) {
                int capacity = getNetworkCapacity(level, worldPosition);
                return capacity * MB_PER_UNIT;
            }
            return MAX_UNITS_PER_PIPE * MB_PER_UNIT;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return stack != null && !stack.isEmpty();
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) return 0;
            if (action.simulate()) {
                // Simulate capacity check
                if (level != null && isRoot) {
                    int networkCapacity = getNetworkCapacity(level, worldPosition);
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
    /* Sync / save                                                             */
    /* ---------------------------------------------------------------------- */

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return this.saveWithoutMetadata(provider);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
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
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        distanceFromRoot = in.getInt("DistanceFromRoot").orElse(Integer.MAX_VALUE);
        prevDistanceFromRoot = distanceFromRoot; // Initialize prev on load
        isRoot           = in.getInt("IsRoot").orElse(0) != 0;
        unitsInjected    = in.getInt("UnitsInjected").orElse(0);
        unitsConsumed    = in.getInt("UnitsConsumed").orElse(0);
        injectedMbAccum  = in.getInt("InjectedMbAccum").orElse(0);
        frontPos         = in.read("FrontPos", Codec.FLOAT).orElse(0f);
        unitsDeliveredToTank = in.getInt("UnitsDeliveredToTank").orElse(0);

        displayedFluid   = in.read("DisplayedFluid", FluidStack.OPTIONAL_CODEC)
                .orElse(FluidStack.EMPTY);
    }
}