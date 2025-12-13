package com.nick.buildcraft.registry;

import com.nick.buildcraft.content.block.fluidpipe.FluidPipeBlockEntity;
import com.nick.buildcraft.content.block.miningwell.MiningWellBlockEntity;
import com.nick.buildcraft.content.block.pump.PumpBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import com.nick.buildcraft.content.block.refinery.RefineryBlock;
import com.nick.buildcraft.content.block.refinery.RefineryBlockEntity;
import com.nick.buildcraft.content.block.tank.TankBlockEntity;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Central capability wiring for BuildCraft.
 *
 * THIS IS CRITICAL:
 *  - Pump exposes its internal FluidTank so pipes/tanks can pull from it.
 *  - Pipe exposes a per-side IFluidHandler so pumps/tanks can push into it.
 *  - Tank exposes a column-wide IFluidHandler so pipes can fill it.
 *  - Quarry / MiningWell expose energy, etc.
 *
 * This class is hooked into the mod event bus from BuildCraft.
 */
public final class ModCapabilities {

    private ModCapabilities() {}

    /** Call this once in your mod constructor: ModCapabilities.register(modEventBus); */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModCapabilities::onRegisterCapabilities);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {

        /* ------------------------------------------------------------------
         * QUARRY CONTROLLER
         * ------------------------------------------------------------------ */

        // (future) item handler, currently none
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, @Nullable Direction side) -> null
        );

        // accepts FE
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, @Nullable Direction side) -> be.getEnergyStorage()
        );

        /* ------------------------------------------------------------------
         * MINING WELL
         * ------------------------------------------------------------------ */

        // accepts FE
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.MINING_WELL.get(),
                (MiningWellBlockEntity be, @Nullable Direction side) -> be.getEnergyStorage()
        );

        /* ------------------------------------------------------------------
         * PUMP
         * ------------------------------------------------------------------ */

        // accepts FE
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.PUMP.get(),
                (PumpBlockEntity be, @Nullable Direction side) -> be.getEnergyStorage()
        );

        // exposes INTERNAL FLUID TANK (same tank from PumpBlockEntity.getFluidHandlerForSide)
        // so pipes / tanks touching the pump can call fill(...) / drain(...)
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntity.PUMP.get(),
                (PumpBlockEntity be, @Nullable Direction side) -> be.getFluidHandlerForSide(side)
        );

        /* ------------------------------------------------------------------
         * TANK
         * ------------------------------------------------------------------ */

        // exposes COLUMN handler (the merged multi-block behavior),
        // not the raw per-block FluidTank.
        //
        // This lets pipes just "fill the tank" from any side and it all goes
        // into the virtual column.
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntity.TANK.get(),
                (TankBlockEntity be, @Nullable Direction side) -> be.getFluidHandlerForSide(side)
        );

        /* ------------------------------------------------------------------
         * FLUID PIPE
         * ------------------------------------------------------------------ */

        // exposes a PER-SIDE fluid handler.
        // This is the one whose fill(...) shoves fluid sections into the pipe.
        // IMPORTANT: if side == null, return null. Pipes are directional.
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntity.FLUID_PIPE.get(),
                (FluidPipeBlockEntity be, @Nullable Direction side) -> {
                    if (side == null) return null;
                    return be.getFluidHandlerForSide(side);
                }
        );

        /* ------------------------------------------------------------------
         * REFINERY
         * ------------------------------------------------------------------ */

        // Register dummy energy capability so engines can detect and auto-rotate to refineries
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.REFINERY.get(),
                (RefineryBlockEntity be, @Nullable Direction side) -> {
                    // Return a dummy energy storage that accepts 0 energy
                    // This is just for engine auto-rotation detection
                    return new net.neoforged.neoforge.energy.IEnergyStorage() {
                        @Override
                        public int receiveEnergy(int maxReceive, boolean simulate) {
                            return 0; // Don't actually accept energy
                        }

                        @Override
                        public int extractEnergy(int maxExtract, boolean simulate) {
                            return 0;
                        }

                        @Override
                        public int getEnergyStored() {
                            return 0;
                        }

                        @Override
                        public int getMaxEnergyStored() {
                            return 0;
                        }

                        @Override
                        public boolean canExtract() {
                            return false;
                        }

                        @Override
                        public boolean canReceive() {
                            return true; // THIS is what makes engines detect it!
                        }
                    };
                }
        );

        // Register fluid handler capability for bucket interactions
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntity.REFINERY.get(),
                (RefineryBlockEntity be, @Nullable Direction side) -> be.getFluidHandlerForSide(side)
        );
    }
}
