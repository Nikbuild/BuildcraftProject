package com.nick.buildcraft.content.block.fluidpipe;

import com.mojang.serialization.Codec;
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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

public class FluidPipeBlockEntity extends BlockEntity {

    public static final int REACH_PER_UNIT = 1;
    private static final int MB_PER_UNIT = 1000;
    private static final float WAVE_SPEED = 0.0f;
    private static final int CHECKPOINT_CAPACITY_MB = 1000;
    private static final int TRANSFER_RATE = 0;
    private static final int VIRGIN_GRACE_PERIOD_TICKS = 40;
    public static final int GAS_PEDAL_GRACE_TICKS = 2;

    boolean isRoot = false;
    int unitsInjected = 0;
    int unitsConsumed = 0;
    float frontPos;
    int injectedMbAccum = 0;
    int distanceFromRoot = Integer.MAX_VALUE;
    int prevDistanceFromRoot = Integer.MAX_VALUE;
    FluidStack displayedFluid = FluidStack.EMPTY;
    int unitsDeliveredToTank = 0;
    boolean pumpPushingThisTick = false;
    long lastPumpPushTick = 0L;
    FluidStack checkpointFluid = FluidStack.EMPTY;
    int checkpointAmount = 0;
    boolean hasCheckpoint = false;
    boolean isOrphaned = false;
    boolean checkpointWasDeliveredByWave = false;
    boolean isVirginPipe = true;
    int virginTicksRemaining = VIRGIN_GRACE_PERIOD_TICKS;
    long placementTime = 0L;
    int tickCounter = 0;
    FluidPipeBlockEntity cachedRoot = null;

    private final EnumMap<Direction, SideFluidHandler> sideHandlers = new EnumMap<>(Direction.class);

