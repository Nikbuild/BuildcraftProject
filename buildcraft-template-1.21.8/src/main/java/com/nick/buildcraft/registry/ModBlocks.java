package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlock;
import com.nick.buildcraft.content.block.pipe.CobblePipeBlock;
import com.nick.buildcraft.content.block.pipe.StonePipeBlock;
import com.nick.buildcraft.content.block.quarry.FrameBlock;
import com.nick.buildcraft.content.block.quarry.QuarryBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BuildCraft.MODID);

    /** Quarry controller block (id intentionally matches the blockstate file name). */
    public static final DeferredBlock<Block> QUARRY_CONTROLLER = BLOCKS.register(
            "blockstate_quarry_controller",
            () -> new QuarryBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.5f)
                            .requiresCorrectToolForDrops()
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "blockstate_quarry_controller")
                            ))
            )
    );


    /** Render-only quarry frame */
    public static final DeferredBlock<Block> FRAME = BLOCKS.register(
            "frame",
            () -> new FrameBlock(
                    BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .noCollission()
                            .strength(1.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "frame")
                            ))
            )
    );

    /** Stone transport pipe */
    public static final DeferredBlock<Block> STONE_PIPE = BLOCKS.register(
            "stone_pipe",
            () -> new StonePipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "stone_pipe")
                            ))
            )
    );

    /** Cobblestone transport pipe */
    public static final DeferredBlock<Block> COBBLE_PIPE = BLOCKS.register(
            "cobble_pipe",
            () -> new CobblePipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "cobble_pipe")
                            ))
            )
    );

    // GOLD pipe
    public static final DeferredBlock<Block> GOLD_PIPE = BLOCKS.register(
            "gold_pipe",
            () -> new com.nick.buildcraft.content.block.pipe.GoldPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .noOcclusion()
                            .strength(1.5F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "gold_pipe")
                            ))
            )
    );

    // IRON pipe
    public static final DeferredBlock<Block> IRON_PIPE = BLOCKS.register(
            "iron_pipe",
            () -> new com.nick.buildcraft.content.block.pipe.IronPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .noOcclusion()
                            .strength(2.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "iron_pipe")
                            ))
            )
    );

    // DIAMOND pipe
    public static final DeferredBlock<Block> DIAMOND_PIPE = BLOCKS.register(
            "diamond_pipe",
            () -> new com.nick.buildcraft.content.block.pipe.DiamondPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.DIAMOND)
                            .noOcclusion()
                            .strength(3.0F, 9.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "diamond_pipe")
                            ))
            )
    );

    // WOOD pipe
    public static final DeferredBlock<Block> WOOD_PIPE = BLOCKS.register(
            "wood_pipe",
            () -> new com.nick.buildcraft.content.block.pipe.WoodPipeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .noOcclusion()
                            .strength(1.0F, 3.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "wood_pipe")
                            ))
            )
    );

    /* -------- New: Redstone Engine -------- */
    public static final DeferredBlock<Block> REDSTONE_ENGINE = BLOCKS.register(
            "blockstate_redstone_engine", // was "redstone_engine"
            () -> new RedstoneEngineBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(2.0F, 6.0F)
                            .setId(ResourceKey.create(
                                    Registries.BLOCK,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "blockstate_redstone_engine") // was "redstone_engine"
                            ))
            )
    );

}
