package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.pipe.WoodPipeBlock;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

// NeoForge
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class EngineBlock extends BaseEngineBlock {

    /* --------------------- static base shapes (local UP) --------------------- */

    private static final VoxelShape BASE_UP = box(1, 0, 1, 15, 4, 15);
    private static final VoxelShape CORE_UP = box(5, 4, 5, 11, 12, 11);
    private static final VoxelShape BASE_PLUS_CORE_UP = Shapes.joinUnoptimized(BASE_UP, CORE_UP, BooleanOp.OR);

    /** Base+core cached for each facing. */
    private static final Map<Direction, VoxelShape> BASE_PLUS_CORE_BY_FACING = new EnumMap<>(Direction.class);
    static {
        for (Direction d : Direction.values()) {
            BASE_PLUS_CORE_BY_FACING.put(d, rotateFromUp(BASE_PLUS_CORE_UP, d));
        }
    }

    /** Engine heat phase for texture selection (BLUE, GREEN, ORANGE, RED). */
    public enum Phase implements StringRepresentable {
        BLUE, GREEN, ORANGE, RED;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Phase> PHASE = EnumProperty.create("phase", Phase.class);

    /** Orientation axis of the engine (where its "core" points). */
    public static final EnumProperty<Direction> FACING =
            EnumProperty.create("facing", Direction.class, Direction.values());

    /** Transient 2-tick pulse used to emit a redstone blip on pump completion. */
    public static final BooleanProperty PULSING = BooleanProperty.create("pulsing");

    private final EngineType type;

    public EngineBlock(EngineType type, BlockBehaviour.Properties props) {
        super(props);
        this.type = type;
        registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(FACING, Direction.NORTH)
                .setValue(PULSING, Boolean.FALSE)
                .setValue(PHASE, Phase.BLUE));
    }

    public EngineType engineType() { return type; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(BlockStateProperties.POWERED);
        b.add(FACING);
        b.add(PULSING);
        b.add(PHASE);
    }

    /* -----------------------------------------------------------------------
     * Redstone: engines emit a brief pulse when a pump completes.
     * (We ignore redstone FROM other engines when deciding POWERED.)
     * --------------------------------------------------------------------- */
    @Override public boolean isSignalSource(BlockState state) { return true; }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        return state.getValue(PULSING) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        return getSignal(state, level, pos, dir);
    }

    /** true if any neighbor (except engines) powers this pos. */
    private static boolean hasNonEnginePower(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            BlockState ns = level.getBlockState(np);
            if (ns.getBlock() instanceof EngineBlock) continue; // ignore other engines
            if (ns.getSignal(level, np, d) > 0) return true;
        }
        return false;
    }

    /* -------------------------------------------------------------------------
     * Smart snapping to acceptors (wood pipe, tag, or capability sink)
     * ---------------------------------------------------------------------- */

    /** Find a neighbor that can accept engine power. */
    @Nullable
    private Direction findAcceptor(LevelAccessor level, BlockPos pos) {
        // Prefer blocks with the tag or explicit wood pipe
        for (Direction d : Direction.values()) {
            BlockState nb = level.getBlockState(pos.relative(d));
            if (nb.is(ModTags.ENGINE_POWER_ACCEPTORS)) return d;
            if (nb.getBlock() instanceof WoodPipeBlock) return d;
        }
        // Server capability fallback
        if (level instanceof Level lvl && !lvl.isClientSide) {
            for (Direction d : Direction.values()) {
                IEnergyStorage sink = lvl.getCapability(
                        Capabilities.EnergyStorage.BLOCK, pos.relative(d), d.getOpposite());
                if (sink != null && sink.canReceive()) return d;
            }
        }
        return null;
    }

    /** Is the current facing still valid as an acceptor? */
    private boolean isFacingValid(LevelAccessor level, BlockPos pos, Direction facing) {
        BlockPos n = pos.relative(facing);
        BlockState nb = level.getBlockState(n);
        if (nb.is(ModTags.ENGINE_POWER_ACCEPTORS)) return true;
        if (nb.getBlock() instanceof WoodPipeBlock) return true;

        if (level instanceof Level lvl && !lvl.isClientSide) {
            IEnergyStorage sink = lvl.getCapability(
                    Capabilities.EnergyStorage.BLOCK, n, facing.getOpposite());
            return sink != null && sink.canReceive();
        }
        return false;
    }

    /* -------------------------------- placement -------------------------------- */

    @Override @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();

        Direction snap = findAcceptor(level, pos);
        Direction facing = (snap != null) ? snap : facingFromHit(ctx);

        return defaultBlockState()
                .setValue(BlockStateProperties.POWERED, hasNonEnginePower(level, pos))
                .setValue(FACING, facing)
                .setValue(PULSING, Boolean.FALSE);
    }

    /** Purely position-driven facing selection (all 6 directions). */
    private static Direction facingFromHit(BlockPlaceContext ctx) {
        final Direction face = ctx.getClickedFace();          // face of the block you clicked
        final BlockPos placePos = ctx.getClickedPos();        // where this block will go
        final Vec3 hit = ctx.getClickLocation();

        final double hx = hit.x - placePos.getX();
        final double hy = hit.y - placePos.getY();
        final double hz = hit.z - placePos.getZ();

        switch (face) {
            case UP -> {
                final double dx = Math.abs(hx - 0.5), dz = Math.abs(hz - 0.5), C = 0.22;
                if (dx < C && dz < C) return Direction.UP;
                return dx > dz ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hz > 0.5 ? Direction.SOUTH : Direction.NORTH);
            }
            case DOWN -> {
                final double dx = Math.abs(hx - 0.5), dz = Math.abs(hz - 0.5), C = 0.22;
                if (dx < C && dz < C) return Direction.DOWN;
                return dx > dz ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hz > 0.5 ? Direction.SOUTH : Direction.NORTH);
            }
            case NORTH -> {
                final double dx = Math.abs(hx - 0.5), dy = Math.abs(hy - 0.5);
                return dx > dy ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hy > 0.5 ? Direction.UP   : Direction.DOWN);
            }
            case SOUTH -> {
                final double dx = Math.abs(hx - 0.5), dy = Math.abs(hy - 0.5);
                return dx > dy ? (hx > 0.5 ? Direction.WEST : Direction.EAST)
                        : (hy > 0.5 ? Direction.UP   : Direction.DOWN);
            }
            case WEST -> {
                final double dz = Math.abs(hz - 0.5), dy = Math.abs(hy - 0.5);
                return dz > dy ? (hz > 0.5 ? Direction.SOUTH : Direction.NORTH)
                        : (hy > 0.5 ? Direction.UP    : Direction.DOWN);
            }
            case EAST -> {
                final double dz = Math.abs(hz - 0.5), dy = Math.abs(hy - 0.5);
                return dz > dy ? (hz > 0.5 ? Direction.NORTH : Direction.SOUTH)
                        : (hy > 0.5 ? Direction.UP    : Direction.DOWN);
            }
        }
        return Direction.NORTH;
    }

    /** After placement, re-snap once server-side in case neighbors appeared same tick. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (level.isClientSide) return;

        Direction cur = state.getValue(FACING);
        if (!isFacingValid(level, pos, cur)) {
            Direction snap = findAcceptor(level, pos);
            if (snap != null && snap != cur) {
                level.setBlock(pos, state.setValue(FACING, snap), Block.UPDATE_CLIENTS);
            }
        }
    }

    /* ----------------------------- neighbor updates ---------------------------- */

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, @Nullable Orientation o, boolean movedByPiston) {
        // Only consider redstone from non-engine sources.
        boolean powered = hasNonEnginePower(level, pos);
        if (state.getValue(BlockStateProperties.POWERED) != powered) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), Block.UPDATE_CLIENTS);
        }

        // Re-snap if our facing is no longer valid but another side is
        if (!level.isClientSide) {
            Direction cur = state.getValue(FACING);
            if (!isFacingValid(level, pos, cur)) {
                Direction snap = findAcceptor(level, pos);
                if (snap != null && snap != cur) {
                    level.setBlock(pos, state.setValue(FACING, snap), Block.UPDATE_CLIENTS);
                }
            }
        }

        super.neighborChanged(state, level, pos, neighborBlock, o, movedByPiston);
    }

    /* -------------------- scheduled pulse reset (2-tick blip) -------------------- */

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand) {
        if (state.getValue(PULSING)) {
            level.setBlock(pos, state.setValue(PULSING, Boolean.FALSE), Block.UPDATE_CLIENTS);
        }
    }

    /* -------------------------------- rotation/mirror --------------------------- */

    @Override
    protected BlockState rotate(BlockState s, Rotation r) {
        return s.setValue(FACING, r.rotate(s.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState s, Mirror m) {
        return rotate(s, m.getRotation(s.getValue(FACING)));
    }

    /* --------------------------------- ticker ---------------------------------- */

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

    /* -------------------------------- shapes ----------------------------------- */

    private static VoxelShape combine(VoxelShape a, VoxelShape b) {
        return Shapes.joinUnoptimized(a, b, BooleanOp.OR);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        final Direction f = state.getValue(FACING);

        double off = 0.0;
        boolean movingUp = false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EngineBlockEntity e) {
            off = e.getCollisionOffset();       // 0..~0.5 blocks
            movingUp = e.isMovingUpForCollision();
        }

        VoxelShape ringLocal = movingUp ? this.type.spec.ring().plateAt(off)
                : this.type.spec.ring().frameAt(off);
        VoxelShape ringRot   = rotateFromUp(ringLocal, f);

        return combine(BASE_PLUS_CORE_BY_FACING.get(f), ringRot);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        final Direction f = state.getValue(FACING);
        double off = 0.0;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EngineBlockEntity e) off = e.getCollisionOffset();

        VoxelShape ringRot = rotateFromUp(this.type.spec.ring().frameAt(off), f);
        return combine(BASE_PLUS_CORE_BY_FACING.get(f), ringRot);
    }

    @Override protected boolean useShapeForLightOcclusion(BlockState state) { return true; }
    @Override protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) { return Shapes.empty(); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    /* ===================== VoxelShape rotation (UP -> facing) ===================== */

    private static VoxelShape rotateFromUp(VoxelShape shape, Direction to) {
        if (to == Direction.UP) return shape;

        VoxelShape out = Shapes.empty();
        for (AABB box : shape.toAabbs()) {
            AABB r = switch (to) {
                case DOWN  -> rotateDown(box);
                case NORTH -> rotateNorth(box);
                case SOUTH -> rotateSouth(box);
                case WEST  -> rotateWest(box);
                case EAST  -> rotateEast(box);
                case UP    -> box;
            };
            out = Shapes.or(out, Shapes.create(r));
        }
        return out.optimize();
    }

    private static AABB rotateDown(AABB b)  { return aabbMap(b, (x,y,z) -> new double[]{x, 1 - y, 1 - z}); }
    private static AABB rotateNorth(AABB b) { return aabbMap(b, (x,y,z) -> new double[]{x, z, 1 - y}); }
    private static AABB rotateSouth(AABB b) { return aabbMap(b, (x,y,z) -> new double[]{x, 1 - z, y}); }
    private static AABB rotateWest(AABB b)  { return aabbMap(b, (x,y,z) -> new double[]{1 - y, x, z}); }
    private static AABB rotateEast(AABB b)  { return aabbMap(b, (x,y,z) -> new double[]{y, 1 - x, z}); }

    @FunctionalInterface private interface PointMap { double[] map(double x, double y, double z); }

    private static AABB aabbMap(AABB in, PointMap mapper) {
        double[][] pts = new double[][]{
                mapper.map(in.minX, in.minY, in.minZ),
                mapper.map(in.minX, in.minY, in.maxZ),
                mapper.map(in.minX, in.maxY, in.minZ),
                mapper.map(in.minX, in.maxY, in.maxZ),
                mapper.map(in.maxX, in.minY, in.minZ),
                mapper.map(in.maxX, in.minY, in.maxZ),
                mapper.map(in.maxX, in.maxY, in.minZ),
                mapper.map(in.maxX, in.maxY, in.maxZ)
        };
        double minX = 1, minY = 1, minZ = 1, maxX = 0, maxY = 0, maxZ = 0;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]); minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]); maxZ = Math.max(maxZ, p[2]);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
