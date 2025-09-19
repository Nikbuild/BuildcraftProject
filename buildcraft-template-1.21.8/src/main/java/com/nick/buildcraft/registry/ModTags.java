package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Centralized block/item tag keys used by the mod. */
public final class ModTags {
    private ModTags() {}

    /** Any block that should be auto-faced by engines and treated as a power sink. */
    public static final TagKey<Block> ENGINE_POWER_ACCEPTORS =
            TagKey.create(
                    Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "engine_power_acceptors")
            );

    /** Kept from your original example – not required by the engine logic here. */
    public static final TagKey<Block> HAS_INVENTORY =
            TagKey.create(
                    Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "has_inventory")
            );
}
