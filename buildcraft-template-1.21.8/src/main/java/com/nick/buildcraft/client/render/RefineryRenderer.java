package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.buildcraft.content.block.refinery.RefineryBlock;
import com.nick.buildcraft.content.block.refinery.RefineryBlockEntity;
import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

/**
 * RefineryRenderer handles client-side rendering of the refinery block.
 * Renders animated magnets with vertical offsets AND fluid levels in the three tanks.
 *
 * Tank layout (based on FACING direction):
 * - Back left: Oil Tank 1
 * - Back right: Oil Tank 2
 * - Front: Fuel Tank
 */
public final class RefineryRenderer implements BlockEntityRenderer<RefineryBlockEntity> {

    private final BlockRenderDispatcher dispatcher;

    private static final float WALL_EPS = 0.001f;
    private static final float FLOOR_CEIL_EPS = 0.002f;

    public RefineryRenderer(BlockEntityRendererProvider.Context ctx) {
        this.dispatcher = ctx.getBlockRenderDispatcher();
    }

    @Override
    public void render(RefineryBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buf, int packedLight, int packedOverlay, Vec3 cameraPos) {
        if (be == null || be.getLevel() == null) {
            return;
        }

        final BlockState state = be.getBlockState();
        final var level = be.getLevel();
        final BlockPos pos = be.getBlockPos();

        // Get interpolated magnet offsets from animation state
        float magnet1Progress = be.getMagnet1Progress(partialTick);
        float magnet2Progress = be.getMagnet2Progress(partialTick);

        // Convert 0-1 progress to actual offset
        float magnet1Offset = (float) (magnet1Progress * RefineryBlockEntity.MAGNET_TRAVEL_DISTANCE);
        float magnet2Offset = (float) (magnet2Progress * RefineryBlockEntity.MAGNET_TRAVEL_DISTANCE);

        // ===== Render Base (Tank) =====
        pose.pushPose();
        BlockState baseState = state.setValue(RefineryBlock.PART, RefineryBlock.Part.BASE);
        dispatcher.renderSingleBlock(baseState, pose, buf, packedLight, packedOverlay, level, pos);
        pose.popPose();

        // ===== Render Fluids in Tanks =====
        renderFluidTanks(be, pose, buf, packedLight, packedOverlay);

        // ===== Render Magnet 1 (Left Magnet) =====
        pose.pushPose();
        pose.translate(0.0, magnet1Offset, 0.0);

        BlockState magnet1State = state.setValue(RefineryBlock.PART, RefineryBlock.Part.MAGNET_LEFT);
        dispatcher.renderSingleBlock(magnet1State, pose, buf, packedLight, packedOverlay, level, pos);

        pose.popPose();

        // ===== Render Magnet 2 (Right Magnet) =====
        pose.pushPose();
        pose.translate(0.0, magnet2Offset, 0.0);

        BlockState magnet2State = state.setValue(RefineryBlock.PART, RefineryBlock.Part.MAGNET_RIGHT);
        dispatcher.renderSingleBlock(magnet2State, pose, buf, packedLight, packedOverlay, level, pos);

        pose.popPose();
    }

    /**
     * Renders fluid levels in the three tanks (oil tank 1, oil tank 2, fuel tank).
     * Coordinates are rotated based on block facing.
     *
     * Base layout (NORTH facing):
     * - Oil Tank 1 (back left): x [0-8], z [0-8]
     * - Oil Tank 2 (back right): x [8-16], z [0-8]
     * - Fuel Tank (front center): x [4-12], z [8-16]
     */
    private void renderFluidTanks(RefineryBlockEntity be, PoseStack pose,
                                   MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(BlockStateProperties.FACING);

        // Oil Tank 1 (back left)
        FluidStack oil1 = be.getOilTank1().getFluid();
        if (!oil1.isEmpty()) {
            float[] bounds1 = rotateTankBounds(facing, 0f / 16f, 8f / 16f, 0f / 16f, 8f / 16f);
            renderTankFluid(oil1, be.getOilTank1().getCapacity(), pose, buffers, packedLight, packedOverlay,
                    bounds1[0], bounds1[1], bounds1[2], bounds1[3]);
        }

        // Oil Tank 2 (back right)
        FluidStack oil2 = be.getOilTank2().getFluid();
        if (!oil2.isEmpty()) {
            float[] bounds2 = rotateTankBounds(facing, 8f / 16f, 16f / 16f, 0f / 16f, 8f / 16f);
            renderTankFluid(oil2, be.getOilTank2().getCapacity(), pose, buffers, packedLight, packedOverlay,
                    bounds2[0], bounds2[1], bounds2[2], bounds2[3]);
        }

        // Fuel Tank (front center)
        FluidStack fuel = be.getFuelTank().getFluid();
        if (!fuel.isEmpty()) {
            float[] boundsFuel = rotateTankBounds(facing, 4f / 16f, 12f / 16f, 8f / 16f, 16f / 16f);
            renderTankFluid(fuel, be.getFuelTank().getCapacity(), pose, buffers, packedLight, packedOverlay,
                    boundsFuel[0], boundsFuel[1], boundsFuel[2], boundsFuel[3]);
        }
    }

