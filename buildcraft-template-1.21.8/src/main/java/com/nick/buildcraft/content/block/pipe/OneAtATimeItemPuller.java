package com.nick.buildcraft.content.block.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Extract EXACTLY ONE item on an engine pulse.
 * Priority: opposite side first, then the remaining 4 sides (skipping the engine side).
 */
public final class OneAtATimeItemPuller {
    private OneAtATimeItemPuller() {}

    /**
     * Try to extract exactly ONE item from an adjacent inventory on the given pipe,
     * skipping the side 'engineDir' (where the engine sits). Returns true if something moved.
     */
    public static boolean pulseExtractOne(Level level, BlockPos pipePos, Direction engineDir) {
        if (level == null || level.isClientSide) return false;

        BlockEntity be = level.getBlockEntity(pipePos);
        if (!(be instanceof StonePipeBlockEntity pipe)) return false;

        // Build probe order: 1) opposite of engine, 2) four remaining sides (skipping engine side)
        Direction[] order = new Direction[5];
        int idx = 0;
        Direction primary = engineDir.getOpposite();
        order[idx++] = primary;
        for (Direction d : Direction.values()) {
            if (d == engineDir || d == primary) continue;
            order[idx++] = d;
        }

        for (int i = 0; i < idx; i++) {
            Direction d = order[i];
            BlockPos invPos = pipePos.relative(d);

            IItemHandler handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK,
                    invPos,
                    d.getOpposite()
            );
            if (handler == null) continue;

            // Scan slots and extract exactly ONE
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack sim = handler.extractItem(slot, 1, true);
                if (sim.isEmpty()) continue;

                ItemStack pulled = handler.extractItem(slot, 1, false);
                if (pulled.isEmpty()) continue; // race

                ItemStack leftover = pipe.offer(pulled, d);
                if (leftover.isEmpty()) {
                    // Success: moved one item, stop immediately
                    return true;
                } else {
                    // Pipe refused (extremely rare with ghosting) – put back safely
                    ItemStack notReinserted = ItemHandlerHelper.insertItem(handler, leftover, false);
                    if (!notReinserted.isEmpty()) {
                        net.minecraft.world.level.block.Block.popResource(level, pipePos, notReinserted);
                    }
                }
            }
        }
        return false;
    }
}
