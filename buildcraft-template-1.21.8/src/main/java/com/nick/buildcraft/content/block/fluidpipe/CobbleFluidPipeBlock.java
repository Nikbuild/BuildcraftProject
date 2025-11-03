package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * CobbleFluidPipeBlock
 *
 * Classic cobble variant. Same throughput as stone for now but
 * does NOT connect to STONE (handled by canMateWith in base).
 */
public class CobbleFluidPipeBlock extends BaseFluidPipeBlock {

    public CobbleFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.COBBLE, props);
    }

    // later we could override canMateWith(...) for weird behavior if needed
}
