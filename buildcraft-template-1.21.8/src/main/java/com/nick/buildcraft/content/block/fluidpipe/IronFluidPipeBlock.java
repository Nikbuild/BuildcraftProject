package com.nick.buildcraft.content.block.fluidpipe;

import com.nick.buildcraft.api.wrench.Wrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * IronFluidPipeBlock
 *
 * Mid-tier fluid pipe. Faster throughput than stone.
 * With wrench support: click core to cycle output; click arm to select that side.
 */
public class IronFluidPipeBlock extends BaseFluidPipeBlock implements Wrenchable {

    // Output flags (one true at a time)
    public static final BooleanProperty NORTH_OUT = BooleanProperty.create("north_out");
    public static final BooleanProperty SOUTH_OUT = BooleanProperty.create("south_out");
    public static final BooleanProperty EAST_OUT  = BooleanProperty.create("east_out");
    public static final BooleanProperty WEST_OUT  = BooleanProperty.create("west_out");
    public static final BooleanProperty UP_OUT    = BooleanProperty.create("up_out");
    public static final BooleanProperty DOWN_OUT  = BooleanProperty.create("down_out");

    private static final Direction[] CYCLE_ORDER = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN
    };

    public IronFluidPipeBlock(BlockBehaviour.Properties props) {
        super(PipeFamily.IRON, props);
        this.registerDefaultState(
                this.getStateDefinition().any()
                        // base connections (from BaseFluidPipeBlock)
                        .setValue(NORTH, false).setValue(SOUTH, false)
                        .setValue(EAST,  false).setValue(WEST,  false)
                        .setValue(UP,    false).setValue(DOWN,  false)
                        // default output → NORTH
                        .setValue(NORTH_OUT, true).setValue(SOUTH_OUT, false)
                        .setValue(EAST_OUT,  false).setValue(WEST_OUT,  false)
                        .setValue(UP_OUT,    false).setValue(DOWN_OUT,  false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b);
        b.add(NORTH_OUT, SOUTH_OUT, EAST_OUT, WEST_OUT, UP_OUT, DOWN_OUT);
    }

    @Override
    public InteractionResult onWrench(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        // Local hit coords inside block (0..1)
        double lx = hit.getLocation().x - pos.getX();
        double ly = hit.getLocation().y - pos.getY();
        double lz = hit.getLocation().z - pos.getZ();

        // Treat the central core as a cube near the center
        final double CORE_HALF = 0.22;
        boolean inCore = Math.abs(lx - 0.5) <= CORE_HALF
                && Math.abs(ly - 0.5) <= CORE_HALF
                && Math.abs(lz - 0.5) <= CORE_HALF;

        Direction newOut;
        if (inCore) {
            newOut = nextConnected(state, getOutput(state));
        } else {
            // pick arm by the dominant axis from center
            double dx = lx - 0.5, dy = ly - 0.5, dz = lz - 0.5;
            if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
                newOut = dx > 0 ? Direction.EAST : Direction.WEST;
            } else if (Math.abs(dz) >= Math.abs(dx) && Math.abs(dz) >= Math.abs(dy)) {
                newOut = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            } else {
                newOut = dy > 0 ? Direction.UP : Direction.DOWN;
            }
            if (!state.getValue(dirProp(newOut))) {
                newOut = nextConnected(state, getOutput(state));
            }
        }

        BlockState updated = withOutput(state, newOut);
        level.setBlock(pos, updated, 3);
        level.levelEvent(2001, pos, Block.getId(updated));
        return InteractionResult.SUCCESS;
    }

    private static Direction nextConnected(BlockState s, Direction cur) {
        int start = 0;
        for (int i = 0; i < CYCLE_ORDER.length; i++) if (CYCLE_ORDER[i] == cur) { start = i; break; }

        // prefer connected sides
        for (int step = 1; step <= CYCLE_ORDER.length; step++) {
            Direction d = CYCLE_ORDER[(start + step) % CYCLE_ORDER.length];
            if (s.getValue(dirProp(d))) return d;
        }
        // fallback: rotate even if not connected
        return CYCLE_ORDER[(start + 1) % CYCLE_ORDER.length];
    }

    /* ---- output direction helpers ---- */

    protected static BooleanProperty dirProp(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    protected static BooleanProperty outProp(Direction d) {
        return switch (d) {
            case NORTH -> NORTH_OUT;
            case SOUTH -> SOUTH_OUT;
            case EAST  -> EAST_OUT;
            case WEST  -> WEST_OUT;
            case UP    -> UP_OUT;
            case DOWN  -> DOWN_OUT;
        };
    }

    public static Direction getOutput(BlockState s) {
        for (Direction d : Direction.values()) {
            BooleanProperty p = outProp(d);
            if (s.hasProperty(p) && s.getValue(p)) return d;
        }
        return Direction.NORTH;
    }

    public static BlockState withOutput(BlockState s, Direction out) {
        BlockState x = s;
        for (Direction d : Direction.values()) x = x.setValue(outProp(d), d == out);
        return x;
    }
}
