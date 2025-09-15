package com.nick.buildcraft;

import com.nick.buildcraft.client.render.LaserEntityRenderer;
import com.nick.buildcraft.client.render.QuarryRenderer;
import com.nick.buildcraft.client.render.RedstoneEngineRenderer;
import com.nick.buildcraft.client.render.StonePipeRenderer;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModEntities;
import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only bootstrap. Safe to reference client code here. */
@Mod(value = BuildCraft.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BuildCraft.MODID, value = Dist.CLIENT)
public final class BuildCraftClient {

    public BuildCraftClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BuildCraft.LOGGER.info("BuildCraft client setup");
        String user = Minecraft.getInstance().getUser() != null
                ? Minecraft.getInstance().getUser().getName()
                : "<unknown>";
        BuildCraft.LOGGER.info("Logged-in player (client): {}", user);

        // Ensure engine/bellows models using alpha render correctly
        event.enqueueWork(() ->
                ItemBlockRenderTypes.setRenderLayer(
                        ModBlocks.REDSTONE_ENGINE.get(),
                        ChunkSectionLayer.CUTOUT
                )
        );
    }

    /** Register entity and block-entity renderers. */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Entities
        event.registerEntityRenderer(ModEntities.LASER.get(), LaserEntityRenderer::new);

        // Block entities
        event.registerBlockEntityRenderer(ModBlockEntity.QUARRY_CONTROLLER.get(), QuarryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.STONE_PIPE.get(), StonePipeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.REDSTONE_ENGINE.get(), RedstoneEngineRenderer::new);
    }
}
