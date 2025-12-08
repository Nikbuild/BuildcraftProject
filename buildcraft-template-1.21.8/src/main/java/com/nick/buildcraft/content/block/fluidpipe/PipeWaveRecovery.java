package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;

import java.util.*;

public class PipeWaveRecovery {

    public static void smartWaveRecovery(FluidPipeBlockEntity root) {
        if (!root.isRoot() || root.getLevel() == null) return;

        clearCacheInNetwork(root);

        int newWavePosition = scanFurthestFilledPipe(root);

        if (newWavePosition <= 0) {
            root.frontPos = 0f;
            root.unitsInjected = 0;
        } else {
            if (root.unitsInjected > 0) {
                root.unitsInjected = Math.min(root.unitsInjected, newWavePosition);
                root.frontPos = Math.min(root.frontPos, (float)newWavePosition);
            } else {
                root.unitsInjected = newWavePosition;
                root.frontPos = (float)newWavePosition;
            }
        }

        root.pumpPushingThisTick = false;
        root.lastPumpPushTick = 0L;

        root.setChanged();
        if (!root.getLevel().isClientSide) {
            root.getLevel().sendBlockUpdated(root.getBlockPos(), root.getBlockState(), root.getBlockState(), 3);
        }
    }

    public static int scanFurthestFilledPipe(FluidPipeBlockEntity start) {
        Level level = start.getLevel();
        if (level == null) return 0;

        Set<BlockPos> visited = new HashSet<>();
        Map<Integer, FluidPipeBlockEntity> pipesByDistance = new HashMap<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(start.getBlockPos());
        visited.add(start.getBlockPos());

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

        int consecutive = 0;

        FluidPipeBlockEntity rootPipe = pipesByDistance.get(1);
        if (rootPipe != null) {
            boolean filled = (rootPipe.hasCheckpoint && rootPipe.checkpointAmount > 0)
                    || rootPipe.unitsInjected > 0;

            if (!filled) return 0;
            consecutive = 1;
        }

        for (int dist = 2; dist <= pipesByDistance.size() + 1; dist++) {
            FluidPipeBlockEntity pipe = pipesByDistance.get(dist);
            if (pipe == null) break;

            boolean filled = (pipe.hasCheckpoint && pipe.checkpointAmount > 0)
                    || pipe.unitsInjected > 0;

            if (filled && dist == consecutive + 1) {
                consecutive = dist;
            } else break;
        }

        return consecutive;
    }

    public static void clearCacheInNetwork(FluidPipeBlockEntity start) {
        Level level = start.getLevel();
        if (level == null) return;

        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(start.getBlockPos());
        visited.add(start.getBlockPos());

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {
                pipe.setCachedRoot(null);

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
    }
}
