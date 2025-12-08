package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * DiamondFluidPipeBlock
 *
 * Advanced routing-capable fluid pipe.
 */
public class DiamondFluidPipeBlock extends BaseFluidPipeBlock {

    public DiamondFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.DIAMOND, props);
    }

    // no extra rules yet
}
