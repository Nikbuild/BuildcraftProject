package com.nick.buildcraft.content.block.fluidpipe;

import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * FluidPipeBlockEntity
 *
 * Think "BC fluid pipe brain".
 * We simulate packets ("sections") of fluid moving through a pipe,
 * bouncing if blocked, and pushing into adjacent pipes / machines.
 *
 * Visual: each section tracks progress 0..1 toward its current outDir.
 * Logic: tickFluids() advances them, tries to hand off at 1.0.
 *
 * Capability: each side exposes a 1-tank handler that only supports fill(...).
 */
public class FluidPipeBlockEntity extends BlockEntity {

    /** mB per moving "section". */
    private static final int SECTION_CAPACITY = 1000;

    /** Base movement speed per tick, 0..1 per tick is "one block per tick". */
    private static final float BASE_SPEED = 0.10f;

    /** Hide a section for a couple ticks after we bounce it, to avoid flicker. */
    private static final int CUT_HIDE_TICKS = 2;

    /** Hard max concurrent sections in one pipe. */
    private static final int MAX_SECTIONS = 16;

    /** One moving blob of fluid. */
    private static final class Section {
        FluidStack fluid = FluidStack.EMPTY;
        int amountMb = 0;

        @Nullable Direction fromDir = null; // where it entered FROM
        @Nullable Direction outDir  = null; // where it's trying to go TO

        float progress = 0f;   // 0..1 visual offset along outDir from pipe center
        int   dirSign  = +1;   // +1 = heading out, -1 = bouncing back toward center
        int   cutHide  = 0;    // render cooldown
        boolean sourceSection = false; // originally injected from neighbor?
        float speed = BASE_SPEED;
    }

    /* ---------------- State ---------------- */

    /** Active moving packets inside this pipe. */
    private final List<Section> sections = new ArrayList<>();

    /**
     * Per-side fluid handler.
     * Neighbors call fill(...) on this to inject fluid INTO the pipe.
     * drain(...) is disabled because pipes don't serve as extractors yet.
     */
    private final EnumMap<Direction, SideFluidHandler> sideHandlers = new EnumMap<>(Direction.class);

