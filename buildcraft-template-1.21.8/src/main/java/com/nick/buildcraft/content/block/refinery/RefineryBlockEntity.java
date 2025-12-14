package com.nick.buildcraft.content.block.refinery;

import com.nick.buildcraft.api.engine.EnginePowerAcceptorApi;
import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.content.block.engine.EngineBlockEntity;
import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/**
 * RefineryBlockEntity - Complete implementation
 * - Two oil input tanks (back left and back right)
 * - One fuel output tank (front)
 * - Converts oil to fuel when powered by engines
 * - Smooth magnet animations
 */
public class RefineryBlockEntity extends BlockEntity
        implements EnginePulseAcceptorApi, EnginePowerAcceptorApi {

    // ===== CONFIGURATION =====
    private static final int TANK_CAPACITY = 10000; // 10 buckets per tank
    private static final int OIL_PER_CONVERSION = 2000; // 2 buckets oil per pulse (1:2 ratio: 2 oil = 1 fuel)
    private static final int FUEL_PER_CONVERSION = 1000; // produces 1 bucket fuel per pulse

    // Animation parameters
    private static final int PERIOD = 40;  // baseline ticks per full cycle (at 30 RPM for redstone base)
    private static final int BASE_RPM = 30;  // redstone engine base RPM (1200/40 period)
    public static final double MAGNET_TRAVEL_DISTANCE = 0.75;
    private static final int RESTING_ANIMATION_SPEED = 5; // ticks to reach resting position

    // Engine RPM tracking
    private int lastQueriedRPM = BASE_RPM;  // RPM from adjacent engines (defaults to base)

    // ===== FLUID STORAGE =====
    // Two oil input tanks (visually on the back)
    private final FluidTank oilTank1 = new FluidTank(TANK_CAPACITY, this::isOil);
    private final FluidTank oilTank2 = new FluidTank(TANK_CAPACITY, this::isOil);

    // One fuel output tank (visually on the front)
    private final FluidTank fuelTank = new FluidTank(TANK_CAPACITY, this::isFuel);

    // ===== ENGINE POWER =====
    private int queuedPulses = 0;

    // ===== ANIMATION STATE =====
    private float magnet1Progress  = 0.0f;
    private float magnet1ProgressO = 0.0f;
    private float magnet2Progress  = 0.0f;
    private float magnet2ProgressO = 0.0f;
    private int pumpTick = 0;
    private boolean isActive = false;

    // Resting animation (smooth falldown when unpowered)
    private boolean isResting = false;
    private float targetRestProgress = 0.0f; // Target position when resting (bottom = 0)

    public RefineryBlockEntity(BlockPos pos, BlockState state) {
        super(RefineryBlockEntityType.REFINERY().get(), pos, state);
    }

    // ===== FLUID VALIDATION =====

    private boolean isOil(FluidStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getFluid() == ModFluids.OIL.get() ||
               stack.getFluid() == ModFluids.FLOWING_OIL.get();
    }

    private boolean isFuel(FluidStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getFluid() == ModFluids.FUEL.get() ||
               stack.getFluid() == ModFluids.FLOWING_FUEL.get();
    }

    // ===== ENGINE API IMPLEMENTATION =====

    @Override
    public boolean acceptEnginePulse(Direction from) {
        if (level != null && !level.isClientSide) {
            queuedPulses = Mth.clamp(queuedPulses + 1, 0, 128);
            setChanged();
        }
        return true;
    }

    @Override
    public void acceptEnginePower(Direction from, int power) {
        // Accept engine power for conversion
        setChanged();
    }

    // ===== MAIN TICK LOGIC =====

    public static void serverTick(Level level, BlockPos pos, BlockState state, RefineryBlockEntity entity) {
        if (!(level instanceof ServerLevel)) return;

        boolean wasPowered = entity.isActive;

        // Query adjacent engines every tick to catch RPM/phase changes immediately
        entity.lastQueriedRPM = entity.getAdjacentEngineRPM(level, pos);

        // Active if we have queued pulses
        entity.isActive = entity.queuedPulses > 0;

        // Process conversion if we have pulses and oil
        if (entity.queuedPulses > 0) {
            entity.tryConvertOilToFuel();
            entity.queuedPulses--;
        }

        // Update animation state
        if (entity.isActive) {
            entity.isResting = false;
            entity.updateActiveAnimation();
        } else {
            // Smoothly lower magnets to resting position
            entity.updateRestingAnimation();
        }

        // Sync to client if power state changed
        if (wasPowered != entity.isActive) {
            entity.syncToClient();
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, RefineryBlockEntity entity) {
        // Client-side animation updates
        entity.magnet1ProgressO = entity.magnet1Progress;
        entity.magnet2ProgressO = entity.magnet2Progress;

        // Query adjacent engines every tick on client too for real-time animation updates
        entity.lastQueriedRPM = entity.getAdjacentEngineRPM(level, pos);

        if (entity.isActive) {
            entity.updateActiveAnimation();
        } else {
            entity.updateRestingAnimation();
        }
    }

    // ===== OIL TO FUEL CONVERSION =====

    private void tryConvertOilToFuel() {
        // Check if we have enough oil in either tank
        int oil1 = oilTank1.getFluidAmount();
        int oil2 = oilTank2.getFluidAmount();
        int totalOil = oil1 + oil2;

        if (totalOil < OIL_PER_CONVERSION) {
            return; // Not enough oil
        }

        // Check if fuel tank has space
        int fuelSpace = fuelTank.getCapacity() - fuelTank.getFluidAmount();
        if (fuelSpace < FUEL_PER_CONVERSION) {
            return; // Not enough space for fuel
        }

        // Instant conversion: 1 pulse = 1 bucket conversion
        // Drain oil from tanks (prefer tank 1, then tank 2)
        int oilNeeded = OIL_PER_CONVERSION;

        if (oil1 > 0) {
            int drain1 = Math.min(oilNeeded, oil1);
            oilTank1.drain(drain1, IFluidHandler.FluidAction.EXECUTE);
            oilNeeded -= drain1;
        }

        if (oilNeeded > 0 && oil2 > 0) {
            int drain2 = Math.min(oilNeeded, oil2);
            oilTank2.drain(drain2, IFluidHandler.FluidAction.EXECUTE);
        }

        // Produce fuel
        FluidStack fuelStack = new FluidStack(ModFluids.FUEL.get(), FUEL_PER_CONVERSION);
        fuelTank.fill(fuelStack, IFluidHandler.FluidAction.EXECUTE);

        setChanged();
        syncToClient();
    }

    // ===== ANIMATION LOGIC =====

    private void updateActiveAnimation() {
        // Calculate dynamic period based on engine RPM
        // Scale inversely: higher RPM = smaller period = faster animation
        float dynamicPeriod = (float) PERIOD * BASE_RPM / Math.max(lastQueriedRPM, 1);

        // Sinusoidal movement for magnet 1
        pumpTick++;
        float theta1 = (float) (2.0 * Math.PI * pumpTick / dynamicPeriod);
        magnet1Progress = (float) ((1.0 + Math.sin(theta1)) / 2.0); // 0..1

        // Magnet 2 is 180° out of phase
        float theta2 = theta1 + (float) Math.PI;
        magnet2Progress = (float) ((1.0 + Math.sin(theta2)) / 2.0);
    }

    private void updateRestingAnimation() {
        if (!isResting) {
            isResting = true;
            targetRestProgress = 0.0f; // Bottom position
        }

        // Smoothly interpolate to resting position
        float speed = 1.0f / RESTING_ANIMATION_SPEED;
        magnet1Progress = Mth.lerp(speed, magnet1Progress, targetRestProgress);
        magnet2Progress = Mth.lerp(speed, magnet2Progress, targetRestProgress);

        // Clamp to avoid overshoot
        if (Math.abs(magnet1Progress - targetRestProgress) < 0.01f) {
            magnet1Progress = targetRestProgress;
        }
        if (Math.abs(magnet2Progress - targetRestProgress) < 0.01f) {
            magnet2Progress = targetRestProgress;
        }
    }

    // ===== CLIENT RENDERING GETTERS =====

    public float getMagnet1Progress(float partialTick) {
        return Mth.lerp(partialTick, magnet1ProgressO, magnet1Progress);
    }

    public float getMagnet2Progress(float partialTick) {
        return Mth.lerp(partialTick, magnet2ProgressO, magnet2Progress);
    }

    public boolean isActive() {
        return isActive;
    }

    // Fluid tank getters for renderer
    public FluidTank getOilTank1() { return oilTank1; }
    public FluidTank getOilTank2() { return oilTank2; }
    public FluidTank getFuelTank() { return fuelTank; }

    // ===== FLUID HANDLER ACCESS =====

    /**
     * Returns the appropriate fluid handler based on which side is accessed.
     * - For bucket interactions: returns specific tank based on clicked side
     * - For pipe/capability interactions: returns appropriate handler
     */
    @Nullable
    public IFluidHandler getFluidHandlerForSide(@Nullable Direction side) {
        if (side == null) return combinedHandler;

        BlockState state = getBlockState();
        Direction facing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);

        // Back left = oil tank 1 (for bucket interactions)
        Direction backLeft = facing.getOpposite().getClockWise();
        // Back right = oil tank 2 (for bucket interactions)
        Direction backRight = facing.getOpposite().getCounterClockWise();
        // Front = dual oil tank handler (fills both oil tanks)

        if (side == backLeft) return oilTank1;
        if (side == backRight) return oilTank2;
        if (side == facing) return dualOilHandler; // Front face fills both oil tanks

        // For all other sides (top, bottom, other horizontals), return combined handler
        // This allows pipes to connect from any side
        return combinedHandler;
    }

    /**
     * Combined fluid handler that:
     * - Accepts oil into whichever oil tank has space (fills tank1 first, then tank2)
     * - Allows draining fuel from the fuel tank
     * - Used for pipe/capability interactions
     */
    private final IFluidHandler combinedHandler = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 3;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return switch (tank) {
                case 0 -> oilTank1.getFluid();
                case 1 -> oilTank2.getFluid();
                case 2 -> fuelTank.getFluid();
                default -> FluidStack.EMPTY;
            };
        }

        @Override
        public int getTankCapacity(int tank) {
            return TANK_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            if (tank == 0 || tank == 1) return isOil(stack);
            if (tank == 2) return isFuel(stack);
            return false;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            // Only accept oil for filling
            if (!isOil(resource)) return 0;

            // Try to fill tank 1 first
            int filled1 = oilTank1.fill(resource, action);
            if (filled1 > 0) {
                if (action.execute()) {
                    setChanged();
                    syncToClient();
                }
                return filled1;
            }

            // If tank 1 is full, try tank 2
            int filled2 = oilTank2.fill(resource, action);
            if (filled2 > 0 && action.execute()) {
                setChanged();
                syncToClient();
            }
            return filled2;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            // Only drain fuel
            if (!isFuel(resource)) return FluidStack.EMPTY;

            FluidStack drained = fuelTank.drain(resource, action);
            if (!drained.isEmpty() && action.execute()) {
                setChanged();
                syncToClient();
            }
            return drained;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            // Drain fuel from fuel tank
            FluidStack drained = fuelTank.drain(maxDrain, action);
            if (!drained.isEmpty() && action.execute()) {
                setChanged();
                syncToClient();
            }
            return drained;
        }
    };

    /**
     * Dual oil tank handler:
     * - Exposes both oil tanks (tank 0 and tank 1) for filling
     * - Does not expose fuel tank
     * - Used for front face interactions (bucket or pipe)
     * - Fills tank1 first, then tank2
     */
    private final IFluidHandler dualOilHandler = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 2; // Only oil tanks, not fuel
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return switch (tank) {
                case 0 -> oilTank1.getFluid();
                case 1 -> oilTank2.getFluid();
                default -> FluidStack.EMPTY;
            };
        }

        @Override
        public int getTankCapacity(int tank) {
            return TANK_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0 || tank == 1 ? isOil(stack) : false;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            // Only accept oil
            if (!isOil(resource)) return 0;

            // Try to fill tank 1 first
            int filled1 = oilTank1.fill(resource, action);
            if (filled1 > 0) {
                if (action.execute()) {
                    setChanged();
                    syncToClient();
                }
                return filled1;
            }

            // If tank 1 is full, try tank 2
            int filled2 = oilTank2.fill(resource, action);
            if (filled2 > 0 && action.execute()) {
                setChanged();
                syncToClient();
            }
            return filled2;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            // Cannot drain from oil tanks via front face
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            // Cannot drain from oil tanks via front face
            return FluidStack.EMPTY;
        }
    };

    // ===== SYNC =====

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

    // ===== SAVE/LOAD =====

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("QueuedPulses", queuedPulses);
        out.putInt("PumpTick", pumpTick);
        out.putInt("IsActive", isActive ? 1 : 0);

        oilTank1.serialize(out.child("OilTank1"));
        oilTank2.serialize(out.child("OilTank2"));
        fuelTank.serialize(out.child("FuelTank"));
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        queuedPulses = in.getInt("QueuedPulses").orElse(0);
        pumpTick = in.getInt("PumpTick").orElse(0);
        isActive = in.getInt("IsActive").orElse(0) != 0;

        oilTank1.deserialize(in.childOrEmpty("OilTank1"));
        oilTank2.deserialize(in.childOrEmpty("OilTank2"));
        fuelTank.deserialize(in.childOrEmpty("FuelTank"));
    }

    // ===== ENGINE RPM QUERYING =====

    /**
     * Queries adjacent block entities for engines and returns the highest RPM found.
     * Searches in all 6 directions (up, down, north, south, east, west).
     */
    private int getAdjacentEngineRPM(Level level, BlockPos pos) {
        int maxRPM = BASE_RPM;  // Default to base if no engines found

        // Check all 6 directions
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            BlockEntity neighborEntity = level.getBlockEntity(neighborPos);

            if (neighborEntity instanceof EngineBlockEntity engine) {
                int engineRPM = engine.getRPM();
                if (engineRPM > maxRPM) {
                    maxRPM = engineRPM;
                }
            }
        }

        return maxRPM;
    }
}
