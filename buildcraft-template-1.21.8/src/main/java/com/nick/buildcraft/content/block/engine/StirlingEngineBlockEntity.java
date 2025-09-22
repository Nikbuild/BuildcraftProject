// src/main/java/com/nick/buildcraft/content/block/engine/StirlingEngineBlockEntity.java
package com.nick.buildcraft.content.block.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.items.ItemStackHandler;

public class StirlingEngineBlockEntity extends EngineBlockEntity implements MenuProvider {
    private final ItemStackHandler inv = new ItemStackHandler(1) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };

    // furnace-style timers
    private int burnTime;       // remaining
    private int burnTimeTotal;  // total of current item

    // client mirror for UI only (set via DataSlot)
    private boolean clientLit;

    public StirlingEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EngineType.STIRLING, pos, state);
    }

    @Override
    protected boolean isActive(BlockState state) {
        // Engine power output logic; keep your redstone requirement if you want
        return burnTime > 0 && isBlockPowered(state);
    }

    @Override public Component getDisplayName() {
        return Component.translatable("screen.buildcraft.stirling_engine");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory invPlayer, Player player) {
        return new StirlingEngineMenu(id, invPlayer, this);
    }

    /* ================= Ticking ================= */

    public static void serverTick(Level level, BlockPos pos, BlockState state, StirlingEngineBlockEntity be) {
        boolean wasLit = be.burnTime > 0;

        // decrement like a furnace (do NOT gate on redstone)
        if (be.burnTime > 0) {
            be.burnTime--;
            be.setChanged();
        }

        // refuel when empty if we have valid fuel
        if (be.burnTime == 0) {
            ItemStack fuel = be.inv.getStackInSlot(0);
            if (!fuel.isEmpty() && level instanceof ServerLevel sl) {
                int burn = fuel.getBurnTime(RecipeType.SMELTING, sl.fuelValues());
                if (burn > 0) {
                    be.burnTime = be.burnTimeTotal = burn;

                    // 1.21: Use Remainder (e.g., lava bucket -> empty bucket)
                    ItemStack remainder = ItemStack.EMPTY;
                    var useRem = fuel.get(DataComponents.USE_REMAINDER);
                    if (useRem != null) {
                        remainder = useRem.convertIntoRemainder(fuel.copyWithCount(1), 1, false, extra -> {});
                    }

                    fuel.shrink(1);
                    be.inv.setStackInSlot(0, fuel.isEmpty() && !remainder.isEmpty() ? remainder : fuel);
                    be.setChanged();
                }
            }
        }

        // keep BlockState LIT exactly in sync with burning (furnace behavior)
        boolean isLit = be.burnTime > 0;
        if (wasLit != isLit && state.hasProperty(BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, isLit), 3);
        }

        // shared warmup/animation/energy push
        EngineBlockEntity.serverTick(level, pos, state, be);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, StirlingEngineBlockEntity be) {
        be.clientLit = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
        EngineBlockEntity.clientTick(level, pos, state, be);
    }

    /* =============== Save/Load =============== */

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.child("Inv").ifPresent(inv::deserialize);
        burnTime      = in.getIntOr("Burn", 0);
        burnTimeTotal = in.getIntOr("BurnTotal", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        inv.serialize(out.child("Inv"));
        out.putInt("Burn", burnTime);
        out.putInt("BurnTotal", burnTimeTotal);
    }

    /* =============== Accessors for menu/screen =============== */

    public ItemStackHandler getInventory() { return inv; }
    public int  getBurnTime()              { return burnTime; }
    public int  getBurnTimeTotal()         { return burnTimeTotal; }

    // DataSlot setters (client)
    public void setBurnTimeClient(int v)      { this.burnTime = v; }
    public void setBurnTimeTotalClient(int v) { this.burnTimeTotal = v; }

    public boolean isClientLit() { return clientLit; }
    public void setClientLit(boolean lit) { this.clientLit = lit; }

    /* helper */
    private boolean isBlockPowered(BlockState state) {
        if (state.hasProperty(BlockStateProperties.POWERED)) return state.getValue(BlockStateProperties.POWERED);
        Level lvl = getLevel();
        return lvl != null && lvl.hasNeighborSignal(getBlockPos());
    }
}
