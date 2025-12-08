package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * WoodFluidPipeBlock
 *
 * Basic early-game fluid pipe. Slow throughput.
 */
public class WoodFluidPipeBlock extends BaseFluidPipeBlock {

    public WoodFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.WOOD, props);
    }

    // no extra rules yet
}
