package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;

public class CombustionEngineMenu extends AbstractContainerMenu {
    public final CombustionEngineBlockEntity be;
    private final ContainerLevelAccess access;

    public CombustionEngineMenu(int id, Inventory playerInv, CombustionEngineBlockEntity be) {
        super(ModMenus.COMBUSTION_ENGINE.get(), id);
        this.be = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());

        // ---- Fluid input slot (single slot like furnace) ----
        // Players drop fuel/water buckets here, server routes to correct tank
        // Position at (46, 35) - aligned with fuel tank visual
        this.addSlot(new FluidInputSlot(0, 52, 41));

        // ---- Player inventory ----
        final int xStart = 8, yStart = 84;

        // main inv (3 rows x 9)
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                this.addSlot(new Slot(playerInv, c + r * 9 + 9, xStart + c * 18, yStart + r * 18));
            }
        }
        // hotbar
        for (int c = 0; c < 9; c++) {
            this.addSlot(new Slot(playerInv, c, xStart + c * 18, yStart + 58));
        }

        // ---- Server → client dataslots ----
        // Heat level (0.0 to 1.0, sent as int by encoding to 0-100 range)
        this.addDataSlot(new DataSlot() {
            @Override public int get() {
                return Math.round(be.getHeat() * 100);
            }
            @Override public void set(int v) {
                be.setHeatClient(v / 100.0f);
            }
        });

        // Fuel tank amount (in mB) - simple int sync like Stirling engine
        this.addDataSlot(new DataSlot() {
            @Override public int get() {
                return be.getFuelAmount();
            }
            @Override public void set(int v) {
                be.setFuelAmountClient(v);
            }
        });

        // Coolant tank amount (in mB) - simple int sync like Stirling engine
        this.addDataSlot(new DataSlot() {
            @Override public int get() {
                return be.getCoolantAmount();
            }
            @Override public void set(int v) {
                be.setCoolantAmountClient(v);
            }
        });
    }

    public CombustionEngineMenu(int id, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        this(id, playerInv, readBE(playerInv.player.level(), buf.readBlockPos()));
    }

    private static CombustionEngineBlockEntity readBE(@Nullable Level level, BlockPos pos) {
        if (level != null && level.getBlockEntity(pos) instanceof CombustionEngineBlockEntity ce) return ce;
        throw new IllegalStateException("CombustionEngine BE missing at " + pos);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, be.getBlockState().getBlock());
    }

    /** Combustion engine has no inventory slots, so shift-click does nothing. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Get fuel tank fill percentage (0.0 to 1.0) */
    public float getFuelFillPercent() {
        int capacity = be.getFuelTank().getCapacity();
        int amount = be.getFuelAmount(); // Use synced amount, not tank directly
        return capacity > 0 ? (float) amount / capacity : 0.0f;
    }

    /** Get coolant tank fill percentage (0.0 to 1.0) */
    public float getCoolantFillPercent() {
        int capacity = be.getCoolantTank().getCapacity();
        int amount = be.getCoolantAmount(); // Use synced amount, not tank directly
        return capacity > 0 ? (float) amount / capacity : 0.0f;
    }

    /** Get heat level (0.0 to 1.0) */
    public float getHeatLevel() {
        return be.getHeat();
    }

    /* --------------------- Fluid Input Slot --------------------- */

    /**
     * Virtual slot that accepts buckets for the combustion engine.
     * When a bucket is placed here, it transfers fluid to the tanks and leaves an empty bucket in the slot.
     * Player can then left-click to pick up the empty bucket.
     */
    private class FluidInputSlot extends Slot {
        public FluidInputSlot(int slotIndex, int x, int y) {
            super(new net.minecraft.world.SimpleContainer(1), slotIndex, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            // Only accept bucket items (which have fluid handlers)
            return !stack.isEmpty() && FluidUtil.getFluidHandler(stack) != null;
        }

        @Override
        public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
            // When a bucket is placed in this slot, immediately process it
            if (!newStack.isEmpty() && FluidUtil.getFluidHandler(newStack).isPresent()) {
                net.neoforged.neoforge.fluids.capability.IFluidHandler fluidHandler =
                    FluidUtil.getFluidHandler(newStack).get();

                // Simulate drain to see what we're getting
                net.neoforged.neoforge.fluids.FluidStack drained =
                    fluidHandler.drain(1000, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);

                if (!drained.isEmpty()) {
                    // Try to fill the engine's tanks
                    int filled = 0;
                    if (drained.getFluid() == com.nick.buildcraft.registry.ModFluids.FUEL.get()) {
                        filled = be.getFuelTank().fill(drained, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    } else {
                        filled = be.getCoolantTank().fill(drained, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    }

                    // If successful, drain the bucket and place empty bucket in slot
                    if (filled > 0) {
                        fluidHandler.drain(filled, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                        // Set the empty bucket in the slot - this will be picked up by broadcastChanges()
                        this.set(new ItemStack(net.minecraft.world.item.Items.BUCKET));
                        return;
                    }
                }
            }
            // If not processed, use default behavior
            super.setByPlayer(newStack, oldStack);
        }

        @Override
        public int getMaxStackSize() {
            return 1; // Buckets stack size of 1
        }

        @Override
        public boolean mayPickup(Player player) {
            return true; // Allow picking up the empty bucket
        }
    }
}
