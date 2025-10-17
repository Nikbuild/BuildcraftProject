package com.nick.buildcraft.content.block.tank;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

/**
 * Continuous fluid column renderer: draws fluid as one seamless volume spanning all connected tanks.
 * Supports water, lava, oil, and fuel with correct coloring and instant first-fill visibility.
 */
public class TankBlockEntityRenderer implements BlockEntityRenderer<TankBlockEntity> {

    private static final ResourceLocation WATER_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
    private static final ResourceLocation LAVA_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/lava_still");

    private static final float WALL_EPS = 0.0008f;
    private static final float FLOOR_CEIL_EPS = 0.0012f;
    private static final float MIN_SLICE_THICKNESS = 0.0025f;
    private static final float FLUID_VERTICAL_OFFSET = 1f / 16f; // slight lift for floating surface

    public TankBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(TankBlockEntity be,
                       float partialTicks,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay,
                       net.minecraft.world.phys.Vec3 camera) {

        Level level = be.getLevel();
        if (level == null) return;

        // Only render from the *bottommost* tank in the stack
        TankBlockEntity.ColumnInfo col = TankBlockEntity.scanColumn(level, be.getBlockPos());
        if (col == null || col.size <= 0 || col.totalAmount <= 0) return;
        if (be.columnIndex() != 0) return; // skip upper tanks

        FluidStack fluid = col.representativeFluid;
        if (fluid.isEmpty()) return;

        // --- Choose correct texture ---
        ResourceLocation spriteLoc;
        if (fluid.getFluid() == ModFluids.OIL.get() || fluid.getFluid() == ModFluids.FLOWING_OIL.get()) {
            spriteLoc = ModFluids.OIL_STILL;
        } else if (fluid.getFluid() == ModFluids.FUEL.get() || fluid.getFluid() == ModFluids.FLOWING_FUEL.get()) {
            spriteLoc = ModFluids.FUEL_STILL;
        } else if (fluid.getFluid() == Fluids.LAVA || fluid.getFluid() == Fluids.FLOWING_LAVA) {
            spriteLoc = LAVA_SPRITE;
        } else {
            spriteLoc = WATER_SPRITE;
        }

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(spriteLoc);

        // --- Compute fluid height ---
        float totalFraction = (float) col.totalAmount / (float) col.totalCapacity();
        float globalHeight = totalFraction * col.size;
        if (globalHeight <= 0f) return;

        // Tank interior bounds
        float x0 = 1f / 16f + WALL_EPS, x1 = 15f / 16f - WALL_EPS;
        float z0 = 1f / 16f + WALL_EPS, z1 = 15f / 16f - WALL_EPS;
        // vertical bounds – adjusted so very small amounts still render
        float y0 = 0f + FLOOR_CEIL_EPS;
        float y1 = Math.max(y0 + MIN_SLICE_THICKNESS,
                Math.min(globalHeight, col.size) - FLOOR_CEIL_EPS);

        if (y1 <= y0) return;


        // --- Tint ---
        int rgb;
        if (fluid.getFluid() == ModFluids.OIL.get() || fluid.getFluid() == ModFluids.FUEL.get()) {
            rgb = 0xFFFFFF; // no tint
        } else if (fluid.getFluid() == Fluids.LAVA || fluid.getFluid() == Fluids.FLOWING_LAVA) {
            rgb = 0xFF6000; // warm lava tint
        } else {
            rgb = BiomeColors.getAverageWaterColor(level, be.getBlockPos());
        }

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int a = 210; // slightly more opaque than before

        pose.pushPose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f mat = pose.last().pose();

        // --- Draw one seamless prism ---
        putFlatQuadTop(vc, mat, x0, x1, z0, z1, y1, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadBottom(vc, mat, x0, x1, z0, z1, y0, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadNorth(vc, mat, x0, x1, y0, y1, z0, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadSouth(vc, mat, x0, x1, y0, y1, z1, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadWest(vc, mat, z0, z1, y0, y1, x0, sprite, packedLight, packedOverlay, r, g, b, a);
        putFlatQuadEast(vc, mat, z0, z1, y0, y1, x1, sprite, packedLight, packedOverlay, r, g, b, a);

        pose.popPose();
    }

    /* ---------- helpers ---------- */

    private static void putFlatQuadTop(VertexConsumer vc, Matrix4f mat,
                                       float x0, float x1, float z0, float z1, float y,
                                       TextureAtlasSprite sprite, int light, int overlay,
                                       int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x0, y, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,1,0);
        vc.addVertex(mat, x1, y, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,1,0);
        vc.addVertex(mat, x1, y, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,1,0);
        vc.addVertex(mat, x0, y, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,1,0);
    }

    private static void putFlatQuadBottom(VertexConsumer vc, Matrix4f mat,
                                          float x0, float x1, float z0, float z1, float y,
                                          TextureAtlasSprite sprite, int light, int overlay,
                                          int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x0, y, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
        vc.addVertex(mat, x1, y, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
        vc.addVertex(mat, x1, y, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
        vc.addVertex(mat, x0, y, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,-1,0);
    }

    private static void putFlatQuadNorth(VertexConsumer vc, Matrix4f mat,
                                         float x0, float x1, float y0, float y1, float z,
                                         TextureAtlasSprite sprite, int light, int overlay,
                                         int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x0, y1, z).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
        vc.addVertex(mat, x1, y1, z).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
        vc.addVertex(mat, x1, y0, z).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
        vc.addVertex(mat, x0, y0, z).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,0,-1);
    }

    private static void putFlatQuadSouth(VertexConsumer vc, Matrix4f mat,
                                         float x0, float x1, float y0, float y1, float z,
                                         TextureAtlasSprite sprite, int light, int overlay,
                                         int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x1, y1, z).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(0,0,1);
        vc.addVertex(mat, x0, y1, z).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(0,0,1);
        vc.addVertex(mat, x0, y0, z).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(0,0,1);
        vc.addVertex(mat, x1, y0, z).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(0,0,1);
    }

    private static void putFlatQuadWest(VertexConsumer vc, Matrix4f mat,
                                        float z0, float z1, float y0, float y1, float x,
                                        TextureAtlasSprite sprite, int light, int overlay,
                                        int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x, y1, z1).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
        vc.addVertex(mat, x, y1, z0).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
        vc.addVertex(mat, x, y0, z0).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
        vc.addVertex(mat, x, y0, z1).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(-1,0,0);
    }

    private static void putFlatQuadEast(VertexConsumer vc, Matrix4f mat,
                                        float z0, float z1, float y0, float y1, float x,
                                        TextureAtlasSprite sprite, int light, int overlay,
                                        int r, int g, int b, int a) {
        float u0 = sprite.getU0(), v0 = sprite.getV0(), u1 = sprite.getU1(), v1 = sprite.getV1();
        vc.addVertex(mat, x, y1, z0).setColor(r,g,b,a).setUv(u0,v0).setOverlay(overlay).setLight(light).setNormal(1,0,0);
        vc.addVertex(mat, x, y1, z1).setColor(r,g,b,a).setUv(u1,v0).setOverlay(overlay).setLight(light).setNormal(1,0,0);
        vc.addVertex(mat, x, y0, z1).setColor(r,g,b,a).setUv(u1,v1).setOverlay(overlay).setLight(light).setNormal(1,0,0);
        vc.addVertex(mat, x, y0, z0).setColor(r,g,b,a).setUv(u0,v1).setOverlay(overlay).setLight(light).setNormal(1,0,0);
    }
}
