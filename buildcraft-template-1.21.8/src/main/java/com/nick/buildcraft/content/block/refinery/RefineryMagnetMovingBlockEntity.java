package com.nick.buildcraft.content.block.refinery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * RefineryMagnetMovingBlockEntity handles the physics of moving magnet blocks.
 * Similar to EngineRingMovingBlockEntity but for refinery magnets.
 *
 * This entity is placed when a magnet is actively moving, handling collision
 * detection and entity pushing like a piston would.
 */
public class RefineryMagnetMovingBlockEntity extends BlockEntity {

    public static final float STEP_PER_TICK = 0.02925f; // ~0.75 blocks / 25 ticks per phase
    public static final float MAX_TRAVEL = 0.75f;

    // Animation state
    private float progress = 0.0F;
    private float progressO = 0.0F;
    private boolean extending = true; // Direction: true = moving away, false = returning

    // Which magnet this is (1 or 2)
    private int magnetIndex = 1;

    public RefineryMagnetMovingBlockEntity(BlockPos pos, BlockState state) {
        super(RefineryBlockEntityType.REFINERY_MAGNET_MOVING().get(), pos, state);
    }

    /**
     * Called every server tick to update moving magnet state.
     */
    public static void tick(Level level, BlockPos pos, BlockState state, RefineryMagnetMovingBlockEntity entity) {
        if (level.isClientSide) {
            return;
        }

        entity.progressO = entity.progress;

        float step = STEP_PER_TICK;
        float nextProgress;

        if (entity.extending) {
            nextProgress = Math.min(1.0F, entity.progress + step);
        } else {
            nextProgress = Math.max(0.0F, entity.progress - step);
        }

        // Move entities in the swept volume (piston-style collision)
        moveCollidedEntities(level, pos, entity.progress, nextProgress, entity.magnetIndex);

        entity.progress = nextProgress;

        // Remove block when animation complete
        if (entity.progress <= 0.0F || entity.progress >= 1.0F) {
            level.removeBlock(pos, false);
        }

        entity.setChanged();
    }

    /**
     * Move entities caught in the magnet's swept volume.
     * This mimics piston push behavior.
     */
    private static void moveCollidedEntities(Level level, BlockPos pos, float oldProgress, float newProgress, int magnetIndex) {
        // TODO: Implement entity pushing like pistons do
        // For now, this is a placeholder for future implementation
        // Would calculate swept volume and push entities accordingly
    }

    /**
     * Get the current vertical offset of this moving magnet.
     */
    public float getOffset(float partialTick) {
        float lerped = net.minecraft.util.Mth.lerp(partialTick, progressO, progress);
        return lerped * MAX_TRAVEL;
    }

    /**
     * Check if magnet is extending (moving away from center).
     */
    public boolean isExtending() {
        return extending;
    }

    /**
     * Set the magnet direction.
     */
    public void setExtending(boolean extending) {
        this.extending = extending;
    }

    /**
     * Set which magnet this entity represents (1 or 2).
     */
    public void setMagnetIndex(int index) {
        this.magnetIndex = index;
    }

    public int getMagnetIndex() {
        return magnetIndex;
    }

    public float getProgress() {
        return progress;
    }

    public float getPreviousProgress() {
        return progressO;
    }

    // ===== NBT Serialization =====

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        progress = in.getFloatOr("progress", 0.0F);
        progressO = in.getFloatOr("progressO", 0.0F);
        extending = in.getBooleanOr("extending", true);
        magnetIndex = in.getIntOr("magnetIndex", 1);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putFloat("progress", progress);
        out.putFloat("progressO", progressO);
        out.putBoolean("extending", extending);
        out.putInt("magnetIndex", magnetIndex);
    }
}
