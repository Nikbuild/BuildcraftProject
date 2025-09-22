package com.nick.buildcraft.content.block.pipe;

import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Shared item-transport pipe block for all variants.
 * Holds the 6-way connection state + shapes, and connection policy via PipeFamily.
 */
public abstract class BaseItemPipeBlock extends Block implements EntityBlock {

    public enum PipeFamily { STONE, COBBLE, GOLD, IRON, DIAMOND, WOOD, GENERIC }

    private final PipeFamily family;

    // 6-way connection booleans
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;

    // shapes (8px arms + 8px core)  ← was 4px
    private static final VoxelShape CORE  = Block.box(4, 4, 4, 12, 12, 12);
    private static final VoxelShape ARM_N = Block.box(4, 4, 0, 12, 12, 4);
    private static final VoxelShape ARM_S = Block.box(4, 4, 12, 12, 12, 16);
    private static final VoxelShape ARM_W = Block.box(0, 4, 4, 4, 12, 12);
    private static final VoxelShape ARM_E = Block.box(12, 4, 4, 16, 12, 12);
    private static final VoxelShape ARM_U = Block.box(4, 12, 4, 12, 16, 12);
    private static final VoxelShape ARM_D = Block.box(4, 0, 4, 12, 4, 12);

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return getShape(state, level, pos, ctx);
    }


    protected BaseItemPipeBlock(PipeFamily family, BlockBehaviour.Properties props) {
        super(props);
        this.family = family;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false).setValue(SOUTH, false)
                .setValue(EAST,  false).setValue(WEST,  false)
                .setValue(UP,    false).setValue(DOWN,  false));
    }

    public PipeFamily family() { return family; }

    /* ---------- state + shapes ---------- */

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShape shape = CORE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, ARM_N);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, ARM_S);
        if (state.getValue(WEST))  shape = Shapes.or(shape, ARM_W);
        if (state.getValue(EAST))  shape = Shapes.or(shape, ARM_E);
        if (state.getValue(UP))    shape = Shapes.or(shape, ARM_U);
        if (state.getValue(DOWN))  shape = Shapes.or(shape, ARM_D);
        return shape;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        return this.defaultBlockState()
                .setValue(NORTH, canConnect(level, pos, Direction.NORTH))
                .setValue(SOUTH, canConnect(level, pos, Direction.SOUTH))
                .setValue(EAST,  canConnect(level, pos, Direction.EAST))
                .setValue(WEST,  canConnect(level, pos, Direction.WEST))
                .setValue(UP,    canConnect(level, pos, Direction.UP))
                .setValue(DOWN,  canConnect(level, pos, Direction.DOWN));
    }

    /** 1.21+ neighbor shape update (also notify BE instantly on server). */
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
            if (be instanceof StonePipeBlockEntity pipe) {
                pipe.onNeighborGraphChanged(neighborPos); // instant cut/hide
            }
        }
        return state.setValue(prop(dir), canConnect(level, pos, dir));
    }

    private static BooleanProperty prop(Direction d) {
        return switch (d) {
            case NORTH -> NORTH; case SOUTH -> SOUTH; case EAST -> EAST;
            case WEST -> WEST;   case UP -> UP;       case DOWN -> DOWN;
        };
    }

    /* ---------- NEW: hide faces toward connected neighbor pipes ---------- */

    @Override
    public boolean skipRendering(BlockState self, BlockState other, Direction dir) {
        if (other.getBlock() instanceof BaseItemPipeBlock) {
            BooleanProperty p = prop(dir);
            if (self.hasProperty(p) && self.getValue(p)) return true;
        }
        return super.skipRendering(self, other, dir);
    }

    /* ---------- connectivity policy ---------- */

    /**
     * Default rule set:
     *  - Connect to other BaseItemPipeBlocks unless it's the classic Stone<->Cobble mismatch
     *  - Connect to Quarry controller block entity
     *  - Connect to any neighbor exposing ItemHandler from that side
     */
    protected boolean canConnect(LevelReader level, BlockPos pos, Direction dir) {
        BlockPos np = pos.relative(dir);
        BlockState other = level.getBlockState(np);

        // 1) other pipe?
        if (other.getBlock() instanceof BaseItemPipeBlock otherPipe) {
            return this.canMateWith(otherPipe) && otherPipe.canMateWith(this);
        }

        // 2) quarry controller?
        BlockEntity be = level.getBlockEntity(np);
        if (be instanceof QuarryBlockEntity) return true;

        // 3) any inventory (requires Level for capability lookup)
        if (level instanceof Level lvl) {
            var handler = lvl.getCapability(Capabilities.ItemHandler.BLOCK, np, dir.getOpposite());
            if (handler != null) return true;
        }

        return false;
    }

    /** Symmetric mating rule between pipe families. */
    protected boolean canMateWith(BaseItemPipeBlock other) {
        boolean stoneCobbleMismatch =
                (this.family == PipeFamily.STONE && other.family == PipeFamily.COBBLE) ||
                        (this.family == PipeFamily.COBBLE && other.family == PipeFamily.STONE);
        return !stoneCobbleMismatch;
    }

    /* ---------- BE + tick ---------- */

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBlockEntity.STONE_PIPE.get().create(pos, state); // shared BE for all item pipes
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ModBlockEntity.STONE_PIPE.get()
                ? (lvl, p, s, be) -> ((StonePipeBlockEntity) be).tick()
                : null;
    }

    /* ---------- extra nudges (belt + suspenders) ---------- */
}
