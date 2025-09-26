// src/main/java/com/nick/buildcraft/content/block/pipe/DiamondPipeBlockEntity.java
package com.nick.buildcraft.content.block.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.EnumSet;
import java.util.Set;

/**
 * Diamond pipe with per-direction item filters.
 *
 * GUI grid is 6 rows × 9 cols. Rows map (top->bottom) to:
 *   0: BLACK  -> DOWN
 *   1: GREY   -> UP
 *   2: RED    -> NORTH
 *   3: BLUE   -> SOUTH
 *   4: GREEN  -> WEST
 *   5: YELLOW -> EAST
 *
 * Semantics used by transport:
 *  - The filter yields a set of "allowed output directions" for a given stack.
 *  - At a border, if the entering face is itself in that set, the diamond acts
 *    like a SOLID WALL for that stack (we reject entry and the sender bounces).
 */
public class DiamondPipeBlockEntity extends StonePipeBlockEntity implements net.minecraft.world.MenuProvider {

    // ---- Filter storage (ghost items) ----------------------------------------------------
    // 6 rows × 9 cols = 54 slots; persisted like a normal handler.
    private final ItemStackHandler filters = new ItemStackHandler(6 * 9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            notifyNeighborsRoutesChanged();
        }
        @Override
        protected void onLoad() {
            notifyNeighborsRoutesChanged();
        }
    };

    public DiamondPipeBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    // Expose to menu
    public ItemStackHandler getFilters() { return filters; }

    // ---- MenuProvider -------------------------------------------------------------------
    @Override
    public Component getDisplayName() { return Component.translatable("screen.buildcraft.diamond_pipe"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new DiamondPipeMenu(id, inv, this);
    }

    // ---- Save / Load --------------------------------------------------------------------
    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
        super.saveAdditional(out);
        net.minecraft.world.ContainerHelper.saveAllItems(out, toNonNullList(filters));
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
        super.loadAdditional(in);
        NonNullList<ItemStack> list = NonNullList.withSize(filters.getSlots(), ItemStack.EMPTY);
        net.minecraft.world.ContainerHelper.loadAllItems(in, list);
        fromNonNullList(filters, list);
        notifyNeighborsRoutesChanged();
    }

    private static NonNullList<ItemStack> toNonNullList(ItemStackHandler h) {
        NonNullList<ItemStack> nl = NonNullList.withSize(h.getSlots(), ItemStack.EMPTY);
        for (int i = 0; i < h.getSlots(); i++) nl.set(i, h.getStackInSlot(i));
        return nl;
    }
    private static void fromNonNullList(ItemStackHandler h, NonNullList<ItemStack> nl) {
        for (int i = 0; i < Math.min(h.getSlots(), nl.size()); i++) h.setStackInSlot(i, nl.get(i));
    }

    // =====================================================================================
    //                               FILTER  LOGIC
    // =====================================================================================

    // rows (top -> bottom) → world direction
    private static final Direction[] ROW_TO_DIR = new Direction[] {
            Direction.DOWN,   // row 0 (black)
            Direction.UP,     // row 1 (grey/white)
            Direction.NORTH,  // row 2 (red)
            Direction.SOUTH,  // row 3 (blue)
            Direction.WEST,   // row 4 (green)
            Direction.EAST    // row 5 (yellow)
    };

    private static final int COLS = 9;
    private static final int ROWS = 6;

    /** True if any slot in the given GUI row matches this stack. */
    public boolean matchesRow(int row, ItemStack stack) {
        int start = row * COLS;
        for (int i = 0; i < COLS; i++) {
            ItemStack f = filters.getStackInSlot(start + i);
            if (!f.isEmpty() && ItemStack.isSameItemSameComponents(f, stack)) return true;
        }
        return false;
    }

    /**
     * Returns all directions explicitly selected for this stack.
     * If none match, the set is empty (meaning "no restriction").
     */
    public EnumSet<Direction> getAllowedDirections(ItemStack stack) {
        EnumSet<Direction> allowed = EnumSet.noneOf(Direction.class);
        if (stack.isEmpty()) return allowed;
        for (int row = 0; row < ROWS; row++) if (matchesRow(row, stack)) allowed.add(ROW_TO_DIR[row]);
        return allowed;
    }

    /**
     * Reduce a candidate set of output faces by the diamond filter.
     * If the filter yields at least one face, only those remain.
     * If the filter yields none, candidates are left unchanged.
     */
    public EnumSet<Direction> filterCandidates(ItemStack stack, Set<Direction> candidates) {
        EnumSet<Direction> filtered = getAllowedDirections(stack);
        if (filtered.isEmpty()) return EnumSet.copyOf(candidates);
        filtered.retainAll(candidates);
        return filtered;
    }

    /**
     * HARD WALL API used by transport:
     * Return true if this diamond would reject an item entering FROM {@code enteringFace}.
     * When true, the sender must NOT transfer ownership; it should bounce locally.
     */
    public boolean rejectsFrom(Direction enteringFace, ItemStack stack) {
        if (enteringFace == null || stack == null || stack.isEmpty()) return false;
        // "Allowed faces" are outputs; entering from one of those faces is disallowed at the border.
        return getAllowedDirections(stack).contains(enteringFace);
    }

    // =====================================================================================
    //                      NEIGHBOR ROUTE INVALIDATION HOOK
    // =====================================================================================

    /** Tell adjacent pipes to drop cached routes immediately. */
    void notifyNeighborsRoutesChanged() {
        if (level == null || level.isClientSide) return;
        for (Direction d : Direction.values()) {
            BlockPos np = worldPosition.relative(d);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof StonePipeBlockEntity sp) sp.onNeighborGraphChanged(worldPosition);
        }
    }
}
