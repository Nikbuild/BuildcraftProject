package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModFluids {
    private ModFluids() {}

    /* ---------------- Registers ---------------- */
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, BuildCraft.MODID);
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, BuildCraft.MODID);
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BuildCraft.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BuildCraft.MODID);

    /* ---------------- Texture RLs (used from client event) ---------------- */
    public static final ResourceLocation OIL_STILL  =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "block/fluids/oil_heat_0_still");
    public static final ResourceLocation OIL_FLOW   =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "block/fluids/oil_heat_0_flow");
    public static final ResourceLocation FUEL_STILL =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "block/fluids/fuel_light_heat_0_still");
    public static final ResourceLocation FUEL_FLOW  =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "block/fluids/fuel_light_heat_0_flow");

    /* =========================================================
       OIL
       ========================================================= */
    public static final DeferredHolder<FluidType, FluidType> OIL_TYPE =
            FLUID_TYPES.register("oil", () ->
                    new FluidType(FluidType.Properties.create()
                            .viscosity(3000)
                            .density(900)
                            .temperature(300)
                            .canSwim(true)
                            .supportsBoating(true)
                            .fallDistanceModifier(0.9f)));

    public static final DeferredHolder<Fluid, FlowingFluid> OIL =
            FLUIDS.register("oil", () -> new BaseFlowingFluid.Source(oilProps()));
    public static final DeferredHolder<Fluid, FlowingFluid> FLOWING_OIL =
            FLUIDS.register("flowing_oil", () -> new BaseFlowingFluid.Flowing(oilProps()));

    public static final DeferredHolder<Block, LiquidBlock> OIL_BLOCK =
            BLOCKS.register("oil", key -> new LiquidBlock(
                    OIL.get(),
                    Block.Properties.of()
                            .noCollission()
                            .strength(100.0F)
                            .noLootTable()
                            .setId(ResourceKey.create(Registries.BLOCK, key))
            ));

    public static final DeferredHolder<Item, Item> BUCKET_OIL =
            ITEMS.register("bucket_oil", key -> new BucketItem(
                    OIL.get(),
                    new Item.Properties()
                            .stacksTo(1)
                            .craftRemainder(Items.BUCKET)
                            .setId(ResourceKey.create(Registries.ITEM, key))
            ));

    private static BaseFlowingFluid.Properties oilProps() {
        return new BaseFlowingFluid.Properties(
                OIL_TYPE::get,
                OIL::get,
                FLOWING_OIL::get
        )
                .bucket(BUCKET_OIL::get)
                .block(OIL_BLOCK::get)
                .levelDecreasePerBlock(1)
                .slopeFindDistance(4)
                .tickRate(5);
    }

    /* =========================================================
       FUEL
       ========================================================= */
    public static final DeferredHolder<FluidType, FluidType> FUEL_TYPE =
            FLUID_TYPES.register("fuel", () ->
                    new FluidType(FluidType.Properties.create()
                            .viscosity(1200)
                            .density(800)
                            .temperature(350)
                            .canSwim(true)
                            .supportsBoating(true)
                            .fallDistanceModifier(0.9f)));

    public static final DeferredHolder<Fluid, FlowingFluid> FUEL =
            FLUIDS.register("fuel", () -> new BaseFlowingFluid.Source(fuelProps()));
    public static final DeferredHolder<Fluid, FlowingFluid> FLOWING_FUEL =
            FLUIDS.register("flowing_fuel", () -> new BaseFlowingFluid.Flowing(fuelProps()));

    public static final DeferredHolder<Block, LiquidBlock> FUEL_BLOCK =
            BLOCKS.register("fuel", key -> new LiquidBlock(
                    FUEL.get(),
                    Block.Properties.of()
                            .noCollission()
                            .strength(100.0F)
                            .noLootTable()
                            .setId(ResourceKey.create(Registries.BLOCK, key))
            ));

    public static final DeferredHolder<Item, Item> BUCKET_FUEL =
            ITEMS.register("bucket_fuel", key -> new BucketItem(
                    FUEL.get(),
                    new Item.Properties()
                            .stacksTo(1)
                            .craftRemainder(Items.BUCKET)
                            .setId(ResourceKey.create(Registries.ITEM, key))
            ));

    private static BaseFlowingFluid.Properties fuelProps() {
        return new BaseFlowingFluid.Properties(
                FUEL_TYPE::get,
                FUEL::get,
                FLOWING_FUEL::get
        )
                .bucket(BUCKET_FUEL::get)
                .block(FUEL_BLOCK::get)
                .levelDecreasePerBlock(1)
                .slopeFindDistance(4)
                .tickRate(5);
    }

    /* ---------------- Register hook ---------------- */
    public static void register(IEventBus modEventBus) {
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    /* --- Client-only: wire textures for fluid rendering --- */
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        // Oil
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture()   { return OIL_STILL; }
            @Override public ResourceLocation getFlowingTexture() { return OIL_FLOW; }
        }, OIL_TYPE.get());

        // Fuel
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture()   { return FUEL_STILL; }
            @Override public ResourceLocation getFlowingTexture() { return FUEL_FLOW; }
        }, FUEL_TYPE.get());
    }

    /* ---------------- Engine-facing energy metadata ---------------- */
    public static final class EnergyValues {
        public static final int OIL_MJ_PER_BUCKET  = 60_000;
        public static final int FUEL_MJ_PER_BUCKET = 180_000;

        public static int getForFluid(Supplier<? extends Fluid> fluid) {
            Fluid f = fluid.get();
            if (f == OIL.get() || f == FLOWING_OIL.get())  return OIL_MJ_PER_BUCKET;
            if (f == FUEL.get() || f == FLOWING_FUEL.get()) return FUEL_MJ_PER_BUCKET;
            return 0;
        }

        private EnergyValues() {}
    }
}
