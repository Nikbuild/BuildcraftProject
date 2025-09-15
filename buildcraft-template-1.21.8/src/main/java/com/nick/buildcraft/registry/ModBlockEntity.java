package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlockEntity;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntity {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = BLOCK_ENTITY_TYPES;

    /** Quarry controller */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuarryBlockEntity>> QUARRY_CONTROLLER =
            BLOCK_ENTITY_TYPES.register("quarry_controller",
                    () -> new BlockEntityType<>(QuarryBlockEntity::new,
                            Set.of(ModBlocks.QUARRY_CONTROLLER.get()))
            );

    /** Shared BE for all pipes */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StonePipeBlockEntity>> STONE_PIPE =
            BLOCK_ENTITY_TYPES.register("stone_pipe",
                    () -> new BlockEntityType<>(
                            StonePipeBlockEntity::new,
                            Set.of(
                                    ModBlocks.STONE_PIPE.get(),
                                    ModBlocks.COBBLE_PIPE.get(),
                                    ModBlocks.GOLD_PIPE.get(),
                                    ModBlocks.IRON_PIPE.get(),
                                    ModBlocks.DIAMOND_PIPE.get(),
                                    ModBlocks.WOOD_PIPE.get()
                            )
                    )
            );

    /** Redstone Engine */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneEngineBlockEntity>> REDSTONE_ENGINE =
            BLOCK_ENTITY_TYPES.register("redstone_engine",
                    () -> new BlockEntityType<>(RedstoneEngineBlockEntity::new,
                            Set.of(ModBlocks.REDSTONE_ENGINE.get()))
            );

    private ModBlockEntity() {}
}
