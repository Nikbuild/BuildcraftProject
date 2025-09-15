// RedstoneEngineRenderer.java
package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlock;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlockEntity;
import com.nick.buildcraft.energy.Energy;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RedstoneEngineRenderer implements BlockEntityRenderer<RedstoneEngineBlockEntity> {
    private final BlockRenderDispatcher dispatcher;

    public RedstoneEngineRenderer(BlockEntityRendererProvider.Context ctx) {
        this.dispatcher = ctx.getBlockRenderDispatcher();
    }

    @Override
    public void render(RedstoneEngineBlockEntity be,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buf,
                       int light,
                       int overlay,
                       Vec3 cameraPos) {
        if (be == null || be.getLevel() == null) return;

        final var level = be.getLevel();
        final BlockPos pos = be.getBlockPos();

        // Ring travel: 8px (from y=4..8 up to y=12..16)
        final float amp = 8f / 16f;
        final boolean powered = level.hasNeighborSignal(pos);

        // Warmup -> speed
        final int w = be.getWarmupClient();
        final float heat  = Math.min(1f, w / (float) Energy.REDSTONE_ENGINE_WARMUP_TICKS);
        final float speed = powered ? (0.04f + 0.10f * heat) : 0f;

        // ---- Constant-velocity (stiff) stroke using a triangle wave 0..1
        float phase = ((level.getGameTime() + partialTick) * speed) % 1f; // 0..1
        float tri   = phase < 0.5f ? (phase * 2f) : (1f - (phase - 0.5f) * 2f); // up then down, 0..1
        float y     = powered ? tri * amp : 0f; // vertical offset for the ring, 0..amp

        // ---- Lighting (avoid black moving parts)
        // Sample above for both moving pieces so alpha textures stay lit.
        final int bellowsLight = LevelRenderer.getLightColor(level, pos.above());
        final int ringLight    = LevelRenderer.getLightColor(level, pos.above());

        // ---- BELLOWS (reveals from y=4 -> y=12 as the ring rises)
        float progress = (amp == 0f) ? 0f : (y / amp); // 0..1
        pose.pushPose();
        pose.translate(0.0, 4f / 16f, 0.0); // pivot at bellows bottom
        pose.scale(1f, progress, 1f);       // invisible at 0, full height at top
        pose.translate(0.0, -4f / 16f, 0.0);

        BlockState bellows = be.getBlockState()
                .setValue(RedstoneEngineBlock.PART, RedstoneEngineBlock.Part.BELLOWS);
        dispatcher.renderSingleBlock(bellows, pose, buf, bellowsLight, overlay, level, pos);
        pose.popPose();

        // ---- RING (pusher)
        pose.pushPose();
        pose.translate(0.0, y, 0.0);
        BlockState ring = be.getBlockState()
                .setValue(RedstoneEngineBlock.PART, RedstoneEngineBlock.Part.RING);
        dispatcher.renderSingleBlock(ring, pose, buf, ringLight, overlay, level, pos);
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(RedstoneEngineBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(0.0, 0.6, 0.0);
    }
}
