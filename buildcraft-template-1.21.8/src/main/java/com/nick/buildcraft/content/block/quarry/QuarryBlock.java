package com.nick.buildcraft.content.block.quarry;

import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Quarry controller block.
 *
 * Responsibilities (no LASER preview):
 *  - Maintains FACING/POWERED state.
 *  - Schedules a polling tick to keep POWERED synced.
 *  - Creates the {@link QuarryBlockEntity} which builds the frame and mines.
 *  - Clears any frame pieces within its bounding box when removed or re-placed.
 */
public class QuarryBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    private static final int HALF   = 5; // 11x11 footprint
    private static final int HEIGHT = 5; // gantry height above controller

    public QuarryBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState()
                .setValue(FACING, ctx.getHorizontalDirection())
                .setValue(POWERED, ctx.getLevel().hasNeighborSignal(ctx.getClickedPos()));
    }

    /* -------------------------------------------------------------------------------------------------------------- */
    /* Lifecycle                                                                                                      */
    /* -------------------------------------------------------------------------------------------------------------- */

    // On place: clear any leftover frame in the area and start redstone polling
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        if (level.isClientSide) return;
        clearStructure(level, pos, state);
        level.scheduleTick(pos, this, 1); // begin polling; reschedules itself
    }

    // Simple polling loop for redstone power changes so POWERED stays in sync
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand) {
        boolean nowPowered = level.hasNeighborSignal(pos);
        if (nowPowered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, nowPowered), Block.UPDATE_CLIENTS);
            // no preview to rebuild; the BE reads power directly and reacts
        }
        level.scheduleTick(pos, this, 2); // keep polling
    }

    // Central cleanup: runs for ALL removals (player, explosion, piston, etc.)
    @Override
    public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            clearStructure(serverLevel, pos, state);
        }
        super.destroy(level, pos, state);
    }

    /* -------------------------------------------------------------------------------------------------------------- */
    /* BlockEntity                                                                                                    */
    /* -------------------------------------------------------------------------------------------------------------- */

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBlockEntity.QUARRY.get().create(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ModBlockEntity.QUARRY.get() && !level.isClientSide
                ? (lvl, p, st, be) -> QuarryBlockEntity.serverTick(lvl, p, st, (QuarryBlockEntity) be)
                : null;
    }

    /* -------------------------------------------------------------------------------------------------------------- */
    /* Helpers                                                                                                        */
    /* -------------------------------------------------------------------------------------------------------------- */

    /** Remove any frame pieces inside this quarry’s bounding box. */
    private void clearStructure(Level level, BlockPos pos, BlockState state) {
        Bounds b = boundsForFacing(pos, state.getValue(FACING));
        for (BlockPos p : frameEdges(b.min(), b.max())) {
            if (level.getBlockState(p).is(ModBlocks.FRAME.get())) {
                level.removeBlock(p, false);
            }
        }
    }

    /** Bounds for the gantry/frame region adjacent to the quarry, based on facing. */
    private static Bounds boundsForFacing(BlockPos pos, Direction facing) {
        final int size = 2 * HALF + 1; // 11
        int x0, x1, z0, z1;
        int y0 = pos.getY(), y1 = pos.getY() + HEIGHT;

        switch (facing) {
            case NORTH -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() - size; z1 = pos.getZ() - 1; }
            case SOUTH -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() + 1;    z1 = pos.getZ() + size; }
            case WEST  -> { x0 = pos.getX() - size; x1 = pos.getX() - 1;    z0 = pos.getZ() - HALF; z1 = pos.getZ() + HALF; }
            case EAST  -> { x0 = pos.getX() + 1;    x1 = pos.getX() + size; z0 = pos.getZ() - HALF; z1 = pos.getZ() + HALF; }
            default    -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() - size; z1 = pos.getZ() - 1; }
        }
        return new Bounds(x0, y0, z0, x1, y1, z1);
    }

    /** Edge coordinates of a rectangular prism (no interior). */
    private static Iterable<BlockPos> frameEdges(BlockPos min, BlockPos max) {
        List<BlockPos> out = new ArrayList<>();
        int x0 = Math.min(min.getX(), max.getX()), x1 = Math.max(min.getX(), max.getX());
        int y0 = Math.min(min.getY(), max.getY()), y1 = Math.max(min.getY(), max.getY());
        int z0 = Math.min(min.getZ(), max.getZ()), z1 = Math.max(min.getZ(), max.getZ());

        for (int y : new int[]{y0, y1})
            for (int z : new int[]{z0, z1})
                for (int x = x0; x <= x1; x++) out.add(new BlockPos(x, y, z));

        for (int y : new int[]{y0, y1})
            for (int x : new int[]{x0, x1})
                for (int z = z0; z <= z1; z++) out.add(new BlockPos(x, y, z));

        for (int x : new int[]{x0, x1})
            for (int z : new int[]{z0, z1})
                for (int y = y0; y <= y1; y++) out.add(new BlockPos(x, y, z));

        return out;
    }

    /* ----- small record for bounds ----- */
    private record Bounds(int x0, int y0, int z0, int x1, int y1, int z1) {
        BlockPos min() { return new BlockPos(x0, y0, z0); }
        BlockPos max() { return new BlockPos(x1, y1, z1); }
    }
}
