package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    private ModTags() {}

    public static final TagKey<Block> HAS_INVENTORY =
            TagKey.create(
                    Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "has_inventory")
            );
}
