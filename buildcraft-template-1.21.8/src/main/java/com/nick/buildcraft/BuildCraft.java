package com.nick.buildcraft;

import com.mojang.logging.LogUtils;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import com.nick.buildcraft.registry.ModCapabilities;
import com.nick.buildcraft.registry.ModEntities;
import com.nick.buildcraft.registry.ModFeatures;
import com.nick.buildcraft.registry.ModFluids;
import com.nick.buildcraft.registry.ModItems;
import com.nick.buildcraft.registry.ModMenus;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(BuildCraft.MODID)
public class BuildCraft {
    public static final String MODID = "buildcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.buildcraft"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> ModItems.QUARRY_CONTROLLER_ITEM.get().getDefaultInstance())
                            .displayItems((params, out) -> {
                                // Machines
                                out.accept(ModItems.QUARRY_CONTROLLER_ITEM.get());

                                // Item Pipes
                                out.accept(ModItems.STONE_PIPE_ITEM.get());
                                out.accept(ModItems.COBBLE_PIPE_ITEM.get());
                                out.accept(ModItems.WOOD_PIPE_ITEM.get());
                                out.accept(ModItems.IRON_PIPE_ITEM.get());
                                out.accept(ModItems.GOLD_PIPE_ITEM.get());
                                out.accept(ModItems.DIAMOND_PIPE_ITEM.get());

                                // Fluid Pipes
                                out.accept(ModItems.STONE_FLUID_PIPE_ITEM.get());
                                out.accept(ModItems.COBBLESTONE_FLUID_PIPE_ITEM.get());
                                out.accept(ModItems.WOOD_FLUID_PIPE_ITEM.get());
                                out.accept(ModItems.IRON_FLUID_PIPE_ITEM.get());
                                out.accept(ModItems.GOLD_FLUID_PIPE_ITEM.get());
                                out.accept(ModItems.DIAMOND_FLUID_PIPE_ITEM.get());

                                // Mining Well (pipe segment has no separate item form)
                                out.accept(ModItems.MINING_WELL_ITEM.get());

                                // Pump
                                out.accept(ModItems.PUMP_ITEM.get());

                                // Engines
                                out.accept(ModItems.MODEL_ITEM_REDSTONE_ENGINE.get());
                                out.accept(ModItems.MODEL_ITEM_STIRLING_ENGINE.get());
                                out.accept(ModItems.MODEL_ITEM_COMBUSTION_ENGINE.get());

                                // Tank block item (storage / multiblock)
                                out.accept(ModItems.TANK_ITEM.get());

                                // Tools
                                out.accept(ModItems.WRENCH);

                                // Fluids (buckets)
                                out.accept(ModFluids.BUCKET_OIL.get());
                                out.accept(ModFluids.BUCKET_FUEL.get());

                                // Gears
                                out.accept(ModItems.GEAR_WOOD);
                                out.accept(ModItems.GEAR_STONE);
                                out.accept(ModItems.GEAR_IRON);
                                out.accept(ModItems.GEAR_GOLD);
                                out.accept(ModItems.GEAR_DIAMOND);
                            })
                            .build()
            );

    public BuildCraft(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntity.BLOCK_ENTITIES.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModFluids.register(modEventBus);
        ModFeatures.FEATURES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // NEW: attach capability registration (fluid/tank/pump/pipe/etc)
        ModCapabilities.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("BuildCraft common setup ready");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModItems.QUARRY_CONTROLLER_ITEM);

            // Item pipes
            event.accept(ModItems.STONE_PIPE_ITEM);
            event.accept(ModItems.COBBLE_PIPE_ITEM);
            event.accept(ModItems.WOOD_PIPE_ITEM);
            event.accept(ModItems.IRON_PIPE_ITEM);
            event.accept(ModItems.GOLD_PIPE_ITEM);
            event.accept(ModItems.DIAMOND_PIPE_ITEM);

            // Fluid pipes
            event.accept(ModItems.STONE_FLUID_PIPE_ITEM);
            event.accept(ModItems.COBBLESTONE_FLUID_PIPE_ITEM);
            event.accept(ModItems.WOOD_FLUID_PIPE_ITEM);
            event.accept(ModItems.IRON_FLUID_PIPE_ITEM);
            event.accept(ModItems.GOLD_FLUID_PIPE_ITEM);
            event.accept(ModItems.DIAMOND_FLUID_PIPE_ITEM);


            // Mining Well
            event.accept(ModItems.MINING_WELL_ITEM);

            // Pump
            event.accept(ModItems.PUMP_ITEM);

            // Engines
            event.accept(ModItems.MODEL_ITEM_REDSTONE_ENGINE);
            event.accept(ModItems.MODEL_ITEM_STIRLING_ENGINE);
            event.accept(ModItems.MODEL_ITEM_COMBUSTION_ENGINE);

            // Gears
            event.accept(ModItems.GEAR_WOOD);
            event.accept(ModItems.GEAR_STONE);
            event.accept(ModItems.GEAR_IRON);
            event.accept(ModItems.GEAR_GOLD);
            event.accept(ModItems.GEAR_DIAMOND);
        }

        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(ModItems.MODEL_ITEM_REDSTONE_ENGINE);
            event.accept(ModItems.MODEL_ITEM_STIRLING_ENGINE);
            event.accept(ModItems.MODEL_ITEM_COMBUSTION_ENGINE);
        }

        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.WRENCH);
        }
    }

    private void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        LOGGER.info("BuildCraft server starting");
    }
}
