package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

public class PipeFluidInjector {

    public static FluidStack offer(FluidPipeBlockEntity pipe, FluidStack stack, Direction from) {
        if (pipe.getPipeLevel() == null || stack.isEmpty()) return stack;

        BlockPos srcPos = pipe.getPipePos().relative(from);
        BlockEntity srcBe = pipe.getPipeLevel().getBlockEntity(srcPos);
        boolean fromPipe = srcBe instanceof FluidPipeBlockEntity;

        if (!fromPipe) {
            FluidPipeBlockEntity existingRoot = PipeNetworkFinder.findExistingRoot(pipe);

            if (existingRoot != null && existingRoot != pipe) {
                return PipeFluidInjector.offer(existingRoot, stack, from);
            }

            pipe.isRoot = true;
            pipe.isOrphaned = false;
            pipe.isVirginPipe = false;
            pipe.virginTicksRemaining = 0;

            if (pipe.displayedFluid.isEmpty()) {
                pipe.displayedFluid = new FluidStack(stack.getFluid(), 1000);
            }

            int networkCapacity = getNetworkCapacity(pipe);
            int unitsInSystem = pipe.unitsInjected - pipe.unitsConsumed;

            if (unitsInSystem >= networkCapacity) {
                return stack;
            }

            int incomingMb = stack.getAmount();
            pipe.injectedMbAccum += incomingMb;

            int newUnits = pipe.injectedMbAccum / 1000;

            if (newUnits > 0) {
                // NEW SEQUENTIAL GAP-FILLING LOGIC
                // Instead of jumping to the furthest checkpoint, we find and fill
                // the NEAREST unfilled gap. This creates realistic plumbing behavior
                // where you must fill gaps sequentially from root outward.

                // Check if system has any checkpoints before applying gap-fill logic
                int nearestGap = PipeSegmentTracker.findNearestUnfilledGap(pipe);
                int furthestCheckpoint = PipeWaveRecovery.scanFurthestFilledPipe(pipe);
                boolean hasCheckpoints = (nearestGap > 0 || furthestCheckpoint > 0);

                if (pipe.unitsInjected == 0 && pipe.unitsConsumed == 0 && hasCheckpoints) {
                    if (nearestGap > 0) {
                        // There's a gap to fill - target the nearest gap
                        pipe.unitsInjected = nearestGap;
                        pipe.frontPos = nearestGap;

                        // Calculate consumed units accounting for filled checkpoints
                        // Consumed = (target position) - (number of unfilled gaps before target)
                        int unfilledGaps = PipeSegmentTracker.countUnfilledGaps(pipe);
                        pipe.unitsConsumed = nearestGap - unfilledGaps;
                    } else if (furthestCheckpoint > 0) {
                        // No gaps - resume from furthest checkpoint normally
                        pipe.unitsInjected = furthestCheckpoint;
                        pipe.frontPos = furthestCheckpoint;

                        int unfilledGaps = PipeSegmentTracker.countUnfilledGaps(pipe);
                        pipe.unitsConsumed = furthestCheckpoint - unfilledGaps;
                    }
                }

                int unitsToAdd = Math.min(newUnits, networkCapacity - unitsInSystem);

                if (unitsToAdd > 0) {
                    int beforeInjected = pipe.unitsInjected;
                    pipe.unitsInjected += unitsToAdd;

                    int actualAdded = pipe.unitsInjected - beforeInjected;
                    if (actualAdded != unitsToAdd) {
                        pipe.unitsInjected = beforeInjected + unitsToAdd;
                    }

                    // NEW: Auto-advance to next gap when current gap is filled
                    // This creates the sequential "skip to next empty pipe" behavior
                    // ONLY applies when we have checkpoints (not a fresh system)
                    if (hasCheckpoints) {
                        int currentTarget = (int)pipe.frontPos;
                        if (pipe.unitsInjected >= currentTarget + 1) {
                            // Current gap is now filled, check for next gap
                            int nextGap = PipeSegmentTracker.findNearestUnfilledGap(pipe);
                            if (nextGap > currentTarget) {
                                // Jump to next unfilled gap
                                pipe.unitsInjected = nextGap;
                                pipe.frontPos = nextGap;
                            }
                        }
                    }

                    pipe.pumpPushingThisTick = true;
                    pipe.lastPumpPushTick = pipe.getPipeLevel().getGameTime();

                    int mbConsumed = unitsToAdd * 1000;
                    pipe.injectedMbAccum -= mbConsumed;

                    int mbRejected = incomingMb - mbConsumed;
                    if (mbRejected > 0) {
                        if (pipe.frontPos < 0f) pipe.frontPos = 0f;
                        pipe.setChanged();
                        if (!pipe.getPipeLevel().isClientSide) {
                            pipe.getPipeLevel().sendBlockUpdated(pipe.getPipePos(), pipe.getBlockState(), pipe.getBlockState(), 3);
                        }
                        return new FluidStack(stack.getFluid(), mbRejected);
                    }
                } else {
                    pipe.injectedMbAccum -= incomingMb;
                    return stack;
                }
            }

            if (pipe.frontPos < 0f) pipe.frontPos = 0f;
            pipe.setChanged();
            if (!pipe.getPipeLevel().isClientSide) {
                pipe.getPipeLevel().sendBlockUpdated(pipe.getPipePos(), pipe.getBlockState(), pipe.getBlockState(), 3);
            }

            return FluidStack.EMPTY;
        }

        return FluidStack.EMPTY;
    }

    public static int getNetworkCapacity(FluidPipeBlockEntity pipe) {
        if (pipe.getPipeLevel() == null) return 1;

        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        queue.add(pipe.getPipePos());
        visited.add(pipe.getPipePos());

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            for (Direction dir : Direction.values()) {
                var state = pipe.getPipeLevel().getBlockState(current);
                var prop = switch (dir) {
                    case NORTH -> BaseFluidPipeBlock.NORTH;
                    case SOUTH -> BaseFluidPipeBlock.SOUTH;
                    case EAST -> BaseFluidPipeBlock.EAST;
                    case WEST -> BaseFluidPipeBlock.WEST;
                    case UP -> BaseFluidPipeBlock.UP;
                    case DOWN -> BaseFluidPipeBlock.DOWN;
                };
                if (!state.hasProperty(prop) || !state.getValue(prop)) continue;

                BlockPos next = current.relative(dir);
                if (visited.contains(next)) continue;

                BlockEntity be = pipe.getPipeLevel().getBlockEntity(next);
                if (be instanceof FluidPipeBlockEntity) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        return visited.size();
    }
}