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
                int checkpointBasedPosition = PipeWaveRecovery.scanFurthestFilledPipe(pipe);

                if (pipe.unitsInjected == 0 && pipe.unitsConsumed == 0 && checkpointBasedPosition > 0) {
                    pipe.unitsInjected = checkpointBasedPosition;
                    pipe.frontPos = checkpointBasedPosition;
                }

                int unitsToAdd = Math.min(newUnits, networkCapacity - unitsInSystem);

                if (unitsToAdd > 0) {
                    int beforeInjected = pipe.unitsInjected;
                    pipe.unitsInjected += unitsToAdd;

                    int actualAdded = pipe.unitsInjected - beforeInjected;
                    if (actualAdded != unitsToAdd) {
                        pipe.unitsInjected = beforeInjected + unitsToAdd;
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
