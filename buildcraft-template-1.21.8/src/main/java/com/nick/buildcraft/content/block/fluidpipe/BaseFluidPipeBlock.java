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
 * BaseFluidPipeBlock
 *
 * Shared behavior for ALL fluid pipes.
 * Things this class handles:
 *  - 6 connection booleans in blockstate
 *  - voxel shapes (core + arms)
 *  - connection logic to other pipes / tanks
 *  - hide interior faces between connected pipes
 *  - spawn + tick FluidPipeBlockEntity
 *
 * Subclasses (StoneFluidPipeBlock, CobbleFluidPipeBlock, GoldFluidPipeBlock, etc.)
 * only exist to:
 *   - pick the "family" (STONE vs COBBLE)
 *   - customize special rules later (speed boost, valves, filters...)
 */
public abstract class BaseFluidPipeBlock extends Block implements EntityBlock {

    /**
     * Like item pipes:
     *  - STONE and COBBLE do NOT connect directly to each other.
     *  - GOLD later = faster throughput.
     *  - IRON later = 1-way valve.
     *  - DIAMOND later = filter by fluid.
     *  - WOOD later = suction / extraction.
     */
    public enum PipeFamily { STONE, COBBLE, GOLD, IRON, DIAMOND, WOOD, GENERIC }

    private final PipeFamily family;

    public PipeFamily family() {
        return family;
    }

    // === blockstate props for connections ===
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;

    // === voxel shapes (8px core, 8px arms) ===
    private static final VoxelShape CORE  = Block.box(4, 4, 4, 12, 12, 12);
    private static final VoxelShape ARM_N = Block.box(4, 4, 0, 12, 12, 4);
    private static final VoxelShape ARM_S = Block.box(4, 4, 12, 12, 12, 16);
    private static final VoxelShape ARM_W = Block.box(0, 4, 4, 4, 12, 12);
    private static final VoxelShape ARM_E = Block.box(12, 4, 4, 16, 12, 12);
    private static final VoxelShape ARM_U = Block.box(4, 12, 4, 12, 16, 12);
    private static final VoxelShape ARM_D = Block.box(4, 0, 4, 12, 4, 12);

    protected BaseFluidPipeBlock(PipeFamily fam, BlockBehaviour.Properties props) {
        super(props);
        this.family = fam;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false).setValue(SOUTH, false)
                .setValue(EAST,  false).setValue(WEST,  false)
                .setValue(UP,    false).setValue(DOWN,  false)
        );
    }

    /* ------------------------------------------------------------ */
    /* Blockstate + shape                                           */
    /* ------------------------------------------------------------ */

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
     * Hide the shared face between two connected fluid pipes so it looks continuous.
     */
    @Override
    protected boolean skipRendering(BlockState self, BlockState other, Direction dir) {
        if (other.getBlock() instanceof BaseFluidPipeBlock) {
            BooleanProperty p = dirProp(dir);
            if (self.hasProperty(p) && self.getValue(p)) {
                return true;
            }
        }
        return super.skipRendering(self, other, dir);
    }

    /* ------------------------------------------------------------ */
    /* Placement + neighbor shape updates                           */
    /* ------------------------------------------------------------ */

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        return this.defaultBlockState()
                .setValue(NORTH, canConnectTo(level, pos, Direction.NORTH))
                .setValue(SOUTH, canConnectTo(level, pos, Direction.SOUTH))
                .setValue(EAST,  canConnectTo(level, pos, Direction.EAST))
                .setValue(WEST,  canConnectTo(level, pos, Direction.WEST))
                .setValue(UP,    canConnectTo(level, pos, Direction.UP))
                .setValue(DOWN,  canConnectTo(level, pos, Direction.DOWN));
    }

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
        // tell BE immediately so it can drop/retarget sections
        if (level instanceof Level lvl && !lvl.isClientSide) {
            BlockEntity be = lvl.getBlockEntity(pos);
            if (be instanceof FluidPipeBlockEntity fluidBe) {
                fluidBe.onNeighborGraphChanged(neighborPos);
            }
        }

        boolean nowConnected = canConnectTo(level, pos, dir);
        return state.setValue(dirProp(dir), nowConnected);
    }

    /* ------------------------------------------------------------ */
    /* Connectivity rules                                           */
    /* ------------------------------------------------------------ */

    /**
     * canConnectTo:
     * - connect to another fluid pipe if families are allowed to mate
     * - OR connect to a block that exposes a FluidHandler on that face (tank, pump, etc.)
     */
    protected boolean canConnectTo(LevelReader level, BlockPos selfPos, Direction dir) {
        BlockPos np = selfPos.relative(dir);
        BlockState otherState = level.getBlockState(np);

        // fluid pipe neighbor?
        if (otherState.getBlock() instanceof BaseFluidPipeBlock otherPipe) {
            return this.canMateWith(otherPipe) && otherPipe.canMateWith(this);
        }

        // machine / tank / pump exposing IFluidHandler on that face?
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

    /**
     * By default:
     *  - STONE <-> COBBLE is forbidden (classic BC "stone vs cobble don't mix")
     *  - everything else is fine
     *
     * Subclasses can override this for iron/wood/diamond behavior later.
     */
    protected boolean canMateWith(BaseFluidPipeBlock other) {
        boolean stoneCobbleMismatch =
                (this.family == PipeFamily.STONE && other.family == PipeFamily.COBBLE) ||
                        (this.family == PipeFamily.COBBLE && other.family == PipeFamily.STONE);
        return !stoneCobbleMismatch;
    }

    private static BooleanProperty dirProp(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    /* ------------------------------------------------------------ */
    /* Block entity + ticker                                        */
    /* ------------------------------------------------------------ */

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Shared BE type for ALL fluid pipes (like STONE_PIPE for item pipes)
        return ModBlockEntity.FLUID_PIPE.get().create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        return type == ModBlockEntity.FLUID_PIPE.get()
                ? (lvl, p, s, be) -> {
            if (!lvl.isClientSide && be instanceof FluidPipeBlockEntity fp) {
                fp.tickFluids();
            }
        }
                : null;
    }
}
