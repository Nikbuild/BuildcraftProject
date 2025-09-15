// RedstoneEngineBlockEntity.java
package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.energy.Energy;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class RedstoneEngineBlockEntity extends BaseEngineBlockEntity {

    public RedstoneEngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.REDSTONE_ENGINE.get(), pos, state);
    }

    @Override
    protected boolean isActive(BlockState state) {
        return state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    @Override
    protected int getGenerationPerTick() {
        int w = warmupTicks();
        if (w >= Energy.REDSTONE_ENGINE_WARMUP_TICKS) return Energy.REDSTONE_ENGINE_GEN_HOT;
        if (w >= Energy.REDSTONE_ENGINE_WARMUP_TICKS / 2) return Energy.REDSTONE_ENGINE_GEN_WARM;
        return Energy.REDSTONE_ENGINE_GEN_COLD;
    }

    // client: expose warmup for animation speed
    public int getWarmupClient() { return warmupTicks(); }

    public static void serverTick(net.minecraft.world.level.Level level,
                                  BlockPos pos,
                                  BlockState state,
                                  RedstoneEngineBlockEntity be) {
        BaseEngineBlockEntity.serverTick(level, pos, state, be);
    }
}
