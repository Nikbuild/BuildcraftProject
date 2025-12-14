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
import org.jetbrains.annotations.Nullable;

public class CombustionEngineMenu extends AbstractContainerMenu {
    public final CombustionEngineBlockEntity be;
    private final ContainerLevelAccess access;

    public CombustionEngineMenu(int id, Inventory playerInv, CombustionEngineBlockEntity be) {
        super(ModMenus.COMBUSTION_ENGINE.get(), id);
        this.be = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());

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
        // Placeholder: will be updated once you add fluid tanks to CombustionEngineBlockEntity
        return 0.0f;
    }

    /** Get coolant tank fill percentage (0.0 to 1.0) */
    public float getCoolantFillPercent() {
        // Placeholder: will be updated once you add fluid tanks to CombustionEngineBlockEntity
        return 0.0f;
    }

    /** Get heat level (0.0 to 1.0) */
    public float getHeatLevel() {
        return be.getHeat();
    }
}
