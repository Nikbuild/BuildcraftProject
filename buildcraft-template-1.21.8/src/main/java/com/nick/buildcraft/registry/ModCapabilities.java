package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.miningwell.MiningWellBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers capability providers for our blocks / block entities.
 *
 * - ItemHandler (Quarry): placeholder (null until inventory is added).
 * - EnergyStorage (Quarry + Mining Well): sink so engines can push FE.
 */
@EventBusSubscriber(modid = BuildCraft.MODID) // NeoForge 21: default bus is MOD
public final class ModCapabilities {
    private ModCapabilities() {}

    @SubscribeEvent
    public static void registerCaps(RegisterCapabilitiesEvent event) {
        /* ---------------- Quarry ---------------- */

        // Item handler placeholder (keep if you plan to add an inventory)
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, /* @Nullable */ net.minecraft.core.Direction side) -> null
        );

        // Accept FE from engines
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, /* @Nullable */ net.minecraft.core.Direction side) -> be.getEnergyStorage()
        );

        /* ---------------- Mining Well ---------------- */

        // Accept FE from engines (sided energy capability)
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.MINING_WELL.get(),
                (MiningWellBlockEntity be, /* @Nullable */ net.minecraft.core.Direction side) -> be.getEnergyStorage()
        );
    }
}
