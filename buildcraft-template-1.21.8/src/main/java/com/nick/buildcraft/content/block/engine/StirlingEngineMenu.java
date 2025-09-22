// src/main/java/com/nick/buildcraft/content/block/engine/StirlingEngineMenu.java
package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

public class StirlingEngineMenu extends AbstractContainerMenu {
    public final StirlingEngineBlockEntity be;
    private final ContainerLevelAccess access;

    public StirlingEngineMenu(int id, Inventory playerInv, StirlingEngineBlockEntity be) {
        super(ModMenus.STIRLING_ENGINE.get(), id);
        this.be = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());

        // ---- BE slots ----
        // Single fuel slot (under the smoke glyph)
        this.addSlot(new FuelOnlySlot(be, 0, 80, 41));

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
        // burn remaining (for flame height)
        this.addDataSlot(new DataSlot() {
            @Override public int get() { return be.getBurnTime(); }
            @Override public void set(int v) { be.setBurnTimeClient(v); }
        });

        // burn total (for flame height)
        this.addDataSlot(new DataSlot() {
            @Override public int get() { return be.getBurnTimeTotal(); }
            @Override public void set(int v) { be.setBurnTimeTotalClient(v); }
        });

        // lit flag => 1 while actually burning (mirrors furnace)
        this.addDataSlot(new DataSlot() {
            @Override public int get() { return be.getBurnTime() > 0 ? 1 : 0; }
            @Override public void set(int v) { be.setClientLit(v != 0); }
        });
    }

    public StirlingEngineMenu(int id, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        this(id, playerInv, readBE(playerInv.player.level(), buf.readBlockPos()));
    }

    private static StirlingEngineBlockEntity readBE(@Nullable Level level, BlockPos pos) {
        if (level != null && level.getBlockEntity(pos) instanceof StirlingEngineBlockEntity se) return se;
        throw new IllegalStateException("StirlingEngine BE missing at " + pos);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, be.getBlockState().getBlock());
    }

    /** Shift-click rules: fuel <-> player inventory only. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack original = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            original = stack.copy();

            final int BE_START = 0, BE_END = 1;      // [0,1)
            final int INV_START = 1, INV_END = 28;   // [1,28) — 27 slots
            final int HOT_START = 28, HOT_END = 37;  // [28,37) — 9 slots

            if (index == 0) {
                // from BE fuel slot -> player inv
                if (!this.moveItemStackTo(stack, INV_START, HOT_END, true)) return ItemStack.EMPTY;
            } else {
                // from player inv -> BE fuel slot (if valid)
                if (FuelOnlySlot.isFuel(stack, player.level())) {
                    if (!this.moveItemStackTo(stack, BE_START, BE_END, false)) return ItemStack.EMPTY;
                } else if (index < INV_END) {
                    if (!this.moveItemStackTo(stack, HOT_START, HOT_END, false)) return ItemStack.EMPTY;
                } else if (!this.moveItemStackTo(stack, INV_START, INV_END, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();

            if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
        }
        return original;
    }

    /** 0..px vertical flame: standard furnace scaling. */
    public int flamePixels(int px) {
        int total = be.getBurnTimeTotal();
        if (be.getBurnTime() > 0 && total > 0) {
            return (int)Math.round((be.getBurnTime() / (double)total) * px);
        }
        return be.isClientLit() ? px : 0;
        // (when not burning but lit==true we draw a full-height idle flame, same as vanilla UX cue)
    }

    /** For GUI indicator (optional). */
    public boolean isLitForUi() { return be.isClientLit(); }

    /* --------------------- slots --------------------- */
    /** Slot that accepts only furnace fuel. */
    private static class FuelOnlySlot extends SlotItemHandler {
        private final Level level;
        public FuelOnlySlot(StirlingEngineBlockEntity be, int index, int x, int y) {
            super(be.getInventory(), index, x, y);
            this.level = be.getLevel();
        }
        @Override public boolean mayPlace(ItemStack stack) { return isFuel(stack, level); }

        static boolean isFuel(ItemStack stack, Level level) {
            if (!(level instanceof ServerLevel sl)) return !stack.isEmpty(); // client: allow; server validates on tick
            return !stack.isEmpty() && stack.getBurnTime(RecipeType.SMELTING, sl.fuelValues()) > 0;
        }
    }
}
