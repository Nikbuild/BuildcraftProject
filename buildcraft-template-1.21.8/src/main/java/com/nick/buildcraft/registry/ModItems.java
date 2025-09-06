package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BuildCraft.MODID);

    /** Quarry block item */
    public static final DeferredItem<BlockItem> QUARRY_ITEM =
            ITEMS.registerSimpleBlockItem("quarry", ModBlocks.QUARRY);

    /** Stone pipe block item */
    public static final DeferredItem<BlockItem> STONE_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("stone_pipe", ModBlocks.STONE_PIPE);

    /** Cobblestone pipe block item */
    public static final DeferredItem<BlockItem> COBBLE_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("cobble_pipe", ModBlocks.COBBLE_PIPE);

    public static final DeferredItem<BlockItem> GOLD_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("gold_pipe", ModBlocks.GOLD_PIPE);

    public static final DeferredItem<BlockItem> IRON_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("iron_pipe", ModBlocks.IRON_PIPE);

    public static final DeferredItem<BlockItem> DIAMOND_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("diamond_pipe", ModBlocks.DIAMOND_PIPE);

    public static final DeferredItem<BlockItem> WOOD_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("wood_pipe", ModBlocks.WOOD_PIPE);


    // (Intentionally no Frame item; frames are placed by the quarry itself.)
}
