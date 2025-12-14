package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Combustion engine block with model-splitting PART (BASE/RING/BELLOWS). */
public class CombustionEngineBlock extends EngineBlock {
    public enum Part implements StringRepresentable {
        BASE, RING, BELLOWS;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public static final MapCodec<CombustionEngineBlock> CODEC = Block.simpleCodec(CombustionEngineBlock::new);
    @Override protected MapCodec<CombustionEngineBlock> codec() { return CODEC; }

    public CombustionEngineBlock(BlockBehaviour.Properties props) {
        super(EngineType.COMBUSTION, props);
        this.registerDefaultState(this.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(PART, Part.BASE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b); // POWERED + FACING
        b.add(PART);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CombustionEngineBlockEntity be && player instanceof ServerPlayer sp) {
            sp.openMenu(be, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CombustionEngineBlockEntity(pos, state);
    }
}
