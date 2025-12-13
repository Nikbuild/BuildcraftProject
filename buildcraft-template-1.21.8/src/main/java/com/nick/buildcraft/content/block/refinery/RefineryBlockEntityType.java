package com.nick.buildcraft.content.block.refinery;

import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Helper class to provide convenient access to refinery block entity types.
 * This avoids circular dependencies and makes imports cleaner.
 */
public final class RefineryBlockEntityType {

    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<RefineryBlockEntity>> REFINERY() {
        return ModBlockEntity.REFINERY;
    }

    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<RefineryMagnetMovingBlockEntity>> REFINERY_MAGNET_MOVING() {
        return ModBlockEntity.REFINERY_MAGNET_MOVING;
    }

    private RefineryBlockEntityType() {
        // no instantiation
    }
}
