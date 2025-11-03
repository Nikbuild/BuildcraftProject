package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.buildcraft.content.block.fluidpipe.FluidPipeBlockEntity;
import com.nick.buildcraft.content.block.fluidpipe.FluidPipeBlockEntity.RenderSection;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * FluidPipeRenderer v2
 *
 * Goals:
 *  - keep the water "inside" the pipe (6px-ish core, like classic BC)
 *  - trail multiple short segments so it looks like a flowing stream,
 *    not single ice cubes
 *  - never draw outside this pipe block (no sticking out past faces)
 *
 * We fake a hose-like stream by drawing several short "slices" per moving
 * section, each one slightly behind the previous along the travel axis.
 */
public class FluidPipeRenderer implements BlockEntityRenderer<FluidPipeBlockEntity> {

    /** inner half-width of the pipe fluid core (in block coords).
     * Classic BuildCraft pipes visually had ~6x6px core, so half ≈ 3/16.
     */
    private static final double CORE_HALF = 3.0 / 16.0; // 0.1875

    /** half-length of each slice ALONG the flow axis.
     * Bigger than CORE_HALF so each slice looks a little stretched.
     */
    private static final double SLICE_HALF_LEN = 0.28; // ~4.5px each way

    /** how many trailing slices we render per section */
    private static final int TRAIL_SLICES = 4;

    /** how far apart trail slices are in "progress units" (0..1 in this block) */
    private static final double TRAIL_SPACING = 0.07;

    /** how far in from the block face we park the nozzle point (to not poke outside glass) */
    private static final double FACE_INSET = 2.0 / 16.0; // 2px in from face

    /** tint alpha for the box color (debugFilledBox ignores alpha, but keep for future custom RT) */
    private static final float ALPHA = 0.7f;

    public FluidPipeRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(
            FluidPipeBlockEntity be,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay,
            Vec3 cameraPos
    ) {
        Level level = be.getLevel();
        if (level == null || be.isRemoved()) return;

        var blobs = be.getRenderSections(16);
        if (blobs.isEmpty()) return;

        final BlockPos selfPos = be.getBlockPos();
        final Vec3 pipeCenter = blockCenter(selfPos);

        // We'll draw everything in this block's local space, so world->local is just -selfPos.
        // pose is already translated for this BE (the vanilla BER system does that),
        // so we only care about local coords inside the 1x1x1 cube.

        // We'll reuse a single RenderType for all quads.
        VertexConsumer vc = buffers.getBuffer(RenderType.debugFilledBox());
        Matrix4f mat = pose.last().pose();

        for (RenderSection rs : blobs) {
            if (rs.hidden) continue;
            if (rs.fluid.isEmpty()) continue;
            if (rs.outDir == null) continue;

            Direction dir = rs.outDir;

            // where the blob is "heading to" INSIDE THIS BLOCK ONLY:
            // not the neighbor's center, just the face center inset.
            Vec3 nozzlePointWorld = faceCenterInset(selfPos, dir, FACE_INSET);

            // clamp server progress to [0..1] inside this block
            double baseT = clamp01(rs.progress);

            // color based on fluid type
            int rgb = pickFluidColor(rs.fluid.getFluid());
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >>  8) & 0xFF) / 255f;
            float b = ((rgb      ) & 0xFF) / 255f;
            float a = ALPHA; // (won't matter yet with debugFilledBox(), but we'll keep it)

