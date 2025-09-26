// src/main/java/com/nick/buildcraft/content/block/pipe/StonePipeBlockEntity.java
package com.nick.buildcraft.content.block.pipe;

import com.mojang.serialization.Codec;
import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Item transport pipe with ghosting trips.
 *
 * Diamond hard-edge rule (server-only, no renderer special cases):
 *   If the neighbor is a Diamond pipe and {@link DiamondPipeBlockEntity#rejectsFrom} returns true
 *   for the entering face, we DO NOT cross the border and DO NOT transfer ownership. We simply
 *   reverse direction INSIDE the current pipe and head back to center.
 *
 * Iron rule:
 *   Items may not ENTER from the iron’s configured output face. Iron accepts to core, then
 *   immediately sends back out the same face (classic BC).
 */
public class StonePipeBlockEntity extends BlockEntity implements EnginePulseAcceptorApi {

    /* ---------------- tuning ---------------- */
    private static final int   SLOTS_PER_PIPE      = 24;
    private static final int   TICKS_PER_SLOT      = 1;
    private static final int   CUT_HIDE_TICKS      = 2;
    private static final int   OPEN_END_BFS_LIMIT  = 1024;
    private static final int   HOP_TTL_MAX         = 256;
    private static final int   MAX_TRIPS_PER_PIPE  = 32;

    // --- speed model (BC-like) ---
    private static final float NORMAL_SPEED = 0.10f;
    private static final float GOLD_TARGET  = 0.30f;
    private static final float GOLD_DELTA   = 0.10f;
    private static final float SPEED_DECAY  = 0.002f;

    // handoff scratch (carry velocity across pipes)
    private static float  HANDOFF_VEL      = NORMAL_SPEED;
    private static boolean HANDOFF_HAS_VEL = false;

    /* ------------- one "trip" (one stack moving through) ------------- */
    private static final class Trip {
        ItemStack cargo = ItemStack.EMPTY;

        final List<BlockPos> route = new ArrayList<>();
        int routeIndex = 0;

        BlockPos sinkPos = null;
        Direction sinkFace = null;

        float segmentProgress = 0f;           // 0..1 along (center->face) of the *current* segment
        Direction receivedFrom = null;        // neighbor we got the item from (opposite of default forward)
        boolean sourceForTrip = false;

        int slotIndex = 0;                    // 0..SLOTS_PER_PIPE
        int slotTick  = 0;

        int cutHideCooldown = 0;
        int hopTTL = HOP_TTL_MAX;

        // --- BC-like speed state ---
        float vel = NORMAL_SPEED;             // relative units
        float slotAccum = 0f;                 // fractional slots advanced (directionless)

        // Segment direction sign: +1 = toward current 'forward' face, -1 = back toward center.
        int dirSign = +1;

        // If non-null, use this as the next outgoing dir once (when rebuilding route), then clear it.
        @Nullable Direction forcedOutOrNull = null;
    }

    /* ---------------- state ---------------- */
    private final List<Trip> trips = new ArrayList<>();

    /** Coalesce multiple pulses from the SAME side in the same tick. */
    private final EnumMap<Direction, Long> lastPulseTickBySide = new EnumMap<>(Direction.class);

    public StonePipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.STONE_PIPE.get(), pos, state);
        for (Direction d : Direction.values()) lastPulseTickBySide.put(d, Long.MIN_VALUE);
    }

    /* ---------------- public API: offer into the pipe ---------------- */

    /** For inventory->pipe insertion when this pipe is a Diamond. */
    boolean shouldRejectAtBorder(Direction from, ItemStack stack) {
        if (this instanceof DiamondPipeBlockEntity dp) {
            return dp.rejectsFrom(from, stack);
        }
        return false;
    }

    /** Injects a stack into this pipe from side {@code from}. Always ghosts through existing trips. */
    public ItemStack offer(ItemStack stack, Direction from) {
        if (level == null || level.isClientSide || stack.isEmpty()) return stack;
        if (trips.size() >= MAX_TRIPS_PER_PIPE) return stack;

        // Diamond edge-reject for inventory -> pipe insert
        if (shouldRejectAtBorder(from, stack)) {
            return stack; // refuse before entering the core
        }

        BlockState here = getBlockState();
        boolean ironBounce = here != null && here.getBlock() instanceof IronPipeBlock
                && !IronPipeBlock.canItemEnterFrom(here, from);

        Trip t = new Trip();
        int canAccept = Math.min(stack.getCount(), stack.getMaxStackSize());
        t.cargo = stack.copy();
        t.cargo.setCount(canAccept);

        ItemStack leftover = stack.copy();
        leftover.shrink(canAccept);

        t.receivedFrom = from;
        t.sourceForTrip = true;
        t.dirSign = +1;

        if (ironBounce) t.forcedOutOrNull = from; // enter core, then immediately head back

        rebuildRouteConsume(t);

        trips.add(t);
        setChanged();
        requestSyncNow();
        return leftover;
    }

    /* ------- neighbor graph immediate cut ---- */
    public void onNeighborGraphChanged(BlockPos fromPos) {
        if (level == null || level.isClientSide || trips.isEmpty()) return;

        boolean any = false;
        for (Trip t : trips) {
            boolean affects = (t.routeIndex < t.route.size() && t.route.get(t.routeIndex).equals(fromPos));
            if (!affects) {
                Direction out = peekOutgoingDir(t); // <-- do NOT consume
                if (out != null && worldPosition.relative(out).equals(fromPos)) affects = true;
            }
            if (affects) {
                t.route.clear();
                t.routeIndex = 0;
                t.sinkPos = null;
                t.sinkFace = null;

                t.slotIndex = Math.min(t.slotIndex, SLOTS_PER_PIPE - 1);
                t.segmentProgress = t.slotIndex / (float) SLOTS_PER_PIPE;
                t.cutHideCooldown = CUT_HIDE_TICKS;

                // Rebuild a fresh route immediately so the trip keeps moving
                rebuildRouteConsume(t);
                // Optional: give a small TTL grace after topology changes
                // t.hopTTL = HOP_TTL_MAX;

                any = true;
            }

        }
        if (any) requestSyncNow();
    }

    /* -------------------- ticking -------------------- */
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    /** Call from block ticker (server). */
    public void tick() {
        if (level == null || level.isClientSide || trips.isEmpty()) return;

        boolean changed = false;

        for (Iterator<Trip> it = trips.iterator(); it.hasNext(); ) {
            Trip t = it.next();

            if (t.cutHideCooldown > 0) t.cutHideCooldown--;

            validateNextHopAndMaybeReroute(t);

            // timing
            if (++t.slotTick < TICKS_PER_SLOT) continue;
            t.slotTick = 0;

            // --- BuildCraft-like acceleration model ---
            if (isGoldHere()) {
                t.vel = Math.min(GOLD_TARGET, t.vel + GOLD_DELTA);
            } else {
                t.vel = Math.max(NORMAL_SPEED, t.vel - SPEED_DECAY);
            }

            t.slotAccum += (t.vel / NORMAL_SPEED);

            int whole = (int) t.slotAccum;
            if (whole <= 0) {
                t.segmentProgress = t.slotIndex / (float) SLOTS_PER_PIPE;
                changed = true;
                continue;
            }
            t.slotAccum -= whole;

            // direction-aware motion
            int delta = whole * t.dirSign;
            int nextIndex = t.slotIndex + delta;

            // ----- moving back toward center? -----
            if (t.dirSign < 0) {
                if (nextIndex > 0) {
                    t.slotIndex = nextIndex;
                    t.segmentProgress = t.slotIndex / (float) SLOTS_PER_PIPE;
                    changed = true;
                    continue;
                }
                // reached/overshot center → flip, rebuild to go back out the way we came
                t.slotIndex = 0;
                t.segmentProgress = 0f;
                t.dirSign = +1;
                t.slotAccum = 0f;
                t.forcedOutOrNull = t.receivedFrom; // next forward segment is the side we came from
                rebuildRouteConsume(t);
                changed = true;
                continue;
            }

            // ----- normal forward motion -----
            if (nextIndex < SLOTS_PER_PIPE) {
                t.slotIndex = nextIndex;
                t.segmentProgress = t.slotIndex / (float) SLOTS_PER_PIPE;
                changed = true;
                continue;
            }

            // would cross the border: check neighbor first
            if (t.routeIndex < t.route.size()) {
                BlockPos nextPipePos = t.route.get(t.routeIndex);

                if (!isValidPipeAt(nextPipePos)) {
                    rebuildRouteConsume(t);
                    changed = true;
                    continue;
                }

                BlockEntity be = level.getBlockEntity(nextPipePos);
                if (be instanceof StonePipeBlockEntity next) {
                    Direction enteringFace = next.directionFrom(worldPosition); // how 'next' sees us

                    // DIAMOND HARD WALL: reject at border -> reverse INSIDE this pipe (no transfer)
                    if (be instanceof DiamondPipeBlockEntity dp &&
                            enteringFace != null &&
                            dp.rejectsFrom(enteringFace, t.cargo)) {

                        t.dirSign = -1;                                  // head back toward center
                        t.slotIndex = Math.min(SLOTS_PER_PIPE - 1, t.slotIndex);
                        t.segmentProgress = t.slotIndex / (float) SLOTS_PER_PIPE;
                        t.slotAccum = 0f;
                        t.cutHideCooldown = CUT_HIDE_TICKS;
                        changed = true;
                        continue;
                    }

                    // normal handoff (no 1.0 render)
                    HANDOFF_VEL = t.vel;
                    HANDOFF_HAS_VEL = true;

                    boolean accepted = next.receiveFromGuide(t.cargo, worldPosition);
                    if (accepted) {
                        t.routeIndex++;
                        it.remove(); // transferred ownership
                        changed = true;
                        continue;
                    } else {
                        // capacity/etc failure → reverse locally
                        HANDOFF_HAS_VEL = false;
                        t.dirSign = -1;
                        t.slotIndex = Math.min(SLOTS_PER_PIPE - 1, t.slotIndex);
                        t.segmentProgress = t.slotIndex / (float) SLOTS_PER_PIPE;
                        t.cutHideCooldown = CUT_HIDE_TICKS;
                        changed = true;
                        continue;
                    }
                }

                // neighbor wasn't a pipe after all → reroute
                rebuildRouteConsume(t);
                changed = true;
                continue;
            }

            // no route left → try sink insert or eject to world if open end
            if (t.sinkPos != null && t.sinkFace != null) {
                IItemHandler dst = level.getCapability(Capabilities.ItemHandler.BLOCK, t.sinkPos, t.sinkFace);
                if (dst != null) {
                    ItemStack leftover = ItemHandlerHelper.insertItem(dst, t.cargo.copy(), false);
                    if (leftover.isEmpty()) {
                        it.remove();
                        changed = true;
                        continue;
                    } else {
                        t.cargo = leftover;
                        changed = true;
                        continue;
                    }
                }
            }

// No sink and no route → open end; eject immediately (don’t wait on TTL)
            abortAndDrop(t);
            it.remove();
            changed = true;
            continue;

        }

        if (changed) {
            setChanged();
            requestSyncNow();
        }
    }

    /** Called by upstream neighbor at segment boundary to transfer a trip. */
    private boolean receiveFromGuide(ItemStack stack, BlockPos upstreamPos) {
        if (level == null || level.isClientSide) return false;

        Direction from = directionFrom(upstreamPos);
        BlockState here = getBlockState();

        // Diamond edge reject for pipe->pipe handoff (bounce at border)
        if (shouldRejectAtBorder(from, stack)) {
            return false; // sender must keep and bounce
        }

        if (trips.size() >= MAX_TRIPS_PER_PIPE) {
            // accept ownership and drop to world to avoid dupes
            Block.popResource(level, worldPosition, stack.copy());
            return true;
        }

        Trip t = new Trip();
        t.vel = HANDOFF_HAS_VEL ? HANDOFF_VEL : NORMAL_SPEED;
        HANDOFF_HAS_VEL = false;
        t.cargo = stack.copy();
        t.cargo.setCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
        t.sourceForTrip = false;
        t.receivedFrom = from;
        t.dirSign = +1;

        // Iron: entry from disallowed face? accept to core, then immediately head back.
        if (here != null && here.getBlock() instanceof IronPipeBlock) {
            if (!IronPipeBlock.canItemEnterFrom(here, from)) {
                t.forcedOutOrNull = from;
            }
        }

        // spawn at start of segment
        t.slotIndex = t.slotTick = 0;
        t.segmentProgress = 0f;
        t.cutHideCooldown = 0;
        t.hopTTL = HOP_TTL_MAX;

        rebuildRouteConsume(t);
        trips.add(t);
        setChanged();
        requestSyncNow();
        return true;
    }

    private Direction directionFrom(BlockPos neighbor) {
        for (Direction d : Direction.values()) if (worldPosition.relative(d).equals(neighbor)) return d;
        return null;
    }

    private void abortAndDrop(Trip t) {
        if (level != null && !t.cargo.isEmpty()) {
            Direction out = peekOutgoingDir(t); // do not consume here
            BlockPos dropPos = (out != null) ? worldPosition.relative(out) : worldPosition;
            Block.popResource(level, dropPos, t.cargo.copy());
        }
    }

    /* ---------- legacy single-trip hooks used by (old) renderer ---------- */
    public ItemStack getCargoForRender() { return trips.isEmpty() ? ItemStack.EMPTY : trips.get(0).cargo; }
    public float getSegmentProgress() { return trips.isEmpty() ? 0f : trips.get(0).segmentProgress; }
    public BlockPos getBlockPos() { return worldPosition; }
    public BlockPos getNextPipeOrNull() {
        if (trips.isEmpty()) return null;
        Trip t = trips.get(0);
        if (t.routeIndex < t.route.size()) {
            BlockPos p = t.route.get(t.routeIndex);
            if (!isValidPipeAt(p)) return null;
            return p;
        }
        return null;
    }
    public boolean isHeadingToSink() { return !trips.isEmpty() && trips.get(0).routeIndex >= trips.get(0).route.size() && trips.get(0).sinkPos != null; }
    public BlockPos getSinkPos() { return trips.isEmpty() ? null : trips.get(0).sinkPos; }
    public Direction getOutgoingDirOrNull() { return trips.isEmpty() ? null : peekOutgoingDir(trips.get(0)); }
    public boolean shouldHideForCut() { return !trips.isEmpty() && trips.get(0).cutHideCooldown > 0; }

    /* ---------- NEW: multi-trip render snapshot ---------- */

    public static final class RenderTrip {
        public final ItemStack stack;
        public final float progress;
        public final BlockPos nextPipeOrNull;
        public final BlockPos sinkPosOrNull;
        public final Direction outgoingDirOrNull;
        public final boolean hiddenForCut;

        public RenderTrip(ItemStack stack, float progress,
                          BlockPos next, BlockPos sink, Direction out, boolean hidden) {
            this.stack = stack;
            this.progress = progress;
            this.nextPipeOrNull = next;
            this.sinkPosOrNull = sink;
            this.outgoingDirOrNull = out;
            this.hiddenForCut = hidden;
        }
    }

    public java.util.List<RenderTrip> getRenderTrips(int max) {
        java.util.ArrayList<RenderTrip> out = new java.util.ArrayList<>(Math.min(max, trips.size()));
        for (int i = 0; i < trips.size() && out.size() < max; i++) {
            Trip t = trips.get(i);
            BlockPos next = null;
            if (t.routeIndex < t.route.size()) {
                BlockPos p = t.route.get(t.routeIndex);
                if (isValidPipeAt(p)) next = p;
            }
            out.add(new RenderTrip(
                    t.cargo,
                    t.segmentProgress,
                    next,
                    t.sinkPos,
                    peekOutgoingDir(t),          // no consume during render
                    t.cutHideCooldown > 0
            ));
        }
        return out;
    }

    /* ---------- sync / save ---------- */
    private void requestSyncNow() {
        if (level == null || level.isClientSide) return;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        out.putInt("tc", trips.size());
        for (int i = 0; i < trips.size(); i++) {
            Trip t = trips.get(i);
            String p = "t" + i + "_";

            out.store(p + "cargo", ItemStack.CODEC, t.cargo);
            out.putInt(p + "ri", t.routeIndex);
            out.store(p + "prog", Codec.FLOAT, t.segmentProgress);
            out.putInt(p + "rf", t.receivedFrom == null ? -1 : t.receivedFrom.get3DDataValue());
            out.putInt(p + "src", t.sourceForTrip ? 1 : 0);

            out.putInt(p + "slotIndex", t.slotIndex);
            out.putInt(p + "slotTick", t.slotTick);
            out.putInt(p + "cutHide", t.cutHideCooldown);
            out.putInt(p + "ttl", t.hopTTL);
            out.putInt(p + "dir", t.dirSign);

            if (t.sinkPos != null) {
                out.putInt(p + "sx", t.sinkPos.getX());
                out.putInt(p + "sy", t.sinkPos.getY());
                out.putInt(p + "sz", t.sinkPos.getZ());
                out.putInt(p + "sf", t.sinkFace == null ? -1 : t.sinkFace.get3DDataValue());
            }

            out.putInt(p + "rc", t.route.size());
            for (int j = 0; j < t.route.size(); j++) {
                BlockPos bp = t.route.get(j);
                out.putInt(p + "rx" + j, bp.getX());
                out.putInt(p + "ry" + j, bp.getY());
                out.putInt(p + "rz" + j, bp.getZ());
            }
        }
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        trips.clear();

        int tc = in.getInt("tc").orElse(0);
        for (int i = 0; i < tc; i++) {
            String p = "t" + i + "_";

            Trip t = new Trip();
            t.cargo = in.read(p + "cargo", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            t.routeIndex = in.getInt(p + "ri").orElse(0);
            t.segmentProgress = in.read(p + "prog", Codec.FLOAT).orElse(0f);
            int rf = in.getInt(p + "rf").orElse(-1);
            t.receivedFrom = (rf >= 0 && rf < 6) ? Direction.from3DDataValue(rf) : null;
            t.sourceForTrip = in.getInt(p + "src").orElse(0) != 0;

            t.slotIndex = in.getInt(p + "slotIndex").orElse(0);
            t.slotTick = in.getInt(p + "slotTick").orElse(0);
            t.cutHideCooldown = in.getInt(p + "cutHide").orElse(0);
            t.hopTTL = in.getInt(p + "ttl").orElse(HOP_TTL_MAX);
            t.dirSign = in.getInt(p + "dir").orElse(+1);

            Integer sx = in.getInt(p + "sx").orElse(null);
            Integer sy = in.getInt(p + "sy").orElse(null);
            Integer sz = in.getInt(p + "sz").orElse(null);
            int sf = in.getInt(p + "sf").orElse(-1);
            if (sx != null && sy != null && sz != null) {
                t.sinkPos = new BlockPos(sx, sy, sz);
                t.sinkFace = (sf >= 0 && sf < 6) ? Direction.from3DDataValue(sf) : null;
            }

            int rc = in.getInt(p + "rc").orElse(0);
            for (int j = 0; j < rc; j++) {
                int x = in.getInt(p + "rx" + j).orElse(0);
                int y = in.getInt(p + "ry" + j).orElse(0);
                int z = in.getInt(p + "rz" + j).orElse(0);
                t.route.add(new BlockPos(x, y, z));
            }

            if (!t.cargo.isEmpty()) trips.add(t);
        }
    }

    /* ------------- internals: routing ------------- */

    private boolean isValidPipeAt(BlockPos pos) {
        if (level == null) return false;
        return level.getBlockEntity(pos) instanceof StonePipeBlockEntity;
    }

    /** Rebuilds route obeying right-of-way + diamond/iron rules. (Consumes one-shot dir if present.) */
    private void rebuildRouteConsume(Trip t) {
        if (level == null) return;

        t.route.clear();
        t.routeIndex = 0;
        t.sinkPos = null;
        t.sinkFace = null;

        Direction forward = preferredOutgoingDir(t, /*consume=*/true);
        if (forward != null) {
            BlockPos ahead = worldPosition.relative(forward);

            if (isValidPipeAt(ahead)) {
                t.route.add(ahead);
                return;
            }

            // straight (chosen) sink?
            IItemHandler straightHandler = getHandler(ahead, forward.getOpposite());
            if (straightHandler != null) {
                t.sinkPos = ahead;
                t.sinkFace = forward.getOpposite();
                return;
            }
        }

        // Pathfind (fallback) – respects diamond filters along the way
        PipeTransport.Path path = PipeTransport.findPath((Level) level, worldPosition, t.receivedFrom, t.cargo);
        if (path != null) {
            t.route.addAll(path.nodes());
            t.sinkPos = path.sinkPos();
            t.sinkFace = path.sinkFace();
            return;
        }

        // No sink → head to nearest open end; if none, farthest node
        List<BlockPos> toEnd = findOpenEndRoute((Level) level, worldPosition, t.receivedFrom);
        if (!toEnd.isEmpty()) t.route.addAll(toEnd);
    }

    private void validateNextHopAndMaybeReroute(Trip t) {
        if (t.routeIndex < t.route.size()) {
            BlockPos next = t.route.get(t.routeIndex);
            if (!isValidPipeAt(next)) { rebuildRouteConsume(t); requestSyncNow(); }
        }
    }

    /** BFS to nearest terminal (excluding parent). If none, pick farthest node. */
    private List<BlockPos> findOpenEndRoute(Level lvl, BlockPos start, Direction fromDir) {
        List<BlockPos> empty = Collections.emptyList();
        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> dist = new HashMap<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();

        visited.add(start); dist.put(start, 0); q.add(start);
        BlockPos farthest = start;

        while (!q.isEmpty() && visited.size() <= OPEN_END_BFS_LIMIT) {
            BlockPos cur = q.pollFirst();
            if (dist.getOrDefault(cur, 0) > dist.getOrDefault(farthest, 0)) farthest = cur;

            BlockPos par = parent.get(cur);

            for (Direction d : Direction.values()) {
                BlockPos np = cur.relative(d);
                if (!isValidPipeAt(np)) continue;

                // avoid U-turn as first hop
                if (cur.equals(start) && fromDir != null && np.equals(start.relative(fromDir))) continue;
                if (par != null && np.equals(par)) continue;

                if (visited.add(np)) {
                    parent.put(np, cur);
                    dist.put(np, dist.getOrDefault(cur, 0) + 1);
                    q.addLast(np);
                }
            }

            // terminal = dead-end pipe (no further pipes excluding parent/entry)
            int forwardCount = 0;
            for (Direction d : Direction.values()) {
                BlockPos np = cur.relative(d);
                if (!isValidPipeAt(np)) continue;
                if (par != null && np.equals(par)) continue; // exclude parent
                if (cur.equals(start) && fromDir != null && np.equals(start.relative(fromDir))) continue; // exclude entry dir at first hop
                forwardCount++;
            }
            boolean terminal = forwardCount == 0 && !cur.equals(start);
            if (terminal) return reconstructPath(start, cur, parent);

        }

        if (!farthest.equals(start)) return reconstructPath(start, farthest, parent);
        return empty;
    }

    private static List<BlockPos> reconstructPath(BlockPos start, BlockPos goal, Map<BlockPos, BlockPos> parent) {
        ArrayList<BlockPos> path = new ArrayList<>();
        BlockPos p = goal;
        while (p != null && !p.equals(start)) { path.add(p); p = parent.get(p); }
        Collections.reverse(path);
        return path;
    }

    /**
     * Preferred outgoing direction inside THIS pipe.
     * If {@code consume} is true, a one-shot forced direction is consumed.
     * If false, it is returned without clearing (for rendering/snapshotting).
     */
    private Direction preferredOutgoingDir(Trip t, boolean consume) {
        BlockState s = getBlockState();

        // honor a one-shot forced direction
        if (t.forcedOutOrNull != null) {
            Direction d = t.forcedOutOrNull;
            if (consume) t.forcedOutOrNull = null; // consume only when routing
            return d;
        }

        // Iron behavior
        if (s != null && s.getBlock() instanceof IronPipeBlock) {
            return IronPipeBlock.getOutput(s);
        }

        // Diamond behavior (choose an output face but NOT the face we came from if possible)
        if (this instanceof DiamondPipeBlockEntity dp && t.cargo != null) {
            EnumSet<Direction> allowed = dp.getAllowedDirections(t.cargo);
            if (!allowed.isEmpty()) {
                if (t.receivedFrom != null && allowed.contains(t.receivedFrom)) {
                    return t.receivedFrom; // bounce back (classic BC feel)
                }
                if (t.receivedFrom != null) {
                    for (Direction d : allowed) if (d != t.receivedFrom) return d;
                } else {
                    return allowed.iterator().next();
                }
            }
        }

        // Default: continue straight
        return t.receivedFrom != null ? t.receivedFrom.getOpposite() : null;
    }

    private Direction peekOutgoingDir(Trip t) { return preferredOutgoingDir(t, false); } // no side effects

    // NeoForge helper
    private IItemHandler getHandler(BlockPos pos, Direction face) {
        if (level == null) return null;
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, be, face);
    }

    private boolean isGoldHere() {
        BlockState s = getBlockState();
        return s != null
                && s.getBlock() instanceof BaseItemPipeBlock p
                && p.family() == BaseItemPipeBlock.PipeFamily.GOLD;
    }

    /* ===== iron helpers ===== */
    @SuppressWarnings("unused")
    private boolean wouldIronRejectEntry(BlockPos neighborPos, Direction ourForward) {
        if (level == null) return false;
        BlockState ns = level.getBlockState(neighborPos);
        if (!(ns.getBlock() instanceof IronPipeBlock)) return false;
        Direction enteringFrom = ourForward.getOpposite();
        return !IronPipeBlock.canItemEnterFrom(ns, enteringFrom);
    }

    /* ================= EnginePulseAcceptorApi ================= */

    @Override
    public boolean acceptEnginePulse(Direction from) {
        if (level == null || level.isClientSide) return false;
        if (trips.size() >= MAX_TRIPS_PER_PIPE) return false;

        long now = level.getGameTime();
        long last = lastPulseTickBySide.getOrDefault(from, Long.MIN_VALUE);
        if (last == now) return true; // already honored a pulse from this side this tick
        lastPulseTickBySide.put(from, now);

        boolean moved = OneAtATimeItemPuller.pulseExtractOne((Level) level, worldPosition, from);

        if (moved) {
            setChanged();
            requestSyncNow();
        }
        return true; // pulse consumed even if nothing moved
    }
}
