// src/main/java/com/nick/buildcraft/content/block/engine/StirlingEngineBlock.java
package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Stirling engine block with model-splitting PART (BASE/RING/BELLOWS). */
public class StirlingEngineBlock extends EngineBlock {
    public enum Part implements StringRepresentable {
        BASE, RING, BELLOWS;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public static final MapCodec<StirlingEngineBlock> CODEC = Block.simpleCodec(StirlingEngineBlock::new);
    @Override public MapCodec<StirlingEngineBlock> codec() { return CODEC; }

    public StirlingEngineBlock(BlockBehaviour.Properties props) {
        super(EngineType.STIRLING, props);
        this.registerDefaultState(this.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.BASE)
                .setValue(BlockStateProperties.LIT, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b); // adds POWERED + FACING
        b.add(PART);
        b.add(BlockStateProperties.LIT);
    }

    /* -------------------------------- placement (with snap) ------------------------------- */

    @Nullable @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        final Level level = ctx.getLevel();
        final BlockPos pos = ctx.getClickedPos();

        Direction snap = findAcceptor(level, pos);
        Direction facing = (snap != null) ? snap : facingFromHit(ctx);

        return this.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, level.hasNeighborSignal(pos))
                .setValue(FACING, facing)
                .setValue(PART, Part.BASE)
                .setValue(BlockStateProperties.LIT, Boolean.FALSE);
    }

    @Nullable
    private static Direction findAcceptor(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).is(ModTags.ENGINE_POWER_ACCEPTORS)) {
                return d;
            }
        }
        return null;
    }

    private static Direction facingFromHit(BlockPlaceContext ctx) {
        final Direction face = ctx.getClickedFace();
        final BlockPos placePos = ctx.getClickedPos();
        final var hit = ctx.getClickLocation();

        final double hx = hit.x - placePos.getX();
        final double hy = hit.y - placePos.getY();
        final double hz = hit.z - placePos.getZ();

        switch (face) {
            case UP: {
                final double dx = Math.abs(hx - 0.5);
                final double dz = Math.abs(hz - 0.5);
                final double CENTER = 0.22;
                if (dx < CENTER && dz < CENTER) return Direction.UP;
                return dx > dz ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hz > 0.5 ? Direction.SOUTH : Direction.NORTH);
            }
            case DOWN: {
                final double dx = Math.abs(hx - 0.5);
                final double dz = Math.abs(hz - 0.5);
                final double CENTER = 0.22;
                if (dx < CENTER && dz < CENTER) return Direction.DOWN;
                return dx > dz ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hz > 0.5 ? Direction.SOUTH : Direction.NORTH);
            }
            case NORTH: {
                final double dx = Math.abs(hx - 0.5);
                final double dy = Math.abs(hy - 0.5);
                return dx > dy ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hy > 0.5 ? Direction.UP   : Direction.DOWN);
            }
            case SOUTH: {
                final double dx = Math.abs(hx - 0.5);
                final double dy = Math.abs(hy - 0.5);
                return dx > dy ? (hx > 0.5 ? Direction.WEST : Direction.EAST)
                        : (hy > 0.5 ? Direction.UP   : Direction.DOWN);
            }
            case WEST: {
                final double dz = Math.abs(hz - 0.5);
                final double dy = Math.abs(hy - 0.5);
                return dz > dy ? (hz > 0.5 ? Direction.SOUTH : Direction.NORTH)
                        : (hy > 0.5 ? Direction.UP    : Direction.DOWN);
            }
            case EAST: {
                final double dz = Math.abs(hz - 0.5);
                final double dy = Math.abs(hy - 0.5);
                return dz > dy ? (hz > 0.5 ? Direction.NORTH : Direction.SOUTH)
                        : (hy > 0.5 ? Direction.UP    : Direction.DOWN);
            }
        }
        return Direction.NORTH;
    }

    /* -------------------------------- block entity / UI ----------------------------------- */

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StirlingEngineBlockEntity(pos, state);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof StirlingEngineBlockEntity be && player instanceof ServerPlayer sp) {
            sp.openMenu(be, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @SuppressWarnings("unchecked")
    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level lvl, BlockState st, BlockEntityType<T> beType) {
        if (beType != ModBlockEntity.ENGINE.get()) return null;
        BlockEntityTicker<StirlingEngineBlockEntity> ticker =
                lvl.isClientSide ? StirlingEngineBlockEntity::clientTick : StirlingEngineBlockEntity::serverTick;
        return (BlockEntityTicker<T>) ticker;
    }

    /* ----------------------- light + particles while LIT ----------------------- */

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT) ? 13 : 0;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource rand) {
        if (!state.hasProperty(BlockStateProperties.LIT) || !state.getValue(BlockStateProperties.LIT)) return;

        double cx = pos.getX() + 0.5;
        double cy = pos.getY();
        double cz = pos.getZ() + 0.5;

        if (rand.nextDouble() < 0.1) {
            level.playLocalSound(cx, cy, cz, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.6F, 1.0F, false);
        }

        Direction facing = state.getValue(FACING);
        Direction.Axis axis = facing.getAxis();
        double off = 0.52;
        double jitter = rand.nextDouble() * 0.6 - 0.3;
        double dx = axis == Direction.Axis.X ? facing.getStepX() * off : jitter;
        double dy = rand.nextDouble() * 6.0 / 16.0;
        double dz = axis == Direction.Axis.Z ? facing.getStepZ() * off : jitter;

        level.addParticle(ParticleTypes.SMOKE, cx + dx, cy + dy, cz + dz, 0.0, 0.0, 0.0);
        level.addParticle(ParticleTypes.FLAME, cx + dx, cy + dy, cz + dz, 0.0, 0.0, 0.0);
    }
}
