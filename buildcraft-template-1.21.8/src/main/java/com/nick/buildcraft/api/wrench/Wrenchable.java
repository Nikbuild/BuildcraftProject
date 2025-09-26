package com.nick.buildcraft.api.wrench;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Blocks that want to react to the wrench implement this. */
public interface Wrenchable {
    InteractionResult onWrench(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit);
}
