// src/main/java/com/nick/buildcraft/content/block/pipe/WoodPipeBlock.java
package com.nick.buildcraft.content.block.pipe;

import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Wood item pipe:
 *  - Connects to ALL pipe families (including wood→wood).
 *  - Connects to non-pipes only when that neighbor is a valid "source"
 *    (inventory/quarry/capability) and marks the *_SRC flags for rendering.
 */
public class WoodPipeBlock extends BaseItemPipeBlock {

    // Render helpers: mark which sides are touching a "source"
    public static final BooleanProperty NORTH_SRC = BooleanProperty.create("north_src");
    public static final BooleanProperty SOUTH_SRC = BooleanProperty.create("south_src");
    public static final BooleanProperty EAST_SRC  = BooleanProperty.create("east_src");
    public static final BooleanProperty WEST_SRC  = BooleanProperty.create("west_src");
    public static final BooleanProperty UP_SRC    = BooleanProperty.create("up_src");
    public static final BooleanProperty DOWN_SRC  = BooleanProperty.create("down_src");

    public WoodPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.WOOD, props);
        this.registerDefaultState(
                this.getStateDefinition().any()
                        .setValue(NORTH, false).setValue(SOUTH, false)
                        .setValue(EAST,  false).setValue(WEST,  false)
                        .setValue(UP,    false).setValue(DOWN,  false)
                        .setValue(NORTH_SRC, false).setValue(SOUTH_SRC, false)
                        .setValue(EAST_SRC,  false).setValue(WEST_SRC,  false)
                        .setValue(UP_SRC,    false).setValue(DOWN_SRC,  false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(NORTH_SRC, SOUTH_SRC, EAST_SRC, WEST_SRC, UP_SRC, DOWN_SRC);
    }

    /** Allow wood↔wood; keep Base rules (stone↔cobble mismatch) for everything else. */
    @Override
    protected boolean canMateWith(BaseItemPipeBlock other) {
        return super.canMateWith(other); // no special-case rejection for wood anymore
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        LevelAccessor level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();

        BlockState state = this.defaultBlockState();
        for (Direction d : Direction.values()) {
            boolean source  = isSource(level, pos.relative(d), d.getOpposite());
            boolean connect = shouldConnect(level, pos, d, source);

            state = state.setValue(dirProp(d), connect)
                    .setValue(srcProp(d), source && connect);
        }
        return state;
    }

    /** Keep connections up to date and notify the pipe BE immediately on the server. */
    @Override
    protected BlockState updateShape(
            BlockState state, LevelReader level, ScheduledTickAccess sched, BlockPos pos,
            Direction dir, BlockPos neighborPos, BlockState neighborState, RandomSource random
    ) {
        if (level instanceof Level lvl && !lvl.isClientSide) {
            BlockEntity be = lvl.getBlockEntity(pos);
            if (be instanceof StonePipeBlockEntity pipe) {
                pipe.onNeighborGraphChanged(neighborPos);
            }
        }

        boolean source  = isSource(level, neighborPos, dir.getOpposite());
        boolean connect = shouldConnect(level, pos, dir, source);

        return state.setValue(dirProp(dir), connect)
                .setValue(srcProp(dir), source && connect);
    }

    /** True if neighbor is a valid wooden-pipe "source" (inventory/quarry/capability). */
    private boolean isSource(LevelReader level, BlockPos neighborPos, Direction toward) {
        BlockState nb = level.getBlockState(neighborPos);

        // Pipes are NOT sources; they are normal connections.
        if (nb.getBlock() instanceof BaseItemPipeBlock) return false;

        BlockEntity be = level.getBlockEntity(neighborPos);
        if (be instanceof QuarryBlockEntity) return true;

        if (level instanceof Level lvl) {
            var handler = lvl.getCapability(Capabilities.ItemHandler.BLOCK, neighborPos, toward);
            if (handler != null) return true;
        }
        return false;
    }

    /**
     * Wood connects to:
     *  - any pipe block that reciprocates (including wood→wood), or
     *  - a non-pipe only if that neighbor is a "source".
     */
    private boolean shouldConnect(LevelReader level, BlockPos pos, Direction dir, boolean source) {
        BlockPos np = pos.relative(dir);
        BlockState other = level.getBlockState(np);

        if (other.getBlock() instanceof BaseItemPipeBlock otherPipe) {
            return this.canMateWith(otherPipe) && otherPipe.canMateWith(this);
        }
        return source;
    }

    // local helpers to address the 6 boolean props
    private static BooleanProperty dirProp(Direction d) {
        return switch (d) {
            case NORTH -> NORTH; case SOUTH -> SOUTH; case EAST -> EAST;
            case WEST  -> WEST;  case UP    -> UP;    case DOWN -> DOWN;
        };
    }
    private static BooleanProperty srcProp(Direction d) {
        return switch (d) {
            case NORTH -> NORTH_SRC; case SOUTH -> SOUTH_SRC; case EAST -> EAST_SRC;
            case WEST  -> WEST_SRC;  case UP    -> UP_SRC;    case DOWN -> DOWN_SRC;
        };
    }
}
