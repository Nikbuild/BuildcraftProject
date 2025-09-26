// src/main/java/com/nick/buildcraft/content/block/engine/BaseEngineBlockEntity.java
package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.energy.BCEnergyStorage;
import com.nick.buildcraft.energy.Energy;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for all engine BEs:
 * - warmup/generation
 * - small internal FE buffer
 * - pushes FE to neighbors (prioritizing the block's FACING)
 *
 // NOTE: Pulse delivery to neighbors now happens in EngineBlockEntity (once per pump), not here per tick.
 * (once per pump), not here per tick.
 */
public abstract class BaseEngineBlockEntity extends BlockEntity {

    protected final BCEnergyStorage buffer =
            new BCEnergyStorage(Energy.ENGINE_BUFFER, Energy.ENGINE_MAX_IO, s -> setChanged());

    private int warmupTicks;

    protected BaseEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected abstract boolean isActive(BlockState state);
    protected abstract int getGenerationPerTick();

    public static void serverTick(Level level, BlockPos pos, BlockState state, BaseEngineBlockEntity be) {
        if (level.isClientSide) return;

        // warmup & generate
        if (be.isActive(state)) {
            be.warmupTicks = Math.min(be.warmupTicks + 1, Energy.REDSTONE_ENGINE_WARMUP_TICKS);
            int gen = be.getGenerationPerTick();
            if (gen > 0) be.buffer.receiveEnergy(gen, false);
        } else {
            be.warmupTicks = Math.max(0, be.warmupTicks - 2);
        }

        // push FE to neighbors (shared budget); face first
        List<Direction> order = new ArrayList<>(6);
        Direction facing = state.hasProperty(EngineBlock.FACING) ? state.getValue(EngineBlock.FACING) : null;
        if (facing != null) order.add(facing);
        for (Direction d : Direction.values()) if (d != facing) order.add(d);

        int remaining = Math.min(be.buffer.getEnergyStored(), Energy.ENGINE_MAX_IO);
        if (remaining > 0) {
            for (Direction dir : order) {
                IEnergyStorage neighbor = level.getCapability(
                        Capabilities.EnergyStorage.BLOCK, pos.relative(dir), dir.getOpposite());
                if (neighbor == null || !neighbor.canReceive()) continue;

                int sent = neighbor.receiveEnergy(remaining, false);
                if (sent > 0) {
                    be.buffer.extractEnergy(sent, false);
                    remaining -= sent;
                    if (remaining <= 0) break;
                }
            }
        }

        be.setChanged();
    }

    protected int warmupTicks() { return warmupTicks; }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.child("Energy").ifPresent(buffer::deserialize);
        this.warmupTicks = in.getIntOr("Warmup", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        buffer.serialize(out.child("Energy"));
        out.putInt("Warmup", warmupTicks);
    }
}
