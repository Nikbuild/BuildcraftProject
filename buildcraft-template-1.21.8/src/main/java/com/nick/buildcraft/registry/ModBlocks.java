package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.CombustionEngineBlock;
import com.nick.buildcraft.content.block.engine.MovingEngineRingBlock;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlock;
import com.nick.buildcraft.content.block.engine.StirlingEngineBlock;
import com.nick.buildcraft.content.block.fluidpipe.*;
import com.nick.buildcraft.content.block.miningwell.MiningPipeBlock;
import com.nick.buildcraft.content.block.miningwell.MiningWellBlock;
import com.nick.buildcraft.content.block.pipe.CobblePipeBlock;
import com.nick.buildcraft.content.block.pipe.DiamondPipeBlock;
import com.nick.buildcraft.content.block.pipe.GoldPipeBlock;
import com.nick.buildcraft.content.block.pipe.IronPipeBlock;
import com.nick.buildcraft.content.block.pipe.StonePipeBlock;
import com.nick.buildcraft.content.block.pipe.WoodPipeBlock;
import com.nick.buildcraft.content.block.pump.PumpBlock;
import com.nick.buildcraft.content.block.pump.PumpTubeSegmentBlock;
import com.nick.buildcraft.content.block.quarry.FrameBlock;
import com.nick.buildcraft.content.block.quarry.QuarryBlock;
import com.nick.buildcraft.content.block.refinery.RefineryBlock;
import com.nick.buildcraft.content.block.tank.TankBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All BuildCraft blocks.
 *
 * Notes:
 *  - Some registry names start with "blockstate_" to match the asset folder names you already have.
 *  - Helper blocks (frame, mining_pipe, moving_engine_ring, pump_tube_segment) are not intended
 *    to be normal placeable blocks with items.
 *  - For pipes (item + fluid), each concrete pipe variant gets its own DeferredBlock. The BE type
 *    groups them.
 */
