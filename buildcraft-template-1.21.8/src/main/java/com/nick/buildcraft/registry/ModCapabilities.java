package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.miningwell.MiningWellBlockEntity;
import com.nick.buildcraft.content.block.pump.PumpBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Capability hookups so engines can push FE, etc.
 *
 * Quarry: item handler stub + energy sink
 * Mining Well: energy sink
 * Pump: energy sink
 */
@EventBusSubscriber(modid = BuildCraft.MODID)
public final class ModCapabilities {
    private ModCapabilities() {}

    @SubscribeEvent
    public static void registerCaps(RegisterCapabilitiesEvent event) {

        /* ---------------- Quarry ---------------- */

        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, Direction side) -> null // placeholder for future inventory
        );

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, Direction side) -> be.getEnergyStorage()
        );

        /* ---------------- Mining Well ---------------- */

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.MINING_WELL.get(),
                (MiningWellBlockEntity be, Direction side) -> be.getEnergyStorage()
        );

        /* ---------------- Pump ---------------- */

        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.PUMP.get(),
                (PumpBlockEntity be, Direction side) -> be.getEnergyStorage()
        );
    }
}
