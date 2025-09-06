package com.nick.buildcraft;

import com.nick.buildcraft.client.render.LaserEntityRenderer;
import com.nick.buildcraft.client.render.QuarryRenderer;
import com.nick.buildcraft.client.render.StonePipeRenderer;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModEntities;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only bootstrap. Safe to reference client code here.
 */
@Mod(value = BuildCraft.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BuildCraft.MODID, value = Dist.CLIENT)
public final class BuildCraftClient {

    public BuildCraftClient(ModContainer container) {
        // Config screen entry in the Mods list
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /** Runs once during client setup (avoid resolving DeferredHolders here). */
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BuildCraft.LOGGER.info("BuildCraft client setup");
        String user = Minecraft.getInstance().getUser() != null
                ? Minecraft.getInstance().getUser().getName()
                : "<unknown>";
        BuildCraft.LOGGER.info("Logged-in player (client): {}", user);
    }

    /** Register entity and block-entity renderers. */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Entities
        event.registerEntityRenderer(ModEntities.LASER.get(), LaserEntityRenderer::new);

        // Block entities
        event.registerBlockEntityRenderer(ModBlockEntity.QUARRY.get(), QuarryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.STONE_PIPE.get(), StonePipeRenderer::new); // animated cargo in pipes
    }
}
