package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class PipeNetworkFinder {

    @Nullable
    public static FluidPipeBlockEntity findExistingRoot(FluidPipeBlockEntity start) {
        Level level = start.getLevel();
        if (level == null) return null;

        // Use cached if valid
        FluidPipeBlockEntity cached = start.getCachedRoot();
        if (cached != null && cached.isRoot() && !cached.isRemoved()) {
            return cached;
        }

        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(start.getBlockPos());
        visited.add(start.getBlockPos());

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockEntity be = level.getBlockEntity(current);

            if (be instanceof FluidPipeBlockEntity pipe) {

                if (pipe.isRoot() && pipe != start) {
                    start.setCachedRoot(pipe);
                    return pipe;
                }

                for (Direction dir : Direction.values()) {
                    // Use accessor instead of private isConnectedInDirection
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

        start.setCachedRoot(null);
        return null;
    }
}