    /**
     * Rotates tank bounds (x0, x1, z0, z1) based on facing direction.
     * Returns [x0, x1, z0, z1] after rotation.
     * Matches blockstate JSON Y rotation around block center (0.5, 0.5).
     */
    private static float[] rotateTankBounds(Direction facing, float x0, float x1, float z0, float z1) {
        float[] result = new float[4];

        switch (facing) {
            case NORTH -> {
                // No rotation
                result[0] = x0;
                result[1] = x1;
                result[2] = z0;
                result[3] = z1;
            }
            case EAST -> {
                // Rotate 90° CCW: new_x = 1 - old_z, new_z = old_x
                float newX0 = 1.0f - z1;
                float newX1 = 1.0f - z0;
                float newZ0 = x0;
                float newZ1 = x1;
                result[0] = newX0;
                result[1] = newX1;
                result[2] = newZ0;
                result[3] = newZ1;
            }
            case SOUTH -> {
                // Rotate 180°: new_x = 1 - old_x, new_z = 1 - old_z
                float newX0 = 1.0f - x1;
                float newX1 = 1.0f - x0;
                float newZ0 = 1.0f - z1;
                float newZ1 = 1.0f - z0;
                result[0] = newX0;
                result[1] = newX1;
                result[2] = newZ0;
                result[3] = newZ1;
            }
            case WEST -> {
                // Rotate 270° CW (3x 90° rotations): Apply rotation 3 times
                // 1st: new_x = 1 - old_z, new_z = old_x
                float x0_1 = 1.0f - z1;
                float x1_1 = 1.0f - z0;
                float z0_1 = x0;
                float z1_1 = x1;
                // 2nd: new_x = 1 - new_z, new_z = new_x
                float x0_2 = 1.0f - z1_1;
                float x1_2 = 1.0f - z0_1;
                float z0_2 = x0_1;
                float z1_2 = x1_1;
                // 3rd: new_x = 1 - new_z, new_z = new_x
                float x0_3 = 1.0f - z1_2;
                float x1_3 = 1.0f - z0_2;
                float z0_3 = x0_2;
                float z1_3 = x1_2;
                result[0] = x0_3;
                result[1] = x1_3;
                result[2] = z0_3;
                result[3] = z1_3;
            }
            default -> {
                result[0] = x0;
                result[1] = x1;
                result[2] = z0;
                result[3] = z1;
            }
        }

        return result;
    }

