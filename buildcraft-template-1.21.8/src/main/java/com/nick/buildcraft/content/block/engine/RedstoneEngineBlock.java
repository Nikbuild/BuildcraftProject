// src/main/java/com/nick/buildcraft/content/block/engine/RedstoneEngineBlock.java
package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.Locale;

/** Redstone engine block with model-splitting PART (BASE/RING/BELLOWS). */
public class RedstoneEngineBlock extends EngineBlock {
    public enum Part implements StringRepresentable {
        BASE, RING, BELLOWS;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public static final MapCodec<RedstoneEngineBlock> CODEC = Block.simpleCodec(RedstoneEngineBlock::new);
    @Override public MapCodec<RedstoneEngineBlock> codec() { return CODEC; }

    public RedstoneEngineBlock(BlockBehaviour.Properties props) {
        super(EngineType.REDSTONE, props);
        // IMPORTANT: start from defaultBlockState() to keep FACING/POWERED set by EngineBlock
        this.registerDefaultState(this.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE) // (already false, but explicit is fine)
                .setValue(PART, Part.BASE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b); // adds POWERED + FACING
        b.add(PART);
    }
}
