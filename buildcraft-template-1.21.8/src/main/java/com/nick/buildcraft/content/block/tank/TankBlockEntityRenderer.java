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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

/**
 * Renders the tank column as per-block slices (one slice per BE's FluidTank).
 * Only blocks that actually hold fluid render in their 1-block vertical band.
 */
public class TankBlockEntityRenderer implements BlockEntityRenderer<TankBlockEntity> {

    private static final ResourceLocation WATER_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
    private static final ResourceLocation LAVA_SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/lava_still");

    private static final float WALL_EPS = 0.0008f;
    private static final float FLOOR_CEIL_EPS = 0.0012f;
    private static final float MIN_SLICE_THICKNESS = 0.0025f;

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

        // Only the *bottommost* BE in a column issues draw calls
        TankBlockEntity.ColumnInfo col = TankBlockEntity.scanColumn(level, be.getBlockPos());
        if (col == null || col.size <= 0) return;
        if (be.columnIndex() != 0) return;

        FluidStack rep = col.representativeFluid;
        if (rep.isEmpty()) return;

        // Resolve milk once
        Fluid milk = findMilkFluid(level);

        // Choose the atlas sprite from the representative fluid
        final ResourceLocation spriteLoc;
        if (rep.getFluid() == ModFluids.OIL.get() || rep.getFluid() == ModFluids.FLOWING_OIL.get()) {
            spriteLoc = ModFluids.OIL_STILL;
        } else if (rep.getFluid() == ModFluids.FUEL.get() || rep.getFluid() == ModFluids.FLOWING_FUEL.get()) {
            spriteLoc = ModFluids.FUEL_STILL;
        } else if (rep.getFluid() == Fluids.LAVA || rep.getFluid() == Fluids.FLOWING_LAVA) {
            spriteLoc = LAVA_SPRITE;
        } else {
            // Fallback for water-like fluids (also fine for milk if no custom still tex)
            spriteLoc = WATER_SPRITE;
        }

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(spriteLoc);

        // Interior XY bounds in a single block
        final float x0 = 1f / 16f + WALL_EPS, x1 = 15f / 16f - WALL_EPS;
        final float z0 = 1f / 16f + WALL_EPS, z1 = 15f / 16f - WALL_EPS;

        pose.pushPose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f mat = pose.last().pose();

        // Walk bottom->up; for each BE, render only what's inside that BE's tank.
        BlockPos p = col.bottom;
        for (int i = 0; i < col.size; i++) {
            var curBe = level.getBlockEntity(p);
            if (!(curBe instanceof TankBlockEntity tbe)) break;

            FluidStack fs = tbe.getTank().getFluid();
            if (fs.isEmpty()) { // empty tank => no slice
                p = p.above();
                continue;
            }

            // Stop at a "barrier" (different fluid); we never render across
            if (!FluidStack.isSameFluidSameComponents(rep, fs)) break;

            // Local vertical band [i, i+1) in the column's local coordinates
            float localBase = (float) (p.getY() - col.bottom.getY());
            float frac = (float) fs.getAmount() / (float) TankBlockEntity.CAPACITY;
            if (frac <= 0f) { p = p.above(); continue; }

            float y0 = localBase + FLOOR_CEIL_EPS;
            float y1 = localBase + Math.min(1f - FLOOR_CEIL_EPS, Math.max(frac, MIN_SLICE_THICKNESS));

            // Per-block tinting
            final int rgb;
            if (rep.getFluid() == Fluids.LAVA || rep.getFluid() == Fluids.FLOWING_LAVA) {
                rgb = 0xFF6000;                     // lava orange
            } else if (rep.getFluid() == ModFluids.OIL.get() || rep.getFluid() == ModFluids.FUEL.get()) {
                rgb = 0xFFFFFF;                     // no tint for our custom fluids (already colored)
            } else if (milk != Fluids.EMPTY && rep.getFluid().isSame(milk)) {
                rgb = 0xFFFFFF;                     // milk → white (not biome-tinted blue)
            } else {
                rgb = BiomeColors.getAverageWaterColor(level, p); // water and waterlikes
            }
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF, a = 210;

            // Draw this tank's slice
            putFlatQuadTop   (vc, mat, x0, x1, z0, z1, y1, sprite, packedLight, packedOverlay, r,g,b,a);
            putFlatQuadBottom(vc, mat, x0, x1, z0, z1, y0, sprite, packedLight, packedOverlay, r,g,b,a);
            putFlatQuadNorth (vc, mat, x0, x1, y0, y1, z0, sprite, packedLight, packedOverlay, r,g,b,a);
            putFlatQuadSouth (vc, mat, x0, x1, y0, y1, z1, sprite, packedLight, packedOverlay, r,g,b,a);
            putFlatQuadWest  (vc, mat, z0, z1, y0, y1, x0, sprite, packedLight, packedOverlay, r,g,b,a);
            putFlatQuadEast  (vc, mat, z0, z1, y0, y1, x1, sprite, packedLight, packedOverlay, r,g,b,a);

            p = p.above();
        }

        pose.popPose();
    }

    /* ---------- quad helpers ---------- */

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

    @Override
    public AABB getRenderBoundingBox(TankBlockEntity blockEntity) {
        TankBlockEntity.ColumnInfo col = TankBlockEntity.scanColumn(blockEntity.getLevel(), blockEntity.getBlockPos());
        if (col == null) return new AABB(blockEntity.getBlockPos());

        BlockPos bottom = col.bottom;
        return new AABB(
                bottom.getX(),
                bottom.getY(),
                bottom.getZ(),
                bottom.getX() + 1.0,
                bottom.getY() + col.size,
                bottom.getZ() + 1.0
        ).inflate(0.1);
    }

    /* -------- Milk helper (shared logic with block) -------- */

    private static Fluid findMilkFluid(Level level) {
        try {
            var reg = level.registryAccess().lookupOrThrow(Registries.FLUID);
            ResourceLocation[] candidates = new ResourceLocation[] {
                    ResourceLocation.parse("neoforge:milk"),
                    ResourceLocation.parse("forge:milk"),
                    ResourceLocation.parse("create:milk"),
                    ResourceLocation.parse("minecraft:milk")
            };
            for (ResourceLocation rl : candidates) {
                var opt = reg.get(rl);
                if (opt.isPresent()) {
                    Fluid f = opt.get().value();
                    if (f != null && f != Fluids.EMPTY) return f;
                }
            }
        } catch (Exception ignored) {}
        return Fluids.EMPTY;
    }
}
