package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers capability providers for our blocks / block entities.
 *
 * - ItemHandler: placeholder (null until inventory is added).
 * - EnergyStorage: sink so engines can push FE into the quarry controller.
 */
@EventBusSubscriber(modid = BuildCraft.MODID) // no Bus enum on NeoForge 21
public final class ModCapabilities {
    private ModCapabilities() {}

    @SubscribeEvent
    public static void registerCaps(RegisterCapabilitiesEvent event) {
        // Quarry Controller: expose an item handler (placeholder)
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, /* @Nullable */ net.minecraft.core.Direction side) -> null
        );

        // Quarry Controller: accept FE from engines
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                (QuarryBlockEntity be, /* @Nullable */ net.minecraft.core.Direction side) -> be.getEnergyStorage()
        );
    }
}
