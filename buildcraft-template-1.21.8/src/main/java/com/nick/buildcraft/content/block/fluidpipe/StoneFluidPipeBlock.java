package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * StoneFluidPipeBlock
 *
 * Vanilla/basic fluid pipe. Slow. Can’t connect to COBBLE pipes directly (handled in base).
 */
public class StoneFluidPipeBlock extends BaseFluidPipeBlock {

    public StoneFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.STONE, props);
    }

    // no extra rules yet
}
