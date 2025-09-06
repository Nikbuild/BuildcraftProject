package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.quarry.FrameBlock;
import com.nick.buildcraft.content.block.quarry.QuarryBlock;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Client gantry renderer with smooth XY motion + one-per-cell slam
 * (down/hold/up) and strict centering when drilling.
 */
public class QuarryRenderer implements BlockEntityRenderer<QuarryBlockEntity> {

    private static final int HALF = 5;
    private static final int HEIGHT = 5;

    /** Interp length between server samples. */
    private static final int SMOOTH_TICKS = 4;

    /** "Still" detection & arming. */
    private static final double STILL_SEG_EPS = 1.0e-4; // blocks
    private static final int STILL_TICKS_TO_ARM = 3;    // samples

    /** Slam profile (ticks). */
    private static final int SLAM_DOWN_T = 2;
    private static final int SLAM_HOLD_T = 2;
    private static final int SLAM_UP_T   = 6;
    private static final int SLAM_TOTAL  = SLAM_DOWN_T + SLAM_HOLD_T + SLAM_UP_T;
    private static final double SLAM_DIP_BLOCKS = 1.0;

    private final BlockRenderDispatcher brd;

    /** Per-quarry animation state. */
    private static final class Anim {
        Vec3 from, to, lastPacket;
        long startGT;

        int stillTicks;

        // slam state
        long slamStartGT = -1;             // <0 = no slam active
        int curCellX = Integer.MIN_VALUE;  // current column (world)
        int curCellZ = Integer.MIN_VALUE;
        boolean slammedThisCell = false;   // gate: only 1 slam per visited cell
    }
    private static final Map<BlockPos, Anim> ANIMS = new HashMap<>();

    public QuarryRenderer(BlockEntityRendererProvider.Context ctx) {
        this.brd = Minecraft.getInstance().getBlockRenderer();
    }

