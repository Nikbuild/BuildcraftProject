package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/**
 * MASTER SEGMENT TRACKER - Fixed Version
 */
public class PipeSegmentTracker {

    public static class PipeSegment {
        public int startDistance;
        public int endDistance;
        public boolean hasFilled;
        public List<FluidPipeBlockEntity> pipes = new ArrayList<>();

        public PipeSegment(int start, int end) {
            this.startDistance = start;
            this.endDistance = end;
        }
    }

    public static List<PipeSegment> identifySegments(FluidPipeBlockEntity root) {
        Level level = root.getLevel();
        if (level == null) return new ArrayList<>();

        Map<Integer, FluidPipeBlockEntity> pipesByDistance = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(root.getBlockPos());
        visited.add(root.getBlockPos());

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                int d = pipe.getDistanceFromRoot();
                if (d != Integer.MAX_VALUE) {
                    pipesByDistance.put(d, pipe);
                }

                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnected(dir)) continue;
                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        List<PipeSegment> segments = new ArrayList<>();
        if (pipesByDistance.isEmpty()) return segments;

        int maxDistance = Collections.max(pipesByDistance.keySet());
        PipeSegment currentSegment = null;

        for (int dist = 1; dist <= maxDistance; dist++) {
            FluidPipeBlockEntity pipe = pipesByDistance.get(dist);

            if (pipe != null) {
                if (currentSegment == null) {
                    currentSegment = new PipeSegment(dist, dist);
                } else {
                    currentSegment.endDistance = dist;
                }

                currentSegment.pipes.add(pipe);

                boolean hasFilled = (pipe.hasCheckpoint && pipe.checkpointAmount > 0)
                        || pipe.unitsInjected > 0;
                if (hasFilled) {
                    currentSegment.hasFilled = true;
                }

            } else {
                if (currentSegment != null) {
                    segments.add(currentSegment);
                    currentSegment = null;
                }
            }
        }

        if (currentSegment != null) {
            segments.add(currentSegment);
        }

        return segments;
    }

    public static int getFurthestFilledSegmentEnd(FluidPipeBlockEntity root) {
        List<PipeSegment> segments = identifySegments(root);
        int furthest = 0;

        for (PipeSegment segment : segments) {
            if (segment.hasFilled) {
                furthest = Math.max(furthest, segment.endDistance);
            }
        }

        return furthest;
    }

    public static int getMasterInjectionTarget(FluidPipeBlockEntity root) {
        return getFurthestFilledSegmentEnd(root);
    }

    /**
     * NEW: Find the NEAREST unfilled gap in the pipe network.
     * This enables sequential gap-filling behavior like real plumbing.
     *
     * Returns the distance of the first empty pipe encountered when scanning
     * from root outward. If there are no gaps before the furthest checkpoint,
     * returns the furthest filled position instead.
     *
     * Example:
     *   [Root][1-Filled][2-EMPTY][3-Filled][4-Filled]
     *   Returns: 2 (fill the gap at position 2 first)
     */
    public static int findNearestUnfilledGap(FluidPipeBlockEntity root) {
        List<PipeSegment> segments = identifySegments(root);

        if (segments.isEmpty()) {
            return 0;
        }

        // First, find if there's ANY filled segment (to know we have checkpoints)
        boolean hasAnyFilledSegment = false;
        for (PipeSegment seg : segments) {
            if (seg.hasFilled) {
                hasAnyFilledSegment = true;
                break;
            }
        }

        // If no checkpoints exist, use normal behavior (furthest position)
        if (!hasAnyFilledSegment) {
            return 0;
        }

        // Scan from distance 1 outward to find the first unfilled pipe
        Level level = root.getLevel();
        if (level == null) return 0;

        Map<Integer, FluidPipeBlockEntity> pipesByDistance = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(root.getBlockPos());
        visited.add(root.getBlockPos());

        // Build map of all pipes by distance
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                int d = pipe.getDistanceFromRoot();
                if (d != Integer.MAX_VALUE) {
                    pipesByDistance.put(d, pipe);
                }

                for (Direction dir : Direction.values()) {
                    if (!pipe.isConnected(dir)) continue;
                    BlockPos next = current.relative(dir);
                    if (visited.contains(next)) continue;

                    BlockEntity nextBe = level.getBlockEntity(next);
                    if (nextBe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        if (pipesByDistance.isEmpty()) return 0;

        int maxDistance = Collections.max(pipesByDistance.keySet());
        int furthestFilled = getFurthestFilledSegmentEnd(root);

        // Scan from distance 1 to furthest filled position
        for (int dist = 1; dist <= Math.max(maxDistance, furthestFilled); dist++) {
            FluidPipeBlockEntity pipe = pipesByDistance.get(dist);

            if (pipe == null) {
                // Missing pipe in chain - this is a broken network gap, skip it
                continue;
            }

            // Check if this pipe is unfilled
            boolean hasCheckpoint = pipe.hasCheckpoint && pipe.checkpointAmount > 0;
            boolean hasActiveWave = pipe.unitsInjected > 0;

            if (!hasCheckpoint && !hasActiveWave) {
                // Found the nearest unfilled gap!
                return dist;
            }
        }

        // No gaps found - all pipes up to furthest checkpoint are filled
        // Return furthest filled position for normal wave progression
        return furthestFilled;
    }

    /**
     * CRITICAL: Count unfilled gaps that actually need wave fluid.
     * Used to calculate virtual consumed units.
     */
    public static int countUnfilledGaps(FluidPipeBlockEntity root) {
        List<PipeSegment> segments = identifySegments(root);

        int unfilledCount = 0;

        for (PipeSegment segment : segments) {
            for (FluidPipeBlockEntity pipe : segment.pipes) {
                boolean hasCheckpoint = pipe.hasCheckpoint && pipe.checkpointAmount > 0;
                boolean hasActiveWave = pipe.unitsInjected > 0;

                if (!hasCheckpoint && !hasActiveWave) {
                    unfilledCount++;
                }
            }
        }

        return unfilledCount;
    }

    public static void debugPrintSegments(FluidPipeBlockEntity root) {
        List<PipeSegment> segments = identifySegments(root);

        System.out.println("=== PIPE SEGMENT ANALYSIS ===");
        for (int i = 0; i < segments.size(); i++) {
            PipeSegment seg = segments.get(i);
            System.out.println("Segment " + (i+1) + ": distances " + seg.startDistance +
                    " to " + seg.endDistance +
                    ", filled=" + seg.hasFilled +
                    ", pipes=" + seg.pipes.size());
        }
        System.out.println("Master injection target: " + getMasterInjectionTarget(root));
        System.out.println("Unfilled gaps: " + countUnfilledGaps(root));
        System.out.println("=============================");
    }
}
