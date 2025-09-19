package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nick.buildcraft.content.block.engine.EngineBlock;
import com.nick.buildcraft.content.block.engine.EngineBlockEntity;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlock;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RedstoneEngineRenderer implements BlockEntityRenderer<EngineBlockEntity> {
    private final BlockRenderDispatcher dispatcher;

    public RedstoneEngineRenderer(BlockEntityRendererProvider.Context ctx) {
        this.dispatcher = ctx.getBlockRenderDispatcher();
    }

    @Override
    public void render(
            EngineBlockEntity be,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buf,
            int packedLight,
            int packedOverlay,
            Vec3 cameraPos
    ) {
        if (be == null || be.getLevel() == null) return;

        final var level = be.getLevel();
        final BlockPos pos = be.getBlockPos();
        final BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof RedstoneEngineBlock)) return;

        final Direction facing = state.getValue(EngineBlock.FACING);
        final float offset = be.getRenderOffset(partialTick); // ~0..0.5 along engine axis

        // Base light to use for both animated parts.
        // Using the value provided by the caller avoids "all black" cases.
        int lightForParts = packedLight;

        // Optional: if the ring moves upward, brighten a bit using sky from the block above.
        if (facing == Direction.UP && offset > 0.01f) {
            lightForParts = LevelRenderer.getLightColor(level, pos.above());
        }

        // ----------------- BELLOWS (scale along local +Y) -----------------
        final float maxTravel = 8f / 16f;
        final float progress01 = Mth.clamp(maxTravel == 0f ? 0f : (offset / maxTravel), 0f, 1f);

        pose.pushPose();
        rotatePoseToFacingCentered(pose, facing);
        pose.translate(0.0, 4f / 16f, 0.0);
        pose.scale(1f, progress01, 1f);
        pose.translate(0.0, -4f / 16f, 0.0);

        BlockState bellows = state
                .setValue(RedstoneEngineBlock.PART, RedstoneEngineBlock.Part.BELLOWS)
                // prevent double rotation; pose already points +Y along FACING
                .setValue(EngineBlock.FACING, Direction.UP);
        dispatcher.renderSingleBlock(bellows, pose, buf, lightForParts, packedOverlay, level, pos);
        pose.popPose();

        // --------------------- RING (translate on local +Y) ---------------------
        pose.pushPose();
        rotatePoseToFacingCentered(pose, facing);
        pose.translate(0.0, offset, 0.0);

        BlockState ring = state
                .setValue(RedstoneEngineBlock.PART, RedstoneEngineBlock.Part.RING)
                .setValue(EngineBlock.FACING, Direction.UP);
        dispatcher.renderSingleBlock(ring, pose, buf, lightForParts, packedOverlay, level, pos);
        pose.popPose();
    }

    /** Rotate so local +Y points along {@code facing}, pivoting at the block center. */
    private static void rotatePoseToFacingCentered(PoseStack pose, Direction facing) {
        pose.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case UP    -> { /* no rotation */ }
            case DOWN  -> pose.mulPose(Axis.XP.rotationDegrees(180)); // +Y -> -Y
            case NORTH -> pose.mulPose(Axis.XN.rotationDegrees(90));  // +Y -> -Z
            case SOUTH -> pose.mulPose(Axis.XP.rotationDegrees(90));  // +Y -> +Z
            case WEST  -> pose.mulPose(Axis.ZP.rotationDegrees(90));  // +Y -> -X
            case EAST  -> pose.mulPose(Axis.ZN.rotationDegrees(90));  // +Y -> +X
        }
        pose.translate(-0.5, -0.5, -0.5);
    }

    @Override
    public AABB getRenderBoundingBox(EngineBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(0.5, 0.75, 0.5);
    }
}
