package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.quarry.FrameBlock;
import com.nick.buildcraft.content.block.quarry.QuarryBlock;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryGeometryHelper;
import com.nick.buildcraft.content.block.quarry.QuarryMiningManager;
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
    private static final double STILL_SEG_EPS = 1.0e-6;  // Much stricter "still" detection (was 1.0e-4)
    private static final int STILL_TICKS_TO_ARM = 40;    // Wait 2 seconds before snapping (was 3 ticks)
    private static final int SLAM_DOWN_T = 2;
    private static final int SLAM_HOLD_T = 2;
    private static final int SLAM_UP_T = 6;
    private static final int SLAM_TOTAL = SLAM_DOWN_T + SLAM_HOLD_T + SLAM_UP_T;
    private static final double SLAM_DIP_BLOCKS = 1.0;

    private final BlockRenderDispatcher brd;

    private static final class Anim {
        int stillTicks;
        long slamStartGT = -1;
        int curCellX = Integer.MIN_VALUE;
        int curCellZ = Integer.MIN_VALUE;
        boolean slammedThisCell = false;
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
        final QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(base, facing);
        final int xMin = b.x0 + 1, xMax = b.x1 - 1;
        final int zMin = b.z0 + 1, zMax = b.z1 - 1;
        if (xMax < xMin || zMax < zMin) return;

        // Use Calen's interpolation technique: interpolate between prev and current using partialTick
        Vec3 currentPos = be.getGantryPos();
        Vec3 prevPos = be.prevGantryPos;

        // Initialize positions if needed
        if (currentPos == null) currentPos = new Vec3(xMin + 0.5, b.y0 + 0.5, zMin + 0.5);
        if (prevPos == null) prevPos = currentPos;

        // Clamp positions to valid range
        currentPos = new Vec3(
                Mth.clamp(currentPos.x, xMin + 0.5, xMax + 0.5),
                b.y0 + 0.5,
                Mth.clamp(currentPos.z, zMin + 0.5, zMax + 0.5)
        );
        prevPos = new Vec3(
                Mth.clamp(prevPos.x, xMin + 0.5, xMax + 0.5),
                b.y0 + 0.5,
                Mth.clamp(prevPos.z, zMin + 0.5, zMax + 0.5)
        );

        // Interpolate between previous and current position using partialTick
        // This gives smooth sub-tick movement as the server updates position every tick
        Vec3 dispW = prevPos.add(currentPos.subtract(prevPos).scale(partialTick));
        double cxW = Mth.clamp(dispW.x, xMin + 0.5, xMax + 0.5);
        double czW = Mth.clamp(dispW.z, zMin + 0.5, zMax + 0.5);

        Anim anim = ANIMS.computeIfAbsent(base, k -> new Anim());
        final long gt = level.getGameTime();

        int wx = Mth.floor(cxW), wz = Mth.floor(czW);
        if (wx != anim.curCellX || wz != anim.curCellZ) {
            anim.curCellX = wx; anim.curCellZ = wz;
            anim.slammedThisCell = false; anim.slamStartGT = -1; anim.stillTicks = 0;
        }

        // Check if gantry is still (based on position delta)
        Vec3 movementDelta = currentPos.subtract(prevPos);
        boolean segmentIsStill = movementDelta.lengthSqr() <= STILL_SEG_EPS * STILL_SEG_EPS;
        if (segmentIsStill) anim.stillTicks++; else { anim.stillTicks = 0; anim.slamStartGT = -1; }

        int minY = level.dimensionType().minY();
        boolean ceilingBlocked = columnBlockedAtCeiling(level, b, wx, wz);
        int firstMineY = ceilingBlocked ? Integer.MIN_VALUE : findFirstMineableYDown(level, wx, wz, b.y0 - 1, minY);
        boolean mineableHere = firstMineY != Integer.MIN_VALUE;

        // Don't snap - let smooth interpolation handle positioning
        // The server will center the gantry naturally through atTarget logic

        double cx = cxW - base.getX();
        double cz = czW - base.getZ();

        // Rails stay at fixed height (top of frame)
        final int yRailLocal = b.y1 - base.getY();

        // Vertical lift for obstacle clearance - ONLY affects drill retraction, NOT rail height
        // Use Calen's interpolation for smooth vertical movement
        double currentLift = be.gantryLiftY;
        double prevLift = be.prevGantryLiftY;
        double interpolatedLiftY = prevLift + (currentLift - prevLift) * partialTick;

        int tipYWorld;
        if (ceilingBlocked) tipYWorld = b.y0;
        else if (mineableHere) tipYWorld = firstMineY;
        else {
            int floorY = findTopNonAirYDown(level, wx, wz, b.y0 - 1, minY);
            tipYWorld = (floorY != Integer.MIN_VALUE) ? floorY : b.y0;
        }
        final int tipLocalYi = tipYWorld - base.getY();

        // "Mechanic loosening stuck bolt" animation:
        // - Drill stays UP while breaking (drillTicks 0-9)
        // - Block breaks at drillTicks=10, drill SLAMS DOWN hard
        // - Then retracts back up
        double slamDipBlocks = 0.0;
        if (mineableHere && be.atTarget && be.drillTicks > 0) {
            int drillTicks = be.drillTicks;
            final int DRILL_TIME = 10; // Match server MINE_TICKS_PER_BLOCK

            if (drillTicks < DRILL_TIME) {
                // Breaking block (0-9): drill stays UP, no extension
                slamDipBlocks = 0.0;
                anim.slamStartGT = -1; // Not slamming yet
            } else {
                // Block just broke (drillTicks >= 10): SLAM DOWN sequence
                if (anim.slamStartGT < 0) anim.slamStartGT = gt; // Start slam animation

                double dt = (gt + partialTick - anim.slamStartGT);

                if (dt < SLAM_DOWN_T) {
                    // Slam down phase (0-2 ticks): quick extension
                    double downProgress = dt / SLAM_DOWN_T;
                    slamDipBlocks = SLAM_DIP_BLOCKS * easeOutCubic(downProgress);
                } else if (dt < SLAM_DOWN_T + SLAM_HOLD_T) {
                    // Hold at bottom phase (2-4 ticks): stay extended
                    slamDipBlocks = SLAM_DIP_BLOCKS;
                } else if (dt < SLAM_TOTAL) {
                    // Retract phase (4-10 ticks): pull back up
                    double upProgress = (dt - SLAM_DOWN_T - SLAM_HOLD_T) / SLAM_UP_T;
                    slamDipBlocks = SLAM_DIP_BLOCKS * (1.0 - easeOutCubic(upProgress));
                } else {
                    // Animation complete
                    slamDipBlocks = 0.0;
                    anim.slamStartGT = -1; // Reset for next block
                }
            }
        } else {
            // Not mining: reset animation
            slamDipBlocks = 0.0;
            anim.slamStartGT = -1;
        }

        BlockState railX = ModBlocks.FRAME.get().defaultBlockState().setValue(FrameBlock.AXIS, Direction.Axis.X).setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE);
        BlockState railZ = ModBlocks.FRAME.get().defaultBlockState().setValue(FrameBlock.AXIS, Direction.Axis.Z).setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE);
        BlockState riser = ModBlocks.FRAME.get().defaultBlockState().setValue(FrameBlock.AXIS, Direction.Axis.Y).setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE);

        // Sample light from gantry center position for consistent lighting across all components
        BlockPos gantryLightPos = new BlockPos(Mth.floor(cxW), b.y0, Mth.floor(czW));
        int gantryLight = LevelRenderer.getLightColor(level, gantryLightPos);

        pose.pushPose();

        // X rail - stays at FIXED rail height (doesn't move up/down)
        for (int ix = (b.x0 - base.getX()); ix <= (b.x1 - base.getX()); ix++) {
            pose.pushPose();
            pose.translate(ix, yRailLocal, cz - 0.5);
            brd.renderSingleBlock(railX, pose, buffers, gantryLight, packedOverlay);
            pose.popPose();
        }

        // Z rail - stays at FIXED rail height (doesn't move up/down)
        for (int iz = (b.z0 - base.getZ()); iz <= (b.z1 - base.getZ()); iz++) {
            pose.pushPose();
            pose.translate(cx - 0.5, yRailLocal, iz);
            brd.renderSingleBlock(railZ, pose, buffers, gantryLight, packedOverlay);
            pose.popPose();
        }

        // --- Vertical riser: RETRACTS from BOTTOM when lifting (like sucking up a straw) ---
        // The drill tip LIFTS UP when obstacle clearance is needed
        int extraRiser = (int)Math.floor(slamDipBlocks + 1.0e-6);

        // Rails stay at fixed yRailLocal (top of frame)
        // When lifting, the BOTTOM (drill tip) retracts UP by interpolatedLiftY
        int baseDrillTipY = tipLocalYi + 1 - extraRiser; // Normal drill tip position

        // CRITICAL FIX: Use FULL fractional lift for smooth retraction (not just integer part)
        // The drill tip position includes the fractional component for silky smooth movement
        double liftedDrillTipYExact = baseDrillTipY + interpolatedLiftY;
        int liftedDrillTipY = (int)Math.floor(liftedDrillTipYExact); // Integer part for block iteration
        double bottomFraction = liftedDrillTipYExact - liftedDrillTipY; // Fractional part for smooth bottom

        // Render full blocks from rail down to lifted drill tip
        // SKIP rendering if there's a solid block at that position (prevents clipping through obstacles)
        // Use unified gantry light to avoid checkered lighting artifacts
        for (int y = yRailLocal; y >= liftedDrillTipY + 1; y--) {
            // Check if there's a solid block at this position
            BlockPos checkPos = new BlockPos(Mth.floor(cxW), base.getY() + y, Mth.floor(czW));
            BlockState stateAtPos = level.getBlockState(checkPos);

            // Only render riser if the position is air or non-solid (don't clip through blocks)
            if (stateAtPos.isAir() || !stateAtPos.isSolidRender()) {
                pose.pushPose();
                pose.translate(cx - 0.5, y, cz - 0.5);
                brd.renderSingleBlock(riser, pose, buffers, gantryLight, packedOverlay);
                pose.popPose();
            }
        }

        // Render partial fractional block at the BOTTOM (drill tip retracting upward)
        // Only render if not clipping through a solid block
        if (bottomFraction > 0.01) {
            BlockPos checkPosFrac = new BlockPos(Mth.floor(cxW), base.getY() + liftedDrillTipY, Mth.floor(czW));
            BlockState stateAtFrac = level.getBlockState(checkPosFrac);

            if (stateAtFrac.isAir() || !stateAtFrac.isSolidRender()) {
                pose.pushPose();
                // Position at the lifted drill tip, scale down from bottom
                pose.translate(cx - 0.5, liftedDrillTipY + bottomFraction, cz - 0.5);
                pose.scale(1.0f, (float)(1.0 - bottomFraction), 1.0f);
                brd.renderSingleBlock(riser, pose, buffers, gantryLight, packedOverlay);
                pose.popPose();
            }
        }

        // 4-pixel lip that barely touches diamond tip - moves with the EXACT lifted drill tip position
        // Only render if not clipping through a solid block
        double capH = 0.25;
        double yLipExact = liftedDrillTipYExact; // Use exact fractional position
        int yLipWorld = (int)Math.floor(liftedDrillTipYExact);
        BlockPos posLip = new BlockPos(Mth.floor(cxW), base.getY() + yLipWorld, Mth.floor(czW));
        BlockState stateAtLip = level.getBlockState(posLip);

        if (stateAtLip.isAir() || !stateAtLip.isSolidRender()) {
            pose.pushPose();
            pose.translate(cx - 0.5, yLipExact + (1.0 - capH) + 0.05, cz - 0.5);
            pose.scale(1.0f, (float)capH, 1.0f);
            int lightLip = LevelRenderer.getLightColor(level, posLip);
            brd.renderSingleBlock(riser, pose, buffers, lightLip, packedOverlay);
            pose.popPose();
        }

        // Diamond tip - FULL SIZE, moves with the lifted drill tip like a fishhook being reeled in
        // Only render if not clipping through a solid block
        float sx = 0.28f, sy = 0.90f, sz = 0.28f;
        // Position the tip at the EXACT lifted position (using fractional lift for smooth movement)
        double tipYLocalF = liftedDrillTipYExact - slamDipBlocks + (0.5 - sy * 0.5);
        int tipWorldY = base.getY() + (int)Math.floor(liftedDrillTipYExact - slamDipBlocks);
        BlockPos posTip = new BlockPos(Mth.floor(cxW), tipWorldY, Mth.floor(czW));
        BlockState stateAtTip = level.getBlockState(posTip);

        if (stateAtTip.isAir() || !stateAtTip.isSolidRender()) {
            int lightTip = LevelRenderer.getLightColor(level, posTip);
            pose.pushPose();
            pose.translate((cx - 0.5) + (0.5 - sx * 0.5), tipYLocalF, (cz - 0.5) + (0.5 - sz * 0.5));
            pose.scale(sx, sy, sz); // Keep tip at full size - doesn't shrink!
            brd.renderSingleBlock(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(),
                    pose, buffers, lightTip, packedOverlay);
            pose.popPose();
        }

        pose.popPose();
    }

    // Easing helper
    private static double easeOutCubic(double x) {
        x = Mth.clamp(x, 0.0, 1.0);
        return 1.0 - Math.pow(1.0 - x, 3.0);
    }

    private static boolean edgesAreFrames(Level level, QuarryBlockEntity be) {
        BlockPos base = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(base, facing);
        for (BlockPos p : frameEdges(b)) if (!level.getBlockState(p).is(ModBlocks.FRAME.get())) return false;
        return true;
    }

    private static int findFirstMineableYDown(Level level, int x, int z, int startY, int minY) {
        for (int y = startY; y >= minY; y--) if (QuarryMiningManager.shouldMine(level, new BlockPos(x, y, z))) return y;
        return Integer.MIN_VALUE;
    }

    private static int findTopNonAirYDown(Level level, int x, int z, int startY, int minY) {
        for (int y = startY; y >= minY; y--) {
            BlockState bs = level.getBlockState(new BlockPos(x, y, z));
            if (!bs.isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean columnBlockedAtCeiling(Level level, QuarryGeometryHelper.Bounds b, int x, int z) {
        int y0 = b.y0;
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos p = new BlockPos(x, y0 + dy, z);
            BlockState bs = level.getBlockState(p);
            if (!bs.isAir() && !bs.getCollisionShape(level, p).isEmpty()) return true;
        }
        return false;
    }

    @Override
    public AABB getRenderBoundingBox(QuarryBlockEntity be) {
        final Level lvl = be.getLevel();
        if (lvl == null) return BlockEntityRenderer.super.getRenderBoundingBox(be);
        final int r = 256;
        final BlockPos p = be.getBlockPos();
        final int minY = lvl.dimensionType().minY();
        final int maxY = lvl.dimensionType().height() + minY;
        return new AABB(p.getX() - r, minY, p.getZ() - r, p.getX() + r + 1, maxY, p.getZ() + r + 1);
    }

    @Override
    public boolean shouldRender(QuarryBlockEntity be, Vec3 cameraPos) { return true; }

    @Override
    public boolean shouldRenderOffScreen() { return true; }

    @Override
    public int getViewDistance() { return 256; }

    private static List<BlockPos> frameEdges(QuarryGeometryHelper.Bounds b) {
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
