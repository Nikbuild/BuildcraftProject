package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class SteamEngineBlock extends EngineBlock {
    public static final MapCodec<SteamEngineBlock> CODEC = Block.simpleCodec(SteamEngineBlock::new);
    @Override public MapCodec<SteamEngineBlock> codec() { return CODEC; }

    public SteamEngineBlock(BlockBehaviour.Properties props) {
        super(EngineType.STEAM, props);
    }
}
