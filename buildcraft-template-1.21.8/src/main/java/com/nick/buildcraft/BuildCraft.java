package com.nick.buildcraft;

import com.mojang.logging.LogUtils;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import com.nick.buildcraft.registry.ModEntities;
import com.nick.buildcraft.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(BuildCraft.MODID)
public class BuildCraft {
    public static final String MODID = "buildcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ---- Creative tab ----
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.buildcraft"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> ModItems.QUARRY_ITEM.get().getDefaultInstance())
                            .displayItems((params, out) -> {
                                // Blocks/items we always want visible in our tab
                                out.accept(ModItems.QUARRY_ITEM.get());

                                // All pipe variants
                                out.accept(ModItems.STONE_PIPE_ITEM.get());
                                out.accept(ModItems.COBBLE_PIPE_ITEM.get());
                                out.accept(ModItems.WOOD_PIPE_ITEM.get());
                                out.accept(ModItems.IRON_PIPE_ITEM.get());
                                out.accept(ModItems.GOLD_PIPE_ITEM.get());
                                out.accept(ModItems.DIAMOND_PIPE_ITEM.get());
                            })
                            .build()
            );

    public BuildCraft(IEventBus modEventBus, ModContainer modContainer) {
        // lifecycle
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        // registries
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntity.BLOCK_ENTITIES.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // config
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // forge/neo bus listeners
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("BuildCraft common setup ready");
    }

    /**
     * Add items to vanilla tabs as well, so players can find them without switching tabs.
     */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModItems.QUARRY_ITEM);

            // All pipe variants in vanilla Building Blocks tab
            event.accept(ModItems.STONE_PIPE_ITEM);
            event.accept(ModItems.COBBLE_PIPE_ITEM);
            event.accept(ModItems.WOOD_PIPE_ITEM);
            event.accept(ModItems.IRON_PIPE_ITEM);
            event.accept(ModItems.GOLD_PIPE_ITEM);
            event.accept(ModItems.DIAMOND_PIPE_ITEM);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("BuildCraft server starting");
    }
}
