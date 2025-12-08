package com.nick.buildcraft.content.block.fluidpipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashSet;
import java.util.LinkedList;

public class PipeWavePropagator {

    public static void propagateFluidType(FluidPipeBlockEntity pipe) {
        Level level = pipe.getLevel();
        if (level == null) return;
        if (pipe.isRoot()) return;

        FluidPipeBlockEntity root = PipeNetworkFinder.findExistingRoot(pipe);
        if (root == null) {
            if (!pipe.displayedFluid.isEmpty()) {
                pipe.displayedFluid = FluidStack.EMPTY;
                pipe.setChanged();
            }
            return;
        }

        if (pipe.unitsInjected > 0) {
            if (!FluidStack.isSameFluidSameComponents(root.displayedFluid, pipe.displayedFluid)) {
                pipe.displayedFluid = root.displayedFluid.isEmpty()
                        ? FluidStack.EMPTY
                        : root.displayedFluid.copy();

                pipe.setChanged();
                if (!level.isClientSide) {
                    level.sendBlockUpdated(pipe.getBlockPos(), pipe.getBlockState(), pipe.getBlockState(), 3);
                }
            }
        } else {
            if (!pipe.displayedFluid.isEmpty()) {
                pipe.displayedFluid = FluidStack.EMPTY;
                pipe.setChanged();
                if (!level.isClientSide) {
                    level.sendBlockUpdated(pipe.getBlockPos(), pipe.getBlockState(), pipe.getBlockState(), 3);
                }
            }
        }
    }

    public static void propagateFrontPos(FluidPipeBlockEntity pipe) {
        Level level = pipe.getLevel();
        if (level == null) return;
        if (pipe.isRoot()) return;
        if (pipe.distanceFromRoot == Integer.MAX_VALUE) return;

        if (pipe.isVirginPipe && pipe.virginTicksRemaining > 0) {
            if (pipe.unitsInjected > 0) {
                pipe.unitsInjected = 0;
                pipe.unitsConsumed = 0;
                pipe.setChanged();
            }
            return;
        }

        FluidPipeBlockEntity root = PipeNetworkFinder.findExistingRoot(pipe);
        if (root == null) return;

        boolean pumpApproves = root.unitsInjected >= pipe.distanceFromRoot;
        boolean waveApproves = root.unitsInjected >= pipe.distanceFromRoot;

        if (root.unitsInjected == 1 && pipe.distanceFromRoot > 1) {
            waveApproves = false;
        }

        long currentTick = level.getGameTime();
        long ticksSinceLastPush = currentTick - root.lastPumpPushTick;

        boolean gasPedalPressed =
                root.pumpPushingThisTick ||
                        (ticksSinceLastPush <= FluidPipeBlockEntity.GAS_PEDAL_GRACE_TICKS);

        if (pumpApproves && waveApproves && gasPedalPressed) {
            int unitsReached = root.unitsInjected;

            if (unitsReached == 1 && pipe.distanceFromRoot > 1) {
                unitsReached = 0;
            }

            if (unitsReached != pipe.unitsInjected) {
                pipe.unitsInjected = unitsReached;
                pipe.unitsConsumed = root.unitsConsumed;
                pipe.setChanged();
            }
        } else {
            if (pipe.unitsInjected > 0) {
                pipe.unitsInjected = 0;
                pipe.unitsConsumed = 0;
                pipe.setChanged();
            }
        }
    }

    public static void advanceWaveFront(FluidPipeBlockEntity pipe) {
        Level level = pipe.getLevel();
        if (level == null) return;
        if (!pipe.isRoot()) return;

        float target = pipe.unitsInjected;

        if (pipe.frontPos != target) {
            pipe.frontPos = target;
            pipe.setChanged();
        }
    }

    public static void syncConsumedUnits(FluidPipeBlockEntity pipe) {
        Level level = pipe.getLevel();
        if (level == null) return;
        if (!pipe.isRoot()) return;

        int max = 0;

        HashSet<BlockPos> visited = new HashSet<>();
        LinkedList<BlockPos> queue = new LinkedList<>();

        queue.add(pipe.getBlockPos());
        visited.add(pipe.getBlockPos());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockEntity be = level.getBlockEntity(pos);

            if (be instanceof FluidPipeBlockEntity p) {
                max = Math.max(max, p.unitsDeliveredToTank);

                for (Direction d : Direction.values()) {
                    if (!p.isConnectedInDirection(d)) continue;

                    BlockPos next = pos.relative(d);
                    if (visited.contains(next)) continue;

                    BlockEntity nbe = level.getBlockEntity(next);
                    if (nbe instanceof FluidPipeBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        if (max != pipe.unitsConsumed) {
            pipe.unitsConsumed = max;
            pipe.setChanged();
        }
    }
}
