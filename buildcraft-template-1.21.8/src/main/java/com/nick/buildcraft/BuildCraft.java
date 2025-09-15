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
                            .icon(() -> ModItems.QUARRY_CONTROLLER_ITEM.get().getDefaultInstance())
                            .displayItems((params, out) -> {
                                // Always-visible items in the BuildCraft tab
                                out.accept(ModItems.QUARRY_CONTROLLER_ITEM.get());

                                // Pipes
                                out.accept(ModItems.STONE_PIPE_ITEM.get());
                                out.accept(ModItems.COBBLE_PIPE_ITEM.get());
                                out.accept(ModItems.WOOD_PIPE_ITEM.get());
                                out.accept(ModItems.IRON_PIPE_ITEM.get());
                                out.accept(ModItems.GOLD_PIPE_ITEM.get());
                                out.accept(ModItems.DIAMOND_PIPE_ITEM.get());

                                // Engines
                                out.accept(ModItems.MODEL_ITEM_REDSTONE_ENGINE.get());

                                // Gears (if present in ModItems)
                                out.accept(ModItems.GEAR_WOOD);
                                out.accept(ModItems.GEAR_STONE);
                                out.accept(ModItems.GEAR_IRON);
                                out.accept(ModItems.GEAR_GOLD);
                                out.accept(ModItems.GEAR_DIAMOND);
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
     * Also surface content in vanilla tabs so it’s easy to find.
     */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModItems.QUARRY_CONTROLLER_ITEM);

            // Pipes (vanilla Building Blocks tab)
            event.accept(ModItems.STONE_PIPE_ITEM);
            event.accept(ModItems.COBBLE_PIPE_ITEM);
            event.accept(ModItems.WOOD_PIPE_ITEM);
            event.accept(ModItems.IRON_PIPE_ITEM);
            event.accept(ModItems.GOLD_PIPE_ITEM);
            event.accept(ModItems.DIAMOND_PIPE_ITEM);

            // Engines that are also "placeable blocks"
            event.accept(ModItems.MODEL_ITEM_REDSTONE_ENGINE);

            // Gears (if present)
            event.accept(ModItems.GEAR_WOOD);
            event.accept(ModItems.GEAR_STONE);
            event.accept(ModItems.GEAR_IRON);
            event.accept(ModItems.GEAR_GOLD);
            event.accept(ModItems.GEAR_DIAMOND);
        }

        // It also makes sense to show engines in the Redstone tab
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(ModItems.MODEL_ITEM_REDSTONE_ENGINE);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("BuildCraft server starting");
    }
}
