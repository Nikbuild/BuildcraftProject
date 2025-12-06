package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.buildcraft.content.block.fluidpipe.BaseFluidPipeBlock;
import com.nick.buildcraft.content.block.fluidpipe.FluidPipeBlockEntity;
import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

/**
 * Fluid pipe renderer - uses same rendering logic as TankBlockEntityRenderer
 */
public class FluidPipeRenderer implements BlockEntityRenderer<FluidPipeBlockEntity> {

    private final Minecraft mc;

    // Pipe dimensions (4-12 pixel pipe = 0.25 to 0.75 in block units)
    private static final float PIPE_INNER = 0.25f;  // 4 pixels from edge
    private static final float PIPE_OUTER = 0.75f;  // 12 pixels from edge

    // Fluid fills slightly less than pipe interior
    private static final float FLUID_RADIUS = 0.23f;

    public FluidPipeRenderer(BlockEntityRendererProvider.Context ctx) {
        this.mc = Minecraft.getInstance();
    }

    @Override
    public void render(FluidPipeBlockEntity be,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay,
                       net.minecraft.world.phys.Vec3 cameraPos) {

        Level level = be.getLevel();
        if (level == null) return;

        float fill = be.getFillFraction();
        if (fill <= 0f) return;

        FluidStack stack = be.getDisplayedFluid();
        if (stack == null || stack.isEmpty()) return;

        Fluid fluid = stack.getFluid();
        if (fluid == null || fluid == Fluids.EMPTY) return;

        IClientFluidTypeExtensions attrs = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation tex = attrs.getStillTexture(stack);
        if (tex == null) tex = attrs.getFlowingTexture(stack);
        if (tex == null) return;

        TextureAtlasSprite sprite = mc.getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(tex);
        if (sprite == null) return;

        // Use SAME color logic as TankBlockEntityRenderer
        final int rgb;
        if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) {
            rgb = 0xFF6000;  // lava orange
        } else if (fluid == ModFluids.OIL.get() || fluid == ModFluids.FUEL.get()) {
            rgb = 0xFFFFFF;  // no tint for custom fluids (already colored)
        } else {
            rgb = 0x3F76E4;  // water blue fallback
        }

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int a = 210;  // Same alpha as tank

        BlockPos self = be.getBlockPos();
        int blockLight = level.getBrightness(LightLayer.BLOCK, self);
        int skyLight = level.getBrightness(LightLayer.SKY, self);
        int lightUv = (skyLight << 20) | (blockLight << 4);

        BlockState state = be.getBlockState();

        pose.pushPose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f mat = pose.last().pose();

        float fillAmount = Math.min(1.0f, fill);
        float size = FLUID_RADIUS;

        // Core - render as solid box from -size to +size
        if (fill > 0) {
            renderBox(vc, mat, 0.5f - size, 0.5f - size, 0.5f - size,
                    0.5f + size, 0.5f + size, 0.5f + size,
                    sprite, lightUv, packedOverlay, r, g, b, a);
        }