    public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.FLUID_PIPE.get(), pos, state);

        for (Direction d : Direction.values()) {
            sideHandlers.put(d, new SideFluidHandler(d));
        }
    }

    /* =========================================================================================
     * Capability entry point
     * =========================================================================================
     */

    /**
     * Accept incoming fluid from a neighbor on a given side (like IFluidHandler.fill).
     * Returns amount actually accepted.
     */
    private int tryAcceptFromSide(FluidStack incoming, Direction fromSide, IFluidHandler.FluidAction action) {
        if (incoming == null || incoming.isEmpty()) return 0;
        if (sections.size() >= MAX_SECTIONS) return 0;

        // only the server mutates
        if (level == null || level.isClientSide) {
            if (action.execute()) return 0;
        }

        // Try to merge into an existing compatible section.
        Section s = null;
        for (Section existing : sections) {
            if (canMerge(existing, incoming, fromSide)) {
                s = existing;
                break;
            }
        }

        // If none found, consider spawning a brand new section.
        if (s == null) {
            if (action.simulate()) {
                // We *could* create one and accept up to capacity
                return Math.min(incoming.getAmount(), SECTION_CAPACITY);
            }

            s = new Section();
            s.sourceSection = true;
            s.fromDir = fromSide;
            s.outDir = pickInitialOutDir(fromSide, incoming);
            s.progress = 0f;
            s.dirSign = +1;
            s.speed = BASE_SPEED;
            s.fluid = incoming.copy();
            s.fluid.setAmount(0); // we'll grow it as we accept
            s.amountMb = 0;

            sections.add(s);
        }

        int canTake = SECTION_CAPACITY - s.amountMb;
        if (canTake <= 0) return 0;

        int toTake = Math.min(canTake, incoming.getAmount());
        if (toTake <= 0) return 0;

        if (action.execute()) {
            s.amountMb += toTake;
            s.fluid.setAmount(s.amountMb);
            setChanged();
            requestSyncNow();
        }

        return toTake;
    }

    /** true if we can merge 'incoming' into existing section */
    private boolean canMerge(Section s, FluidStack incoming, Direction fromSide) {
        if (s == null) return false;
        if (s.amountMb >= SECTION_CAPACITY) return false;
        if (s.fluid.isEmpty()) return true;

        // Must be same fluid+components
        if (!FluidStack.isSameFluidSameComponents(s.fluid, incoming)) return false;

        // We don't care yet about fromSide/outDir matching.
        return true;
    }

    @Nullable
    private Direction pickInitialOutDir(Direction fromSide, FluidStack stack) {
        // naive default: shoot it straight out the opposite face
        return fromSide.getOpposite();
    }

    /* =========================================================================================
     * Main tick logic
     * =========================================================================================
     */

    /** External ticker calls this each tick. */
    public void tickFluids() {
        tickInternal();
    }

    private void tickInternal() {
        if (level == null || level.isClientSide) return;
        if (sections.isEmpty()) return;

        boolean changed = false;

        for (Iterator<Section> it = sections.iterator(); it.hasNext();) {
            Section sec = it.next();

            if (sec.cutHide > 0) sec.cutHide--;

            // Make sure target direction still makes sense.
            validateOrRetarget(sec);

            // Move toward / away from edge
            sec.progress += sec.speed * sec.dirSign;

            // clamp
            if (sec.progress < 0f) sec.progress = 0f;
            if (sec.progress > 1f) sec.progress = 1f;

            // If bouncing inward and we reached center, flip back outward
            if (sec.dirSign < 0 && sec.progress <= 0f) {
                sec.dirSign = +1;
                sec.outDir = sec.fromDir;
                changed = true;
                continue;
            }

            // If going outward and we reached edge, try to hand off
            if (sec.dirSign > 0 && sec.progress >= 1f) {
                boolean transferred = tryPushToNeighbor(sec);
                if (transferred) {
                    // section completely moved out
                    it.remove();
                    changed = true;
                } else {
                    // blocked, bounce
                    sec.dirSign = -1;
                    sec.progress = 1f;
                    sec.cutHide = CUT_HIDE_TICKS;
                    changed = true;
                }
            }
        }

        if (changed) {
            setChanged();
            requestSyncNow();
        }
    }

    /**
     * Called by the block when neighbors are changed, so we can re-check routing.
     */
    public void onNeighborGraphChanged(BlockPos changedPos) {
        if (level == null || level.isClientSide) return;
        setChanged();
        requestSyncNow();
    }

    /** Recheck if sec.outDir is still valid; reroute or bounce if not. */
    private void validateOrRetarget(Section sec) {
        if (sec.outDir == null) return;

        Direction out = sec.outDir;
        BlockPos destPos = worldPosition.relative(out);

        if (!canOutputTo(destPos, out, sec)) {
            Direction newDir = chooseBetterDir(sec);
            if (newDir != null) {
                sec.outDir = newDir;
            } else {
                // bounce
                sec.dirSign = -1;
                sec.cutHide = CUT_HIDE_TICKS;
            }
        }
    }

    @Nullable
    private Direction chooseBetterDir(Section sec) {
        for (Direction d : Direction.values()) {
            if (sec.fromDir != null && d == sec.fromDir) continue;
            BlockPos np = worldPosition.relative(d);
            if (canOutputTo(np, d, sec)) return d;
        }
        return null;
    }

    /**
     * Can we push this section into neighbor at pos/toward?
     * Accepts:
     *  - another FluidPipeBlockEntity with room
     *  - any block face exposing IFluidHandler that will fill(...)
     */
    private boolean canOutputTo(BlockPos pos, Direction toward, Section sec) {
        if (level == null) return false;

        // Pipe neighbor
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FluidPipeBlockEntity other) {
            return other.canAcceptFromNeighbor(sec, toward.getOpposite());
        }

        // Generic fluid endpoint
        IFluidHandler cap = level.getCapability(
                Capabilities.FluidHandler.BLOCK,
                pos,
                toward.getOpposite()
        );
        if (cap != null) {
            FluidStack offer = sec.fluid.copyWithAmount(sec.amountMb);
            int filled = cap.fill(offer, IFluidHandler.FluidAction.SIMULATE);
            return filled > 0;
        }

        return false;
    }

    /** Whether THIS pipe would accept some/all of "fromOther" coming in from given side. */
    private boolean canAcceptFromNeighbor(Section fromOther, Direction incomingFrom) {
        if (sections.size() >= MAX_SECTIONS) return false;

        // can we merge?
        for (Section s : sections) {
            if (canMerge(s, fromOther.fluid, incomingFrom)) return true;
        }
        // or spawn new one
        return true;
    }

    /**
     * Try to hand this section's fluid off to neighbor in sec.outDir.
     * Return true if entire section got handed off (so caller should remove it).
     */
    private boolean tryPushToNeighbor(Section sec) {
        if (sec.outDir == null || sec.amountMb <= 0 || level == null) return false;

        BlockPos targetPos = worldPosition.relative(sec.outDir);

        // 1) Neighbor fluid pipe
        BlockEntity be = level.getBlockEntity(targetPos);
        if (be instanceof FluidPipeBlockEntity other) {
            int moved = other.receiveFromNeighbor(sec, sec.outDir.getOpposite());
            if (moved > 0) {
                sec.amountMb -= moved;
                sec.fluid.setAmount(sec.amountMb);
                return sec.amountMb <= 0;
            }
        }

        // 2) Generic fluid handler (tank, pump, etc.)
        IFluidHandler cap = level.getCapability(
                Capabilities.FluidHandler.BLOCK,
                targetPos,
                sec.outDir.getOpposite()
        );
        if (cap != null) {
            FluidStack offer = sec.fluid.copyWithAmount(sec.amountMb);
            int filled = cap.fill(offer, IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) {
                sec.amountMb -= filled;
                sec.fluid.setAmount(sec.amountMb);
                return sec.amountMb <= 0;
            }
        }

        return false;
    }

    /**
     * Called by neighbor pipe when handing off fluid.
     * Always executes immediately on server.
     */
    private int receiveFromNeighbor(Section fromOther, Direction incomingFrom) {
        if (fromOther == null || fromOther.amountMb <= 0) return 0;
        if (sections.size() >= MAX_SECTIONS) return 0;

        // try to merge into existing
        for (Section s : sections) {
            if (!canMerge(s, fromOther.fluid, incomingFrom)) continue;

            int canTake = SECTION_CAPACITY - s.amountMb;
            if (canTake <= 0) continue;

            int toTake = Math.min(canTake, fromOther.amountMb);
            if (toTake <= 0) continue;

            s.amountMb += toTake;
            s.fluid.setAmount(s.amountMb);
            s.fromDir = incomingFrom;
            s.outDir = pickInitialOutDir(incomingFrom, s.fluid);

            setChanged();
            requestSyncNow();
            return toTake;
        }

        // else spawn brand new section
        Section s = new Section();
        s.sourceSection = false;
        s.fromDir = incomingFrom;
        s.outDir = pickInitialOutDir(incomingFrom, fromOther.fluid);
        s.progress = 0f;
        s.dirSign = +1;
        s.speed = fromOther.speed;

        int toTake = Math.min(SECTION_CAPACITY, fromOther.amountMb);
        s.fluid = fromOther.fluid.copyWithAmount(toTake);
        s.amountMb = toTake;

        sections.add(s);
        setChanged();
        requestSyncNow();
        return toTake;
    }

    /* =========================================================================================
     * Sync / Save
     * =========================================================================================
     */

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    private void requestSyncNow() {
        if (level == null || level.isClientSide) return;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        out.putInt("sec_count", sections.size());

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            ValueOutput child = out.child("sec_" + i);

            // Store fluid via OPTIONAL_CODEC (same style as tanks)
            child.store("fluid", FluidStack.OPTIONAL_CODEC, s.fluid);

            child.putInt("amt", s.amountMb);
            child.putInt("dirSign", s.dirSign);
            child.putFloat("prog", s.progress);
            child.putFloat("spd",  s.speed);
            child.putInt("cut", s.cutHide);
            child.putBoolean("src", s.sourceSection);

            child.putInt("from", s.fromDir == null ? -1 : s.fromDir.get3DDataValue());
            child.putInt("out",  s.outDir  == null ? -1 : s.outDir.get3DDataValue());
        }
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        sections.clear();

        int count = in.getInt("sec_count").orElse(0);
        for (int i = 0; i < count; i++) {
            Optional<ValueInput> optChild = in.child("sec_" + i);
            if (optChild.isEmpty()) continue;
            ValueInput child = optChild.get();

            Section s = new Section();

            child.read("fluid", FluidStack.OPTIONAL_CODEC).ifPresent(fs -> {
                s.fluid = fs.copy();
            });

            s.amountMb = child.getInt("amt").orElse(0);
            s.dirSign  = child.getInt("dirSign").orElse(+1);
            s.progress = child.getFloatOr("prog", 0f);
            s.speed    = child.getFloatOr("spd", BASE_SPEED);
            s.cutHide  = child.getInt("cut").orElse(0);
            s.sourceSection = child.getBooleanOr("src", false);

            int fromVal = child.getInt("from").orElse(-1);
            int outVal  = child.getInt("out").orElse(-1);
            s.fromDir = (fromVal >= 0 && fromVal < 6) ? Direction.from3DDataValue(fromVal) : null;
            s.outDir  = (outVal  >= 0 && outVal  < 6) ? Direction.from3DDataValue(outVal)  : null;

            // keep stack's internal amount in sync
            if (s.fluid != null && !s.fluid.isEmpty()) {
                s.fluid.setAmount(s.amountMb);
            } else {
                s.fluid = FluidStack.EMPTY;
                s.amountMb = 0;
            }

            sections.add(s);
        }
    }

    /* =========================================================================================
     * Renderer snapshot helpers
     * =========================================================================================
     */

    public static final class RenderSection {
        public final FluidStack fluid;
        public final float progress;
        public final Direction outDir;
        public final boolean hidden;
        public final int amountMb;

        private RenderSection(FluidStack fluid, float progress, Direction outDir, boolean hidden, int amountMb) {
            this.fluid = fluid;
            this.progress = progress;
            this.outDir = outDir;
            this.hidden = hidden;
            this.amountMb = amountMb;
        }
    }

    /** Snapshot for BER. */
    public List<RenderSection> getRenderSections(int max) {
        List<RenderSection> list = new ArrayList<>();
        for (int i = 0; i < sections.size() && list.size() < max; i++) {
            Section s = sections.get(i);
            list.add(new RenderSection(
                    s.fluid.copyWithAmount(s.amountMb),
                    s.progress,
                    s.outDir,
                    s.cutHide > 0,
                    s.amountMb
            ));
        }
        return list;
    }

    /* =========================================================================================
     * Capability access
     * =========================================================================================
     */

    private final class SideFluidHandler implements IFluidHandler {
        private final Direction side;
        SideFluidHandler(Direction side) { this.side = side; }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            // pipes don't expose a standing "tank" of fluid to outsiders
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return SECTION_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return stack != null && !stack.isEmpty();
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return tryAcceptFromSide(resource, side, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    /**
     * Capability lookup helper.
     * ModCapabilities will hook this into Capabilities.FluidHandler.BLOCK
     * so other blocks can do level.getCapability(...).
     */
    @Nullable
    public IFluidHandler getFluidHandlerForSide(Direction side) {
        return sideHandlers.get(side);
    }
}
