// src/main/java/com/nick/buildcraft/content/block/pipe/DiamondPipeMenu.java
package com.nick.buildcraft.content.block.pipe;

import com.nick.buildcraft.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.Optional;

public class DiamondPipeMenu extends AbstractContainerMenu {
    public final DiamondPipeBlockEntity be;
    private final ContainerLevelAccess access;

    // 6 rows × 9 cols = 54 filter slots
    private static final int FILTER_COLS = 9;
    private static final int FILTER_ROWS = 6;
    private static final int FILTER_SIZE = FILTER_COLS * FILTER_ROWS;

    // top-left of filter grid in your 175×225 texture
    private static final int FILTER_X = 8;
    private static final int FILTER_Y = 18;

    public DiamondPipeMenu(int id, Inventory playerInv, DiamondPipeBlockEntity be) {
        super(ModMenus.DIAMOND_PIPE.get(), id);
        this.be = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());

        // ---- FILTER GRID (ghost) ----------------------------------------------------------
        for (int r = 0; r < FILTER_ROWS; r++) {
            for (int c = 0; c < FILTER_COLS; c++) {
                int x = FILTER_X + c * 18;
                int y = FILTER_Y + r * 18;
                this.addSlot(new FilterSlot(be.getFilters(), c + r * FILTER_COLS, x, y));
            }
        }

        // ---- Player inventory (standard 27 + 9) ------------------------------------------
        final int invX = 8;
        final int invY = 140;
        // main inv
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++)
            this.addSlot(new Slot(playerInv, c + r * 9 + 9, invX + c * 18, invY + r * 18));
        // hotbar
        for (int c = 0; c < 9; c++)
            this.addSlot(new Slot(playerInv, c, invX + c * 18, invY + 58));
    }

    public DiamondPipeMenu(int id, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        this(id, playerInv, readBE(playerInv.player.level(), buf.readBlockPos()));
    }

    private static DiamondPipeBlockEntity readBE(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DiamondPipeBlockEntity be) return be;
        throw new IllegalStateException("DiamondPipe BE missing at " + pos);
    }

    @Override public boolean stillValid(Player player) {
        return stillValid(access, player, be.getBlockState().getBlock());
    }

    /** No shift-move rules for ghost grid; only player inv <-> hotbar. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack original = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        // filter slots occupy [0, FILTER_SIZE)
        if (index < FILTER_SIZE) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        original = stack.copy();

        final int INV_START = FILTER_SIZE;
        final int INV_END   = INV_START + 27;
        final int HOT_START = INV_END;
        final int HOT_END   = HOT_START + 9;

        if (index < HOT_START) {
            if (!this.moveItemStackTo(stack, HOT_START, HOT_END, false)) return ItemStack.EMPTY;
        } else {
            if (!this.moveItemStackTo(stack, INV_START, INV_END, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    // Allow drag across ghost slots
    @Override
    public boolean canDragTo(Slot slot) { return true; }

    // QoL: erase-as-you-drag with RMB when hand is empty
    @Override
    public void clicked(int slotId, int button, ClickType click, Player player) {
        // Handle QUICK_CRAFT (drag) right-click with empty cursor to rapidly clear filter slots
        if (click == ClickType.QUICK_CRAFT && this.getCarried().isEmpty()) {
            int header = getQuickcraftHeader(button); // 0=start, 1=continue, 2=end
            int type   = getQuickcraftType(button);   // 1 == right-mouse “one per slot”
            if (type == 1 && header == 1 && slotId >= 0 && slotId < FILTER_SIZE) {
                Slot s = this.slots.get(slotId);
                if (s instanceof FilterSlot fs) {
                    fs.setByPlayer(ItemStack.EMPTY);
                    fs.setChanged();
                    this.broadcastChanges();
                }
                return; // consume
            }
            // swallow other quick-craft packets with empty hand so vanilla doesn’t try anything
            if (type == 1) return;
        }

        // Otherwise use vanilla (FilterSlot overrides keep ghost behavior correct)
        super.clicked(slotId, button, click, player);
    }

    // -------------------------------------------------------------------------------------
    // Ghost-filter slot: always shows at most 1 item, never consumes or gives real items.
    // LMB/RMB with an item in hand -> set that item (count 1).  LMB/RMB with empty hand -> clear.
    // -------------------------------------------------------------------------------------
    private static final class FilterSlot extends SlotItemHandler {
        public FilterSlot(net.neoforged.neoforge.items.IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override public boolean mayPlace(ItemStack stack) { return !stack.isEmpty(); }
        @Override public boolean mayPickup(Player player) { return true; }
        @Override public int getMaxStackSize(ItemStack stack) { return 1; }
        @Override public boolean isHighlightable() { return true; }
        @Override public boolean isFake() { return true; } // render as “fake/ghost” on client

        /** Don’t consume from the carried stack; just copy a 1-count “ghost” into this slot. */
        @Override
        public ItemStack safeInsert(ItemStack carried, int increment) {
            if (carried.isEmpty()) return carried;
            super.setByPlayer(carried.copyWithCount(1));
            return carried; // unchanged carried stack
        }

        /** Clicking to take simply clears the slot; never gives a real stack to the player. */
        @Override
        public Optional<ItemStack> tryRemove(int count, int decrement, Player player) {
            if (!this.hasItem()) return Optional.empty();
            super.setByPlayer(ItemStack.EMPTY);
            return Optional.of(ItemStack.EMPTY);
        }

        @Override
        public ItemStack remove(int amount) {
            super.setByPlayer(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }

        /** Ensure any direct set only stores a single “ghost” item. */
        @Override
        public void setByPlayer(ItemStack stack) {
            super.setByPlayer(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }

        /** Don’t block drag-splitting across filter slots. */
        @Override
        public boolean allowModification(Player player) { return true; }
    }
}
