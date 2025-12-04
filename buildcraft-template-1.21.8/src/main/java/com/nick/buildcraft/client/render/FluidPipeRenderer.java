package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.buildcraft.content.block.fluidpipe.FluidPipeBlockEntity;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Renders the pipe contents as a short "snake" made of overlapping cubes.
 *
 * Path-aware + direction-aware:
 * - Uses distanceFromRoot + frontPos (via getFillFraction/getFrontPos) from the BE.
 * - Figures out entry/exit direction per pipe from neighbours.
 * - Straight segments fill from entry face toward exit face.
 * - Corners fill entry->centre then centre->exit.
 * - Junctions (T/+) keep the old "blob" behaviour for simplicity.
 */
public class FluidPipeRenderer implements BlockEntityRenderer<FluidPipeBlockEntity> {

    private final Minecraft mc;

    // Short snake: how big the core is
    private static final float FLUID_HALF_EXTENT = 0.24f;

    // How many small cubes in the snake per block
    private static final int SNAKE_SEGMENTS = 6;

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
                       Vec3 cameraPos) {

        Level level = be.getLevel();
        if (level == null) return;

        // How full THIS block is (derived from frontPos + distanceFromRoot)
        float fill = be.getFillFraction(); // 0..1
        if (fill <= 0f) return;

        // Fluid to show
        FluidStack stack = be.getDisplayedFluid();
        if (stack == null || stack.isEmpty()) return;

        Fluid fluid = stack.getFluid();
        if (fluid == null || fluid == Fluids.EMPTY) return;

        IClientFluidTypeExtensions attrs = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation tex = attrs.getStillTexture();
        if (tex == null) tex = attrs.getFlowingTexture();
        if (tex == null) return;

        TextureAtlasSprite sprite = mc.getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(tex);
        if (sprite == null || sprite.contents().name().equals("missingno"))
            return;

        VertexConsumer buf =
                buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));

        int color = attrs.getTintColor(stack);
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float aAlpha = 0.9f;

        BlockPos self = be.getBlockPos();
        int blockLight = level.getBrightness(LightLayer.BLOCK, self);
        int skyLight   = level.getBrightness(LightLayer.SKY, self);
        int lightUv    = (skyLight << 20) | (blockLight << 4);

        BlockState state = be.getBlockState();

        // Ensure this pipe is actually on a rooted path
        int myDist = be.getDistanceFromRoot();
        if (myDist == Integer.MAX_VALUE) return;

        float head = be.getFrontPos();
        if (head <= 0f) return;

        FluidPath path = findPathThroughPipe(be);
        if (path.isEmpty()) return;

        pose.pushPose();
        // Center of the block
        pose.translate(0.5, 0.5, 0.5);

        float half = FLUID_HALF_EXTENT;

        /* ------------------------------------------------------------------ */
        /* Junctions: keep old "+" behaviour                                  */
        /* ------------------------------------------------------------------ */
        if (path.isJunction()) {
            boolean up    = has(state, BlockStateProperties.UP);
            boolean down  = has(state, BlockStateProperties.DOWN);
            boolean north = has(state, BlockStateProperties.NORTH);
            boolean south = has(state, BlockStateProperties.SOUTH);
            boolean east  = has(state, BlockStateProperties.EAST);
            boolean west  = has(state, BlockStateProperties.WEST);

            boolean axisY = up || down;
            boolean axisX = east || west;
            boolean axisZ = north || south;

            int maxSegs = SNAKE_SEGMENTS;
            for (int i = 0; i < maxSegs; i++) {
                float u = (i + 0.5f) / maxSegs;
                if (u > fill) break; // respect local fill

                float t = -0.5f + u; // always from -0.5 -> +0.5

                if (axisY) {
                    pose.pushPose();
                    pose.translate(0.0, t, 0.0);
                    Matrix4f mat = pose.last().pose();
                    drawCube(buf, mat,
                            -half, -half * 0.8f, -half,
                            half,  half * 0.8f,  half,
                            r, g, b, aAlpha,
                            sprite, lightUv);
                    pose.popPose();
                }
                if (axisX) {
                    pose.pushPose();
                    pose.translate(t, 0.0, 0.0);
                    Matrix4f mat = pose.last().pose();
                    drawCube(buf, mat,
                            -half, -half * 0.8f, -half,
                            half,  half * 0.8f,  half,
                            r, g, b, aAlpha,
                            sprite, lightUv);
                    pose.popPose();
                }
                if (axisZ) {
                    pose.pushPose();
                    pose.translate(0.0, 0.0, t);
                    Matrix4f mat = pose.last().pose();
                    drawCube(buf, mat,
                            -half, -half * 0.8f, -half,
                            half,  half * 0.8f,  half,
                            r, g, b, aAlpha,
                            sprite, lightUv);
                    pose.popPose();
                }
            }

            pose.popPose();
            return;
        }

        Direction entry = path.entry();
        Direction exit  = path.exit();

        /* ------------------------------------------------------------------ */
        /* Straight segment: entry & exit share axis                          */
        /* ------------------------------------------------------------------ */
        if (entry.getAxis() == exit.getAxis()) {
            Direction.Axis axis = entry.getAxis();
            int dirSign;

            switch (axis) {
                case X -> dirSign = exit.getStepX();
                case Y -> dirSign = exit.getStepY();
                case Z -> dirSign = exit.getStepZ();
                default -> dirSign = 1;
            }

            int maxSegs = SNAKE_SEGMENTS;
            for (int i = 0; i < maxSegs; i++) {
                float u = (i + 0.5f) / maxSegs; // 0..1 from entry to exit
                if (u > fill) break;

                float t = (dirSign > 0)
                        ? -0.5f + u
                        :  0.5f - u;

                pose.pushPose();
                switch (axis) {
                    case X -> pose.translate(t, 0.0, 0.0);
                    case Y -> pose.translate(0.0, t, 0.0);
                    case Z -> pose.translate(0.0, 0.0, t);
                }
                Matrix4f mat = pose.last().pose();
                drawCube(buf, mat,
                        -half, -half * 0.8f, -half,
                        half,  half * 0.8f,  half,
                        r, g, b, aAlpha,
                        sprite, lightUv);
                pose.popPose();
            }

            pose.popPose();
            return;
        }

        /* ------------------------------------------------------------------ */
        /* Corner: entry & exit are different axes                            */
        /* Treat path inside block as length 1:                               */
        /*   0..0.5  entry face -> centre                                     */
        /*   0.5..1  centre -> exit face                                      */
        /* ------------------------------------------------------------------ */

        float p = Math.min(1f, fill);         // 0..1 along corner path
        float entryProgress = Math.min(1f, p * 2f);      // 0..1 along entry leg
        float exitProgress  = Math.max(0f, p * 2f - 1f); // 0..1 along exit leg

        int maxSegs = SNAKE_SEGMENTS;

        // ---- Entry leg: entry face -> centre (coord = +/-0.5 -> 0) ----
        if (entryProgress > 0f) {
            int segs = Math.max(1, Math.round(maxSegs * entryProgress));
            for (int i = 0; i < segs; i++) {
                float u = (i + 0.5f) / maxSegs; // 0..1 along leg

                pose.pushPose();
                switch (entry.getAxis()) {
                    case X -> {
                        float entryFace = (entry.getStepX() > 0 ? 0.5f : -0.5f);
                        float x = entryFace + (0.0f - entryFace) * u; // face -> centre
                        pose.translate(x, 0.0, 0.0);
                    }
                    case Y -> {
                        float entryFace = (entry.getStepY() > 0 ? 0.5f : -0.5f);
                        float y = entryFace + (0.0f - entryFace) * u;
                        pose.translate(0.0, y, 0.0);
                    }
                    case Z -> {
                        float entryFace = (entry.getStepZ() > 0 ? 0.5f : -0.5f);
                        float z = entryFace + (0.0f - entryFace) * u;
                        pose.translate(0.0, 0.0, z);
                    }
                }
                Matrix4f mat = pose.last().pose();
                drawCube(buf, mat,
                        -half, -half * 0.8f, -half,
                        half,  half * 0.8f,  half,
                        r, g, b, aAlpha,
                        sprite, lightUv);
                pose.popPose();
            }
        }

        // ---- Exit leg: centre -> exit face (coord = 0 -> +/-0.5) ----
        if (exitProgress > 0f) {
            int segs = Math.max(1, Math.round(maxSegs * exitProgress));
            for (int i = 0; i < segs; i++) {
                float u = (i + 0.5f) / maxSegs; // 0..1 along leg

                pose.pushPose();
                switch (exit.getAxis()) {
                    case X -> {
                        float exitFace = (exit.getStepX() > 0 ? 0.5f : -0.5f);
                        float x = 0.0f + (exitFace - 0.0f) * u; // centre -> face
                        pose.translate(x, 0.0, 0.0);
                    }
                    case Y -> {
                        float exitFace = (exit.getStepY() > 0 ? 0.5f : -0.5f);
                        float y = 0.0f + (exitFace - 0.0f) * u;
                        pose.translate(0.0, y, 0.0);
                    }
                    case Z -> {
                        float exitFace = (exit.getStepZ() > 0 ? 0.5f : -0.5f);
                        float z = 0.0f + (exitFace - 0.0f) * u;
                        pose.translate(0.0, 0.0, z);
                    }
                }
                Matrix4f mat = pose.last().pose();
                drawCube(buf, mat,
                        -half, -half * 0.8f, -half,
                        half,  half * 0.8f,  half,
                        r, g, b, aAlpha,
                        sprite, lightUv);
                pose.popPose();
            }
        }

        pose.popPose();
    }

    /* ---------------------------------------------------------------------- */
    /* Path analysis helpers                                                  */
    /* ---------------------------------------------------------------------- */

    /** Describes how the wave passes through a single pipe block. */
    private static final class FluidPath {
        private final Direction entry;
        private final Direction exit;
        private final boolean junction;

        private FluidPath(Direction entry, Direction exit, boolean junction) {
            this.entry = entry;
            this.exit = exit;
            this.junction = junction;
        }

        static FluidPath empty()    { return new FluidPath(null, null, false); }
        static FluidPath junction() { return new FluidPath(null, null, true); }

        static FluidPath of(Direction entry, Direction exit) {
            return new FluidPath(entry, exit, false);
        }

        boolean isEmpty() {
            return !junction && entry == null && exit == null;
        }

        boolean isJunction() {
            return junction;
        }

        Direction entry() { return entry; }
        Direction exit()  { return exit;  }
    }

    /**
     * Use distanceFromRoot of neighbours to find entry / exit directions.
     *
     * Cases:
     *  - 1 entry, 1 exit   -> normal straight / corner segment
     *  - 1 entry, 0 exit   -> end-of-line cap (we fake exit = opposite(entry))
     *  - 0 entry, 1 exit   -> root/source (we fake entry = opposite(exit))
     *  - anything else     -> junction (T/+)
     */
    private static FluidPath findPathThroughPipe(FluidPipeBlockEntity pipe) {
        Level level = pipe.getLevel();
        if (level == null) return FluidPath.empty();

        int myDist = pipe.getDistanceFromRoot();
        if (myDist == Integer.MAX_VALUE) return FluidPath.empty();

        Direction entry = null;
        Direction exit  = null;
        int entryCount = 0;
        int exitCount  = 0;

        BlockPos pos = pipe.getBlockPos();

        for (Direction dir : Direction.values()) {
            BlockPos np = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (!(be instanceof FluidPipeBlockEntity other)) continue;

            int od = other.getDistanceFromRoot();
            if (od == myDist - 1) {          // closer to root → entry
                entry = dir;
                entryCount++;
            } else if (od == myDist + 1) {   // further from root → exit
                exit = dir;
                exitCount++;
            }
        }

        // End of line: have exactly one entry, no exit.
        if (entryCount == 1 && exitCount == 0) {
            return FluidPath.of(entry, entry.getOpposite());
        }

        // Root / source: no entry, exactly one exit.
        if (entryCount == 0 && exitCount == 1) {
            return FluidPath.of(exit.getOpposite(), exit);
        }

        // Normal case
        if (entryCount == 1 && exitCount == 1) {
            return FluidPath.of(entry, exit);
        }

        // Anything more complex is a junction.
        return FluidPath.junction();
    }

    private static boolean has(BlockState state, BooleanProperty prop) {
        return state.hasProperty(prop) && state.getValue(prop);
    }

    /* ---------------------------------------------------------------------- */
    /* cube helpers                                                           */
    /* ---------------------------------------------------------------------- */

    private void drawCube(VertexConsumer buf, Matrix4f mat,
                          float x1, float y1, float z1,
                          float x2, float y2, float z2,
                          float r, float g, float b, float a,
                          TextureAtlasSprite sprite, int light) {
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        int overlay = 0;

        addQuad(buf, mat, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2,
                r, g, b, a, u0, v0, u1, v1, light, overlay, new Vector3f(0, 1, 0));
        addQuad(buf, mat, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2,
                r, g, b, a, u0, v0, u1, v1, light, overlay, new Vector3f(0, -1, 0));
        addQuad(buf, mat, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2,
                r, g, b, a, u0, v0, u1, v1, light, overlay, new Vector3f(0, 0, 1));
        addQuad(buf, mat, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1,
                r, g, b, a, u0, v0, u1, v1, light, overlay, new Vector3f(0, 0, -1));
        addQuad(buf, mat, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1,
                r, g, b, a, u0, v0, u1, v1, light, overlay, new Vector3f(1, 0, 0));
        addQuad(buf, mat, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1,
                r, g, b, a, u0, v0, u1, v1, light, overlay, new Vector3f(-1, 0, 0));
    }

    private void addQuad(VertexConsumer buf, Matrix4f mat,
                         float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float x3, float y3, float z3,
                         float r, float g, float b, float a,
                         float u0, float v0, float u1, float v1,
                         int light, int overlay, Vector3f normal) {
        buf.addVertex(mat, x0, y0, z0).setColor(r, g, b, a).setUv(u0, v0)
                .setOverlay(overlay).setLight(light).setNormal(normal.x, normal.y, normal.z);
        buf.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setUv(u1, v0)
                .setOverlay(overlay).setLight(light).setNormal(normal.x, normal.y, normal.z);
        buf.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setUv(u1, v1)
                .setOverlay(overlay).setLight(light).setNormal(normal.x, normal.y, normal.z);
        buf.addVertex(mat, x3, y3, z3).setColor(r, g, b, a).setUv(u0, v1)
                .setOverlay(overlay).setLight(light).setNormal(normal.x, normal.y, normal.z);
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}
