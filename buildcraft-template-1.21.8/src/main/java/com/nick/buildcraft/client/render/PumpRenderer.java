package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.pump.PumpBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the pump's suction hose as a vertical stack of the baked model
 * pump_tube_segment, perfectly centered under the pump block.
 *
 * Notes:
 * - We do NOT subtract camPos. The pose we get is already positioned at
 *   (pumpPos - cameraPos), same as QuarryRenderer.
 * - Each segment is drawn as if it lives in the block directly below the pump,
 *   so visually the hose is a straight column down from the center of the pump.
 */
public class PumpRenderer implements BlockEntityRenderer<PumpBlockEntity> {

    private final BlockRenderDispatcher brd;

    public PumpRenderer(BlockEntityRendererProvider.Context ctx) {
        this.brd = Minecraft.getInstance().getBlockRenderer();
    }

    @Override
    public void render(
            PumpBlockEntity be,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay,
            Vec3 camPos // provided by MC, but we don't manually use it
    ) {
        Level level = be.getLevel();
        if (level == null || be.isRemoved()) return;

        // animated bottom of hose
        Integer hoseTipY = be.getTubeRenderY();
        if (hoseTipY == null) return;

        BlockPos pumpPos = be.getBlockPos();
        int pumpY = pumpPos.getY();
        int tipY  = hoseTipY;

        // only draw if it's actually below the pump
        if (tipY >= pumpY) return;

        // we'll render from just under the pump down to just above the tip
        int startY = pumpY - 1;
        int endY   = tipY + 1;
        if (endY > startY) return;

        // baked skinny tube blockstate
        var tubeState = ModBlocks.PUMP_TUBE_SEGMENT.get().defaultBlockState();

        // pose is already translated so that (0,0,0) == pumpPos in camera space.
        // So translating by (0, dy, 0) moves to world y = pumpY + dy.
        for (int y = startY; y >= endY; y--) {
            int dy = y - pumpY;

            // lighting for this segment at its actual world position
            BlockPos segWorldPos = new BlockPos(pumpPos.getX(), y, pumpPos.getZ());
            int segLight = LevelRenderer.getLightColor(level, segWorldPos);

            pose.pushPose();

            // OLD (off-center):
            // pose.translate(-0.5, dy, -0.5);
            //
            // NEW (centered under the pump X/Z):
            pose.translate(0.0, dy, 0.0);

            brd.renderSingleBlock(
                    tubeState,
                    pose,
                    buffers,
                    segLight,
                    packedOverlay
            );

            pose.popPose();
        }
    }

    /* ---------------- culling / bounds hints ---------------- */

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public boolean shouldRender(PumpBlockEntity be, Vec3 cameraPos) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(PumpBlockEntity be) {
        Level lvl = be.getLevel();
        if (lvl == null) {
            return BlockEntityRenderer.super.getRenderBoundingBox(be);
        }

        BlockPos p = be.getBlockPos();
        int minY = lvl.dimensionType().minY();
        int maxY = lvl.dimensionType().height() + minY;

        // huge vertical column so the hose never gets frustum-culled early
        return new AABB(
                p.getX(), minY, p.getZ(),
                p.getX() + 1, maxY, p.getZ() + 1
        ).inflate(0.5);
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
