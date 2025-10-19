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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stacked tanks behave as one column; renderer draws a translucent continuous fluid volume.
 * This version includes a dynamic AABB that ensures the column stays visible when the base block is off-screen.
 */
public class TankBlockEntity extends BlockEntity {

    /** Each tank holds 16 buckets (16,000 mB). */
    public static final int CAPACITY = 16_000;

    /** Per-block storage. The column capability wraps this. */
    private final FluidTank tank = new FluidTank(CAPACITY) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            Level lvl = getLevel();
            if (lvl == null || lvl.isClientSide) return;

            // Recalculate column once on the server
            recomputeColumnCache();

            // Notify all column segments of change
            BlockPos p = getColumnBottom();
            for (int i = 0; i < getColumnSize(); i++) {
                BlockEntity be = lvl.getBlockEntity(p);
                if (be instanceof TankBlockEntity tbe) {
                    tbe.onColumnContentsChanged();
                    // Force chunk rebuild on clients
                    lvl.sendBlockUpdated(p, tbe.getBlockState(), tbe.getBlockState(), 3);
                }
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

    /* -------- column-wide capability -------- */
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
        recomputeColumnCache();

        Level lvl = getLevel();
        ColumnInfo col = scanColumn(lvl, getBlockPos());
        int total = (col == null) ? 0 : col.totalAmount;
        clientPrevTotal = total;
        clientCurTotal  = total;
        clientChangeGameTime = (lvl != null) ? lvl.getGameTime() : 0L;
    }

    /** Called by block on neighbor placements/removals to refresh the cache. */
    public void onNeighborChanged() {
        recomputeColumnCache();
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
    public IFluidHandler getColumnHandler() { return columnHandler; }

    /** Server helper to replace contents (fires change notifications). */
    public void setFluid(FluidStack stack) {
        tank.setFluid(stack.copy());
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

    /** Scan the vertical column that contains {@code origin}. */
    @Nullable
    public static ColumnInfo scanColumn(@Nullable Level level, BlockPos origin) {
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(origin);
        if (!(be instanceof TankBlockEntity)) return null;

        BlockPos bottom = origin;
        while (isTank(level, bottom.below())) bottom = bottom.below();

        int size = 0, total = 0;
        FluidStack rep = FluidStack.EMPTY;
        BlockPos p = bottom;

        while (isTank(level, p)) {
            BlockEntity cur = level.getBlockEntity(p);
            if (!(cur instanceof TankBlockEntity tbe)) break;
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

    private void recomputeColumnCache() {
        Level lvl = getLevel();
        if (lvl == null) return;

        BlockPos bottom = this.worldPosition;
        while (isTank(lvl, bottom.below())) bottom = bottom.below();

        int size = 0;
        BlockPos p = bottom;
        while (isTank(lvl, p)) {
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

    private List<TankBlockEntity> collectColumn() {
        List<TankBlockEntity> list = new ArrayList<>();
        Level lvl = getLevel();
        if (lvl == null) return list;
        BlockPos p = getColumnBottom();
        for (int i = 0; i < getColumnSize(); i++) {
            BlockEntity be = lvl.getBlockEntity(p);
            if (be instanceof TankBlockEntity t) list.add(t);
            p = p.above();
        }
        return list;
    }

    /* ----------------- Column-wide IFluidHandler ----------------- */

    private final class ColumnFluidHandler implements IFluidHandler {
        @Override public int getTanks() { return 1; }

        @Override
        public FluidStack getFluidInTank(int tankIndex) {
            ColumnInfo col = scanColumn(getLevel(), worldPosition);
            if (col == null || col.totalAmount <= 0) return FluidStack.EMPTY;
            FluidStack out = col.representativeFluid.copy();
            out.setAmount(col.totalAmount);
            return out;
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

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) return 0;

            List<TankBlockEntity> tanks = collectColumn();
            if (tanks.isEmpty()) return 0;

            // No mixing.
            FluidStack existing = getFluidInTank(0);
            if (!existing.isEmpty() && !FluidStack.isSameFluidSameComponents(existing, resource)) return 0;

            int toFill = resource.getAmount();
            int filled = 0;

            // Bottom → top
            for (TankBlockEntity tbe : tanks) {
                FluidStack inTank = tbe.tank.getFluid();
                if (!inTank.isEmpty() && !FluidStack.isSameFluidSameComponents(inTank, resource)) break;

                int cap = tbe.tank.getCapacity();
                int space = cap - inTank.getAmount();
                if (space <= 0) continue;

                int step = Math.min(space, toFill - filled);
                if (step <= 0) continue;

                if (action.execute()) {
                    FluidStack portion = resource.copy();
                    portion.setAmount(step);
                    filled += tbe.tank.fill(portion, FluidAction.EXECUTE);
                } else {
                    filled += step;
                }

                if (filled >= toFill) break;
            }
            return filled;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) return FluidStack.EMPTY;

            FluidStack existing = getFluidInTank(0);
            if (existing.isEmpty() || !FluidStack.isSameFluidSameComponents(existing, resource)) return FluidStack.EMPTY;

            return drain(resource.getAmount(), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain <= 0) return FluidStack.EMPTY;

            List<TankBlockEntity> tanks = collectColumn();
            if (tanks.isEmpty()) return FluidStack.EMPTY;

            FluidStack existing = getFluidInTank(0);
            if (existing.isEmpty()) return FluidStack.EMPTY;

            int remaining = Math.min(maxDrain, existing.getAmount());
            int drained = 0;

            // Top → bottom
            for (int i = tanks.size() - 1; i >= 0 && remaining > 0; i--) {
                TankBlockEntity tbe = tanks.get(i);
                FluidStack inTank = tbe.tank.getFluid();
                if (inTank.isEmpty()) continue;
                if (!FluidStack.isSameFluidSameComponents(inTank, existing)) break;

                int step = Math.min(remaining, inTank.getAmount());
                if (step <= 0) continue;

                if (action.execute()) {
                    FluidStack got = tbe.tank.drain(step, FluidAction.EXECUTE);
                    drained += got.getAmount();
                } else {
                    drained += step;
                }
                remaining = maxDrain - drained;
            }

            if (drained <= 0) return FluidStack.EMPTY;
            FluidStack out = existing.copy();
            out.setAmount(drained);
            return out;
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

    /* ----------------- Render Bounding Box ----------------- */


}
