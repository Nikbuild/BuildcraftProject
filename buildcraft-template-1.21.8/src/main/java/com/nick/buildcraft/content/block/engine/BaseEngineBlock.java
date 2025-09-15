package com.nick.buildcraft.content.block.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for all BuildCraft engine blocks.
 * Provides basic entity handling and structure for shared behavior.
 */
public abstract class BaseEngineBlock extends Block implements EntityBlock {

    protected BaseEngineBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Creates the block entity for this engine.
     */
    @Nullable
    @Override
    public abstract BlockEntity newBlockEntity(BlockPos pos, BlockState state);
}
