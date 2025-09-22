package com.nick.buildcraft;

import com.nick.buildcraft.client.render.LaserEntityRenderer;
import com.nick.buildcraft.client.render.QuarryRenderer;
import com.nick.buildcraft.client.render.EngineRenderer;
import com.nick.buildcraft.client.render.StonePipeRenderer;
import com.nick.buildcraft.client.screen.StirlingEngineScreen;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import com.nick.buildcraft.registry.ModEntities;
import com.nick.buildcraft.registry.ModMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;   // ← no bus param in this annotation
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = BuildCraft.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BuildCraft.MODID, value = Dist.CLIENT) // ← removed “bus = …”
public final class BuildCraftClient {

    public BuildCraftClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent // IModBusEvent → routed to mod bus automatically
    static void onClientSetup(FMLClientSetupEvent event) {
        BuildCraft.LOGGER.info("BuildCraft client setup");
        String user = Minecraft.getInstance().getUser() != null
                ? Minecraft.getInstance().getUser().getName()
                : "<unknown>";
        BuildCraft.LOGGER.info("Logged-in player (client): {}", user);

        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.REDSTONE_ENGINE.get(), ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.STIRLING_ENGINE.get(), ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.COMBUSTION_ENGINE.get(), ChunkSectionLayer.CUTOUT);

            // pipes: allow both cutout + cutout_mipped
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.STONE_PIPE.get(),   ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.STONE_PIPE.get(),   ChunkSectionLayer.CUTOUT_MIPPED);

            ItemBlockRenderTypes.setRenderLayer(ModBlocks.COBBLE_PIPE.get(),  ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.COBBLE_PIPE.get(),  ChunkSectionLayer.CUTOUT_MIPPED);

            ItemBlockRenderTypes.setRenderLayer(ModBlocks.GOLD_PIPE.get(),    ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.GOLD_PIPE.get(),    ChunkSectionLayer.CUTOUT_MIPPED);

            ItemBlockRenderTypes.setRenderLayer(ModBlocks.IRON_PIPE.get(),    ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.IRON_PIPE.get(),    ChunkSectionLayer.CUTOUT_MIPPED);

            ItemBlockRenderTypes.setRenderLayer(ModBlocks.DIAMOND_PIPE.get(), ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.DIAMOND_PIPE.get(), ChunkSectionLayer.CUTOUT_MIPPED);

            ItemBlockRenderTypes.setRenderLayer(ModBlocks.WOOD_PIPE.get(),    ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.WOOD_PIPE.get(),    ChunkSectionLayer.CUTOUT_MIPPED);
        });
    }

    @SubscribeEvent // IModBusEvent → mod bus
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.STIRLING_ENGINE.get(), StirlingEngineScreen::new);
    }

    @SubscribeEvent // global bus (non-IModBusEvent)
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.LASER.get(), LaserEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.QUARRY_CONTROLLER.get(), QuarryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.STONE_PIPE.get(), StonePipeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.ENGINE.get(), EngineRenderer::new);
    }
}
