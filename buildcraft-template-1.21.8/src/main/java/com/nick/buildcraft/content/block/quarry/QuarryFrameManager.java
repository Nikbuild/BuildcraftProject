package com.nick.buildcraft.content.block.quarry;

import com.nick.buildcraft.content.entity.laser.LaserEntity;
import com.nick.buildcraft.registry.ModBlocks;
import com.nick.buildcraft.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Frame construction, visualization, and verification.
 * Handles progressive frame building with laser preview.
 */
public class QuarryFrameManager {

    private static final int FRAME_TICKS_PER_PIECE = 2;

    /**
     * Progressive frame building step.
     * Returns true when frame is fully constructed.
     */
    public static boolean stepFrameBuild(QuarryBlockEntity qbe, Level level, BlockPos origin, BlockState controllerState) {
        if (qbe.frameBuildQueue.isEmpty()) {
            populateFrameQueueFromOwnBounds(qbe, level, origin, controllerState);
            if (qbe.frameBuildQueue.isEmpty()) return verifyEdgesAreFrames(qbe);
        }

        qbe.frameTickCounter++;
        if (qbe.frameTickCounter < FRAME_TICKS_PER_PIECE) return false;

        BlockPos next = qbe.frameBuildQueue.pollFirst();
        qbe.frameTickCounter = 0;

        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(origin, facing);

        BlockState state = stateForEdgeBlock(next, b, facing);

        if (!level.getBlockState(next).is(ModBlocks.FRAME.get())) {
            level.setBlock(next, state, Block.UPDATE_CLIENTS);
        } else {
            BlockState cur = level.getBlockState(next);
            if (cur != state) level.setBlock(next, state, Block.UPDATE_CLIENTS);
        }

        return qbe.frameBuildQueue.isEmpty() && verifyEdgesAreFrames(qbe);
    }

    /**
     * Populate the frame build queue from the quarry bounds.
     * Implements the desired spawn pattern:
     * 1. Bottom floor: left-right alternating from front (green X) to back (purple X)
     * 2. Corner pillars: one corner at a time, bottom to top
     * 3. Top floor: four-color pattern (green → blue → orange → dark red)
     */
    private static void populateFrameQueueFromOwnBounds(QuarryBlockEntity qbe, Level level, BlockPos origin, BlockState controllerState) {
        Direction facing = controllerState.getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(origin, facing);

        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos p : QuarryGeometryHelper.frameEdges(b.min(), b.max())) {
            if (!level.getBlockState(p).is(ModBlocks.FRAME.get())) todo.add(p);
        }
        if (todo.isEmpty()) return;

        // Separate blocks into phases
        List<BlockPos> bottomFloor = new ArrayList<>();
        List<BlockPos> corners = new ArrayList<>();
        List<BlockPos> topFloor = new ArrayList<>();

        int minY = b.y0;
        int maxY = b.y1;

        for (BlockPos p : todo) {
            boolean onX0 = p.getX() == b.x0, onX1 = p.getX() == b.x1;
            boolean onZ0 = p.getZ() == b.z0, onZ1 = p.getZ() == b.z1;
            boolean onY0 = p.getY() == minY, onY1 = p.getY() == maxY;

            // Bottom floor: y=min AND on perimeter (not interior)
            if (onY0 && (onX0 || onX1 || onZ0 || onZ1)) {
                bottomFloor.add(p);
            }
            // Top floor: y=max AND on perimeter (not interior)
            else if (onY1 && (onX0 || onX1 || onZ0 || onZ1)) {
                topFloor.add(p);
            }
            // Corner pillars: at X and Z edges but not on top/bottom
            else if ((onX0 || onX1) && (onZ0 || onZ1)) {
                corners.add(p);
            }
        }

