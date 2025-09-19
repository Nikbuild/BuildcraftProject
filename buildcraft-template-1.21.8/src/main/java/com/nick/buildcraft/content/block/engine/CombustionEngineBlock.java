package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class CombustionEngineBlock extends EngineBlock {
    public static final MapCodec<CombustionEngineBlock> CODEC = Block.simpleCodec(CombustionEngineBlock::new);
    @Override public MapCodec<CombustionEngineBlock> codec() { return CODEC; }

    public CombustionEngineBlock(BlockBehaviour.Properties props) {
        super(EngineType.COMBUSTION, props);
    }
}
