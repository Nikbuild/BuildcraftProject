package com.nick.buildcraft.content.block.quarry;

import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import com.nick.buildcraft.energy.Energy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Item queue management and drop-safe output delivery.
 * Prevents duplication and "pop-outs" during power cycles.
 */
public class QuarryOutputManager {

    private static final boolean DROP_WHEN_DISCONNECTED = true;
    private static final int FLUSH_TRIES_PER_TICK = 4;

    /**
     * Mine a block and queue its drops for output.
     * Sets the power-on flush gate once a block is successfully mined.
     */
    public static void mineOneBlockToQueue(QuarryBlockEntity qbe, ServerLevel level, BlockPos quarryPos, BlockPos target) {
        BlockState bs = level.getBlockState(target);
        if (bs.isAir()) return;

        // Collect drops first, then break without vanilla drop
        BlockEntity beAtTarget = level.getBlockEntity(target);
        List<ItemStack> drops = Block.getDrops(bs, level, target, beAtTarget);
        level.destroyBlock(target, false);

        // Queue the drops
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) continue;
            qbe.outputQueue.addLast(stack.copy());
        }

        // We mined something after (re)powering on → allow flushing again
        qbe.allowFlushAfterPowerOn = true;

        qbe.setChanged();
    }

    /**
     * Try to push queued stacks into the block above.
     * Gated to avoid phantom motion on power-on.
     */
    public static void flushOutput(QuarryBlockEntity qbe, ServerLevel level, BlockPos quarryPos) {
        if (!qbe.allowFlushAfterPowerOn) return;     // Gate until we mine again
        if (qbe.outputQueue.isEmpty()) return;

        // If there's nowhere to send items and the toggle is on, spill everything upward
        if (DROP_WHEN_DISCONNECTED && !hasOutputTarget(level, quarryPos)) {
            BlockPos eject = quarryPos.above();
            while (!qbe.outputQueue.isEmpty()) {
                ItemStack s = qbe.outputQueue.pollFirst();
                if (s != null && !s.isEmpty()) {
                    Block.popResource(level, eject, s);
                }
            }
            return;
        }

        // Otherwise, try to insert a few per tick; requeue leftovers and stop early on backpressure
        int tries = Math.min(FLUSH_TRIES_PER_TICK, qbe.outputQueue.size());
        for (int i = 0; i < tries; i++) {
            ItemStack s = qbe.outputQueue.pollFirst();
            if (s == null || s.isEmpty()) continue;

            ItemStack leftover = tryOutputUp(level, quarryPos, s);
            if (!leftover.isEmpty()) {
                // Still blocked: put it back and stop early this tick
                qbe.outputQueue.addFirst(leftover);
                break;
            }
        }
    }

    /**
     * Try to output an item stack into the block above.
     * Returns the leftover amount that couldn't be inserted.
     */
    private static ItemStack tryOutputUp(Level level, BlockPos quarryPos, ItemStack stackIn) {
        if (!(level instanceof ServerLevel sl) || stackIn.isEmpty()) return stackIn;

        BlockPos up = quarryPos.above();

        // 1) Prefer a normal inventory/pipe that exposes IItemHandler
        IItemHandler handler = sl.getCapability(Capabilities.ItemHandler.BLOCK, up, Direction.DOWN);
        if (handler != null) {
            ItemStack remaining = stackIn;
            for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                remaining = handler.insertItem(i, remaining, false);
            }
            if (!remaining.isEmpty()) {
                // 2) If that handler didn't take it, fall back to pipe contract
                BlockEntity be = sl.getBlockEntity(up);
                if (be instanceof StonePipeBlockEntity pipe) {
                    remaining = pipe.offer(remaining, Direction.DOWN);
                }
            }
            return remaining;
        }

        // 3) No handler? If it's our pipe, use its lightweight offer API
        BlockEntity be = sl.getBlockEntity(up);
        if (be instanceof StonePipeBlockEntity pipe) {
            return pipe.offer(stackIn, Direction.DOWN);
        }

        // 4) Nothing to accept it
        return stackIn;
    }

    /**
     * Check if the block above has an inventory or pipe to accept output.
     */
    private static boolean hasOutputTarget(ServerLevel level, BlockPos quarryPos) {
        BlockPos up = quarryPos.above();

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, up, Direction.DOWN);
        if (handler != null) return true;

        BlockEntity be = level.getBlockEntity(up);
        return (be instanceof StonePipeBlockEntity);
    }

    /**
     * Reset the power-on flush gate when power is lost.
     */
    public static void resetFlushGate(QuarryBlockEntity qbe) {
        qbe.allowFlushAfterPowerOn = false;
    }
}
