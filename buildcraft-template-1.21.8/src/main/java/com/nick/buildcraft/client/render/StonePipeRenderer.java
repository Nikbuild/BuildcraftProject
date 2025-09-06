package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public class StonePipeRenderer implements BlockEntityRenderer<StonePipeBlockEntity> {

    private final ItemRenderer itemRenderer;

    // micro nudges (blocks)
    private static final double BIAS_ALL_AXES = 0.0;
    private static final double BIAS_HORIZONTAL_Y = 0.0;
    private static final double BIAS_VERTICAL_Y = 0.0;

    // stop INSIDE the nozzle when the run is open
    private static final double NOZZLE_INSET = 0.12;

    public StonePipeRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemRenderer = ctx.getItemRenderer();
    }

    @Override
    public void render(StonePipeBlockEntity be,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay,
                       Vec3 cameraPos) {

        ItemStack stack = be.getCargoForRender();
        if (stack.isEmpty() || be.getLevel() == null) return;

        // Hide for a couple ticks right after a cut so there is no “float” frame.
        if (be.shouldHideForCut()) {
            if (be.getNextPipeOrNull() == null && !be.isHeadingToSink()) return;
        }

        BlockPos self = be.getBlockPos();
        Vec3 a = center(self);

        // pick endpoint
        Vec3 b;
        BlockPos next = be.getNextPipeOrNull();
        if (next != null) {
            b = center(next);
        } else if (be.isHeadingToSink() && be.getSinkPos() != null) {
            b = center(be.getSinkPos());
        } else {
            Direction out = be.getOutgoingDirOrNull();
            b = (out != null) ? faceCenterInset(self, out, NOZZLE_INSET) : a;
        }

        float t = Math.max(0f, Math.min(1f, be.getSegmentProgress()));
        double wx = a.x + (b.x - a.x) * t;
        double wy = a.y + (b.y - a.y) * t;
        double wz = a.z + (b.z - a.z) * t;

        boolean vertical = Math.abs(b.y - a.y) > Math.abs(b.x - a.x) || Math.abs(b.y - a.y) > Math.abs(b.z - a.z);
        wy += (vertical ? BIAS_VERTICAL_Y : BIAS_HORIZONTAL_Y);
        wx += BIAS_ALL_AXES; wy += BIAS_ALL_AXES; wz += BIAS_ALL_AXES;

        pose.pushPose();
        pose.translate(wx - self.getX(), wy - self.getY(), wz - self.getZ());
        pose.scale(0.45f, 0.45f, 0.45f);

        itemRenderer.renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                packedLight,
                packedOverlay,
                pose,
                buffers,
                be.getLevel(),
                0
        );

        pose.popPose();
    }

    private static Vec3 center(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    /** Center point on the given face of this block, inset INSIDE by `inset`. */
    private static Vec3 faceCenterInset(BlockPos p, Direction dir, double inset) {
        double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;
        double d = 0.5 - Math.max(0.0, inset);
        return switch (dir) {
            case UP    -> new Vec3(cx, cy + d, cz);
            case DOWN  -> new Vec3(cx, cy - d, cz);
            case NORTH -> new Vec3(cx, cy, cz - d);
            case SOUTH -> new Vec3(cx, cy, cz + d);
            case WEST  -> new Vec3(cx - d, cy, cz);
            case EAST  -> new Vec3(cx + d, cy, cz);
        };
    }

    @Override
    public int getViewDistance() { return 64; }
}
