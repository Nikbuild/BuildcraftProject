package com.nick.buildcraft;

import com.nick.buildcraft.client.render.EngineRenderer;
import com.nick.buildcraft.client.render.LaserEntityRenderer;
import com.nick.buildcraft.client.render.MiningWellRenderer;
import com.nick.buildcraft.client.render.PumpRenderer;
import com.nick.buildcraft.client.render.QuarryRenderer;
import com.nick.buildcraft.client.render.StonePipeRenderer;
import com.nick.buildcraft.client.screen.DiamondPipeScreen;
import com.nick.buildcraft.client.screen.StirlingEngineScreen;
import com.nick.buildcraft.content.block.tank.TankBlockEntityRenderer;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import com.nick.buildcraft.registry.ModEntities;
import com.nick.buildcraft.registry.ModFluids;
import com.nick.buildcraft.registry.ModMenus;
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
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only setup.
 */
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

        event.enqueueWork(() -> {
            // Tank uses cutout (bars + holes); fluid itself is drawn translucent by BER
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.TANK.get(), ChunkSectionLayer.CUTOUT);

            // Mining Pipe is a skinny column model actually placed in-world (cutout)
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.MINING_PIPE.get(), ChunkSectionLayer.CUTOUT);

            // Pump tube is NOT a block, so no render layer registration needed.
        });
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.STIRLING_ENGINE.get(), StirlingEngineScreen::new);
        event.register(ModMenus.DIAMOND_PIPE.get(), DiamondPipeScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // entity renderer for lasers
        event.registerEntityRenderer(ModEntities.LASER.get(), LaserEntityRenderer::new);

        // BERs
        event.registerBlockEntityRenderer(ModBlockEntity.QUARRY_CONTROLLER.get(),   QuarryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.STONE_PIPE.get(),          StonePipeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.ENGINE.get(),              EngineRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.TANK.get(),                TankBlockEntityRenderer::new);

        // Mining Well BER (handles tall drill column bounding box)
        event.registerBlockEntityRenderer(ModBlockEntity.MINING_WELL.get(),         MiningWellRenderer::new);

        // Pump BER (will render the suction tube down into the fluid using pump_tube.png)
        event.registerBlockEntityRenderer(ModBlockEntity.PUMP.get(),                PumpRenderer::new);
    }

    // Attach fluid textures via client extensions
    @SubscribeEvent
    static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        // Oil
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public net.minecraft.resources.ResourceLocation getStillTexture()   { return ModFluids.OIL_STILL; }
            @Override public net.minecraft.resources.ResourceLocation getFlowingTexture() { return ModFluids.OIL_FLOW; }
        }, ModFluids.OIL_TYPE.get());

        // Fuel
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public net.minecraft.resources.ResourceLocation getStillTexture()   { return ModFluids.FUEL_STILL; }
            @Override public net.minecraft.resources.ResourceLocation getFlowingTexture() { return ModFluids.FUEL_FLOW; }
        }, ModFluids.FUEL_TYPE.get());
    }
}