    /**
     * Renders a single fluid tank at the specified bounds.
     */
    private void renderTankFluid(FluidStack stack, int capacity, PoseStack pose,
                                  MultiBufferSource buffers, int packedLight, int packedOverlay,
                                  float x0, float x1, float z0, float z1) {
        if (stack.isEmpty() || capacity <= 0) return;

        // Calculate fill percentage
        float fillPercent = (float) stack.getAmount() / (float) capacity;
        if (fillPercent <= 0f) return;

        // Vertical range (bottom to top based on fill level)
        float y0 = FLOOR_CEIL_EPS;
        float y1 = Math.min(1f - FLOOR_CEIL_EPS, Math.max(fillPercent, 0.05f)); // Min 5% height for visibility

        // Add wall epsilon to x/z to avoid z-fighting with tank walls
        x0 += WALL_EPS;
        x1 -= WALL_EPS;
        z0 += WALL_EPS;
        z1 -= WALL_EPS;

        // Get fluid sprite
        ResourceLocation spriteLoc = getFluidSprite(stack);
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(spriteLoc);

        // Get fluid color (oil and fuel are white/untinted)
        int r = 255, g = 255, b = 255, a = 210;

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f mat = pose.last().pose();

        // Draw all 6 faces
        putFlatQuadTop(vc, mat, x0, x1, z0, z1, y1, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadBottom(vc, mat, x0, x1, z0, z1, y0, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadNorth(vc, mat, x0, x1, y0, y1, z0, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadSouth(vc, mat, x0, x1, y0, y1, z1, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadWest(vc, mat, z0, z1, y0, y1, x0, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadEast(vc, mat, z0, z1, y0, y1, x1, sprite, packedLight, packedOverlay, r, g, b, a);
    }

    /**
     * Gets the texture sprite for the given fluid.
     */
    private ResourceLocation getFluidSprite(FluidStack stack) {
        if (stack.getFluid() == ModFluids.OIL.get() || stack.getFluid() == ModFluids.FLOWING_OIL.get()) {
            return ModFluids.OIL_STILL;
        } else if (stack.getFluid() == ModFluids.FUEL.get() || stack.getFluid() == ModFluids.FLOWING_FUEL.get()) {
            return ModFluids.FUEL_STILL;
        } else {
            // Fallback to water
            return ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
        }
    }

    /* ---------- Quad helper methods (copied from TankBlockEntityRenderer) ---------- */

    private static void putFlatQuadTop(VertexConsumer vc, Matrix4f mat,
                                       float x0, float x1, float z0, float z1, float y,
                                       TextureAtlasSprite sprite, int light, int overlay,
                                       int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x0, y, z0).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
        vc.addVertex(mat, x1, y, z0).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
        vc.addVertex(mat, x1, y, z1).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
        vc.addVertex(mat, x0, y, z1).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
    }

    private static void putFlatQuadBottom(VertexConsumer vc, Matrix4f mat,
                                          float x0, float x1, float z0, float z1, float y,
                                          TextureAtlasSprite sprite, int light, int overlay,
                                          int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x0, y, z1).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(0, -1, 0);
        vc.addVertex(mat, x1, y, z1).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(0, -1, 0);
        vc.addVertex(mat, x1, y, z0).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(0, -1, 0);
        vc.addVertex(mat, x0, y, z0).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(0, -1, 0);
    }

    private static void putFlatQuadNorth(VertexConsumer vc, Matrix4f mat,
                                         float x0, float x1, float y0, float y1, float z,
                                         TextureAtlasSprite sprite, int light, int overlay,
                                         int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x0, y1, z).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(0, 0, -1);
        vc.addVertex(mat, x1, y1, z).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(0, 0, -1);
        vc.addVertex(mat, x1, y0, z).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(0, 0, -1);
        vc.addVertex(mat, x0, y0, z).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(0, 0, -1);
    }

    private static void putFlatQuadSouth(VertexConsumer vc, Matrix4f mat,
                                         float x0, float x1, float y0, float y1, float z,
                                         TextureAtlasSprite sprite, int light, int overlay,
                                         int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x1, y1, z).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(mat, x0, y1, z).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(mat, x0, y0, z).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(mat, x1, y0, z).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(0, 0, 1);
    }

    private static void putFlatQuadWest(VertexConsumer vc, Matrix4f mat,
                                        float z0, float z1, float y0, float y1, float x,
                                        TextureAtlasSprite sprite, int light, int overlay,
                                        int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x, y1, z1).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(-1, 0, 0);
        vc.addVertex(mat, x, y1, z0).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(-1, 0, 0);
        vc.addVertex(mat, x, y0, z0).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(-1, 0, 0);
        vc.addVertex(mat, x, y0, z1).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(-1, 0, 0);
    }

    private static void putFlatQuadEast(VertexConsumer vc, Matrix4f mat,
                                        float z0, float z1, float y0, float y1, float x,
                                        TextureAtlasSprite sprite, int light, int overlay,
                                        int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x, y1, z0).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(1, 0, 0);
        vc.addVertex(mat, x, y1, z1).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(1, 0, 0);
        vc.addVertex(mat, x, y0, z1).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(1, 0, 0);
        vc.addVertex(mat, x, y0, z0).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(1, 0, 0);
    }
}
