package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * GoldFluidPipeBlock
 *
 * High-speed fluid pipe. Very fast transport.
 */
public class GoldFluidPipeBlock extends BaseFluidPipeBlock {

    public GoldFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.GOLD, props);
    }

    // no extra rules yet
}
