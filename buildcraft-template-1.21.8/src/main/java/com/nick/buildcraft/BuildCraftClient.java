// src/main/java/com/nick/buildcraft/BuildCraftClient.java
package com.nick.buildcraft;

import com.nick.buildcraft.client.render.EngineRenderer;
import com.nick.buildcraft.client.render.LaserEntityRenderer;
import com.nick.buildcraft.client.render.QuarryRenderer;
import com.nick.buildcraft.client.render.StonePipeRenderer;
import com.nick.buildcraft.client.screen.StirlingEngineScreen;
import com.nick.buildcraft.client.screen.DiamondPipeScreen;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModEntities;
import com.nick.buildcraft.registry.ModMenus;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

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
        // render layer setup removed – models' "render_type" handles this now
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.STIRLING_ENGINE.get(), StirlingEngineScreen::new);
        event.register(ModMenus.DIAMOND_PIPE.get(),   DiamondPipeScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.LASER.get(), LaserEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.QUARRY_CONTROLLER.get(), QuarryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.STONE_PIPE.get(), StonePipeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntity.ENGINE.get(), EngineRenderer::new);
    }
}
