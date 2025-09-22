package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Shared engine BE; variants are driven by {@link EngineType}. */
public class EngineBlockEntity extends BaseEngineBlockEntity {

    private static final double PUSH_EPSILON = 1.0E-5;

    protected final EngineType type;     // <- protected for subclasses

    private float  progress  = 0.0f; // [0..1]
    private float  progressO = 0.0f; // [0..1]

    /** Profile state: when true, next powered tick will “bang” to 1.0. */
    private boolean armed = true;

    public EngineBlockEntity(EngineType type, BlockPos pos, BlockState state) {
        super(ModBlockEntity.ENGINE.get(), pos, state);
        this.type = type;
    }

    /* ---------- BaseEngineBlockEntity hooks ---------- */

    @Override
    protected boolean isActive(BlockState state) {
        return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
    }

    @Override
    protected int getGenerationPerTick() {
        int w = warmupTicks();
        var s = type.spec;
        if (w >= s.warmupTicks()) return s.genHot();
        if (w >= s.warmupTicks() / 2) return s.genWarm();
        return s.genCold();
    }

    /* ---------- tick ---------- */

    public static void serverTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        BaseEngineBlockEntity.serverTick(level, pos, state, be);
        be.tick(level, pos, state);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        be.tick(level, pos, state);
    }

    /** Made protected so SteamEngineBlockEntity can reuse it. */
    protected void tick(Level level, BlockPos pos, BlockState state) {
        boolean powered = isActive(state);
        progressO = progress;

        boolean[] flag = new boolean[]{ this.armed };
        float next = type.spec.stroke().next(
                progress, powered,
                type.spec.extendStep(), type.spec.retractStep(),
                flag
        );
        this.armed = flag[0];

        // Server-side entity push (swept collision like pistons)
        double offPrev = offset(progress), offNow = offset(next);
        if (!level.isClientSide && offPrev != offNow) pushLikePiston(level, pos, offPrev, offNow);

        progress = next;
        setChanged();
    }

    /* ---------- sweeping push (vertical only) ---------- */

    private double offset(float prog) { return RingShapes.MAX_TRAVEL_BLOCKS * prog; }

    private void pushLikePiston(Level level, BlockPos pos, double offPrev, double offNow) {
        boolean movingUp = offNow > offPrev;
        double moveAmt   = Math.abs(offNow - offPrev);
        if (moveAmt <= 0) return;

        VoxelShape start = type.spec.ring().plateAt(offPrev);
        VoxelShape end   = type.spec.ring().plateAt(offNow);

        // Sweep bounds
        AABB a0 = start.bounds(), a1 = end.bounds();
        AABB sweep = new AABB(
                Math.min(a0.minX, a1.minX), Math.min(a0.minY, a1.minY), Math.min(a0.minZ, a1.minZ),
                Math.max(a0.maxX, a1.maxX), Math.max(a0.maxY, a1.maxY), Math.max(a0.maxZ, a1.maxZ)
        );

        List<Entity> candidates = level.getEntities(null, sweep);
        if (candidates.isEmpty()) return;

        // Per-part swept AABBs (Y only)
        List<AABB> partsStart = start.toAabbs();
        List<AABB> partsEnd   = end.toAabbs();
        List<AABB> sweptParts = new ArrayList<>(partsStart.size());
        for (int i = 0; i < partsStart.size(); i++) {
            AABB s = partsStart.get(i), e = partsEnd.get(i);
            sweptParts.add(new AABB(
                    s.minX, Math.min(s.minY, e.minY), s.minZ,
                    s.maxX, Math.max(s.maxY, e.maxY), s.maxZ
            ));
        }

        for (Entity ent : candidates) {
            if (ent.isSpectator() || ent.getPistonPushReaction() == PushReaction.IGNORE) continue;

            double needed = 0.0;
            AABB bb = ent.getBoundingBox();
            for (AABB sp : sweptParts) {
                if (!sp.intersects(bb)) continue;
                double pen = movingUp ? (sp.maxY - bb.minY) : (bb.maxY - sp.minY);
                if (pen > needed) needed = pen;
                if (needed >= moveAmt) break;
            }
            if (needed <= 0.0) continue;

            double push = Math.min(needed + PUSH_EPSILON, moveAmt);
            if (!movingUp) push = -push;

            Vec3 before = ent.position();
            ent.move(MoverType.PISTON, new Vec3(0.0, push, 0.0));
            ent.applyEffectsFromBlocks(before, ent.position());
            ent.fallDistance = 0.0f;
        }
    }

    /* ----- helpers for the block ----- */
    public float  getRenderOffset(float partialTicks) { return (float)(Mth.lerp(partialTicks, progressO, progress) * RingShapes.MAX_TRAVEL_BLOCKS); }
    public double getCollisionOffset() { return offset(progress); }
    public boolean isMovingUpForCollision() { return progress > progressO && progress < 1.0f; }
}