        // Render fluid along each connected direction
        for (Direction dir : Direction.values()) {
            if (!isConnected(state, dir)) continue;

            // Calculate how far fluid extends in this direction
            float extension = fillAmount * 0.5f;  // Max extends to block edge (0.5)

            float x0, y0, z0, x1, y1, z1;

            switch (dir) {
                case DOWN -> {
                    x0 = 0.5f - size; y0 = 0; z0 = 0.5f - size;
                    x1 = 0.5f + size; y1 = 0.5f - size; z1 = 0.5f + size;
                }
                case UP -> {
                    x0 = 0.5f - size; y0 = 0.5f + size; z0 = 0.5f - size;
                    x1 = 0.5f + size; y1 = 1.0f; z1 = 0.5f + size;
                }
                case NORTH -> {
                    x0 = 0.5f - size; y0 = 0.5f - size; z0 = 0;
                    x1 = 0.5f + size; y1 = 0.5f + size; z1 = 0.5f - size;
                }
                case SOUTH -> {
                    x0 = 0.5f - size; y0 = 0.5f - size; z0 = 0.5f + size;
                    x1 = 0.5f + size; y1 = 0.5f + size; z1 = 1.0f;
                }
                case WEST -> {
                    x0 = 0; y0 = 0.5f - size; z0 = 0.5f - size;
                    x1 = 0.5f - size; y1 = 0.5f + size; z1 = 0.5f + size;
                }
                case EAST -> {
                    x0 = 0.5f + size; y0 = 0.5f - size; z0 = 0.5f - size;
                    x1 = 1.0f; y1 = 0.5f + size; z1 = 0.5f + size;
                }
                default -> {
                    continue;
                }
            }

            // Scale extension by fill amount
            if (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
                switch (dir.getAxis()) {
                    case X -> x1 = 0.5f + size + extension;
                    case Y -> y1 = 0.5f + size + extension;
                    case Z -> z1 = 0.5f + size + extension;
                }
            } else {
                switch (dir.getAxis()) {
                    case X -> x0 = 0.5f - size - extension;
                    case Y -> y0 = 0.5f - size - extension;
                    case Z -> z0 = 0.5f - size - extension;
                }
            }

            renderBox(vc, mat, x0, y0, z0, x1, y1, z1, sprite, lightUv, packedOverlay, r, g, b, a);
        }

        pose.popPose();
    }

    private static boolean isConnected(BlockState state, Direction dir) {
        BooleanProperty prop = switch (dir) {
            case NORTH -> BaseFluidPipeBlock.NORTH;
            case SOUTH -> BaseFluidPipeBlock.SOUTH;
            case EAST -> BaseFluidPipeBlock.EAST;
            case WEST -> BaseFluidPipeBlock.WEST;
            case UP -> BaseFluidPipeBlock.UP;
            case DOWN -> BaseFluidPipeBlock.DOWN;
        };
        return state.hasProperty(prop) && state.getValue(prop);
    }

    // Render a box exactly like TankBlockEntityRenderer does
    private void renderBox(VertexConsumer vc, Matrix4f mat,
                           float x0, float y0, float z0,
                           float x1, float y1, float z1,
                           TextureAtlasSprite sprite, int light, int overlay,
                           int r, int g, int b, int a) {

        float u0 = sprite.getU0(), v0 = sprite.getV0();
        float u1 = sprite.getU1(), v1 = sprite.getV1();

        // Top face (Y+)
        vc.addVertex(mat, x0, y1, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,1,0);
        vc.addVertex(mat, x1, y1, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,1,0);
        vc.addVertex(mat, x1, y1, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,1,0);
        vc.addVertex(mat, x0, y1, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,1,0);

        // Bottom face (Y-)
        vc.addVertex(mat, x0, y0, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
        vc.addVertex(mat, x1, y0, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
        vc.addVertex(mat, x1, y0, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
        vc.addVertex(mat, x0, y0, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,-1,0);

        // North face (Z-)
        vc.addVertex(mat, x0, y1, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
        vc.addVertex(mat, x1, y1, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
        vc.addVertex(mat, x1, y0, z0).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
        vc.addVertex(mat, x0, y0, z0).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,0,-1);

        // South face (Z+)
        vc.addVertex(mat, x1, y1, z1).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,0,1);
        vc.addVertex(mat, x0, y1, z1).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,0,1);
        vc.addVertex(mat, x0, y0, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,0,1);
        vc.addVertex(mat, x1, y0, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,0,1);

        // West face (X-)
        vc.addVertex(mat, x0, y1, z1).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
        vc.addVertex(mat, x0, y1, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
        vc.addVertex(mat, x0, y0, z0).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
        vc.addVertex(mat, x0, y0, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(-1,0,0);

        // East face (X+)
        vc.addVertex(mat, x1, y1, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(1,0,0);
        vc.addVertex(mat, x1, y1, z1).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(1,0,0);
        vc.addVertex(mat, x1, y0, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(1,0,0);
        vc.addVertex(mat, x1, y0, z0).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(1,0,0);
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}