package com.nick.buildcraft.content.block.miningwell;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Skinny drill “pipe” column. No drops; not a full cube. */
public class MiningPipeBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(6, 0, 6, 10, 16, 10); // centered 4×16×4

    public MiningPipeBlock(BlockBehaviour.Properties props) {
        super(props.noOcclusion().noLootTable());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public boolean isCollisionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }
}
