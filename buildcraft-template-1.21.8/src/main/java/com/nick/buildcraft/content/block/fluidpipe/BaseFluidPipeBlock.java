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
 *
 * NEW: Endpoint-only connection logic - pipes can only connect to:
 *   1. Non-pipe blocks (pumps, tanks, etc.)
 *   2. The endpoint of another pipe chain (pipes with 0 or 1 existing connections)
 */
public abstract class BaseFluidPipeBlock extends Block implements EntityBlock {

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
        // Recompute connection based on endpoint logic
        boolean nowConnected = canConnectTo(level, pos, dir);
        return state.setValue(dirProp(dir), nowConnected);
    }

    /* ------------------------------------------------------------ */
    /* Connectivity rules (ENDPOINT-ONLY LOGIC)                     */
    /* ------------------------------------------------------------ */

    /**
     * Check if this pipe at selfPos can connect in direction dir.
     *
     * Rules:
     * 1. Always connect to non-pipe blocks (pumps, tanks, etc.) with fluid handlers
     * 2. Connect to another pipe ONLY IF:
     *    a) This pipe has 0 or 1 existing connections (is an endpoint)
     *    b) The target pipe has 0 or 1 existing connections (is an endpoint)
     */
    protected boolean canConnectTo(LevelReader level, BlockPos selfPos, Direction dir) {
        BlockPos neighborPos = selfPos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);

        // Case 1: Neighbor is another fluid pipe
        if (neighborState.getBlock() instanceof BaseFluidPipeBlock otherPipe) {
            // Check family compatibility first
            if (!this.canMateWith(otherPipe) || !otherPipe.canMateWith(this)) {
                return false;
            }

            // NEW: Endpoint-only connection logic
            // Count how many connections this pipe currently has
            int selfConnections = countExistingConnections(level, selfPos, dir);

            // Count how many connections the neighbor pipe has
            int neighborConnections = countExistingConnections(level, neighborPos, dir.getOpposite());

            // Both pipes must be endpoints (0 or 1 existing connection) to connect
            return selfConnections <= 1 && neighborConnections <= 1;
        }

        // Case 2: Neighbor is a machine/tank/pump with IFluidHandler
        if (level instanceof Level lvl) {
            IFluidHandler handler = lvl.getCapability(
                    Capabilities.FluidHandler.BLOCK,
                    neighborPos,
                    dir.getOpposite()
            );
            if (handler != null) {
                // Always allow connection to non-pipe fluid handlers
                return true;
            }
        }

        return false;
    }

    /**
     * Count how many connections this pipe already has (excluding the checkDir).
     *
     * @param level The level
     * @param pos Position of the pipe to check
     * @param excludeDir Direction to exclude from count (the direction we're checking for new connection)
     * @return Number of existing connections (0, 1, 2, 3, 4, 5, or 6)
     */
    private int countExistingConnections(LevelReader level, BlockPos pos, Direction excludeDir) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BaseFluidPipeBlock)) {
            return 0;
        }

        int count = 0;

        for (Direction dir : Direction.values()) {
            // Skip the direction we're currently checking
            if (dir == excludeDir) continue;

            BooleanProperty prop = dirProp(dir);
            if (state.hasProperty(prop) && state.getValue(prop)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Check if two pipe families can connect together.
     * Stone and Cobble pipes cannot mate with each other.
     */
    protected boolean canMateWith(BaseFluidPipeBlock other) {
        boolean stoneCobbleMismatch =
                (this.family == PipeFamily.STONE && other.family == PipeFamily.COBBLE) ||
                        (this.family == PipeFamily.COBBLE && other.family == PipeFamily.STONE);
        return !stoneCobbleMismatch;
    }

    /**
     * Helper to map Direction to the corresponding BooleanProperty.
     */
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
        // Shared BE type for ALL fluid pipes
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