    @Override
    public void render(QuarryBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay, Vec3 camPos) {
        Level level = be.getLevel();
        if (level == null) return;

        final BlockPos base = be.getBlockPos();
        if (be.isRemoved()) { ANIMS.remove(base); return; }

        if (!edgesAreFrames(level, be)) return;

        final Direction facing = be.getBlockState().getValue(QuarryBlock.FACING);
        final Bounds b = boundsForFacing(base, facing);

        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;
        if (xMax < xMin || zMax < zMin) return;

        // ---------- server-synced gantry center (world) ----------
        Vec3 packet = be.getGantryPos();
        if (packet == null) packet = new Vec3(xMin + 0.5, b.y0 + 0.5, zMin + 0.5);
        packet = new Vec3(
                Mth.clamp(packet.x, xMin + 0.5, xMax + 0.5),
                b.y0 + 0.5,
                Mth.clamp(packet.z, zMin + 0.5, zMax + 0.5)
        );

        // ---------- interpolation ----------
        Anim anim = ANIMS.computeIfAbsent(base, k -> new Anim());
        final long gt = level.getGameTime();

        if (anim.to == null) {
            anim.from = anim.to = anim.lastPacket = packet;
            anim.startGT = gt;
        } else if (anim.lastPacket == null || anim.lastPacket.distanceToSqr(packet) > 1.0e-6) {
            anim.from = anim.to;
            anim.to = packet;
            anim.lastPacket = packet;
            anim.startGT = gt;
        }

        double tRaw = (gt + partialTick - anim.startGT) / (double) SMOOTH_TICKS;
        double tClamped = Mth.clamp(tRaw, 0.0, 1.0);
        double u = tClamped * tClamped * (3.0 - 2.0 * tClamped); // smoothstep
        Vec3 seg = anim.to.subtract(anim.from);

        Vec3 dispW;
        if (tRaw <= 1.0) {
            dispW = anim.from.add(seg.scale(u));   // smooth to the packet
        } else {
            dispW = anim.to;                        // stop at the packet; no overshoot
        }

        double cxW = Mth.clamp(dispW.x, xMin + 0.5, xMax + 0.5);
        double czW = Mth.clamp(dispW.z, zMin + 0.5, zMax + 0.5);

        // ---------- cell tracking (ONE-PER-CELL SLAM) ----------
        int wx = Mth.floor(cxW), wz = Mth.floor(czW);
        if (wx != anim.curCellX || wz != anim.curCellZ) {
            anim.curCellX = wx;
            anim.curCellZ = wz;
            anim.slammedThisCell = false;
            anim.slamStartGT = -1; // abort any old slam if we moved
            anim.stillTicks = 0;
        }

        // ----- still detection -----
        boolean segmentIsStill = seg.lengthSqr() <= STILL_SEG_EPS * STILL_SEG_EPS;
        if (segmentIsStill) anim.stillTicks++; else { anim.stillTicks = 0; anim.slamStartGT = -1; }

        // ----- mining check (is there something under this column?) -----
        int minY = level.dimensionType().minY();
        boolean ceilingBlocked = columnBlockedAtCeiling(level, b, wx, wz);
        int firstMineY = ceilingBlocked ? Integer.MIN_VALUE : findFirstMineableYDown(level, wx, wz, b.y0 - 1, minY);
        boolean mineableHere = firstMineY != Integer.MIN_VALUE;

        // ----- centering + arming -----
        boolean lockCenter = false;
        if (mineableHere && (anim.slamStartGT >= 0 || anim.slammedThisCell || anim.stillTicks >= STILL_TICKS_TO_ARM)) {
            // hard center while armed / slamming / post-slam waiting
            cxW = wx + 0.5;
            czW = wz + 0.5;
            dispW = anim.to; // kill forward nudge
            lockCenter = true;
        }

        if (mineableHere && anim.slamStartGT < 0 && !anim.slammedThisCell && anim.stillTicks >= STILL_TICKS_TO_ARM) {
            anim.slamStartGT = gt;         // start exactly once for this cell
            anim.slammedThisCell = true;
        }

        // convert to local space
        double cx = cxW - base.getX();
        double cz = czW - base.getZ();

        // top y local
        final int yTopLocalI = b.y1 - base.getY();

        // ---- tip target Y (server-truth for column content) ----
        int tipYWorld;
        if (ceilingBlocked) {
            tipYWorld = b.y0;
        } else if (mineableHere) {
            tipYWorld = firstMineY;
        } else {
            int floorY = findTopNonAirYDown(level, wx, wz, b.y0 - 1, minY);
            tipYWorld = (floorY != Integer.MIN_VALUE) ? floorY : b.y0;
        }
        final int tipLocalYi = tipYWorld - base.getY();

        // ---------- slam offset ----------
        double slamDipBlocks = 0.0;
        if (anim.slamStartGT >= 0) {
            double dt = (gt + partialTick - anim.slamStartGT);
            if (dt <= SLAM_DOWN_T) {
                double x = dt / SLAM_DOWN_T;
                slamDipBlocks = SLAM_DIP_BLOCKS * easeOutCubic(x);
            } else if (dt <= SLAM_DOWN_T + SLAM_HOLD_T) {
                slamDipBlocks = SLAM_DIP_BLOCKS;
            } else if (dt <= SLAM_TOTAL) {
                double x = (dt - SLAM_DOWN_T - SLAM_HOLD_T) / SLAM_UP_T;
                slamDipBlocks = SLAM_DIP_BLOCKS * (1.0 - easeOutCubic(x));
            } else {
                anim.slamStartGT = -1; // finished
            }
        }

        // ---------- render ----------
        BlockState railX = ModBlocks.FRAME.get().defaultBlockState()
                .setValue(FrameBlock.AXIS, Direction.Axis.X)
                .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE);
        BlockState railZ = ModBlocks.FRAME.get().defaultBlockState()
                .setValue(FrameBlock.AXIS, Direction.Axis.Z)
                .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE);
        BlockState riser = ModBlocks.FRAME.get().defaultBlockState()
                .setValue(FrameBlock.AXIS, Direction.Axis.Y)
                .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE);

        pose.pushPose();

        // X rail (full width) at current Z
        for (int ix = (b.x0 - base.getX()); ix <= (b.x1 - base.getX()); ix++) {
            pose.pushPose();
            pose.translate(ix, yTopLocalI, cz - 0.5);
            BlockPos posX = base.offset(ix, yTopLocalI, (int)(cz - 0.5));
            brd.renderSingleBlock(railX, pose, buffers, LevelRenderer.getLightColor(level, posX), packedOverlay);
            pose.popPose();
        }

        // Z rail (full depth) at current X
        for (int iz = (b.z0 - base.getZ()); iz <= (b.z1 - base.getZ()); iz++) {
            pose.pushPose();
            pose.translate(cx - 0.5, yTopLocalI, iz);
            BlockPos posZ = base.offset((int)(cx - 0.5), yTopLocalI, iz);
            brd.renderSingleBlock(railZ, pose, buffers, LevelRenderer.getLightColor(level, posZ), packedOverlay);
            pose.popPose();
        }

        // Vertical riser – keep one block of diamond visible when retracted; extend with slam
        int extraRiser = (int)Math.floor(slamDipBlocks + 1.0e-6);
        int bottomInclusive = tipLocalYi + 2 - extraRiser;
        for (int y = yTopLocalI; y >= bottomInclusive; y--) {
            pose.pushPose();
            pose.translate(cx - 0.5, y, cz - 0.5);
            BlockPos posY = base.offset((int)(cx - 0.5), y, (int)(cz - 0.5));
            brd.renderSingleBlock(riser, pose, buffers, LevelRenderer.getLightColor(level, posY), packedOverlay);
            pose.popPose();
        }

        // Diamond tip
        {
            float sx = 0.28f, sy = 0.90f, sz = 0.28f;
            double tipBaseY = (tipLocalYi + 1) + (0.5 - sy * 0.5);
            double tipYLocalF = tipBaseY - slamDipBlocks;

            BlockPos posTip = base.offset((int)(cx - 0.5),
                    tipLocalYi + 1 - (int)Math.floor(slamDipBlocks),
                    (int)(cz - 0.5));
            int lightTip = LevelRenderer.getLightColor(level, posTip);

            pose.pushPose();
            pose.translate((cx - 0.5) + (0.5 - sx * 0.5), tipYLocalF, (cz - 0.5) + (0.5 - sz * 0.5));
            pose.scale(sx, sy, sz);
            brd.renderSingleBlock(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(),
                    pose, buffers, lightTip, packedOverlay);
            pose.popPose();
        }

        pose.popPose();
    }

    /* ---------------- helpers ---------------- */

    private static double easeOutCubic(double x) {
        x = Mth.clamp(x, 0.0, 1.0);
        return 1.0 - Math.pow(1.0 - x, 3.0);
    }

    private static boolean edgesAreFrames(Level level, QuarryBlockEntity be) {
        BlockPos base = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(base, facing);
        for (BlockPos p : frameEdges(b)) if (!level.getBlockState(p).is(ModBlocks.FRAME.get())) return false;
        return true;
    }

    private static boolean shouldMine(Level level, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        if (bs.isAir()) return false;
        FluidState fs = bs.getFluidState();
        if (!fs.isEmpty()) return false;
        if (bs.getDestroySpeed(level, p) < 0) return false;
        if (bs.is(ModBlocks.FRAME.get())) return false;
        return true;
    }

    private static int findFirstMineableYDown(Level level, int x, int z, int startY, int minY) {
        for (int y = startY; y >= minY; y--) if (shouldMine(level, new BlockPos(x, y, z))) return y;
        return Integer.MIN_VALUE;
    }

    private static boolean isCeilingObstacle(Level level, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        if (bs.isAir()) return false;
        if (bs.is(ModBlocks.FRAME.get())) return true;
        return !bs.getCollisionShape(level, p).isEmpty();
    }

    private static boolean columnBlockedAtCeiling(Level level, Bounds b, int x, int z) {
        int y0 = b.y0;
        if (isCeilingObstacle(level, new BlockPos(x, y0,     z))) return true;
        if (isCeilingObstacle(level, new BlockPos(x, y0 + 1, z))) return true;
        if (isCeilingObstacle(level, new BlockPos(x, y0 + 2, z))) return true;
        return false;
    }

    @Override
    public AABB getRenderBoundingBox(QuarryBlockEntity be) {
        Direction facing = be.getBlockState().getValue(QuarryBlock.FACING);
        Bounds b = boundsForFacing(be.getBlockPos(), facing);
        return new AABB(b.x0, b.y0 - 1, b.z0, b.x1 + 1, b.y1 + 1, b.z1 + 1);
    }

    private static int findTopNonAirYDown(Level level, int x, int z, int startY, int minY) {
        for (int y = startY; y >= minY; y--) if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        return Integer.MIN_VALUE;
    }

    @Override public int getViewDistance() { return 256; }

    /* ------------ geometry ------------ */

    private record Bounds(int x0, int y0, int z0, int x1, int y1, int z1) {}

    private static Bounds boundsForFacing(BlockPos pos, Direction facing) {
        final int size = 2 * HALF + 1;
        int x0, x1, z0, z1;
        int y0 = pos.getY(), y1 = pos.getY() + HEIGHT;

        switch (facing) {
            case NORTH -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() - size; z1 = pos.getZ() - 1; }
            case SOUTH -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() + 1;    z1 = pos.getZ() + size; }
            case WEST  -> { x0 = pos.getX() - size; x1 = pos.getX() - 1;    z0 = pos.getZ() - HALF; z1 = pos.getZ() + HALF; }
            case EAST  -> { x0 = pos.getX() + 1;    x1 = pos.getX() + size; z0 = pos.getZ() - HALF; z1 = pos.getZ() + HALF; }
            default    -> { x0 = pos.getX() - HALF; x1 = pos.getX() + HALF; z0 = pos.getZ() - size; z1 = pos.getZ() - 1; }
        }
        return new Bounds(x0, y0, z0, x1, y1, z1);
    }

    private static List<BlockPos> frameEdges(Bounds b) {
        List<BlockPos> out = new ArrayList<>();
        int x0=b.x0, x1=b.x1, y0=b.y0, y1=b.y1, z0=b.z0, z1=b.z1;

        for (int y : new int[]{y0, y1})
            for (int z : new int[]{z0, z1})
                for (int x = x0; x <= x1; x++) out.add(new BlockPos(x, y, z));
        for (int y : new int[]{y0, y1})
            for (int x : new int[]{x0, x1})
                for (int z = z0; z <= z1; z++) out.add(new BlockPos(x, y, z));
        for (int x : new int[]{x0, x1})
            for (int z : new int[]{z0, z1})
                for (int y = y0; y <= y1; y++) out.add(new BlockPos(x, y, z));
        return out;
    }
}
