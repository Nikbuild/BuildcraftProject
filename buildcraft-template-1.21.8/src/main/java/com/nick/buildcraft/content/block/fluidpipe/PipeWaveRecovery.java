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

    /**
     * UPDATED: Now uses the MASTER SEGMENT TRACKER to find furthest filled pipe.
     *
     * The master tracker is the overlord that sees the whole physical network
     * and tells us where to resume, skipping over gaps.
     */
    public static int scanFurthestFilledPipe(FluidPipeBlockEntity start) {
        // Use the master segment tracker - it knows everything!
        return PipeSegmentTracker.getMasterInjectionTarget(start);
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