public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BuildCraft.MODID);

    /* --------------------------------------------------------------------- */
    /* Quarry controller + frame                                             */
    /* --------------------------------------------------------------------- */

    public static final DeferredBlock<Block> QUARRY_CONTROLLER = BLOCKS.register(
            "blockstate_quarry_controller",
            () -> new QuarryBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.5f)
                            .requiresCorrectToolForDrops()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "blockstate_quarry_controller"
                                    )
                            ))
            )
    );

    /** Structural frame piece the quarry builds and the renderer also fakes visually. */
    public static final DeferredBlock<Block> FRAME = BLOCKS.register(
            "frame",
            () -> new FrameBlock(
                    BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .noCollission()
                            .strength(1.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "frame"
                                    )
                            ))
            )
    );

    /* --------------------------------------------------------------------- */
    /* Item transport pipes (items)                                          */
    /* --------------------------------------------------------------------- */

    public static final DeferredBlock<Block> STONE_PIPE = BLOCKS.register(
            "stone_pipe",
            () -> new StonePipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "stone_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> COBBLE_PIPE = BLOCKS.register(
            "cobble_pipe",
            () -> new CobblePipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "cobble_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> GOLD_PIPE = BLOCKS.register(
            "gold_pipe",
            () -> new GoldPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "gold_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> IRON_PIPE = BLOCKS.register(
            "iron_pipe",
            () -> new IronPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .noOcclusion()
                            .strength(2.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "iron_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> DIAMOND_PIPE = BLOCKS.register(
            "diamond_pipe",
            () -> new DiamondPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.DIAMOND)
                            .noOcclusion()
                            .strength(3.0F, 9.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "diamond_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> WOOD_PIPE = BLOCKS.register(
            "wood_pipe",
            () -> new WoodPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .noOcclusion()
                            .strength(1.0F, 3.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "wood_pipe"
                                    )
                            ))
            )
    );

    /* --------------------------------------------------------------------- */
    /* Fluid transport pipes (fluids)                                        */
    /* --------------------------------------------------------------------- */

    // Basic BuildCraft fluid pipes: stone and cobble versions.
    // These correspond to StoneFluidPipeBlock / CobbleFluidPipeBlock, which subclass BaseFluidPipeBlock.

    public static final DeferredBlock<Block> STONE_FLUID_PIPE = BLOCKS.register(
            "stone_fluid_pipe",
            () -> new StoneFluidPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "stone_fluid_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> WOOD_FLUID_PIPE = BLOCKS.register(
            "wood_fluid_pipe",
            () -> new WoodFluidPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .noOcclusion()
                            .strength(1.0F, 3.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "wood_fluid_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> IRON_FLUID_PIPE = BLOCKS.register(
            "iron_fluid_pipe",
            () -> new IronFluidPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .noOcclusion()
                            .strength(2.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "iron_fluid_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> GOLD_FLUID_PIPE = BLOCKS.register(
            "gold_fluid_pipe",
            () -> new GoldFluidPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "gold_fluid_pipe"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> DIAMOND_FLUID_PIPE = BLOCKS.register(
            "diamond_fluid_pipe",
            () -> new DiamondFluidPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.DIAMOND)
                            .noOcclusion()
                            .strength(3.0F, 9.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "diamond_fluid_pipe"
                                    )
                            ))
            )
    );


    public static final DeferredBlock<Block> COBBLESTONE_FLUID_PIPE = BLOCKS.register(
            "cobblestone_fluid_pipe",
            () -> new CobbleStoneFluidPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "cobblestone_fluid_pipe"
                                    )
                            ))
            )
    );


    /* --------------------------------------------------------------------- */
    /* Engines                                                                */
    /* --------------------------------------------------------------------- */

    public static final DeferredBlock<Block> REDSTONE_ENGINE = BLOCKS.register(
            "blockstate_redstone_engine",
            () -> new RedstoneEngineBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(2.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "blockstate_redstone_engine"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> STIRLING_ENGINE = BLOCKS.register(
            "blockstate_stirling_engine",
            () -> new StirlingEngineBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_LIGHT_GRAY)
                            .strength(2.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "blockstate_stirling_engine"
                                    )
                            ))
            )
    );

    public static final DeferredBlock<Block> COMBUSTION_ENGINE = BLOCKS.register(
            "blockstate_combustion_engine",
            () -> new CombustionEngineBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_ORANGE)
                            .strength(3.0F, 9.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "blockstate_combustion_engine"
                                    )
                            ))
            )
    );

    /**
     * Invisible helper block used only during the engine's moving ring animation.
     * No item, no drops, no occlusion. It's basically like a piston head.
     */
    public static final DeferredBlock<Block> MOVING_ENGINE_RING = BLOCKS.register(
            "moving_engine_ring",
            () -> new MovingEngineRingBlock(
                    BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .strength(0.5F)
                            .noLootTable()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "moving_engine_ring"
                                    )
                            ))
            )
    );

    /* --------------------------------------------------------------------- */
    /* Tank                                                                   */
    /* --------------------------------------------------------------------- */

    public static final DeferredBlock<Block> TANK = BLOCKS.register(
            "blockstate_tank",
            () -> new TankBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .noOcclusion()
                            .strength(2.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "blockstate_tank"
                                    )
                            ))
            )
    );

    /* --------------------------------------------------------------------- */
    /* Mining Well (drill)                                                   */
    /* --------------------------------------------------------------------- */

    // id MUST be "mining_well" because assets depend on that exact name
    public static final DeferredBlock<Block> MINING_WELL = BLOCKS.register(
            "mining_well",
            () -> new MiningWellBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "mining_well"
                                    )
                            ))
            )
    );

    /**
     * Thin drill pipe column that the Mining Well actually places downward in-world.
     * No item form; mostly visual / functional column.
     */
    public static final DeferredBlock<Block> MINING_PIPE = BLOCKS.register(
            "mining_pipe",
            () -> new MiningPipeBlock(
                    BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .strength(1.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "mining_pipe"
                                    )
                            ))
            )
    );

    /* --------------------------------------------------------------------- */
    /* Refinery                                                             */
    /* --------------------------------------------------------------------- */

    public static final DeferredBlock<Block> REFINERY = BLOCKS.register(
            "refinery",
            () -> new RefineryBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0F, 6.0F)
                            .noOcclusion()      // <--- FIXES SEE-THROUGH ISSUE
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "refinery"
                                    )
                            ))
            )
    );



    /* --------------------------------------------------------------------- */
    /* Pump machine + its visual-only hose segments                          */
    /* --------------------------------------------------------------------- */

    /** The actual pump controller block you place in the world. */
    public static final DeferredBlock<Block> PUMP = BLOCKS.register(
            "pump",
            () -> new PumpBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "pump"
                                    )
                            ))
            )
    );

    /**
     * One visual "hose segment" for the suction tube.
     * We NEVER place this in the world. It's just for baked-model rendering in PumpRenderer.
     *
     * No item, no loot table, no occlusion.
     */
    public static final DeferredBlock<Block> PUMP_TUBE_SEGMENT = BLOCKS.register(
            "pump_tube_segment",
            () -> new PumpTubeSegmentBlock(
                    BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .noCollission()
                            .strength(1.0F)
                            .noLootTable()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(
                                            BuildCraft.MODID,
                                            "pump_tube_segment"
                                    )
                            ))
            )
    );
}
