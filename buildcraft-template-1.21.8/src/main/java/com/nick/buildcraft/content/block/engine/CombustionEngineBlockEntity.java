package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

public class CombustionEngineBlockEntity extends EngineBlockEntity implements MenuProvider {

    /** Fuel tank - holds FUEL fluid (10,000 mB capacity) */
    private final FluidTank fuelTank = new FluidTank(10_000) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }

        @Override
        public boolean isFluidValid(FluidStack stack) {
            // Only accept FUEL fluid
            return stack != null && stack.getFluid() == ModFluids.FUEL.get();
        }
    };

    /** Coolant tank - holds water or other coolant fluids (10,000 mB capacity) */
    private final FluidTank coolantTank = new FluidTank(10_000) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }

        @Override
        public boolean isFluidValid(FluidStack stack) {
            // Accept any fluid for coolant (water, etc.)
            return stack != null && !stack.isEmpty();
        }
    };

    // Heat level (0.0 = cold, 1.0 = max heat)
    private float heat = 0.0f;
    private float targetHeat = 0.0f;

    // Tracked amounts (for syncing to client via DataSlots)
    // These mirror the actual tank fluid amounts but as simple ints
    private int fuelAmount = 0;
    private int coolantAmount = 0;

    public CombustionEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EngineType.COMBUSTION, pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.buildcraft.combustion_engine");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory invPlayer, Player player) {
        return new CombustionEngineMenu(id, invPlayer, this);
    }

    /* =============== Server Tick =============== */

    public static void serverTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        if (!(be instanceof CombustionEngineBlockEntity cbe)) return;

        // Consume 1 mB of fuel per tick if available, and generate heat/power
        if (cbe.fuelTank.getFluidAmount() > 0) {
            cbe.fuelTank.drain(1, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            cbe.targetHeat = 1.0f; // set to max heat when burning
        } else {
            cbe.targetHeat = 0.0f; // no heat when no fuel
        }

        // Update tracked amounts for client sync
        cbe.fuelAmount = cbe.fuelTank.getFluidAmount();
        cbe.coolantAmount = cbe.coolantTank.getFluidAmount();

        // Smooth heat transitions (lerp toward target)
        float heatDamping = 0.05f;
        cbe.heat += (cbe.targetHeat - cbe.heat) * heatDamping;

        // Shared engine logic (animation, energy push, etc.)
        EngineBlockEntity.serverTick(level, pos, state, be);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        EngineBlockEntity.clientTick(level, pos, state, be);
    }

    /* =============== Fluid Tank Accessors =============== */

    public FluidTank getFuelTank() {
        return fuelTank;
    }

    public FluidTank getCoolantTank() {
        return coolantTank;
    }

    /* =============== Heat Accessors =============== */

    public float getHeat() {
        return heat;
    }

    public void setHeatClient(float heat) {
        this.heat = heat;
    }

    /* =============== Tracked Amount Accessors (for DataSlot syncing) =============== */

    public int getFuelAmount() {
        return fuelAmount;
    }

    public void setFuelAmountClient(int amount) {
        this.fuelAmount = amount;
    }

    public int getCoolantAmount() {
        return coolantAmount;
    }

    public void setCoolantAmountClient(int amount) {
        this.coolantAmount = amount;
    }

    /* =============== Save/Load =============== */

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.store("Fuel", FluidStack.OPTIONAL_CODEC, fuelTank.getFluid());
        out.store("Coolant", FluidStack.OPTIONAL_CODEC, coolantTank.getFluid());
        out.putFloat("Heat", heat);
        out.putFloat("TargetHeat", targetHeat);
        out.putInt("FuelAmount", fuelAmount);
        out.putInt("CoolantAmount", coolantAmount);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.read("Fuel", FluidStack.OPTIONAL_CODEC).ifPresent(fuelTank::setFluid);
        in.read("Coolant", FluidStack.OPTIONAL_CODEC).ifPresent(coolantTank::setFluid);
        heat = in.getFloatOr("Heat", 0.0f);
        targetHeat = in.getFloatOr("TargetHeat", 0.0f);
        fuelAmount = in.getIntOr("FuelAmount", 0);
        coolantAmount = in.getIntOr("CoolantAmount", 0);
        // Ensure chunk-load state sync by marking as changed
        if (this.level != null && !this.level.isClientSide) {
            this.setChanged();
        }
    }

    /* =============== Network Sync =============== */

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }
}