            // axis unit vector for dir (axis aligned, length 1)
            Vec3 axis = new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ());

            // Lighting sample: we'll just grab light at the main head position
            double headWx = lerp(pipeCenter.x, nozzlePointWorld.x, baseT);
            double headWy = lerp(pipeCenter.y, nozzlePointWorld.y, baseT);
            double headWz = lerp(pipeCenter.z, nozzlePointWorld.z, baseT);
            int headLight = LevelRenderer.getLightColor(level, BlockPos.containing(headWx, headWy, headWz));

            // render multiple slices, trailing backward along the flow axis
            for (int slice = 0; slice < TRAIL_SLICES; slice++) {
                double tSlice = baseT - slice * TRAIL_SPACING;
                if (tSlice < 0) continue; // don't wrap around backward past center

                tSlice = clamp01(tSlice);

                // world-space center of this slice
                double sx = lerp(pipeCenter.x, nozzlePointWorld.x, tSlice);
                double sy = lerp(pipeCenter.y, nozzlePointWorld.y, tSlice);
                double sz = lerp(pipeCenter.z, nozzlePointWorld.z, tSlice);

                // convert to local-in-block coords (pose is already translated to block origin)
                double lx = sx - selfPos.getX();
                double ly = sy - selfPos.getY();
                double lz = sz - selfPos.getZ();

                // build a slim AABB for this slice, aligned to the pipe axis
                AABB sliceBox = buildSliceBox(dir, lx, ly, lz);

                // dump all 6 faces into the buffer
                emitBoxQuads(
                        vc,
                        mat,
                        sliceBox,
                        r, g, b, a,
                        headLight
                );
            }
        }
    }

    /* ------------------------------------------------------------
     * BlockEntityRenderer plumbing
     * ------------------------------------------------------------
     */

    @Override
    public boolean shouldRender(FluidPipeBlockEntity be, Vec3 cameraPos) {
        // pipes are tiny, just always render if BE is in range
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(FluidPipeBlockEntity be) {
        // 1-block AABB slightly inflated so faces at ~0.0/1.0 aren't clipped
        BlockPos p = be.getBlockPos();
        return new AABB(
                p.getX() - 0.1, p.getY() - 0.1, p.getZ() - 0.1,
                p.getX() + 1.1, p.getY() + 1.1, p.getZ() + 1.1
        );
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    /* ------------------------------------------------------------
     * Geometry helpers
     * ------------------------------------------------------------
     */

    private static Vec3 blockCenter(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    /**
     * Point at the center of a given face of this block,
     * pulled inward a little so nothing pokes out of the pipe glass.
     */
    private static Vec3 faceCenterInset(BlockPos p, Direction dir, double inset) {
        double cx = p.getX() + 0.5;
        double cy = p.getY() + 0.5;
        double cz = p.getZ() + 0.5;
        double d = 0.5 - Math.max(0.0, inset); // so e.g. inset=2px => d ~ 0.375

        return switch (dir) {
            case UP    -> new Vec3(cx, cy + d, cz);
            case DOWN  -> new Vec3(cx, cy - d, cz);
            case NORTH -> new Vec3(cx, cy, cz - d);
            case SOUTH -> new Vec3(cx, cy, cz + d);
            case WEST  -> new Vec3(cx - d, cy, cz);
            case EAST  -> new Vec3(cx + d, cy, cz);
        };
    }

    /**
     * Build an oriented AABB for ONE slice of flowing fluid.
     *
     * - Along the pipe axis we use SLICE_HALF_LEN (long-ish capsule feel).
     * - Perpendicular axes we use CORE_HALF (tight 6px-ish column).
     *
     * All coords are already local to THIS block (0..1 space).
     */
    private static AABB buildSliceBox(Direction dir, double cx, double cy, double cz) {
        double hw = CORE_HALF;       // half width in the "small" axes
        double hl = SLICE_HALF_LEN;  // half length along travel axis

        return switch (dir) {
            case UP, DOWN -> new AABB(
                    cx - hw, cy - hl, cz - hw,
                    cx + hw, cy + hl, cz + hw
            );
            case NORTH, SOUTH -> new AABB(
                    cx - hw, cy - hw, cz - hl,
                    cx + hw, cy + hw, cz + hl
            );
            case WEST, EAST -> new AABB(
                    cx - hl, cy - hw, cz - hw,
                    cx + hl, cy + hw, cz + hw
            );
        };
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /* ------------------------------------------------------------
     * Mesh emission
     * ------------------------------------------------------------
     *
     * We feed faces directly into the 'debugFilledBox' buffer.
     * That buffer expects: position -> color -> light -> normal.
     */

    private static void emitBoxQuads(
            VertexConsumer vc,
            Matrix4f mat,
            AABB box,
            float r, float g, float b, float a,
            int packedLight
    ) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;

        // +X
        quad(vc, mat, packedLight, r,g,b,a,
                1f,0f,0f,
                x1,y0,z0,
                x1,y1,z0,
                x1,y1,z1,
                x1,y0,z1);

        // -X
        quad(vc, mat, packedLight, r,g,b,a,
                -1f,0f,0f,
                x0,y0,z1,
                x0,y1,z1,
                x0,y1,z0,
                x0,y0,z0);

        // +Y
        quad(vc, mat, packedLight, r,g,b,a,
                0f,1f,0f,
                x0,y1,z0,
                x0,y1,z1,
                x1,y1,z1,
                x1,y1,z0);

        // -Y
        quad(vc, mat, packedLight, r,g,b,a,
                0f,-1f,0f,
                x1,y0,z0,
                x1,y0,z1,
                x0,y0,z1,
                x0,y0,z0);

        // +Z
        quad(vc, mat, packedLight, r,g,b,a,
                0f,0f,1f,
                x1,y0,z1,
                x1,y1,z1,
                x0,y1,z1,
                x0,y0,z1);

        // -Z
        quad(vc, mat, packedLight, r,g,b,a,
                0f,0f,-1f,
                x0,y0,z0,
                x0,y1,z0,
                x1,y1,z0,
                x1,y0,z0);
    }

    private static void quad(
            VertexConsumer vc,
            Matrix4f mat,
            int packedLight,
            float r, float g, float b, float a,
            float nx, float ny, float nz,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3
    ) {
        // tri 0-1-2
        vc.addVertex(mat, x0, y0, z0)
                .setColor(r, g, b, a)
                .setLight(packedLight)
                .setNormal(nx, ny, nz);
        vc.addVertex(mat, x1, y1, z1)
                .setColor(r, g, b, a)
                .setLight(packedLight)
                .setNormal(nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2)
                .setColor(r, g, b, a)
                .setLight(packedLight)
                .setNormal(nx, ny, nz);

        // tri 0-2-3
        vc.addVertex(mat, x0, y0, z0)
                .setColor(r, g, b, a)
                .setLight(packedLight)
                .setNormal(nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2)
                .setColor(r, g, b, a)
                .setLight(packedLight)
                .setNormal(nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3)
                .setColor(r, g, b, a)
                .setLight(packedLight)
                .setNormal(nx, ny, nz);
    }

    /* ------------------------------------------------------------
     * Fluid tint helper
     * ------------------------------------------------------------
     *
     * (super dumb heuristic, but it gives oil-ish / fuel-ish / water-ish colors
     * without needing sprites or shaders yet)
     */
    private static int pickFluidColor(Fluid f) {
        var key = BuiltInRegistries.FLUID.getKey(f);
        if (key != null) {
            String path = key.getPath();
            if (path.contains("water")) return 0x3F76E4; // blue
            if (path.contains("oil"))   return 0x1E1B15; // near-black
            if (path.contains("fuel"))  return 0xC8B400; // yellow
        }
        // fallback: olive-ish BC style
        return 0x4A5C28;
    }
}