        // Sort bottom floor: zigzag pattern from front to back
        // Front wall (controller side) and back wall (opposite side) have opposite patterns
        // because back wall needs to build from corners inward
        bottomFloor.sort((p1, p2) -> {
            int[] norm1 = normalizeCoordinates(p1, b, facing);
            int[] norm2 = normalizeCoordinates(p2, b, facing);
            int normZ1 = norm1[1], normZ2 = norm2[1];
            int normX1 = norm1[0], normX2 = norm2[0];

            // Detect which wall each block is on (controller wall vs opposite wall)
            boolean onControllerWall1 = (normZ1 > 0);  // Controller is at max Z
            boolean onControllerWall2 = (normZ2 > 0);

            // If blocks are on different walls, need to check wall pattern difference
            // For now, sort by Z first to keep walls separate
            int cmp = Integer.compare(normZ1, normZ2);
            if (cmp != 0) return cmp;

            // Within same Z (same wall): determine distance and direction
            int distX1 = Math.abs(normX1);
            int distX2 = Math.abs(normX2);

            // First sort by distance from center
            cmp = Integer.compare(distX2, distX1);  // Reverse: farther from center first (edges before center)
            if (cmp != 0) return cmp;

            // Same X distance from center: apply wall-specific patterns
            boolean isLeft1 = normX1 < 0;
            boolean isLeft2 = normX2 < 0;

            if (onControllerWall1) {
                // Controller wall: build from edges toward center (7,1 → 6,2 → 5,3 → 4)
                // Always right first (positive X)
                cmp = Boolean.compare(!isLeft1, !isLeft2);  // right (false) before left (true)
            } else {
                // Opposite wall: build from center outward (4 → 5,3 → 6,2 → 7,1)
                // Always left first (negative X)
                cmp = Boolean.compare(isLeft1, isLeft2);  // left (true) before right (false)
            }
            if (cmp != 0) return cmp;

            // Final tiebreaker: blocks at exact same Z, X distance, and direction
            // Order by actual X coordinate value
            return Integer.compare(normX1, normX2);
        });

        // Sort corner pillars: one corner at a time, bottom to top
        corners.sort((p1, p2) -> {
            int[] norm1 = normalizeCoordinates(p1, b, facing);
            int[] norm2 = normalizeCoordinates(p2, b, facing);
            int normX1 = norm1[0], normZ1 = norm1[1];
            int normX2 = norm2[0], normZ2 = norm2[1];

            // Determine corner priority: front-left → front-right → back-left → back-right
            boolean isLeft1 = normX1 < 0, isLeft2 = normX2 < 0;
            boolean isFront1 = normZ1 < 0, isFront2 = normZ2 < 0;

            int corner1 = (isLeft1 ? 0 : 2) + (isFront1 ? 0 : 1);
            int corner2 = (isLeft2 ? 0 : 2) + (isFront2 ? 0 : 1);

            int cmp = Integer.compare(corner1, corner2);
            if (cmp != 0) return cmp;

            // Within corner: build bottom to top
            return Integer.compare(p1.getY(), p2.getY());
        });

        // Sort top floor: perimeter walk pattern (green → blue → orange → purple)
        // Walks around the edge continuously: right-front → right-back → left-back → left-front
        topFloor.sort((p1, p2) -> {
            int[] norm1 = normalizeCoordinates(p1, b, facing);
            int[] norm2 = normalizeCoordinates(p2, b, facing);
            int normX1 = norm1[0], normZ1 = norm1[1];
            int normX2 = norm2[0], normZ2 = norm2[1];

            // Distance from center: only process edge blocks first (dist > 0), then center (dist = 0)
            int distFromCenter1 = Math.abs(normX1) + Math.abs(normZ1);
            int distFromCenter2 = Math.abs(normX2) + Math.abs(normZ2);

            // Center blocks (dist = 0) go last
            if (distFromCenter1 == 0 && distFromCenter2 > 0) return 1;
            if (distFromCenter2 == 0 && distFromCenter1 > 0) return -1;
            if (distFromCenter1 == 0 && distFromCenter2 == 0) return 0;

            // For edge blocks (distFromCenter > 0): perimeter walk
            boolean isLeft1 = normX1 < 0, isLeft2 = normX2 < 0;
            boolean isFront1 = normZ1 < 0, isFront2 = normZ2 < 0;

            // Perimeter segments (starting from right-front, going clockwise):
            // Green: right-front (X > 0, Z < 0)
            // Blue: right-back (X > 0, Z >= 0)
            // Orange: left-back (X <= 0, Z >= 0)
            // Purple: left-front (X <= 0, Z < 0)
            int segment1, segment2;
            if (!isLeft1 && isFront1) segment1 = 0;      // green: right-front
            else if (!isLeft1 && !isFront1) segment1 = 1; // blue: right-back
            else if (isLeft1 && !isFront1) segment1 = 2;  // orange: left-back
            else segment1 = 3;                              // purple: left-front

            if (!isLeft2 && isFront2) segment2 = 0;      // green: right-front
            else if (!isLeft2 && !isFront2) segment2 = 1; // blue: right-back
            else if (isLeft2 && !isFront2) segment2 = 2;  // orange: left-back
            else segment2 = 3;                              // purple: left-front

            int cmp = Integer.compare(segment1, segment2);
            if (cmp != 0) return cmp;

            // Within same segment: build from EDGE outward toward center
            // Larger distance = farther from center = corner edge (build first)
            // Smaller distance = closer to center (build last)
            cmp = Integer.compare(distFromCenter2, distFromCenter1);  // Reversed!
            if (cmp != 0) return cmp;

            // Tiebreaker within segment and distance: maintain spatial order
            // Green/Blue (right side): order by Z then X
            // Orange/Purple (left side): order by Z then X
            if (!isLeft1) {
                // Right side: front to back (Z ascending)
                cmp = Integer.compare(normZ1, normZ2);
                if (cmp != 0) return cmp;
                return Integer.compare(normX1, normX2);
            } else {
                // Left side: back to front (Z descending)
                cmp = Integer.compare(normZ2, normZ1);
                if (cmp != 0) return cmp;
                return Integer.compare(normX2, normX1);
            }
        });

