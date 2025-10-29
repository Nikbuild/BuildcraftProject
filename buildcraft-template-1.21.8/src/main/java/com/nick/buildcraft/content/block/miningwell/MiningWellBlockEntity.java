package com.nick.buildcraft.content.block.miningwell;

import com.nick.buildcraft.api.engine.EnginePowerAcceptorApi;
import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.energy.BCEnergyStorage;
import com.nick.buildcraft.energy.Energy;
import com.nick.buildcraft.registry.ModBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.Objects;

/**
 * Mining Well:
 * - Engines pulse it (speed) AND push FE into an internal buffer (via capability registration).
 * - Consumes FE per block based on hardness.
 * - Shows vanilla break overlay while working.
 * - Places/maintains a vertical column of ModBlocks.MINING_PIPE.
 *
 * New behavior:
 * - If any pipe segment above the drill head is missing or replaced, the well will
 *   repair that segment first (place a pipe, or mine the foreign block then place a pipe)
 *   before continuing to dig deeper.
 */
public class MiningWellBlockEntity extends BlockEntity
        implements EnginePulseAcceptorApi, EnginePowerAcceptorApi {

    private static final int MAX_DEPTH = 256;
    private static final int BASE_FE_PER_BLOCK = Energy.QUARRY_ENERGY_PER_OPERATION; // 80 FE
    private static final int FE_PER_PULSE_STEP = 80;                                  // matches engine pulse

    private final BCEnergyStorage energy = new BCEnergyStorage(200_000, 200_000, s -> setChanged());

    /** pulses queued by engines (RPM -> pulses) */
    private int queuedPulses = 0;

    /** the current world position being acted upon (gap repair or drill head) */
    private BlockPos curTarget = null;
    private int feNeeded = 0;
    private int feProgress = 0;

    /** stable id for vanilla "crack" overlay */
    private final int breakerId;

    public MiningWellBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.MINING_WELL.get(), pos, state);
        this.breakerId = (int)(Objects.hash(pos.getX(), pos.getY(), pos.getZ()) & 0x7fffffff);
    }

    /* ---------------- Engine integration ---------------- */

    @Override
    public boolean acceptEnginePulse(Direction from) {
        if (this.level != null && !this.level.isClientSide) {
            queuedPulses = Mth.clamp(queuedPulses + 1, 0, 64);
            setChanged();
        }
        return true;
    }

    @Override
    public void acceptEnginePower(Direction from, int power) {
        energy.receiveEnergy(Math.max(0, power), false);
        setChanged();
    }

    /* ---------------- Server tick ---------------- */

    public static void serverTick(Level level, BlockPos pos, BlockState state, MiningWellBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        // Re-evaluate the *first non-pipe* position from the top every tick.
        // This lets us detect and repair any mid-column damage before digging deeper.
        BlockPos expected = be.findNextTarget(server, pos);

        if (be.curTarget == null || !be.curTarget.equals(expected)) {
            be.curTarget = expected;
            be.feProgress = 0;
            be.feNeeded = be.energyCostFor(server, be.curTarget);
            be.updateCrackStage(server);
        }

        if (be.queuedPulses <= 0) return;

        be.queuedPulses--;
        int step = Math.min(FE_PER_PULSE_STEP, be.energy.extractEnergy(FE_PER_PULSE_STEP, false));
        if (step <= 0) return;

        BlockState targetState = server.getBlockState(be.curTarget);

        // If the target spot is air, it's a missing pipe: replace immediately, then re-evaluate.
        if (targetState.isAir()) {
            be.placePipe(server, be.curTarget);
            be.curTarget = be.findNextTarget(server, pos);
            be.feProgress = 0;
            be.feNeeded = be.energyCostFor(server, be.curTarget);
            be.updateCrackStage(server);
            return;
        }

        // If someone put an unbreakable block here, give up gracefully.
        if (be.isUnbreakable(server, be.curTarget)) {
            be.clearCrackStage(server);
            return;
        }

        // Apply progress toward breaking the target (ore at head OR foreign block replacing a pipe)
        be.feProgress += step;
        be.updateCrackStage(server);

        if (be.feProgress >= be.feNeeded) {
            // Mine the target and output drops
            List<ItemStack> drops = Block.getDrops(targetState, server, be.curTarget, server.getBlockEntity(be.curTarget));
            server.destroyBlock(be.curTarget, false);

            for (Direction d : Direction.values()) {
                BlockPos adj = pos.relative(d);
                IItemHandler handler = server.getCapability(Capabilities.ItemHandler.BLOCK, adj, d.getOpposite());
                if (handler == null) continue;
                for (int i = 0; i < drops.size(); i++) {
                    ItemStack s = drops.get(i);
                    if (s.isEmpty()) continue;
                    s = tryInsert(handler, s);
                    drops.set(i, s);
                }
            }
            for (ItemStack s : drops) {
                if (!s.isEmpty()) Containers.dropItemStack(server, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, s);
            }

            // After removing whatever was there, place a pipe and move on.
            be.placePipe(server, be.curTarget);
            be.curTarget = be.findNextTarget(server, pos);
            be.feProgress = 0;
            be.feNeeded = be.energyCostFor(server, be.curTarget);
            be.updateCrackStage(server);
        }

        be.setChanged();
    }

    /* ---------------- Helpers ---------------- */

    /** Returns the first position below the controller that is NOT a mining pipe. */
    private BlockPos findNextTarget(ServerLevel level, BlockPos origin) {
        BlockPos cur = origin.below();
        int traversed = 0;
        while (traversed < MAX_DEPTH && level.getBlockState(cur).is(ModBlocks.MINING_PIPE.get())) {
            cur = cur.below();
            traversed++;
        }
        return cur;
    }

    private boolean isUnbreakable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(Blocks.BEDROCK) || s.getDestroySpeed(level, p) < 0f;
    }

    /** FE budget scales with hardness (sand ≪ cobble ≪ obsidian). */
    private int energyCostFor(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return 0;
        float hardness = Math.max(0f, s.getDestroySpeed(level, p));
        float scale = Math.max(0.5f, hardness);
        return Math.max(1, Math.round(BASE_FE_PER_BLOCK * scale));
    }

    private void placePipe(ServerLevel level, BlockPos at) {
        level.setBlock(at, ModBlocks.MINING_PIPE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        level.blockEntityChanged(this.worldPosition);
        level.setBlocksDirty(this.worldPosition, getBlockState(), getBlockState());
    }

    private static ItemStack tryInsert(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
            remaining = handler.insertItem(i, remaining, false);
        }
        return remaining;
    }

    /* ---------------- crack overlay ---------------- */

    private void updateCrackStage(ServerLevel level) {
        if (curTarget == null || feNeeded <= 0) { clearCrackStage(level); return; }
        float pct = Mth.clamp(feProgress / (float) feNeeded, 0f, 0.999f);
        int stage = Mth.clamp((int)(pct * 10f), 0, 9);
        level.destroyBlockProgress(breakerId, curTarget, stage);
    }

    private void clearCrackStage(ServerLevel level) {
        if (curTarget != null) level.destroyBlockProgress(breakerId, curTarget, -1);
    }

    /* ---------------- capability-registrar helper ---------------- */

    public BCEnergyStorage getEnergyStorage() { return energy; }
}
