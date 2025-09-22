// was: RedstoneEngineRenderer
package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nick.buildcraft.content.block.engine.EngineBlock;
import com.nick.buildcraft.content.block.engine.EngineBlockEntity;
import com.nick.buildcraft.content.block.engine.RedstoneEngineBlock;
import com.nick.buildcraft.content.block.engine.StirlingEngineBlock;   // NEW
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

public final class EngineRenderer implements BlockEntityRenderer<EngineBlockEntity> {  // renamed
    private final BlockRenderDispatcher dispatcher;

    public EngineRenderer(BlockEntityRendererProvider.Context ctx) {
        this.dispatcher = ctx.getBlockRenderDispatcher();
    }

    @Override
    public void render(EngineBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buf, int packedLight, int packedOverlay, Vec3 cameraPos) {
        if (be == null || be.getLevel() == null) return;

        final BlockState state = be.getBlockState();
        final boolean isRed = state.getBlock() instanceof RedstoneEngineBlock;
        final boolean isSti = state.getBlock() instanceof StirlingEngineBlock;
        if (!isRed && !isSti) return; // only render for our engine blocks

        final var level = be.getLevel();
        final BlockPos pos = be.getBlockPos();
        final Direction facing = state.getValue(EngineBlock.FACING);
        final float offset = be.getRenderOffset(partialTick); // ~0..0.5 along axis

        int lightForParts = packedLight;
        if (facing == Direction.UP && offset > 0.01f) {
            lightForParts = LevelRenderer.getLightColor(level, pos.above());
        }

        // ----- BELLOWS (scale along local +Y) -----
        final float maxTravel = 8f / 16f;
        final float progress01 = Mth.clamp(maxTravel == 0f ? 0f : (offset / maxTravel), 0f, 1f);

        pose.pushPose();
        rotatePoseToFacingCentered(pose, facing);
        pose.translate(0.0, 4f / 16f, 0.0);
        pose.scale(1f, progress01, 1f);
        pose.translate(0.0, -4f / 16f, 0.0);

        BlockState bellows = isRed
                ? state.setValue(RedstoneEngineBlock.PART, RedstoneEngineBlock.Part.BELLOWS)
                .setValue(EngineBlock.FACING, Direction.UP)
                : state.setValue(StirlingEngineBlock.PART, StirlingEngineBlock.Part.BELLOWS)
                .setValue(EngineBlock.FACING, Direction.UP);

        dispatcher.renderSingleBlock(bellows, pose, buf, lightForParts, packedOverlay, level, pos);
        pose.popPose();

        // ----- RING (translate on local +Y) -----
        pose.pushPose();
        rotatePoseToFacingCentered(pose, facing);
        pose.translate(0.0, offset, 0.0);

        BlockState ring = isRed
                ? state.setValue(RedstoneEngineBlock.PART, RedstoneEngineBlock.Part.RING)
                .setValue(EngineBlock.FACING, Direction.UP)
                : state.setValue(StirlingEngineBlock.PART, StirlingEngineBlock.Part.RING)
                .setValue(EngineBlock.FACING, Direction.UP);

        dispatcher.renderSingleBlock(ring, pose, buf, lightForParts, packedOverlay, level, pos);
        pose.popPose();
    }

    private static void rotatePoseToFacingCentered(PoseStack pose, Direction facing) {
        pose.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case UP -> {}
            case DOWN  -> pose.mulPose(Axis.XP.rotationDegrees(180));
            case NORTH -> pose.mulPose(Axis.XN.rotationDegrees(90));
            case SOUTH -> pose.mulPose(Axis.XP.rotationDegrees(90));
            case WEST  -> pose.mulPose(Axis.ZP.rotationDegrees(90));
            case EAST  -> pose.mulPose(Axis.ZN.rotationDegrees(90));
        }
        pose.translate(-0.5, -0.5, -0.5);
    }

    @Override
    public AABB getRenderBoundingBox(EngineBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(0.5, 0.75, 0.5);
    }
}