    public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.FLUID_PIPE.get(), pos, state);
        for (Direction d : Direction.values()) {
            sideHandlers.put(d, new SideFluidHandler(d));
        }
        clearAllFluidState();
    }

    public void clearAllFluidState() {
        isRoot = false;
        unitsInjected = 0;
        unitsConsumed = 0;
        frontPos = 0f;
        injectedMbAccum = 0;
        distanceFromRoot = Integer.MAX_VALUE;
        prevDistanceFromRoot = Integer.MAX_VALUE;
        displayedFluid = FluidStack.EMPTY;
        unitsDeliveredToTank = 0;
        pumpPushingThisTick = false;
        lastPumpPushTick = 0L;
        checkpointFluid = FluidStack.EMPTY;
        checkpointAmount = 0;
        hasCheckpoint = false;
        isOrphaned = false;
        checkpointWasDeliveredByWave = false;
        isVirginPipe = true;
        virginTicksRemaining = VIRGIN_GRACE_PERIOD_TICKS;

        if (level != null) {
            placementTime = level.getGameTime();
        } else {
            placementTime = 0L;
        }

        tickCounter = 0;
    }

    public Level getPipeLevel() {
        return this.level;
    }

    public BlockPos getPipePos() {
        return this.worldPosition;
    }

    public FluidStack getDisplayedFluid() {
        if (!displayedFluid.isEmpty()) return displayedFluid;
        if (!checkpointFluid.isEmpty()) return checkpointFluid;
        return FluidStack.EMPTY;
    }

    public float getFillFraction() {
        if (hasCheckpoint && checkpointAmount > 0) {
            return (float) checkpointAmount / (float) CHECKPOINT_CAPACITY_MB;
        }

        if (distanceFromRoot == Integer.MAX_VALUE) return 0f;
        if (level == null) return 0f;

        FluidPipeBlockEntity root = PipeNetworkFinder.findExistingRoot(this);
        if (root == null) return 0f;

        float head = (float) root.unitsInjected;
        if (head <= 0f) return 0f;

        float segmentStart = distanceFromRoot - 1;
        float segmentEnd = distanceFromRoot;

        if (head <= segmentStart) return 0f;
        if (head >= segmentEnd) return 1f;

        return head - segmentStart;
    }

    public float getFrontPos() {
        if (isRoot) return frontPos;
        if (level == null || distanceFromRoot == Integer.MAX_VALUE) return 0f;

        FluidPipeBlockEntity root = PipeNetworkFinder.findExistingRoot(this);
        if (root == null) return 0f;

        return (float) root.unitsInjected;
    }

    public int getDistanceFromRoot() {
        return distanceFromRoot;
    }

    public FluidPipeBlockEntity getCachedRoot() {
        return cachedRoot;
    }

    public void setCachedRoot(FluidPipeBlockEntity root) {
        this.cachedRoot = root;
    }

    public boolean isConnected(Direction dir) {
        return isConnectedInDirection(dir);
    }

    public boolean isRoot() {
        return isRoot;
    }

    public boolean isOnActiveFlowPath() {
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

    public boolean isConnectedInDirection(Direction dir) {
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
    public void tickFluids() {
        if (level == null) return;

        if (isRoot && !level.isClientSide) {
            pumpPushingThisTick = false;
        }

        // CRITICAL: Update distance FIRST before any virgin grace checks
        // This ensures we know our position in the network
        PipeDistanceUpdater.updateDistanceFromRoot(this);

        // 🔥 CHECKPOINT RECOVERY FIX
        // Auto-disable virgin grace if we're being placed in a gap with checkpoints downstream
        // This allows immediate wave flow when replacing a broken pipe in a checkpoint chain
        if (isVirginPipe && virginTicksRemaining > 0 && !level.isClientSide) {
            // Only check if we have a valid distance (not orphaned)
            if (distanceFromRoot != Integer.MAX_VALUE) {
                FluidPipeBlockEntity root = PipeNetworkFinder.findExistingRoot(this);
                if (root != null && !root.equals(this)) {
                    // Scan for checkpoints in the network
                    int checkpointPos = PipeWaveRecovery.scanFurthestFilledPipe(root);

                    // If there are checkpoints downstream of our position, we're filling a gap
                    // Disable virgin grace to allow wave to flow through immediately
                    if (checkpointPos > this.distanceFromRoot) {
                        isVirginPipe = false;
                        virginTicksRemaining = 0;
                        setChanged();
                    }
                }
            }
        }

        // Normal virgin grace countdown
        if (isVirginPipe && virginTicksRemaining > 0) {
            virginTicksRemaining--;
            if (virginTicksRemaining <= 0) {
                isVirginPipe = false;
            }
        }

        PipeWavePropagator.propagateFluidType(this);

        if (!level.isClientSide) {
            if (isRoot) {
                PipeWavePropagator.advanceWaveFront(this);
                PipeWavePropagator.syncConsumedUnits(this);
            }

            PipeWavePropagator.propagateFrontPos(this);
            PipeCheckpointManager.updateCheckpoint(this);
            PipeCheckpointManager.deliverToTanks(this);
        }

        tickCounter++;
        if (tickCounter >= 10) {
            tickCounter = 0;
            syncToClient();
        }
    }

    boolean hasNonPipeNeighbor() {
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
                    int networkCapacity = PipeFluidInjector.getNetworkCapacity(FluidPipeBlockEntity.this);
                    int unitsInSystem = unitsInjected - unitsConsumed;
                    int availableUnits = networkCapacity - unitsInSystem;
                    int availableMb = availableUnits * MB_PER_UNIT;
                    return Math.min(resource.getAmount(), Math.max(0, availableMb));
                }
                return resource.getAmount();
            }

            FluidStack copy = resource.copy();
            FluidStack leftover = PipeFluidInjector.offer(FluidPipeBlockEntity.this, copy, side);
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

        out.putInt("PumpPushingThisTick", pumpPushingThisTick ? 1 : 0);
        out.putLong("LastPumpPushTick", lastPumpPushTick);

        out.putInt("HasCheckpoint", hasCheckpoint ? 1 : 0);
        out.putInt("IsOrphaned", isOrphaned ? 1 : 0);
        out.putInt("CheckpointAmount", checkpointAmount);
        out.putInt("CheckpointWasDeliveredByWave", checkpointWasDeliveredByWave ? 1 : 0);

        if (!checkpointFluid.isEmpty()) {
            out.store("CheckpointFluid", FluidStack.OPTIONAL_CODEC, checkpointFluid);
        }

        out.putInt("IsVirginPipe", isVirginPipe ? 1 : 0);
        out.putInt("VirginTicksRemaining", virginTicksRemaining);
        out.putLong("PlacementTime", placementTime);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

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

        pumpPushingThisTick = in.getInt("PumpPushingThisTick").orElse(0) != 0;
        lastPumpPushTick = in.getLong("LastPumpPushTick").orElse(0L);

        hasCheckpoint = in.getInt("HasCheckpoint").orElse(0) != 0;
        isOrphaned = in.getInt("IsOrphaned").orElse(0) != 0;
        checkpointAmount = in.getInt("CheckpointAmount").orElse(0);
        checkpointWasDeliveredByWave = in.getInt("CheckpointWasDeliveredByWave").orElse(0) != 0;

        checkpointFluid = in.read("CheckpointFluid", FluidStack.OPTIONAL_CODEC)
                .orElse(FluidStack.EMPTY);

        isVirginPipe = in.getInt("IsVirginPipe").orElse(1) != 0;
        virginTicksRemaining = in.getInt("VirginTicksRemaining").orElse(VIRGIN_GRACE_PERIOD_TICKS);
        placementTime = in.getLong("PlacementTime").orElse(0L);
    }
}