        // Combine all phases: bottom → corners → top
        qbe.frameBuildQueue.clear();
        qbe.frameBuildQueue.addAll(bottomFloor);
        qbe.frameBuildQueue.addAll(corners);
        qbe.frameBuildQueue.addAll(topFloor);
    }

    /**
     * Convert world coordinates to normalized coordinates based on facing.
     * This ensures consistent spawn order regardless of quarry direction.
     * Front edge (facing direction) always has smaller Z values in normalized space.
     */
    private static int[] normalizeCoordinates(BlockPos p, QuarryGeometryHelper.Bounds b, Direction facing) {
        int relX = p.getX() - (b.x0 + b.x1) / 2;  // relative to center
        int relZ = p.getZ() - (b.z0 + b.z1) / 2;  // relative to center

        // Normalize so front edge (facing direction) always has smallest Z
        // and frame building always starts from the front-left corner
        int normX, normZ;
        switch (facing) {
            case NORTH -> {
                // Looking North (toward -Z)
                // relX: left is negative, right is positive
                // relZ: front (-11) is negative, back (-1) is positive
                // Want: normZ small at front, large at back (preserve relZ)
                // Want: normX positive on right (where you see it)
                normX = relX;
                normZ = relZ;
            }
            case SOUTH -> {
                // Looking South (toward +Z)
                // relX: left is positive, right is negative
                // relZ: front (+1) is positive, back (+11) is positive and larger
                // Want: normZ small at front, large at back (invert relZ)
                // Want: normX consistent with NORTH (flip)
                normX = -relX;
                normZ = -relZ;
            }
            case EAST -> {
                // Looking East (toward +X)
                // relX: front (+1) is positive, back (+11) is positive and larger
                // relZ: left is negative, right is positive
                // Map: relX becomes normZ (front-back), relZ becomes normX (left-right)
                // Want: normZ small at front → invert relX
                // Want: normX consistent with NORTH (right is positive)
                normX = relZ;
                normZ = -relX;
            }
            case WEST -> {
                // Looking West (toward -X)
                // relX: front (-11) is negative, back (-1) is negative but less negative
                // relZ: left is positive, right is negative
                // Map: relX becomes normZ (front-back), relZ becomes normX (left-right, inverted)
                // Want: normZ small at front → preserve relX
                // Want: normX consistent with NORTH (right is positive → flip relZ)
                normX = -relZ;
                normZ = relX;
            }
            default -> {
                normX = relX;
                normZ = relZ;
            }
        }

        return new int[]{normX, normZ};
    }

    /**
     * Verify that all frame edges exist and are properly constructed.
     */
    public static boolean verifyEdgesAreFrames(QuarryBlockEntity qbe) {
        Level lvl = qbe.getLevel();
        if (lvl == null) return false;
        Direction facing = qbe.getBlockState().getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(qbe.getBlockPos(), facing);
        for (BlockPos p : QuarryGeometryHelper.frameEdges(b.min(), b.max())) {
            if (!lvl.getBlockState(p).is(ModBlocks.FRAME.get())) return false;
        }
        return true;
    }

    /**
     * Ensure frame boundary lasers are visible during construction.
     */
    public static void ensurePlacementLasers(QuarryBlockEntity qbe, ServerLevel level) {
        Direction facing = qbe.getBlockState().getValue(QuarryBlock.FACING);
        QuarryGeometryHelper.Bounds b = QuarryGeometryHelper.boundsForFacing(qbe.getBlockPos(), facing);

        double xL = b.x0 + 0.5, xR = b.x1 + 0.5;
        double zN = b.z0 + 0.5, zS = b.z1 + 0.5;
        double yB = b.y0 + 0.5, yT = b.y1 + 0.5;

        Vec3 b00 = new Vec3(xL, yB, zN), b10 = new Vec3(xR, yB, zN),
                b11 = new Vec3(xR, yB, zS), b01 = new Vec3(xL, yB, zS);
        Vec3 t00 = new Vec3(xL, yT, zN), t10 = new Vec3(xR, yT, zN),
                t11 = new Vec3(xR, yT, zS), t01 = new Vec3(xL, yT, zS);

        ensureLaser(qbe, level, 0, t00, t10);
        ensureLaser(qbe, level, 1, t10, t11);
        ensureLaser(qbe, level, 2, t11, t01);
        ensureLaser(qbe, level, 3, t01, t00);

        ensureLaser(qbe, level, 4, b00, b10);
        ensureLaser(qbe, level, 5, b10, b11);
        ensureLaser(qbe, level, 6, b11, b01);
        ensureLaser(qbe, level, 7, b01, b00);

        ensureLaser(qbe, level, 8,  b00, t00);
        ensureLaser(qbe, level, 9,  b10, t10);
        ensureLaser(qbe, level, 10, b11, t11);
        ensureLaser(qbe, level, 11, b01, t01);
    }

    /**
     * Ensure a specific laser entity exists and has correct endpoints.
     */
    private static void ensureLaser(QuarryBlockEntity qbe, ServerLevel level, int idx, Vec3 a, Vec3 b) {
        UUID id = qbe.placementLaserIds[idx];
        LaserEntity le;

        if (id == null) {
            le = new LaserEntity(ModEntities.LASER.get(), level);
            le.setColor(0xFF0000);
            level.addFreshEntity(le);
            qbe.placementLaserIds[idx] = le.getUUID();
        } else {
            Entity e = level.getEntity(id);
            if (!(e instanceof LaserEntity leE) || !e.isAlive()) {
                le = new LaserEntity(ModEntities.LASER.get(), level);
                le.setColor(0xFF0000);
                level.addFreshEntity(le);
                qbe.placementLaserIds[idx] = le.getUUID();
            } else {
                le = leE;
            }
        }

        le.setEndpoints(a, b);
    }

    /**
     * Clear all frame placement lasers.
     */
    public static void clearPlacementLasers(QuarryBlockEntity qbe, ServerLevel level) {
        for (int i = 0; i < qbe.placementLaserIds.length; i++) {
            UUID id = qbe.placementLaserIds[i];
            if (id != null) {
                Entity e = level.getEntity(id);
                if (e != null) e.discard();
                qbe.placementLaserIds[i] = null;
            }
        }
    }

    /**
     * Decide AXIS/CORNER for a frame block at position p on the shell.
     */
    private static BlockState stateForEdgeBlock(BlockPos p, QuarryGeometryHelper.Bounds b, Direction facing) {
        boolean onX0 = p.getX() == b.x0, onX1 = p.getX() == b.x1;
        boolean onZ0 = p.getZ() == b.z0, onZ1 = p.getZ() == b.z1;
        boolean onY0 = p.getY() == b.y0, onY1 = p.getY() == b.y1;

        // Corner piece?
        if ((onX0 || onX1) && (onZ0 || onZ1) && (onY0 || onY1)) {
            FrameBlock.Corner corner;
            boolean bottom = onY0;
            if (onX0 && onZ0) corner = bottom ? FrameBlock.Corner.XMIN_ZMIN_BOTTOM : FrameBlock.Corner.XMIN_ZMIN_TOP;
            else if (onX0 && onZ1) corner = bottom ? FrameBlock.Corner.XMIN_ZMAX_BOTTOM : FrameBlock.Corner.XMIN_ZMAX_TOP;
            else if (onX1 && onZ0) corner = bottom ? FrameBlock.Corner.XMAX_ZMIN_BOTTOM : FrameBlock.Corner.XMAX_ZMIN_TOP;
            else                   corner = bottom ? FrameBlock.Corner.XMAX_ZMAX_BOTTOM : FrameBlock.Corner.XMAX_ZMAX_TOP;

            return ModBlocks.FRAME.get().defaultBlockState()
                    .setValue(FrameBlock.CORNER, corner)
                    .setValue(FrameBlock.AXIS, Direction.Axis.Y);
        }

        // Top/bottom edges along X
        if ((onY0 || onY1) && (onZ0 || onZ1) && !(onX0 || onX1)) {
            return ModBlocks.FRAME.get().defaultBlockState()
                    .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE)
                    .setValue(FrameBlock.AXIS, Direction.Axis.X);
        }

        // Top/bottom edges along Z
        if ((onY0 || onY1) && (onX0 || onX1) && !(onZ0 || onZ1)) {
            return ModBlocks.FRAME.get().defaultBlockState()
                    .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE)
                    .setValue(FrameBlock.AXIS, Direction.Axis.Z);
        }

        // Uprights
        return ModBlocks.FRAME.get().defaultBlockState()
                .setValue(FrameBlock.CORNER, FrameBlock.Corner.NONE)
                .setValue(FrameBlock.AXIS, Direction.Axis.Y);
    }
}
