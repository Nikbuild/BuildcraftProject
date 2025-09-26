// src/main/java/com/nick/buildcraft/content/block/engine/EngineBlockEntity.java
package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class EngineBlockEntity extends BaseEngineBlockEntity {
    private static final double PUSH_EPSILON = 1.0E-5;
    private static final int FE_PER_PULSE = 80;

    private static final int PERIOD_REDSTONE   = 40;
    private static final int PERIOD_STIRLING   = 20;
    private static final int PERIOD_COMBUSTION = 10;

    protected final EngineType type;

    private float progress  = 0.0f;
    private float progressO = 0.0f;

    private int  pumpTick     = 0;
    private int  cachedPeriod = 20;
    private boolean wasPowered = false;

    public EngineBlockEntity(EngineType type, BlockPos pos, BlockState state) {
        super(ModBlockEntity.ENGINE.get(), pos, state);
        this.type = type;
        this.cachedPeriod = basePeriodFor(type);
    }

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

    public static void serverTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        BaseEngineBlockEntity.serverTick(level, pos, state, be);
        be.tick(level, pos, state);
    }
    public static void clientTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        be.tick(level, pos, state);
    }

    protected void tick(Level level, BlockPos pos, BlockState state) {
        boolean powered = isActive(state);
        cachedPeriod = basePeriodFor(type);
        int period   = Math.max(4, cachedPeriod);

        if (!powered) {
            // HARD stop: clear pulse, zero progress, freeze client & server
            if (!level.isClientSide && state.getBlock() instanceof EngineBlock eb && state.getValue(EngineBlock.PULSING)) {
                level.setBlock(pos, state.setValue(EngineBlock.PULSING, Boolean.FALSE), Block.UPDATE_CLIENTS);
            }
            pumpTick = 0;
            progress = progressO = 0f;
            wasPowered = false;
            setChanged();
            return;
        }

        if (!wasPowered) {
            pumpTick = 0;
            progress = progressO = 0f;
            wasPowered = true;
        }

        progressO = progress;

        pumpTick++;
        if (pumpTick > period) pumpTick = 1;

        int half = period / 2;
        float stroke = (pumpTick <= half)
                ? (pumpTick / (float)Math.max(1, half))
                : (1f - ((pumpTick - half) / (float)Math.max(1, period - half)));
        setProgress(level, pos, stroke);

        if (!level.isClientSide && pumpTick == half) {
            firePumpPulse(level, pos, state);
        }

        setChanged();
    }

    private void setProgress(Level level, BlockPos pos, float next) {
        double offPrev = offset(progress);
        double offNow  = offset(next);
        if (!level.isClientSide && offPrev != offNow) {
            pushLikePiston(level, pos, offPrev, offNow);
        }
        progress = next;
    }

    private void firePumpPulse(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof EngineBlock eb) {
            level.setBlock(pos, state.setValue(EngineBlock.PULSING, Boolean.TRUE), Block.UPDATE_CLIENTS);
            if (!level.isClientSide) {
                ((net.minecraft.server.level.ServerLevel) level).scheduleTick(pos, state.getBlock(), 2);
            }
        }

        Direction facing = state.getValue(EngineBlock.FACING);
        BlockPos neighborPos = pos.relative(facing);
        Direction from = facing.getOpposite();

        var be = level.getBlockEntity(neighborPos);
        if (be instanceof EnginePulseAcceptorApi acceptor) {
            acceptor.acceptEnginePulse(from);
        }

        var sink = net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK;
        var ie = level.getCapability(sink, neighborPos, from);
        if (ie != null) ie.receiveEnergy(FE_PER_PULSE, false);
    }

    private double offset(float prog) { return RingShapes.MAX_TRAVEL_BLOCKS * prog; }

    private void pushLikePiston(Level level, BlockPos pos, double offPrev, double offNow) {
        boolean movingUp = offNow > offPrev;
        double moveAmt   = Math.abs(offNow - offPrev);
        if (moveAmt <= 0) return;

        VoxelShape start = type.spec.ring().plateAt(offPrev);
        VoxelShape end   = type.spec.ring().plateAt(offNow);

        AABB a0 = start.bounds(), a1 = end.bounds();
        AABB sweep = new AABB(
                Math.min(a0.minX, a1.minX), Math.min(a0.minY, a1.minY), Math.min(a0.minZ, a1.minZ),
                Math.max(a0.maxX, a1.maxX), Math.max(a0.maxY, a1.maxY), Math.max(a0.maxZ, a1.maxZ)
        );

        List<Entity> candidates = level.getEntities(null, sweep);
        if (candidates.isEmpty()) return;

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

    public float  getRenderOffset(float partialTicks) { return (float)(Mth.lerp(partialTicks, progressO, progress) * RingShapes.MAX_TRAVEL_BLOCKS); }
    public double getCollisionOffset() { return offset(progress); }
    public boolean isMovingUpForCollision() { return progress > progressO && progress < 1.0f; }

    private static int basePeriodFor(EngineType type) {
        return switch (type) {
            case REDSTONE   -> PERIOD_REDSTONE;
            case STIRLING   -> PERIOD_STIRLING;
            case COMBUSTION -> PERIOD_COMBUSTION;
        };
    }
}
