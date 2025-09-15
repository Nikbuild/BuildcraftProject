// RedstoneEngineBlock.java
package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

/** Simple engine block powered by redstone. */
public class RedstoneEngineBlock extends BaseEngineBlock implements EntityBlock {

    // --- PART property (BASE/RING/BELLOWS) ---
    public enum Part implements StringRepresentable {
        BASE, RING, BELLOWS;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /* --- Codec (matches BaseEntityBlock signature) --- */
    public static final MapCodec<RedstoneEngineBlock> CODEC =
            Block.simpleCodec(RedstoneEngineBlock::new);

    @Override
    protected MapCodec<RedstoneEngineBlock> codec() {
        return CODEC;
    }

    /** IMPORTANT: required by Block.simpleCodec(Function<Properties, B>) */
    public RedstoneEngineBlock(BlockBehaviour.Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(PART, Part.BASE));
    }

    /* --- State --- */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); // keep props from BaseEngineBlock (e.g., FACING)
        builder.add(BlockStateProperties.POWERED, PART);
    }

    /* --- Redstone updates (NeoForge 1.21+ uses Orientation) --- */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(BlockStateProperties.POWERED) != powered) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), Block.UPDATE_CLIENTS);
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    /* --- Block Entity --- */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RedstoneEngineBlockEntity(pos, state);
    }

    /* --- Server ticker only --- */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntity.REDSTONE_ENGINE.get()
                ? (lvl, p, st, be) -> RedstoneEngineBlockEntity.serverTick(lvl, p, st, (RedstoneEngineBlockEntity) be)
                : null;
    }
}
