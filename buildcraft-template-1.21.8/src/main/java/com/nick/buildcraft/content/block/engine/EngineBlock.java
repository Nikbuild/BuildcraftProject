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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

// NEW imports for capability fallback
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

    /** Orientation axis of the engine (where its “core” points). */
    public static final EnumProperty<Direction> FACING =
            EnumProperty.create("facing", Direction.class, Direction.values());

    private final EngineType type;

    public EngineBlock(EngineType type, BlockBehaviour.Properties props) {
        super(props);
        this.type = type;
        registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(FACING, Direction.NORTH));
    }

    public EngineType engineType() { return type; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(BlockStateProperties.POWERED);
        b.add(FACING);
    }

    /* -------------------------------------------------------------------------
     * Smart snapping (tag first, capability fallback on server)
     * ---------------------------------------------------------------------- */

    /**
     * Returns a direction that has a valid acceptor neighbor:
     * 1) Prefer blocks tagged with ENGINE_POWER_ACCEPTORS (works on both sides)
     * 2) Fallback (server only): a neighbor exposing EnergyStorage that canReceive()
     */
    @Nullable
    private Direction findAcceptor(LevelAccessor level, BlockPos pos) {
        // 1) Tags: reliable on both client & server
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).is(ModTags.ENGINE_POWER_ACCEPTORS)) {
                return d;
            }
        }
        // 2) Capability fallback: server truth
        if (level instanceof Level lvl && !lvl.isClientSide) {
            for (Direction d : Direction.values()) {
                IEnergyStorage sink = lvl.getCapability(
                        Capabilities.EnergyStorage.BLOCK,
                        pos.relative(d),
                        d.getOpposite());
                if (sink != null && sink.canReceive()) {
                    return d;
                }
            }
        }
        return null;
    }

    /** Does the block currently face a valid acceptor (tag OR capability)? */
    private boolean isFacingValid(LevelAccessor level, BlockPos pos, Direction facing) {
        BlockPos n = pos.relative(facing);
        // Tag check first
        if (level.getBlockState(n).is(ModTags.ENGINE_POWER_ACCEPTORS)) return true;

        // Server capability fallback
        if (level instanceof Level lvl && !lvl.isClientSide) {
            IEnergyStorage sink = lvl.getCapability(
                    Capabilities.EnergyStorage.BLOCK,
                    n,
                    facing.getOpposite());
            return sink != null && sink.canReceive();
        }
        return false;
    }

    /* -------------------------------- placement -------------------------------- */

    @Override @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();

        // Prefer a tagged/capability neighbor if present, otherwise fall back to hit-based logic
        Direction snap = findAcceptor(level, pos);
        Direction facing = (snap != null) ? snap : facingFromHit(ctx);

        return defaultBlockState()
                .setValue(BlockStateProperties.POWERED, level.hasNeighborSignal(pos))
                .setValue(FACING, facing);
    }

    /** Purely position-driven facing selection (all 6 directions). */
    private static Direction facingFromHit(BlockPlaceContext ctx) {
        final Direction face = ctx.getClickedFace();          // face of the block you clicked
        final BlockPos placePos = ctx.getClickedPos();        // where this block will go
        final Vec3 hit = ctx.getClickLocation();

        // Hit position within the placed block’s local [0,1]^3
        final double hx = hit.x - placePos.getX();
        final double hy = hit.y - placePos.getY();
        final double hz = hit.z - placePos.getZ();

        // Helper: pick which axis in the plane you were closer to (edge vs edge).
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

    /** After placement, do a one-time recheck in case neighbors arrived same tick. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
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
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(BlockStateProperties.POWERED) != powered) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), Block.UPDATE_CLIENTS);
        }

        // Re-snap if our current facing no longer points at an acceptor but another side does
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
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        final Direction f = state.getValue(FACING);

        double off = 0.0;
        boolean movingUp = false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EngineBlockEntity e) {
            off = e.getCollisionOffset();       // 0..~0.5 blocks
            movingUp = e.isMovingUpForCollision();
        }

        // ring is defined in local-UP space -> rotate to FACING
        VoxelShape ringLocal = movingUp ? this.type.spec.ring().plateAt(off)
                : this.type.spec.ring().frameAt(off);
        VoxelShape ringRot   = rotateFromUp(ringLocal, f);

        return combine(BASE_PLUS_CORE_BY_FACING.get(f), ringRot);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        final Direction f = state.getValue(FACING);
        double off = 0.0;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof EngineBlockEntity e) off = e.getCollisionOffset();

        VoxelShape ringRot = rotateFromUp(this.type.spec.ring().frameAt(off), f);
        return combine(BASE_PLUS_CORE_BY_FACING.get(f), ringRot);
    }

    @Override public boolean useShapeForLightOcclusion(BlockState state) { return true; }
    @Override public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return Shapes.empty(); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

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

    // All rotations map local-UP to the requested facing.
    private static AABB rotateDown(AABB b)  { return aabbMap(b, (x,y,z) -> new double[]{x, 1 - y, 1 - z}); }
    private static AABB rotateNorth(AABB b) { return aabbMap(b, (x,y,z) -> new double[]{x, z, 1 - y}); }
    private static AABB rotateSouth(AABB b) { return aabbMap(b, (x,y,z) -> new double[]{x, 1 - z, y}); }
    private static AABB rotateWest(AABB b)  { return aabbMap(b, (x,y,z) -> new double[]{1 - y, x, z}); }
    private static AABB rotateEast(AABB b)  { return aabbMap(b, (x,y,z) -> new double[]{y, 1 - x, z}); }

    @FunctionalInterface
    private interface PointMap { double[] map(double x, double y, double z); }

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
