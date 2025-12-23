package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nick.buildcraft.content.entity.laser.LaserEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class LaserEntityRenderer extends EntityRenderer<LaserEntity, LaserEntityRenderState> {

    private static final ResourceLocation TEXTURE_DEFAULT =
            ResourceLocation.fromNamespaceAndPath("buildcraft",
                    "textures/entity/laser/quarry_marker_laser_guide_wire.png");

    private static final ResourceLocation TEXTURE_TAPE_MEASURE =
            ResourceLocation.fromNamespaceAndPath("buildcraft",
                    "textures/entity/laser/tape_measure.png");

    // thickness (half-extents) of the rectangular beam
    private static final float HALF_W = 1f / 64f;
    private static final float HALF_H = 1f / 64f;

    public LaserEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
        this.shadowStrength = 0f;
    }

    @Override public LaserEntityRenderState createRenderState() { return new LaserEntityRenderState(); }

    @Override
    public void extractRenderState(LaserEntity e, LaserEntityRenderState s, float pt) {
        super.extractRenderState(e, s, pt);
        s.color = e.getColor();
        s.start = e.getStart();
        s.end   = e.getEnd();
        s.textureType = e.getTextureType();
    }

    /** Use an AABB from start→end so the beam isn’t culled when the midpoint box leaves the frustum. */
    @Override
    public boolean shouldRender(LaserEntity e, Frustum frustum, double camX, double camY, double camZ) {
        Vec3 s = e.getStart();
        Vec3 t = e.getEnd();
        if (s == null || t == null) return false;

        // Build a world-space AABB that covers the whole beam and inflate a little for thickness
        AABB box = new AABB(s, t).inflate(Math.max(HALF_W, HALF_H) + 0.05f);
        return frustum.isVisible(box);
    }

    @Override
    public void render(LaserEntityRenderState s, PoseStack ps, MultiBufferSource buffers, int packedLight) {
        if (s.start == null || s.end == null) return;
        if (s.start.distanceToSqr(s.end) < 1.0E-6) return;

        // Select texture based on type
        ResourceLocation texture = (s.textureType == 1) ? TEXTURE_TAPE_MEASURE : TEXTURE_DEFAULT;

        // No face culling so it's visible from any angle
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Work in space centered at the entity's midpoint (entity should already be positioned there)
        Vec3 mid = s.start.add(s.end).scale(0.5);
        Vec3 a = s.start.subtract(mid);
        Vec3 b = s.end.subtract(mid);

        drawBox(ps, vc, a, b, HALF_W, HALF_H, s.color, packedLight);
    }

    /** Render a thin rectangular prism (four side quads, no end caps). */
    private static void drawBox(
            PoseStack ps, VertexConsumer vc,
            Vec3 start, Vec3 end,
            float halfW, float halfH,
            int rgb, int packedLight
    ) {
        Vec3 axis = end.subtract(start);
        if (axis.lengthSqr() < 1.0E-12) return;

        // two perpendicular unit vectors to the beam axis
        Vec3 n = AxisHelper.UP.perpTo(axis).normalize();
        Vec3 m = axis.normalize().cross(n).normalize();

        Vec3 nx = n.scale(halfW);
        Vec3 my = m.scale(halfH);

        // start rectangle
        Vec3 s00 = start.subtract(nx).subtract(my);
        Vec3 s01 = start.subtract(nx).add(my);
        Vec3 s10 = start.add(nx).subtract(my);
        Vec3 s11 = start.add(nx).add(my);

        // end rectangle
        Vec3 e00 = end.subtract(nx).subtract(my);
        Vec3 e01 = end.subtract(nx).add(my);
        Vec3 e10 = end.add(nx).subtract(my);
        Vec3 e11 = end.add(nx).add(my);

        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8)  & 0xFF) / 255f;
        float b = ( rgb        & 0xFF) / 255f;
        float a = 1f;

        PoseStack.Pose pose = ps.last();

        // Four sides with proper per-face normals
        putQuad(pose, vc, s00, s01, e01, e00, r,g,b,a, packedLight, (float)-n.x, (float)-n.y, (float)-n.z); // -N
        putQuad(pose, vc, s11, s10, e10, e11, r,g,b,a, packedLight, (float) n.x, (float) n.y, (float) n.z); // +N
        putQuad(pose, vc, s10, s00, e00, e10, r,g,b,a, packedLight, (float)-m.x, (float)-m.y, (float)-m.z); // -M
        putQuad(pose, vc, s01, s11, e11, e01, r,g,b,a, packedLight, (float) m.x, (float) m.y, (float) m.z); // +M
    }

    private static void putQuad(
            PoseStack.Pose pose, VertexConsumer vc,
            Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
            float r, float g, float b, float a,
            int light, float nx, float ny, float nz
    ) {
        vc.addVertex(pose.pose(), (float)v0.x, (float)v0.y, (float)v0.z)
                .setColor(r,g,b,a).setUv(0f,0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx,ny,nz);
        vc.addVertex(pose.pose(), (float)v1.x, (float)v1.y, (float)v1.z)
                .setColor(r,g,b,a).setUv(1f,0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx,ny,nz);
        vc.addVertex(pose.pose(), (float)v2.x, (float)v2.y, (float)v2.z)
                .setColor(r,g,b,a).setUv(1f,1f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx,ny,nz);
        vc.addVertex(pose.pose(), (float)v3.x, (float)v3.y, (float)v3.z)
                .setColor(r,g,b,a).setUv(0f,1f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx,ny,nz);
    }

    /** Stable perpendicular chooser to avoid flips when nearly vertical/horizontal. */
    private enum AxisHelper {
        UP(new Vector3f(0,1,0)),
        RIGHT(new Vector3f(1,0,0));

        final Vector3f axis;
        AxisHelper(Vector3f a){ this.axis = a; }

        Vec3 perpTo(Vec3 dir) {
            Vec3 a = new Vec3(axis.x, axis.y, axis.z);
            Vec3 p = a.cross(dir);
            if (p.lengthSqr() < 1.0e-8) {
                a = this == UP ? new Vec3(1,0,0) : new Vec3(0,1,0);
                p = a.cross(dir);
            }
            return p;
        }
    }
}
