package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.energy.BCEnergyStorage;
import com.nick.buildcraft.energy.Energy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Shared logic for all engine block entities.
 * - Server-side warmup & generation
 * - Small internal FE buffer
 * - Pushes FE to all neighbors each tick (up to ENGINE_MAX_IO total)
 * Uses ValueInput/ValueOutput for IO (NeoForge 1.21.x).
 */
public abstract class BaseEngineBlockEntity extends BlockEntity {

    /** Internal energy buffer for this engine. */
    protected final BCEnergyStorage buffer =
            new BCEnergyStorage(Energy.ENGINE_BUFFER, Energy.ENGINE_MAX_IO, s -> setChanged());

    /** Warm-up counter (ticks). */
    private int warmupTicks;

    protected BaseEngineBlockEntity(
            net.minecraft.world.level.block.entity.BlockEntityType<?> type,
            BlockPos pos,
            BlockState state
    ) {
        super(type, pos, state);
    }

    /* ---------------- Abstract hooks ---------------- */

    /** Whether the engine is actively generating power (e.g., powered by redstone). */
    protected abstract boolean isActive(BlockState state);

    /** How much FE this engine generates per tick (may depend on warmup). */
    protected abstract int getGenerationPerTick();

    /* ---------------- Server tick ---------------- */

    /** Call from your block ticker. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, BaseEngineBlockEntity be) {
        if (level.isClientSide) return;

        // Warm-up & generation
        if (be.isActive(state)) {
            be.warmupTicks = Math.min(be.warmupTicks + 1, Energy.REDSTONE_ENGINE_WARMUP_TICKS);
            int gen = be.getGenerationPerTick();
            if (gen > 0) be.buffer.receiveEnergy(gen, false);
        } else {
            be.warmupTicks = Math.max(0, be.warmupTicks - 2); // cool down when inactive
        }

        // Push FE to adjacent receivers (shared budget across all sides)
        int remaining = Math.min(be.buffer.getEnergyStored(), Energy.ENGINE_MAX_IO);
        if (remaining > 0) {
            for (Direction dir : Direction.values()) {
                IEnergyStorage neighbor = level.getCapability(
                        Capabilities.EnergyStorage.BLOCK,
                        pos.relative(dir),
                        dir.getOpposite()
                );
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

    /* ---------------- Warmup accessor ---------------- */

    protected int warmupTicks() {
        return warmupTicks;
    }

    /* ---------------- Serialization ---------------- */

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("Energy").ifPresent(child -> this.buffer.deserialize(child));
        this.warmupTicks = input.getIntOr("Warmup", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.buffer.serialize(output.child("Energy"));
        output.putInt("Warmup", this.warmupTicks);
    }
}
