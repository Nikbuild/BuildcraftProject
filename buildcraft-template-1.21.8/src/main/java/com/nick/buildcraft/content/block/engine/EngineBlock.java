// src/main/java/com/nick/buildcraft/content/block/engine/EngineBlock.java
package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

public class EngineBlock extends BaseEngineBlock {

    /* ------------------------------- shapes ------------------------------- */

    private static final VoxelShape BASE = box(1, 0, 1, 15, 4, 15);
    private static final VoxelShape CORE = box(5, 4, 5, 11, 12, 11);
    private static final VoxelShape BASE_PLUS_CORE = Shapes.joinUnoptimized(BASE, CORE, BooleanOp.OR);

    private static VoxelShape combine(VoxelShape ring) {
        return Shapes.joinUnoptimized(BASE_PLUS_CORE, ring, BooleanOp.OR);
    }

    /* ------------------------------ state ------------------------------- */

    /** Output direction (engine core points this way). */
    public static final EnumProperty<Direction> FACING =
            EnumProperty.create("facing", Direction.class, Direction.values());

    private final EngineType type;

    public EngineBlock(EngineType type, BlockBehaviour.Properties props) {
        super(props);
        this.type = type;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(FACING, Direction.NORTH));
    }

    public EngineType engineType() { return type; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(BlockStateProperties.POWERED);
        b.add(FACING);
    }

    /* ---------------------------- placement ---------------------------- */

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();

        // 1) Prefer pointing at a known acceptor (so the core “touches” it).
        Direction best = findAcceptorFirst(level, pos);

        // 2) Otherwise, honor the player's click-aim (supports vertical).
        if (best == null) best = faceFromClick(ctx);

        // 3) Final fallback: away from the face we clicked.
        if (best == null) best = ctx.getClickedFace().getOpposite();

        return defaultBlockState()
                .setValue(BlockStateProperties.POWERED, level.hasNeighborSignal(pos))
                .setValue(FACING, best);
    }

    /** After placement, re-evaluate a couple times to catch late BE capability init. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            updateFacing(level, pos, state);
            level.scheduleTick(pos, this, 1);
            level.scheduleTick(pos, this, 4);
        }
    }

    /** Scheduled retries from {@link #setPlacedBy}. */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        updateFacing(level, pos, state);
        // If still not pointed at a sink, try again shortly.
        if (!isPowerAcceptor(level, pos, state.getValue(FACING))) {
            level.scheduleTick(pos, this, 4);
        }
    }

    /* --------------------- player click → direction --------------------- */

    // “Near center” window (in blocks) used on TOP/BOTTOM clicks to choose vertical.
    private static final double CENTER_EPS = 4.0 / 16.0; // 4px

    /** Map the exact click location to a facing. Supports UP/DOWN as well. */
    @Nullable
    private static Direction faceFromClick(BlockPlaceContext ctx) {
        final Direction clickedFace = ctx.getClickedFace();
        final BlockPos pos = ctx.getClickedPos();

        // Position relative to block center in [-0.5..0.5] on each axis.
        Vec3 rel = ctx.getClickLocation().subtract(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        double dx = rel.x, dy = rel.y, dz = rel.z;

        switch (clickedFace.getAxis()) {
            case Y: // clicking top/bottom
                // Near the middle of the face -> aim vertical (toward the face).
                if (Math.abs(dx) <= CENTER_EPS && Math.abs(dz) <= CENTER_EPS) {
                    return clickedFace; // UP for top, DOWN for bottom
                }
                // Otherwise choose a horizontal based on dominant component.
                if (Math.abs(dx) >= Math.abs(dz)) {
                    return dx >= 0 ? Direction.EAST : Direction.WEST;
                } else {
                    return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
                }

            case X: // clicking east/west side
                // If near top/bottom band and dominates, aim vertical.
                if (Math.abs(dy) > Math.abs(dz) && Math.abs(dy) > 0.25) {
                    return dy >= 0 ? Direction.UP : Direction.DOWN;
                }
                // Else if away from vertical, let Z dominate for north/south.
                if (Math.abs(dz) > 0.25) {
                    return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
                }
                // Otherwise, aim into the block we clicked.
                return clickedFace;

            case Z: // clicking north/south side
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 0.25) {
                    return dy >= 0 ? Direction.UP : Direction.DOWN;
                }
                if (Math.abs(dx) > 0.25) {
                    return dx >= 0 ? Direction.EAST : Direction.WEST;
                }
                return clickedFace;
        }
        return null;
    }

    /* ------------------------ neighbor + power ------------------------- */

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, @Nullable Orientation o, boolean movedByPiston) {
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(BlockStateProperties.POWERED) != powered) {
            state = state.setValue(BlockStateProperties.POWERED, powered);
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        }

        if (!level.isClientSide) {
            updateFacing(level, pos, state);
        }
        super.neighborChanged(state, level, pos, neighborBlock, o, movedByPiston);
    }

    private void updateFacing(Level level, BlockPos pos, BlockState state) {
        Direction current = state.getValue(FACING);
        // Keep current if still valid; else choose a deterministic best.
        Direction best = (isPowerAcceptor(level, pos, current)) ? current : findAcceptorFirst(level, pos);
        if (best != null && best != current) {
            level.setBlock(pos, state.setValue(FACING, best), Block.UPDATE_CLIENTS);
        }
    }

    /** Prefer sinks horizontally, then vertically (tags first, then caps). */
    @Nullable
    private static Direction findAcceptorFirst(Level level, BlockPos pos) {
        Direction[] horiz = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        Direction[] vert  = {Direction.DOWN, Direction.UP};

        for (Direction d : horiz) if (isTagSink(level, pos, d)) return d;
        for (Direction d : vert)  if (isTagSink(level, pos, d)) return d;
        for (Direction d : horiz) if (isCapSink(level, pos, d)) return d;
        for (Direction d : vert)  if (isCapSink(level, pos, d)) return d;

        return null;
    }

    private static boolean isPowerAcceptor(LevelReader level, BlockPos pos, Direction dir) {
        return isTagSink(level, pos, dir) || (level instanceof Level lvl && isCapSink(lvl, pos, dir));
    }

    private static boolean isTagSink(LevelReader level, BlockPos pos, Direction dir) {
        return level.getBlockState(pos.relative(dir)).is(ModTags.ENGINE_POWER_ACCEPTORS);
    }

    private static boolean isCapSink(Level lvl, BlockPos pos, Direction dir) {
        var es = lvl.getCapability(Capabilities.EnergyStorage.BLOCK, pos.relative(dir), dir.getOpposite());
        return es != null && es.canReceive();
    }

    /* -------------------------- rotation / mirror -------------------------- */

    @Override
    protected BlockState rotate(BlockState s, Rotation r) {
        return s.setValue(FACING, r.rotate(s.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState s, Mirror m) {
        return rotate(s, m.getRotation(s.getValue(FACING)));
    }

    /* ------------------------------- ticker ------------------------------- */

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level lvl, BlockState st, BlockEntityType<T> beType) {
        if (beType != ModBlockEntity.ENGINE.get()) return null;
        BlockEntityTicker<EngineBlockEntity> ticker =
                lvl.isClientSide ? EngineBlockEntity::clientTick : EngineBlockEntity::serverTick;
        return (BlockEntityTicker<T>) ticker;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EngineBlockEntity(type, pos, state);
    }

    /* ------------------------------- shapes ------------------------------- */

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        double off = 0.0; boolean movingUp = false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EngineBlockEntity e) {
            off = e.getCollisionOffset();
            movingUp = e.isMovingUpForCollision();
        }
        VoxelShape ring = movingUp ? this.type.spec.ring().plateAt(off) : this.type.spec.ring().frameAt(off);
        return combine(ring);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        double off = 0.0;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EngineBlockEntity e) off = e.getCollisionOffset();
        return combine(this.type.spec.ring().frameAt(off));
    }

    @Override public boolean useShapeForLightOcclusion(BlockState state) { return true; }
    @Override public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return Shapes.empty(); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
}
