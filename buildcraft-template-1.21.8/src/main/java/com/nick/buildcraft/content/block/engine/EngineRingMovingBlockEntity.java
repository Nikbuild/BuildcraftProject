package com.nick.buildcraft.content.block.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Piston-style moving block entity that carries ONLY the engine ring's collision.
 * - Uses a NOCLIP thread-local exactly like pistons so players can strafe smoothly while being lifted.
 * - Moves entities using a swept-volume test and a tiny "+0.01" headroom push per tick.
 *
 * Life-cycle: place a MovingEngineRingBlock with this BE while the ring animates;
 * this BE advances progress, moves entities, exposes ring collision, and removes the block at end.
 */
public final class EngineRingMovingBlockEntity extends BlockEntity {

    /* ------------ Ring geometry (frame) ------------ */
    // Frame bars are 2px thick; outer 1..15 px; inner 3..13 px; vertical slab 4px tall
    private static final double PX   = 1.0 / 16.0;
    private static final double TH   = 2  * PX;      // frame thickness
    private static final double OUT0 = 1  * PX;      // 1/16
    private static final double OUT1 = 15 * PX;      // 15/16
    private static final double IN0  = OUT0 + TH;    // 3/16
    private static final double IN1  = OUT1 - TH;    // 13/16

    // At REST the ring slab occupies Y:[4..8] px; it travels 4 px (to Y:[8..12]) so it ends flush with the core top (12 px).
    private static final double RING_Y0_LOCAL = 4  * PX;
    private static final double RING_Y1_LOCAL = 8  * PX;
    private static final double MAX_OFFSET    = 4  * PX;   // total travel (blocks)

    // Piston-style "extra" nudge so entities don't jitter when brushing the face.
    private static final double PUSH_EPS = 0.01;

    // Thread-local NOCLIP direction, same trick pistons use.
    private static final ThreadLocal<Direction> NOCLIP = ThreadLocal.withInitial(() -> null);

    /* ------------ Motion state ------------ */
    // Progress in [0..1] over the whole up/down stroke of the ring.
    private float progressO = 0.0f;
    private float progress  = 0.0f;
    private boolean extending = true;   // true = ring rising, false = ring lowering

    // Step per tick (as a fraction of [0..1]). Keep this in sync with your engine speed:
    // If your engine uses blocks/tick SPEED_B (e.g., 0.01097), then step = SPEED_B / MAX_OFFSET.
    // You can also wire this from RedstoneEngineBlockEntity.SPEED_PUBLIC if you prefer.
    private static final float STEP_PER_TICK = 0.02925f; // <- matches your previous progress step

    public EngineRingMovingBlockEntity(BlockPos pos, BlockState state) {
        super(com.nick.buildcraft.registry.ModBlockEntity.ENGINE_RING_MOVING.get(), pos, state);
    }

    /* ====================================================================== */
    /*                                  TICK                                  */
    /* ====================================================================== */
    public static void tick(Level level, BlockPos pos, BlockState state, EngineRingMovingBlockEntity be) {
        be.progressO = be.progress;

        // Advance with piston-like pre-move: compute "partial" BEFORE we commit, then push entities using that.
        float step = STEP_PER_TICK;
        float partial = be.progress + step;
        if (be.extending) {
            partial = Math.min(1.0f, partial);
        } else {
            partial = Math.max(0.0f, be.progress - step);
        }

        // Move entities using swept volume between progress and partial (piston logic).
        moveCollidedEntities(level, pos, partial, be);

        // Commit progress after entity motion.
        be.progress = partial;

        // Done? Remove the moving block; your engine should resume static collision.
        if (be.progress <= 0.0f || be.progress >= 1.0f) {
            level.removeBlock(pos, false);
        }
    }

    /* ====================================================================== */
    /*                         Piston-style entity push                        */
    /* ====================================================================== */
    private static void moveCollidedEntities(Level level, BlockPos pos, float partial, EngineRingMovingBlockEntity be) {
        // We only push UP/DOWN for this engine; choose the direction based on whether we’re extending.
        final Direction dir = Direction.UP;
        final double dProg  = Math.abs(partial - be.progress);
        if (dProg <= 0.0) return;

        // Distance (in world blocks) moved this tick.
        final double moveThisTick = dProg * MAX_OFFSET;

        // The ring shape at the "partial" position (where we want entities to be next).
        VoxelShape ringAtPartial = ringFrameAt(pos, partial);
        if (ringAtPartial.isEmpty()) return;

        // Compute swept region (expand along +Y by moveThisTick) to gather candidate entities.
        AABB sweep = expandForMovement(ringAtPartial.bounds(), dir, moveThisTick);
        for (Entity e : level.getEntities(null, sweep)) {
            if (e.isSpectator() || e.getPistonPushReaction() == PushReaction.IGNORE) continue;

            // Find how far we need to push THIS entity along +Y so it clears the ring this tick.
            double needed = 0.0;
            for (AABB part : ringAtPartial.toAabbs()) {
                AABB moved = expandForMovement(part, dir, moveThisTick);
                if (moved.intersects(e.getBoundingBox())) {
                    needed = Math.max(needed, getMovement(moved, dir, e.getBoundingBox()));
                    if (needed >= moveThisTick) break;
                }
            }
            if (needed <= 0.0) continue;

            // Cap to one tick of travel and add the piston "+0.01" headroom.
            double push = Math.min(needed, moveThisTick) + PUSH_EPS;

            // NOCLIP like pistons: allow smooth lateral motion while being carried.
            NOCLIP.set(dir);
            Vec3 before = e.position();
            e.move(MoverType.PISTON, new Vec3(0.0, push, 0.0));
            e.applyEffectsFromBlocks(before, e.position());
            e.removeLatestMovementRecording();
            NOCLIP.set(null);

            // No fall damage while riding the platform.
            e.fallDistance = 0.0f;
        }
    }

