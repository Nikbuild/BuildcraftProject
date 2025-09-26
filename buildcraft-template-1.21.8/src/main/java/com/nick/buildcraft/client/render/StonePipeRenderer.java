// src/main/java/com/nick/buildcraft/client/render/StonePipeRenderer.java
package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.pipe.DiamondPipeBlockEntity;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity.RenderTrip;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class StonePipeRenderer implements BlockEntityRenderer<StonePipeBlockEntity> {

    private final ItemRenderer itemRenderer;

    private static final float  ITEM_SCALE              = 0.45f;
    private static final double FACE_INSET              = 2.0 / 16.0; // stop just inside the glass
    private static final double ALONG_EPSILON_PER_ITEM  = 0.0025;
    private static final float  MAX_VISUAL_PROGRESS     = 0.9999f;

    // server clamps at 23/24 when bouncing at a diamond edge; we remap that to 0..1 here
    private static final float  SERVER_STOP_CAP         = 23f / 24f;

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

        if (be.getLevel() == null) return;

        var trips = be.getRenderTrips(8);
        if (trips.isEmpty()) return;

        final BlockPos self = be.getBlockPos();
        final Vec3 a = centerOf(self);

        for (int i = 0; i < trips.size(); i++) {
            RenderTrip rt = trips.get(i);
            if (rt.hiddenForCut) continue;
            ItemStack stack = rt.stack;
            if (stack.isEmpty()) continue;

            // Figure our “forward” direction
            Direction out = rt.outgoingDirOrNull;
            if (out == null) {
                if (rt.nextPipeOrNull != null) out = directionFromTo(self, rt.nextPipeOrNull);
                else if (rt.sinkPosOrNull != null) out = directionFromTo(self, rt.sinkPosOrNull);
                else out = Direction.NORTH;
            }

            // === Choose endpoint 'b' smartly ===
            Vec3 b;
            boolean stopAtNozzle = false;

            if (rt.nextPipeOrNull != null) {
                // Default: smooth to neighbor center
                b = centerOf(rt.nextPipeOrNull);

                // If the neighbor is a Diamond pipe and would reject from our entering face,
                // DO NOT cross visually; stop at our nozzle instead.
                var nextBe = be.getLevel().getBlockEntity(rt.nextPipeOrNull);
                if (nextBe instanceof DiamondPipeBlockEntity dp) {
                    Direction enteringFace = out.getOpposite(); // how 'next' sees us
                    EnumSet<Direction> allowed = dp.getAllowedDirections(stack);
                    if (allowed.contains(enteringFace)) stopAtNozzle = true;
                }

                if (stopAtNozzle) {
                    b = faceCenterInset(self, out, FACE_INSET);
                }
            } else if (rt.sinkPosOrNull != null) {
                // Heading into an inventory → stop at our nozzle
                b = faceCenterInset(self, out, FACE_INSET);
                stopAtNozzle = true;
            } else {
                // No next/sink known → head toward our nozzle
                b = faceCenterInset(self, out, FACE_INSET);
                stopAtNozzle = true; // conservative
            }

            // --- Progress remap ---
            float raw = Math.max(0f, Math.min(1f, rt.progress));
            float t;
            if (stopAtNozzle) {
                // Server never lets progress reach 1.0 in this case (it clamps ~0.9583).
                // Linearly stretch [0..23/24] to [0..MAX_VISUAL_PROGRESS] so motion looks uniform.
                float capped = Math.min(raw, SERVER_STOP_CAP);
                t = (SERVER_STOP_CAP == 0f) ? 0f : (capped / SERVER_STOP_CAP) * MAX_VISUAL_PROGRESS;
            } else {
                t = Math.min(raw, MAX_VISUAL_PROGRESS);
            }

            // Lerp from center to chosen endpoint
            double wx = lerp(a.x, b.x, t);
            double wy = lerp(a.y, b.y, t);
            double wz = lerp(a.z, b.z, t);

            // Tiny along-path epsilon so multiple items don't Z-fight
            Vec3 seg = b.subtract(a);
            double len = Math.max(1.0E-6, seg.length());
            Vec3 unit = new Vec3(seg.x / len, seg.y / len, seg.z / len);
            double centeredIdx = i - (trips.size() - 1) * 0.5;
            double eps = centeredIdx * ALONG_EPSILON_PER_ITEM;
            wx += unit.x * eps; wy += unit.y * eps; wz += unit.z * eps;

            pose.pushPose();
            pose.translate(wx - self.getX(), wy - self.getY(), wz - self.getZ());
            pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
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
    }

    // -- helpers --------------------------------------------------------------------------

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static Vec3 centerOf(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

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

    private static Direction directionFromTo(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dy = Integer.compare(to.getY() - from.getY(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST  : Direction.WEST;
        if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)) return dy >= 0 ? Direction.UP    : Direction.DOWN;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    @Override
    public int getViewDistance() { return 64; }
}
