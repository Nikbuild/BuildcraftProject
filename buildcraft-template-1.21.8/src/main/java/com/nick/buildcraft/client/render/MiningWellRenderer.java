package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.miningwell.MiningWellBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Minimal renderer for the Mining Well controller.
 * - Keeps rendering when the controller is off-screen.
 * - Enlarges the render AABB vertically so the deep pipe column doesn't get culled.
 * - No custom geometry yet (block model handles visuals). Add FX inside {@link #render} later.
 */
public final class MiningWellRenderer implements BlockEntityRenderer<MiningWellBlockEntity> {

    public MiningWellRenderer(BlockEntityRendererProvider.Context ctx) {
        // keep for future use (baked models, atlas sprites, etc.)
    }

    @Override
    public void render(MiningWellBlockEntity be,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay,
                       Vec3 cameraPos) {
        // No custom drawing yet.
    }

    /** Keep this renderer active even when outside the frustum. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    /** Also allow rendering when far from the camera (matches deep drill columns). */
    @Override
    public boolean shouldRender(MiningWellBlockEntity be, Vec3 cameraPos) {
        return true;
    }

    /** Extend the culling box vertically to cover the whole drill column. */
    @Override
    public AABB getRenderBoundingBox(MiningWellBlockEntity be) {
        Level lvl = be.getLevel();
        if (lvl == null) return BlockEntityRenderer.super.getRenderBoundingBox(be);

        BlockPos p = be.getBlockPos();
        int minY = lvl.dimensionType().minY();
        int maxY = lvl.dimensionType().height() + minY;

        // 1×(dimension height)×1 column centered on the well, with a small horizontal pad.
        return new AABB(p.getX(), minY, p.getZ(), p.getX() + 1, maxY, p.getZ() + 1).inflate(0.5);
    }

    /** Increase cull distance so the tall column is considered further away. */
    @Override
    public int getViewDistance() {
        return 256; // matches your typical drill depth
    }
}
