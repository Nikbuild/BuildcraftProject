package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * IronFluidPipeBlock
 *
 * Mid-tier fluid pipe. Faster throughput than stone.
 */
public class IronFluidPipeBlock extends BaseFluidPipeBlock {

    public IronFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.IRON, props);
    }

    // no extra rules yet
}