    private static AABB expandForMovement(AABB aabb, Direction dir, double dist) {
        // Expand the AABB only in 'dir' by 'dist' (positive distance).
        return switch (dir.getAxis()) {
            case X -> dir.getStepX() > 0
                    ? new AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX + dist, aabb.maxY, aabb.maxZ)
                    : new AABB(aabb.minX - dist, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
            case Y -> dir.getStepY() > 0
                    ? new AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY + dist, aabb.maxZ)
                    : new AABB(aabb.minX, aabb.minY - dist, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
            case Z -> dir.getStepZ() > 0
                    ? new AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ + dist)
                    : new AABB(aabb.minX, aabb.minY, aabb.minZ - dist, aabb.maxX, aabb.maxY, aabb.maxZ);
        };
    }

    private static double getMovement(AABB headShape, Direction dir, AABB entityBB) {
        // "How much do we need to slide the entity along dir so the volumes stop intersecting?"
        return switch (dir) {
            case EAST  -> headShape.maxX - entityBB.minX;
            case WEST  -> entityBB.maxX - headShape.minX;
            case UP    -> headShape.maxY - entityBB.minY;
            case DOWN  -> entityBB.maxY - headShape.minY;
            case SOUTH -> headShape.maxZ - entityBB.minZ;
            case NORTH -> entityBB.maxZ - headShape.minZ;
        };
    }

    /* ====================================================================== */
    /*                          Collision shape exposure                       */
    /* ====================================================================== */
    public VoxelShape getCollisionShape(BlockGetter level, BlockPos pos) {
        // EXACT piston trick:
        // While the current entity is being pushed in the *same* direction this tick
        // (NOCLIP==UP) and we’re still mid-move (progress<1), expose an EMPTY shape.
        // This lets players strafe smoothly while riding up.
        Direction d = NOCLIP.get();
        if (this.progress < 1.0f && d == Direction.UP) {
            return Shapes.empty();
        }

        // Otherwise, expose the ring frame at the current progress (solid collision).
        return ringFrameAt(pos, this.progress);
    }

    /* ====================================================================== */
    /*                                Helpers                                  */
    /* ====================================================================== */
    private static VoxelShape ringFrameAt(BlockPos pos, float prog) {
        final double off = (double)prog * MAX_OFFSET;
        final double x = pos.getX();
        final double y0 = pos.getY() + RING_Y0_LOCAL + off;
        final double y1 = pos.getY() + RING_Y1_LOCAL + off;
        final double z = pos.getZ();

        VoxelShape v = Shapes.empty();
        // West bar
        v = Shapes.joinUnoptimized(v, Shapes.create(x + OUT0, y0, z + OUT0, x + IN0,  y1, z + OUT1), BooleanOp.OR);
        // East bar
        v = Shapes.joinUnoptimized(v, Shapes.create(x + IN1,  y0, z + OUT0, x + OUT1, y1, z + OUT1), BooleanOp.OR);
        // North bar
        v = Shapes.joinUnoptimized(v, Shapes.create(x + IN0,  y0, z + OUT0, x + IN1,  y1, z + OUT0 + TH), BooleanOp.OR);
        // South bar
        v = Shapes.joinUnoptimized(v, Shapes.create(x + IN0,  y0, z + OUT1 - TH, x + IN1, y1, z + OUT1), BooleanOp.OR);

        return v;
    }

    /* ------------ API for the spawner (engine BE) ------------ */

    /** Set direction: true=rising, false=lowering. */
    public void setExtending(boolean extending) {
        this.extending = extending;
    }

    /** Initialize the progress to current ring position [0..1]. */
    public void setProgress(float progress) {
        this.progress = this.progressO = Math.max(0.0f, Math.min(1.0f, progress));
    }
}
