package com.nick.buildcraft;

import com.nick.buildcraft.client.render.EngineRenderer;
import com.nick.buildcraft.client.render.FluidPipeRenderer;
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
 * Client-only setup (render layers, BERs, screens, fluid textures).
 *
 * IMPORTANT:
 * - We now register a BlockEntityRenderer for FLUID_PIPE so the
 *   moving fluid blobs actually render clientside.
 * - We also assign CUTOUT layer to the fluid pipe blocks so you
 *   can see inside them.
 */
@Mod(value = BuildCraft.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BuildCraft.MODID, value = Dist.CLIENT)
public final class BuildCraftClient {

    public BuildCraftClient(ModContainer container) {
        // Hook up mod config screen in the Mods list UI
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BuildCraft.LOGGER.info("BuildCraft client setup");

        String user = Minecraft.getInstance().getUser() != null
                ? Minecraft.getInstance().getUser().getName()
                : "<unknown>";
        BuildCraft.LOGGER.info("Logged-in player (client): {}", user);

        // Render layer setup must run on the render thread, so enqueueWork
        event.enqueueWork(() -> {
            // Tank:
            //  - The block's shell/bars are cutout.
            //  - The actual liquid column is rendered separately in TankBlockEntityRenderer.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.TANK.get(), ChunkSectionLayer.CUTOUT);

            // Mining Pipe column is a skinny frame model with holes -> cutout.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.MINING_PIPE.get(), ChunkSectionLayer.CUTOUT);

            // Fluid pipes (stone/cobble):
            //  - We want them drawn like glass rings / frames with holes.
            //  - CUTOUT makes the see-through bits work and lets us see our BER blobs inside.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.STONE_FLUID_PIPE.get(), ChunkSectionLayer.CUTOUT);
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.COBBLE_FLUID_PIPE.get(), ChunkSectionLayer.CUTOUT);

            // Pump's hanging hose is *not* a block, so no render layer registration needed.
        });
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.STIRLING_ENGINE.get(), StirlingEngineScreen::new);
        event.register(ModMenus.DIAMOND_PIPE.get(),  DiamondPipeScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        //
        // ENTITY RENDERERS
        //
        event.registerEntityRenderer(ModEntities.LASER.get(), LaserEntityRenderer::new);

        //
        // BLOCK ENTITY RENDERERS (BERs)
        //
        // Quarry controller frame/arm animation
        event.registerBlockEntityRenderer(
                ModBlockEntity.QUARRY_CONTROLLER.get(),
                QuarryRenderer::new
        );

        // Item pipes (stone / cobble / iron / gold / wood / diamond)
        // Shows traveling item stacks in the tubes
        event.registerBlockEntityRenderer(
                ModBlockEntity.STONE_PIPE.get(),
                StonePipeRenderer::new
        );

        // *** NEW: Fluid pipes (stone / cobble fluid pipes)
        // Shows moving fluid blobs inside pipes
        event.registerBlockEntityRenderer(
                ModBlockEntity.FLUID_PIPE.get(),
                FluidPipeRenderer::new
        );

        // Engines (piston animation, heat glow, etc.)
        event.registerBlockEntityRenderer(
                ModBlockEntity.ENGINE.get(),
                EngineRenderer::new
        );

        // Tank: renders internal fluid column and smooth fill animation
        event.registerBlockEntityRenderer(
                ModBlockEntity.TANK.get(),
                TankBlockEntityRenderer::new
        );

        // Mining Well: renders the tall drill stack down the hole
        event.registerBlockEntityRenderer(
                ModBlockEntity.MINING_WELL.get(),
                MiningWellRenderer::new
        );

        // Pump: renders the hanging intake hose and ring segment
        event.registerBlockEntityRenderer(
                ModBlockEntity.PUMP.get(),
                PumpRenderer::new
        );
    }

    /**
     * Per-fluid client visual data (textures etc.) for custom fluids.
     * This tells the client which still/flow sprites to use in-world,
     * in tanks, in buckets, etc.
     */
    @SubscribeEvent
    static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        // Oil
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override
            public net.minecraft.resources.ResourceLocation getStillTexture() {
                return ModFluids.OIL_STILL;
            }

            @Override
            public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                return ModFluids.OIL_FLOW;
            }
        }, ModFluids.OIL_TYPE.get());

        // Fuel
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override
            public net.minecraft.resources.ResourceLocation getStillTexture() {
                return ModFluids.FUEL_STILL;
            }

            @Override
            public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                return ModFluids.FUEL_FLOW;
            }
        }, ModFluids.FUEL_TYPE.get());
    }
}
