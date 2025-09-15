package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BuildCraft.MODID);

    /* ---------- Block items ---------- */

    /** Quarry controller block item (id matches blockstate filename). */
    public static final DeferredItem<BlockItem> QUARRY_CONTROLLER_ITEM =
            ITEMS.registerSimpleBlockItem("model_item_quarry_controller", ModBlocks.QUARRY_CONTROLLER);

    /** Redstone Engine block item */
    public static final DeferredItem<BlockItem> MODEL_ITEM_REDSTONE_ENGINE =
            ITEMS.registerSimpleBlockItem("model_item_redstone_engine", ModBlocks.REDSTONE_ENGINE);

    /** Pipes */
    public static final DeferredItem<BlockItem> STONE_PIPE_ITEM =
            ITEMS.registerSimpleBlockItem("stone_pipe", ModBlocks.STONE_PIPE);
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

    /* ---------- Gear items (simple Items, not blocks) ---------- */

    public static final DeferredItem<Item> GEAR_WOOD =
            ITEMS.register("gear_wood",
                    () -> new Item(new Item.Properties().setId(
                            ResourceKey.create(
                                    Registries.ITEM,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "gear_wood")
                            )
                    )));

    public static final DeferredItem<Item> GEAR_STONE =
            ITEMS.register("gear_stone",
                    () -> new Item(new Item.Properties().setId(
                            ResourceKey.create(
                                    Registries.ITEM,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "gear_stone")
                            )
                    )));

    public static final DeferredItem<Item> GEAR_IRON =
            ITEMS.register("gear_iron",
                    () -> new Item(new Item.Properties().setId(
                            ResourceKey.create(
                                    Registries.ITEM,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "gear_iron")
                            )
                    )));

    public static final DeferredItem<Item> GEAR_GOLD =
            ITEMS.register("gear_gold",
                    () -> new Item(new Item.Properties().setId(
                            ResourceKey.create(
                                    Registries.ITEM,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "gear_gold")
                            )
                    )));

    public static final DeferredItem<Item> GEAR_DIAMOND =
            ITEMS.register("gear_diamond",
                    () -> new Item(new Item.Properties().setId(
                            ResourceKey.create(
                                    Registries.ITEM,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "gear_diamond")
                            )
                    )));
}
