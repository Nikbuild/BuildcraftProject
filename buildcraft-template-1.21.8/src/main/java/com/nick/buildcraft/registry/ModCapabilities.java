package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Registers capability providers for our blocks / block entities.
 */
@EventBusSubscriber(modid = BuildCraft.MODID) // no Bus enum on NeoForge 21
public final class ModCapabilities {
    private ModCapabilities() {}

    @SubscribeEvent
    public static void registerCaps(RegisterCapabilitiesEvent event) {
        // Quarry: expose an item handler (placeholder for now; returns null until inventory is added)
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntity.QUARRY.get(),
                (QuarryBlockEntity be, /* @Nullable */ net.minecraft.core.Direction side) -> null
        );
    }
}
