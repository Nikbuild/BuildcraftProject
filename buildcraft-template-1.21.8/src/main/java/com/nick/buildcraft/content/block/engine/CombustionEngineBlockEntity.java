package com.nick.buildcraft.content.block.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class CombustionEngineBlockEntity extends EngineBlockEntity implements MenuProvider {

    public CombustionEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EngineType.COMBUSTION, pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.buildcraft.combustion_engine");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory invPlayer, Player player) {
        return new CombustionEngineMenu(id, invPlayer, this);
    }

    /* =============== Heat accessors (inherited, but included here for clarity) =============== */
}
