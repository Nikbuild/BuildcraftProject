package com.nick.buildcraft.content.block.fluidpipe;

import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * FluidPipeBlock
 *
 * Placeable fluid pipe block.
 *
 * - Holds a FluidPipeBlockEntity.
 * - Handles 6-direction connection booleans for rendering/shape.
 * - Server ticker calls FluidPipeBlockEntity.tickFluids().
 */
public class FluidPipeBlock extends Block implements EntityBlock {

    // --- same 6 boolean connection props as other pipes ---
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;

    // hitbox pieces: 8x8x8 core + 6 arms
    private static final VoxelShape CORE  = Block.box(4, 4, 4, 12, 12, 12);
    private static final VoxelShape ARM_N = Block.box(4, 4, 0, 12, 12, 4);
    private static final VoxelShape ARM_S = Block.box(4, 4, 12, 12, 12, 16);
    private static final VoxelShape ARM_W = Block.box(0, 4, 4, 4, 12, 12);
    private static final VoxelShape ARM_E = Block.box(12, 4, 4, 16, 12, 12);
    private static final VoxelShape ARM_U = Block.box(4, 12, 4, 12, 16, 12);
    private static final VoxelShape ARM_D = Block.box(4, 0, 4, 12, 4, 12);

    public FluidPipeBlock(BlockBehaviour.Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false).setValue(SOUTH, false)
                .setValue(EAST,  false).setValue(WEST,  false)
                .setValue(UP,    false).setValue(DOWN,  false)
        );
    }

    /* ------------------------------------------------------------------------------------------------
     * Blockstate + shape
     * ------------------------------------------------------------------------------------------------
     */

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShape shape = CORE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, ARM_N);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, ARM_S);
        if (state.getValue(WEST))  shape = Shapes.or(shape, ARM_W);
        if (state.getValue(EAST))  shape = Shapes.or(shape, ARM_E);
        if (state.getValue(UP))    shape = Shapes.or(shape, ARM_U);
        if (state.getValue(DOWN))  shape = Shapes.or(shape, ARM_D);
        return shape.optimize();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return getShape(state, level, pos, ctx);
    }

    /**
     * Hide the shared face between two connected fluid pipes so it looks like one continuous tube.
     */
    @Override
    protected boolean skipRendering(BlockState self, BlockState other, Direction dir) {
        if (other.getBlock() instanceof FluidPipeBlock) {
            BooleanProperty p = prop(dir);
            if (self.hasProperty(p) && self.getValue(p)) {
                return true;
            }
        }
        return super.skipRendering(self, other, dir);
    }

    /* ------------------------------------------------------------------------------------------------
     * Placement + shape updates
     * ------------------------------------------------------------------------------------------------
     */

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        return this.defaultBlockState()
                .setValue(NORTH, canConnectFluid(level, pos, Direction.NORTH))
                .setValue(SOUTH, canConnectFluid(level, pos, Direction.SOUTH))
                .setValue(EAST,  canConnectFluid(level, pos, Direction.EAST))
                .setValue(WEST,  canConnectFluid(level, pos, Direction.WEST))
                .setValue(UP,    canConnectFluid(level, pos, Direction.UP))
                .setValue(DOWN,  canConnectFluid(level, pos, Direction.DOWN));
    }

    /**
     * 1.21 neighbor shape update hook.
     * We recompute the connection flag on that side and ping the BE so routing can react.
     */
    @Override
    protected BlockState updateShape(
            BlockState state,
            LevelReader level,
            ScheduledTickAccess scheduledTickAccess,
            BlockPos pos,
            Direction dir,
            BlockPos neighborPos,
            BlockState neighborState,
            RandomSource random
    ) {
        if (level instanceof Level lvl && !lvl.isClientSide) {
            BlockEntity be = lvl.getBlockEntity(pos);
            if (be instanceof FluidPipeBlockEntity fluidBe) {
                fluidBe.onNeighborGraphChanged(neighborPos);
            }
        }

        boolean connectNow = canConnectFluid(level, pos, dir);
        return state.setValue(prop(dir), connectNow);
    }

    /* ------------------------------------------------------------------------------------------------
     * Connectivity rules
     * ------------------------------------------------------------------------------------------------
     *
     * We connect if:
     *  - neighbor is another FluidPipeBlock, OR
     *  - that neighbor exposes a FluidHandler capability on the touching face
     *    (tank, pump, machine, etc.)
     */

    protected boolean canConnectFluid(LevelReader level, BlockPos selfPos, Direction dir) {
        BlockPos np = selfPos.relative(dir);
        BlockState otherState = level.getBlockState(np);

        // pipe <-> pipe
        if (otherState.getBlock() instanceof FluidPipeBlock) {
            return true;
        }

        // pipe <-> tank/pump/etc with fluid handler
        if (level instanceof Level lvl) {
            IFluidHandler handler = lvl.getCapability(
                    Capabilities.FluidHandler.BLOCK,
                    np,
                    dir.getOpposite()
            );
            if (handler != null) {
                return true;
            }
        }

        return false;
    }

    /* ------------------------------------------------------------------------------------------------
     * Little helper to map Direction -> correct boolean property
     * ------------------------------------------------------------------------------------------------
     */
    private static BooleanProperty prop(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    /* ------------------------------------------------------------------------------------------------
     * Block entity + ticker hookup
     * ------------------------------------------------------------------------------------------------
     */

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // use our dedicated BE type (registered in ModBlockEntity.FLUID_PIPE)
        return ModBlockEntity.FLUID_PIPE.get().create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        // server-only ticking. calls tickFluids(), not tick().
        return type == ModBlockEntity.FLUID_PIPE.get()
                ? (lvl, p, s, be) -> {
            if (!lvl.isClientSide && be instanceof FluidPipeBlockEntity pipeBe) {
                pipeBe.tickFluids();
            }
        }
                : null;
    }
}
