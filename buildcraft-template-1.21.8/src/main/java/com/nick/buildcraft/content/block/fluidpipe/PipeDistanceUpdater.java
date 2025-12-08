package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

public class PipeDistanceUpdater {

    public static void updateDistanceFromRoot(FluidPipeBlockEntity pipe) {
        Level level = pipe.getLevel();
        if (level == null) return;

        int best = Integer.MAX_VALUE;

        if (pipe.isRoot()) {
            best = 1;
        }

        // Find nearest connected neighbor with valid distance
        for (Direction dir : Direction.values()) {
            if (!pipe.isConnected(dir)) continue;

            BlockPos np = pipe.getBlockPos().relative(dir);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof FluidPipeBlockEntity other) {
                int od = other.distanceFromRoot;
                if (od != Integer.MAX_VALUE) {
                    best = Math.min(best, od + 1);
                }
            }
        }

        if (best != pipe.distanceFromRoot) {
            pipe.prevDistanceFromRoot = pipe.distanceFromRoot;
            pipe.distanceFromRoot = best;

            // Smart wave recovery
            if (!pipe.isRoot() && !level.isClientSide()) {
                FluidPipeBlockEntity root = PipeNetworkFinder.findExistingRoot(pipe);
                if (root != null) {
                    PipeWaveRecovery.smartWaveRecovery(root);
                }
            }

            // Handle orphaning + checkpoint preservation
            if (pipe.distanceFromRoot == Integer.MAX_VALUE && !pipe.isRoot()) {
                pipe.isOrphaned = true;
                pipe.setCachedRoot(null);

                if (pipe.unitsInjected > 0 && pipe.displayedFluid != null && !pipe.displayedFluid.isEmpty()) {
                    if (!pipe.hasCheckpoint) {
                        float start = pipe.prevDistanceFromRoot - 1;
                        float wavePos = pipe.frontPos;
                        float passed = Math.max(0, wavePos - start);

                        if (passed > 0) {
                            pipe.hasCheckpoint = true;
                            pipe.checkpointFluid = pipe.displayedFluid.copy();
                            pipe.checkpointAmount = (int)(Math.min(1f, passed) * 1000);
                            pipe.checkpointFluid.setAmount(pipe.checkpointAmount);
                        }
                    }
                }

                // Clear wave state
                pipe.displayedFluid = FluidStack.EMPTY;
                pipe.frontPos = 0f;
                pipe.unitsInjected = 0;
                pipe.unitsConsumed = 0;
                pipe.unitsDeliveredToTank = 0;
            } else {
                pipe.isOrphaned = false;
            }

            // Lost root status
            if (pipe.isRoot() && !pipe.hasNonPipeNeighbor()) {
                pipe.isRoot = false;
                pipe.isOrphaned = true;
                pipe.hasCheckpoint = false;
                pipe.checkpointFluid = FluidStack.EMPTY;
                pipe.checkpointAmount = 0;
            }

            pipe.setChanged();
            if (!level.isClientSide) {
                level.sendBlockUpdated(pipe.getBlockPos(), pipe.getBlockState(), pipe.getBlockState(), 3);
            }
        }
    }
}
