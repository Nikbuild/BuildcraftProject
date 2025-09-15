package com.nick.buildcraft.energy;

import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.energy.EnergyStorage;
import java.util.function.Consumer;

/**
 * Forge Energy storage with NBT save/load and a change-callback.
 * Compatible with NeoForge's CompoundTag (getIntOr) and EnergyStorage API.
 */
public class BCEnergyStorage extends EnergyStorage {
    private final Consumer<BCEnergyStorage> onChanged;

    public BCEnergyStorage(int capacity, int maxReceive, int maxExtract, Consumer<BCEnergyStorage> onChanged) {
        super(capacity, maxReceive, maxExtract);
        this.onChanged = onChanged == null ? s -> {} : onChanged;
    }

    public BCEnergyStorage(int capacity, int maxTransfer, Consumer<BCEnergyStorage> onChanged) {
        this(capacity, maxTransfer, maxTransfer, onChanged);
    }

    /* ---------- NBT ---------- */

    public CompoundTag saveNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Energy", this.energy);
        return tag;
    }

    public void loadNBT(CompoundTag tag) {
        this.energy = Math.min(tag.getIntOr("Energy", 0), getMaxEnergyStored());
        // no callback here; typically called during load
    }

    /* ---------- Change notifications ---------- */

    private void changedIf(boolean condition) {
        if (condition) onChanged.accept(this);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        int received = super.receiveEnergy(toReceive, simulate);
        changedIf(!simulate && received > 0);
        return received;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        int extracted = super.extractEnergy(toExtract, simulate);
        changedIf(!simulate && extracted > 0);
        return extracted;
    }

    /** Force set energy value. Clamped between 0 and capacity. */
    public int setEnergy(int value) {
        int clamped = Math.min(Math.max(0, value), getMaxEnergyStored());
        boolean changed = (this.energy != clamped);
        this.energy = clamped;
        changedIf(changed);
        return this.energy;
    }
}
