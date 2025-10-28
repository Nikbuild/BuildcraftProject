package com.nick.buildcraft.content.block.tank;

import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stacked tanks behave visually like one column; each tank keeps its own saved fluid.
 * Column handler implements segmented fill/drain inside a compatibility chain:
 *  - Fill: bottom → up (but only if the whole chain is empty or already the same fluid).
 *  - Drain: top → down within the top contiguous same-fluid segment.
 *
 * Additionally, we implement "downward compaction":
 *  - When a vertical neighbor changes (break/place), any fluid in the broken block is lost.
 *  - Remaining fluid above is pulled downward to fill empty space, without crossing
 *    different-fluid barriers.
 *
 * "Compatibility chain": vertical adjacency only counts if fluids are compatible
 * (either side empty OR same fluid+components). All logic respects this.
 */
public class TankBlockEntity extends BlockEntity {

    /** Each tank holds 16 buckets (16,000 mB). */
    public static final int CAPACITY = 16_000;

    /** Per-block storage. The column handler wraps this for bucket UX only. */
    private final FluidTank tank = new FluidTank(CAPACITY) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            Level lvl = getLevel();
            if (lvl == null || lvl.isClientSide) return;

            // Recalculate column once on the server
            recomputeColumnCache();

            // Notify all column segments of change + force live re-renders/cap refresh
            BlockPos p = getColumnBottom();
            for (TankBlockEntity tbe : collectColumn()) {
                tbe.onColumnContentsChanged();
                lvl.sendBlockUpdated(p, tbe.getBlockState(), tbe.getBlockState(), 3);
                lvl.invalidateCapabilities(p);
                lvl.blockEntityChanged(p);
                p = p.above();
            }
        }

        @Override
        public boolean isFluidValid(FluidStack stack) {
            return stack != null && !stack.isEmpty();
        }
    };

    /* -------- non-persistent column cache -------- */
    private @Nullable BlockPos cachedBottom;
    private int cachedSize = -1;

    /* -------- column-wide capability (for buckets only) -------- */
    private final IFluidHandler columnHandler = new ColumnFluidHandler();

    /* -------- client animation state (not saved) -------- */
    private int clientPrevTotal;
    private int clientCurTotal;
    private long clientChangeGameTime;

    public TankBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.TANK.get(), pos, state);
    }

    /* ----------------- Save / Load ----------------- */

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.store("Fluid", FluidStack.OPTIONAL_CODEC, tank.getFluid());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.read("Fluid", FluidStack.OPTIONAL_CODEC).ifPresent(tank::setFluid);
    }

    /* ----------------- Lifecycle ----------------- */

    @Override
    public void onLoad() {
        super.onLoad();

        // Recompute caches locally
        recomputeColumnCache();

        // ---- One-time upgrade/heal for old worlds ----
        Level lvl = getLevel();
        if (lvl != null) {
            for (TankBlockEntity tbe : collectColumn()) {
                BlockPos p = tbe.getBlockPos();
                tbe.recomputeColumnCache();
                lvl.sendBlockUpdated(p, tbe.getBlockState(), tbe.getBlockState(), 3);
                lvl.invalidateCapabilities(p);
                lvl.blockEntityChanged(p);
            }
            if (!lvl.isClientSide) {
                compactDownwardServer(); // normalize gaps once
            }
        }

        // Client animation bootstrap
        ColumnInfo col = scanColumn(lvl, getBlockPos());
        int total = (col == null) ? 0 : col.totalAmount;
        clientPrevTotal = total;
        clientCurTotal  = total;
        clientChangeGameTime = (lvl != null) ? lvl.getGameTime() : 0L;
    }

    /** Called by block on neighbor placements/removals to refresh the cache. */
    public void onNeighborChanged() {
        Level lvl = getLevel();
        if (lvl == null) return;

        // Recompute on this side (server OR client) so render data is correct immediately
        recomputeColumnCache();

        // Notify the whole compatibility chain on the current side
        for (TankBlockEntity tbe : collectColumn()) {
            BlockPos p = tbe.getBlockPos();
            tbe.recomputeColumnCache();
            lvl.sendBlockUpdated(p, tbe.getBlockState(), tbe.getBlockState(), 3);
            lvl.invalidateCapabilities(p);
            lvl.blockEntityChanged(p);
        }
    }

    /* ----------------- Client sync ----------------- */

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /* ----------------- Accessors ----------------- */

    public FluidTank getTank() { return tank; }
    /** For bucket UX (block uses this); NOT exposed as a capability. */
    public IFluidHandler getColumnHandler() { return columnHandler; }
    /** Server helper to replace contents (fires change notifications). */
    public void setFluid(FluidStack stack) { tank.setFluid(stack.copy()); }

    /* ----------------- Compatibility helpers ----------------- */

    /** Two tanks are compatible if at least one is empty OR fluids (and components) match. */
    public static boolean areCompatible(@Nullable TankBlockEntity a, @Nullable TankBlockEntity b) {
        if (a == null || b == null) return false;
        FluidStack fa = a.tank.getFluid();
        FluidStack fb = b.tank.getFluid();
        if (fa.isEmpty() || fb.isEmpty()) return true;
        return FluidStack.isSameFluidSameComponents(fa, fb);
    }

    private static boolean isCompatNeighbor(Level lvl, BlockPos here, BlockPos there) {
        BlockEntity a = lvl.getBlockEntity(here);
        BlockEntity b = lvl.getBlockEntity(there);
        return (a instanceof TankBlockEntity ta) && (b instanceof TankBlockEntity tb) && areCompatible(ta, tb);
    }

    /* ----------------- Column logic ----------------- */

    public static final class ColumnInfo {
        public final BlockPos bottom;
        public final int size;
        public final int capacityPerTank;
        public final int totalAmount;
        public final FluidStack representativeFluid;

        public ColumnInfo(BlockPos bottom, int size, int capacityPerTank, int totalAmount, FluidStack rep) {
            this.bottom = bottom;
            this.size = size;
            this.capacityPerTank = capacityPerTank;
            this.totalAmount = totalAmount;
            this.representativeFluid = rep;
        }

        public int totalCapacity() { return size * capacityPerTank; }
    }

    /** Scan the vertical *compatibility chain* that contains {@code origin}. */
    @Nullable
    public static ColumnInfo scanColumn(@Nullable Level level, BlockPos origin) {
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(origin);
        if (!(be instanceof TankBlockEntity self)) return null;

        // Find bottom by walking down across compatible neighbors only
        BlockPos bottom = origin;
        while (isCompatNeighbor(level, bottom, bottom.below())) bottom = bottom.below();

        int size = 0, total = 0;
        FluidStack rep = FluidStack.EMPTY;
        BlockPos p = bottom;

        // Walk up across compatible neighbors only
        while (true) {
            BlockEntity cur = level.getBlockEntity(p);
            if (!(cur instanceof TankBlockEntity tbe)) break;
            if (size > 0 && !isCompatNeighbor(level, p.below(), p)) break;

            size++;
            FluidStack fs = tbe.tank.getFluid();
            if (!fs.isEmpty()) {
                total += fs.getAmount();
                if (rep.isEmpty()) rep = fs.copy();
            }
            p = p.above();
        }

        return new ColumnInfo(bottom, size, CAPACITY, total, rep);
    }

    public int columnIndex() {
        BlockPos bottom = getColumnBottom();
        return this.worldPosition.getY() - bottom.getY();
    }

    public BlockPos getColumnBottom() {
        if (cachedBottom == null) recomputeColumnCache();
        return cachedBottom == null ? this.worldPosition : cachedBottom;
    }

    public int getColumnSize() {
        if (cachedSize <= 0) recomputeColumnCache();
        return Math.max(1, cachedSize);
    }

    /** Count size from the true bottom (not from this.worldPosition). */
    private void recomputeColumnCache() {
        Level lvl = getLevel();
        if (lvl == null) return;

        // Walk DOWN to find the true bottom across compatible neighbors
        BlockPos bottom = this.worldPosition;
        while (isCompatNeighbor(lvl, bottom, bottom.below())) bottom = bottom.below();

        // Walk UP from that bottom to count the ENTIRE chain
        int size = 1;
        BlockPos p = bottom;
        while (isCompatNeighbor(lvl, p, p.above())) {
            size++;
            p = p.above();
        }

        this.cachedBottom = bottom;
        this.cachedSize   = size;
        setChanged();
    }

    private static boolean isTank(Level lvl, BlockPos pos) {
        return lvl.getBlockEntity(pos) instanceof TankBlockEntity;
    }

    /** Build the chain by scanning up from the true bottom; no reliance on cached size alone. */
    private List<TankBlockEntity> collectColumn() {
        List<TankBlockEntity> list = new ArrayList<>();
        Level lvl = getLevel();
        if (lvl == null) return list;

        BlockPos p = getColumnBottom();
        BlockEntity be = lvl.getBlockEntity(p);
        if (!(be instanceof TankBlockEntity)) return list;

        list.add((TankBlockEntity) be); // bottom
        while (isCompatNeighbor(lvl, p, p.above())) {
            p = p.above();
            BlockEntity nxt = lvl.getBlockEntity(p);
            if (nxt instanceof TankBlockEntity t) list.add(t);
            else break;
        }
        return list;
    }

    /* ----------------- Downward compaction (compat-only) ----------------- */

    private boolean compacting = false;

    public void compactDownwardServer() {
        Level lvl = getLevel();
        if (lvl == null || lvl.isClientSide || compacting) return;

        compacting = true;
        try {
            List<TankBlockEntity> tanks = collectColumn();
            if (tanks.isEmpty()) return;

            for (int i = 0; i < tanks.size(); i++) {
                TankBlockEntity dst = tanks.get(i);
                FluidStack dstFluid = dst.tank.getFluid();
                int space = dst.tank.getCapacity() - dstFluid.getAmount();
                if (space <= 0) continue;

                // Pull from tanks above us within the same compatibility chain.
                for (int j = i + 1; j < tanks.size() && space > 0; j++) {
                    TankBlockEntity src = tanks.get(j);
                    if (!areCompatible(dst, src)) break; // hard barrier

                    FluidStack sfs = src.tank.getFluid();
                    if (sfs.isEmpty()) continue;

                    // If dst already has fluid, only pull the SAME fluid.
                    if (!dstFluid.isEmpty() && !FluidStack.isSameFluidSameComponents(dstFluid, sfs)) {
                        break; // barrier
                    }

                    int tryMove = Math.min(space, sfs.getAmount());
                    int accepted = dst.tank.fill(sfs.copyWithAmount(tryMove), IFluidHandler.FluidAction.SIMULATE);
                    if (accepted <= 0) {
                        if (dstFluid.isEmpty()) break;
                        continue;
                    }

                    FluidStack drained = src.tank.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
                    if (!drained.isEmpty()) {
                        dst.tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                        dstFluid = dst.tank.getFluid();
                        space     = dst.tank.getCapacity() - dstFluid.getAmount();
                    }
                }
            }
        } finally {
            compacting = false;
        }

        // After compaction, send a refresh to the stack.
        for (TankBlockEntity tbe : collectColumn()) {
            BlockPos p = tbe.getBlockPos();
            tbe.onColumnContentsChanged();
            lvl.sendBlockUpdated(p, tbe.getBlockState(), tbe.getBlockState(), 3);
            lvl.invalidateCapabilities(p);
            lvl.blockEntityChanged(p);
        }
    }

    /* ----------------- Column-wide IFluidHandler (SEGMENTED) ----------------- */

    private final class ColumnFluidHandler implements IFluidHandler {
        @Override public int getTanks() { return 1; }

        @Override
        public FluidStack getFluidInTank(int tankIndex) {
            ColumnInfo col = scanColumn(getLevel(), worldPosition);
            if (col == null || col.totalAmount <= 0) return FluidStack.EMPTY;
            return col.representativeFluid.copyWithAmount(col.totalAmount);
        }

        @Override
        public int getTankCapacity(int tankIndex) {
            ColumnInfo col = scanColumn(getLevel(), worldPosition);
            return col == null ? CAPACITY : col.totalCapacity();
        }

        @Override
        public boolean isFluidValid(int tankIndex, FluidStack stack) {
            return stack != null && !stack.isEmpty();
        }

        /**
         * FILL policy:
         * - Hard rule: a chain may only contain one non-empty fluid type.
         *   If any tank in the chain holds a different non-empty fluid, reject the fill (return 0).
         * - Otherwise, fill bottom→up.
         */
        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) return 0;

            List<TankBlockEntity> tanks = collectColumn();
            if (tanks.isEmpty()) return 0;

            // NEW: reject if the chain already contains a different non-empty fluid anywhere
            for (TankBlockEntity t : tanks) {
                FluidStack cur = t.getTank().getFluid();
                if (!cur.isEmpty() && !FluidStack.isSameFluidSameComponents(cur, resource)) {
                    return 0; // disallow mixed chains → no sound, no consume
                }
            }

            // Chain is empty or already the same fluid → normal bottom-up fill.
            int toFill = resource.getAmount();
            int filled = 0;

            for (int i = 0; i < tanks.size() && filled < toFill; i++) {
                TankBlockEntity tbe = tanks.get(i);
                FluidStack cur = tbe.getTank().getFluid();

                int space = tbe.getTank().getCapacity() - cur.getAmount();
                if (space <= 0) continue;

                int step = Math.min(space, toFill - filled);
                if (step <= 0) continue;

                if (action.execute()) {
                    FluidStack portion = resource.copyWithAmount(step);
                    filled += tbe.getTank().fill(portion, FluidAction.EXECUTE);
                } else {
                    filled += step;
                }
            }

            return filled;
        }

        /** SEGMENTED DRAIN within the current compatibility chain. */
        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain <= 0) return FluidStack.EMPTY;

            List<TankBlockEntity> tanks = collectColumn();
            if (tanks.isEmpty()) return FluidStack.EMPTY;

            // Locate the current top segment (topmost non-empty tank)
            int topIndex = -1;
            FluidStack topFluid = FluidStack.EMPTY;
            for (int i = tanks.size() - 1; i >= 0; i--) {
                FluidStack cur = tanks.get(i).getTank().getFluid();
                if (!cur.isEmpty()) {
                    topIndex = i;
                    topFluid = cur.copy();
                    break;
                }
            }
            if (topIndex < 0 || topFluid.isEmpty()) return FluidStack.EMPTY;

            int remaining = maxDrain;
            int drained = 0;

            for (int i = topIndex; i >= 0 && remaining > 0; i--) {
                FluidStack cur = tanks.get(i).getTank().getFluid();
                if (cur.isEmpty()) break; // reached end of the contiguous top segment
                if (!FluidStack.isSameFluidSameComponents(cur, topFluid)) break; // different-fluid barrier

                int step = Math.min(remaining, cur.getAmount());
                if (step <= 0) continue;

                if (action.execute()) {
                    FluidStack got = tanks.get(i).getTank().drain(step, FluidAction.EXECUTE);
                    drained += got.getAmount();
                } else {
                    drained += step;
                }
                remaining = maxDrain - drained;
            }

            if (drained <= 0) return FluidStack.EMPTY;
            return topFluid.copyWithAmount(drained);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) return FluidStack.EMPTY;

            // Only drain the current top segment; require same fluid
            List<TankBlockEntity> tanks = collectColumn();
            if (tanks.isEmpty()) return FluidStack.EMPTY;

            // Find top segment fluid
            FluidStack topFluid = FluidStack.EMPTY;
            for (int i = tanks.size() - 1; i >= 0; i--) {
                FluidStack cur = tanks.get(i).getTank().getFluid();
                if (!cur.isEmpty()) { topFluid = cur.copy(); break; }
            }
            if (topFluid.isEmpty() || !FluidStack.isSameFluidSameComponents(topFluid, resource)) return FluidStack.EMPTY;

            return drain(resource.getAmount(), action);
        }
    }

    /* ----------------- Animation helpers ----------------- */

    /** Called when total fluid changes to update client interpolation state. */
    public void onColumnContentsChanged() {
        Level lvl = getLevel();
        ColumnInfo col = scanColumn(lvl, getBlockPos());
        int newTotal = (col == null) ? 0 : col.totalAmount;
        if (newTotal != clientCurTotal) {
            clientPrevTotal = clientCurTotal;
            clientCurTotal  = newTotal;
            clientChangeGameTime = (lvl != null) ? lvl.getGameTime() : 0L;
        }
    }

    /** Ease 1 whole tank over 8 ticks (½ tick per bucket). */
    public float getAnimatedHeights(float instantaneousHeights, float partialTicks) {
        Level lvl = getLevel();
        if (lvl == null) return instantaneousHeights;

        final float ticksPerWholeTank = 8f;
        float age = (lvl.getGameTime() - clientChangeGameTime) + partialTicks;
        float t = Math.min(1f, Math.max(0f, age / ticksPerWholeTank));

        float prevH = (float) clientPrevTotal / (float) CAPACITY;
        float curH  = (float) clientCurTotal  / (float) CAPACITY;
        return prevH + (curH - prevH) * t;
    }
}
