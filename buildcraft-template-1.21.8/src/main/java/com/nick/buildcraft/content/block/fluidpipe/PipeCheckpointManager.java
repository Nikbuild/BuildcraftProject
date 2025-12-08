package com.nick.buildcraft.content.block.fluidpipe;

import com.nick.buildcraft.content.block.tank.TankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class PipeCheckpointManager {

    public static void updateCheckpoint(FluidPipeBlockEntity pipe) {
        if (pipe.getPipeLevel() == null) return;
        if (pipe.isOrphaned) return;
        if (pipe.displayedFluid.isEmpty()) return;

        float head = pipe.getFrontPos();
        if (head <= 0f) return;

        float segmentStart = pipe.distanceFromRoot - 1;
        float passed = head - segmentStart;

        if (passed > 0f) {
            if (!pipe.hasCheckpoint) {
                pipe.hasCheckpoint = true;
                pipe.checkpointFluid = pipe.displayedFluid.copy();
                pipe.checkpointWasDeliveredByWave = false;
            }

            float fillFraction = Math.min(1f, passed);
            int targetAmount = (int)(fillFraction * 1000);

            if (targetAmount > pipe.checkpointAmount) {
                int toAdd = targetAmount - pipe.checkpointAmount;
                pipe.checkpointAmount += toAdd;
                pipe.checkpointFluid.setAmount(pipe.checkpointAmount);
                pipe.setChanged();
            }
        }
    }

    public static void deliverToTanks(FluidPipeBlockEntity pipe) {
        if (pipe.getPipeLevel() == null) return;

        TankBlockEntity tankBe = null;
        for (Direction dir : Direction.values()) {
            BlockPos np = pipe.getPipePos().relative(dir);
            BlockEntity be = pipe.getPipeLevel().getBlockEntity(np);
            if (be instanceof TankBlockEntity t) {
                tankBe = t;
                break;
            }
        }
        if (tankBe == null) return;

        if (pipe.distanceFromRoot != Integer.MAX_VALUE &&
                !pipe.displayedFluid.isEmpty() &&
                pipe.isOnActiveFlowPath()) {

            deliverWaveToTank(pipe, tankBe);

        } else if (pipe.hasCheckpoint && pipe.checkpointAmount > 0) {

            deliverCheckpointToTank(pipe, tankBe);
        }
    }

    private static void deliverWaveToTank(FluidPipeBlockEntity pipe, TankBlockEntity tankBe) {
        float head = pipe.getFrontPos();
        if (head <= 0f) return;

        float segmentStart = pipe.distanceFromRoot - 1;
        float passed = head - segmentStart;

        int unitsPassedHere = (int)Math.floor(passed);
        if (unitsPassedHere <= pipe.unitsDeliveredToTank) return;

        int newUnits = unitsPassedHere - pipe.unitsDeliveredToTank;
        int amountMb = newUnits * 1000;

        IFluidHandler tankHandler = tankBe.getColumnHandler();
        FluidStack toInsert = new FluidStack(pipe.displayedFluid.getFluid(), amountMb);

        int filled = tankHandler.fill(toInsert, IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0) return;

        int actualUnits = filled / 1000;
        if (actualUnits <= 0) return;

        pipe.unitsDeliveredToTank += actualUnits;

        if (pipe.hasCheckpoint) {
            pipe.checkpointWasDeliveredByWave = true;
        }

        tankBe.setChanged();
        pipe.getPipeLevel().sendBlockUpdated(tankBe.getBlockPos(), tankBe.getBlockState(), tankBe.getBlockState(), 3);
    }

    private static void deliverCheckpointToTank(FluidPipeBlockEntity pipe, TankBlockEntity tankBe) {
        IFluidHandler tankHandler = tankBe.getColumnHandler();
        if (tankHandler == null) return;

        if (pipe.checkpointWasDeliveredByWave) {
            pipe.checkpointFluid = FluidStack.EMPTY;
            pipe.checkpointAmount = 0;
            pipe.hasCheckpoint = false;
            pipe.checkpointWasDeliveredByWave = false;
            pipe.setChanged();
            return;
        }

        int toTransfer = Math.min(100, pipe.checkpointAmount);
        FluidStack toInsert = pipe.checkpointFluid.copyWithAmount(toTransfer);

        int filled = tankHandler.fill(toInsert, IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            pipe.checkpointAmount -= filled;

            if (pipe.checkpointAmount <= 0) {
                pipe.checkpointFluid = FluidStack.EMPTY;
                pipe.checkpointAmount = 0;
                pipe.hasCheckpoint = false;
            } else {
                pipe.checkpointFluid.setAmount(pipe.checkpointAmount);
            }

            tankBe.setChanged();
            pipe.getPipeLevel().sendBlockUpdated(tankBe.getBlockPos(), tankBe.getBlockState(), tankBe.getBlockState(), 3);
            pipe.setChanged();
        }
    }
}
