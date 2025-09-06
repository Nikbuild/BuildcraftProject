package com.nick.buildcraft.content.block.pipe;

import com.mojang.serialization.Codec;
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

import java.util.*;

/**
 * Single-item transport pipe with client animation.
 * Policy:
 *  - **Right-of-way**: always continue straight if possible.
 *  - Avoid U-turn as first hop.
 *  - If no sink exists: head to an open end; if none (pure loop), pick a farthest node and eject.
 *  - Hop TTL to avoid infinite oscillation.
 */
public class StonePipeBlockEntity extends BlockEntity {

    /* ---------------- tuning ---------------- */
    private static final int   SLOTS_PER_PIPE = 24;
    private static final int   TICKS_PER_SLOT = 1;
    private static final int   HANDOFF_RETRY_TICKS = 10;
    private static final int   CUT_HIDE_TICKS = 2;
    private static final int   OPEN_END_BFS_LIMIT = 1024;
    private static final int   HOP_TTL_MAX = 256;

    /* ------------- state (trip) ------------- */
    private ItemStack cargo = ItemStack.EMPTY;

    private final List<BlockPos> route = new ArrayList<>();
    private int routeIndex = 0;

    private BlockPos sinkPos = null;
    private Direction sinkFace = null;

    private float segmentProgress = 0f;

    private Direction receivedFrom = null; // neighbor we got the item from
    private boolean sourceForTrip = false;

    private int slotIndex = 0;
    private int slotTick  = 0;

    private int handoffBackoff = 0;
    private int cutHideCooldown = 0;
    private int hopTTL = HOP_TTL_MAX;

    public StonePipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntity.STONE_PIPE.get(), pos, state);
    }


    /* -------------- API: offer -------------- */
    public ItemStack offer(ItemStack stack, Direction from) {
        if (level == null || level.isClientSide || stack.isEmpty() || hasTrip()) return stack;

        int canAccept = Math.min(stack.getCount(), stack.getMaxStackSize());
        if (canAccept <= 0) return stack;

        cargo = stack.copy();
        cargo.setCount(canAccept);

        ItemStack leftover = stack.copy();
        leftover.shrink(canAccept);

        receivedFrom = from;
        sourceForTrip = true;

        rebuildRoute();

        slotIndex = slotTick = 0;
        segmentProgress = 0f;
        handoffBackoff = 0;
        cutHideCooldown = 0;
        hopTTL = HOP_TTL_MAX;

        setChanged();
        requestSyncNow();
        return leftover;
    }

    private boolean hasTrip() { return !cargo.isEmpty(); }

    /* ------- neighbor graph immediate cut ---- */
    public void onNeighborGraphChanged(BlockPos fromPos) {
        if (level == null || level.isClientSide || !hasTrip()) return;

        boolean affects = (routeIndex < route.size() && route.get(routeIndex).equals(fromPos));
        if (!affects) {
            Direction out = getOutgoingDirOrNull();
            if (out != null && worldPosition.relative(out).equals(fromPos)) affects = true;
        }

        if (affects) {
            route.clear();
            routeIndex = 0;
            sinkPos = null;
            sinkFace = null;

            slotIndex = Math.min(slotIndex, SLOTS_PER_PIPE - 1);
            segmentProgress = slotIndex / (float) SLOTS_PER_PIPE;
            cutHideCooldown = CUT_HIDE_TICKS;

            requestSyncNow();
        }
    }

    /* -------------------- tick -------------------- */
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
        if (level == null || level.isClientSide || !hasTrip()) return;

        if (cutHideCooldown > 0) cutHideCooldown--;

        validateNextHopAndMaybeReroute();

        if (++slotTick < TICKS_PER_SLOT) return;
        slotTick = 0;

        if (++slotIndex <= SLOTS_PER_PIPE) {
            segmentProgress = slotIndex / (float) SLOTS_PER_PIPE;
            requestSyncNow();
            return;
        }

        // segment boundary
        slotIndex = 0;
        segmentProgress = 0f;

        if (--hopTTL <= 0) { abortAndDrop(); return; }

        // route → handoff
        if (routeIndex < route.size()) {
            BlockPos nextPipePos = route.get(routeIndex);
            if (!isValidPipeAt(nextPipePos)) {
                rebuildRoute();
                requestSyncNow();
                return;
            }

            BlockEntity be = level.getBlockEntity(nextPipePos);
            if (be instanceof StonePipeBlockEntity next && next.receiveFromGuide(cargo, worldPosition)) {
                routeIndex++;
                clearTrip();
                handoffBackoff = 0;
                requestSyncNow();
                return;
            }

            if (handoffBackoff++ < HANDOFF_RETRY_TICKS) {
                rebuildRoute();
                requestSyncNow();
                return;
            }

            handoffBackoff = 0;
            abortAndDrop();
            return;
        }

        // sink → insert
        if (sinkPos != null && sinkFace != null) {
            IItemHandler dst = level.getCapability(Capabilities.ItemHandler.BLOCK, sinkPos, sinkFace);
            if (dst != null) {
                ItemStack leftover = ItemHandlerHelper.insertItem(dst, cargo.copy(), false);
                if (leftover.isEmpty()) { clearTrip(); requestSyncNow(); return; }
                cargo = leftover; requestSyncNow(); return;
            }
        }

        // otherwise eject at nozzle
        abortAndDrop();
    }

    private boolean receiveFromGuide(ItemStack stack, BlockPos upstreamPos) {
        if (hasTrip()) return false;

        cargo = stack.copy();
        cargo.setCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
        sourceForTrip = false;
        receivedFrom = directionFrom(upstreamPos);

        rebuildRoute();

        slotIndex = slotTick = 0;
        segmentProgress = 0f;
        handoffBackoff = 0;
        cutHideCooldown = 0;
        hopTTL = HOP_TTL_MAX;

        setChanged();
        requestSyncNow();
        return true;
    }

    private Direction directionFrom(BlockPos neighbor) {
        for (Direction d : Direction.values()) if (worldPosition.relative(d).equals(neighbor)) return d;
        return null;
    }

    private void abortAndDrop() {
        if (level != null && !cargo.isEmpty()) {
            Direction out = getOutgoingDirOrNull();
            BlockPos dropPos = (out != null) ? worldPosition.relative(out) : worldPosition;
            Block.popResource(level, dropPos, cargo);
        }
        clearTrip();
        requestSyncNow();
    }

    private void clearTrip() {
        cargo = ItemStack.EMPTY;
        sourceForTrip = false;
        receivedFrom = null;
        segmentProgress = 0f;
        slotIndex = slotTick = 0;
        route.clear();
        routeIndex = 0;
        sinkPos = null;
        sinkFace = null;
        handoffBackoff = 0;
        cutHideCooldown = 0;
        setChanged();
    }

    /* ---------- client hooks ---------- */
    public ItemStack getCargoForRender() { return cargo; }
    public float getSegmentProgress() { return segmentProgress; }
    public BlockPos getBlockPos() { return worldPosition; }
    public BlockPos getNextPipeOrNull() {
        if (routeIndex < route.size()) {
            BlockPos p = route.get(routeIndex);
            if (!isValidPipeAt(p)) return null;
            return p;
        }
        return null;
    }
    public boolean isHeadingToSink() { return routeIndex >= route.size() && sinkPos != null; }
    public BlockPos getSinkPos() { return sinkPos; }
    public Direction getOutgoingDirOrNull() { return receivedFrom != null ? receivedFrom.getOpposite() : null; }
    public boolean shouldHideForCut() { return cutHideCooldown > 0; }

    /* ---------- sync / save ---------- */
    private void requestSyncNow() {
        if (level == null || level.isClientSide) return;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        out.store("has", Codec.BOOL, hasTrip());
        out.putInt("src", sourceForTrip ? 1 : 0);
        out.putInt("ri", routeIndex);
        out.store("prog", Codec.FLOAT, segmentProgress);
        out.putInt("rf", receivedFrom == null ? -1 : receivedFrom.get3DDataValue());

        out.putInt("slotIndex", slotIndex);
        out.putInt("slotTick", slotTick);
        out.putInt("handoffBackoff", handoffBackoff);
        out.putInt("cutHide", cutHideCooldown);
        out.putInt("ttl", hopTTL);

        if (!cargo.isEmpty()) out.store("cargo", ItemStack.CODEC, cargo);

        if (sinkPos != null) {
            out.putInt("sx", sinkPos.getX());
            out.putInt("sy", sinkPos.getY());
            out.putInt("sz", sinkPos.getZ());
            out.putInt("sf", sinkFace == null ? -1 : sinkFace.get3DDataValue());
        }

        out.putInt("rc", route.size());
        for (int i = 0; i < route.size(); i++) {
            BlockPos p = route.get(i);
            out.putInt("rx" + i, p.getX());
            out.putInt("ry" + i, p.getY());
            out.putInt("rz" + i, p.getZ());
        }
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        boolean has = in.getInt("has").orElse(0) != 0;
        sourceForTrip = in.getInt("src").orElse(0) != 0;
        routeIndex = in.getInt("ri").orElse(0);
        segmentProgress = in.read("prog", Codec.FLOAT).orElse(0f);
        int rf = in.getInt("rf").orElse(-1);
        receivedFrom = (rf >= 0 && rf < 6) ? Direction.from3DDataValue(rf) : null;

        slotIndex = in.getInt("slotIndex").orElse(0);
        slotTick = in.getInt("slotTick").orElse(0);
        handoffBackoff = in.getInt("handoffBackoff").orElse(0);
        cutHideCooldown = in.getInt("cutHide").orElse(0);
        hopTTL = in.getInt("ttl").orElse(HOP_TTL_MAX);

        cargo = has ? in.read("cargo", ItemStack.CODEC).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;

        int rc = in.getInt("rc").orElse(0);
        route.clear();
        for (int i = 0; i < rc; i++) {
            int x = in.getInt("rx" + i).orElse(0);
            int y = in.getInt("ry" + i).orElse(0);
            int z = in.getInt("rz" + i).orElse(0);
            route.add(new BlockPos(x, y, z));
        }

        Integer sx = in.getInt("sx").orElse(null);
        Integer sy = in.getInt("sy").orElse(null);
        Integer sz = in.getInt("sz").orElse(null);
        int sf = in.getInt("sf").orElse(-1);

        if (sx != null && sy != null && sz != null) {
            sinkPos = new BlockPos(sx, sy, sz);
            sinkFace = (sf >= 0 && sf < 6) ? Direction.from3DDataValue(sf) : null;
        } else {
            sinkPos = null;
            sinkFace = null;
        }
    }

    /* ------------- internals: routing ------------- */

    private boolean isValidPipeAt(BlockPos pos) {
        if (level == null) return false;
        return level.getBlockEntity(pos) instanceof StonePipeBlockEntity;
    }

    /** Rebuilds route obeying RIGHT-OF-WAY (straight if possible). */
    private void rebuildRoute() {
        if (level == null) return;

        route.clear();
        routeIndex = 0;
        sinkPos = null;
        sinkFace = null;

        // 0) RIGHT-OF-WAY: continue straight if there’s a pipe ahead
        Direction forward = getOutgoingDirOrNull();
        if (forward != null) {
            BlockPos ahead = worldPosition.relative(forward);

            // straight into a pipe? take that single hop and stop planning
            if (isValidPipeAt(ahead)) {
                route.add(ahead);
                return;
            }

            // straight into an inventory? set sink immediately
            IItemHandler straightHandler = getHandler(ahead, forward.getOpposite());
            if (straightHandler != null) {
                sinkPos = ahead;
                sinkFace = forward.getOpposite();
                return; // no intermediate pipes
            }
        }

        // 1) Prefer true sinks using global pathfinder (may turn)
        var path = PipeTransport.findPath(level, worldPosition, receivedFrom);
        if (path != null) {
            route.addAll(path.nodes());
            sinkPos = path.sinkPos();
            sinkFace = path.sinkFace();
            return;
        }

        // 2) No sink → head to nearest open end; if none, farthest node (loop breaker)
        List<BlockPos> toEnd = findOpenEndRoute((Level) level, worldPosition, receivedFrom);
        if (!toEnd.isEmpty()) {
            route.addAll(toEnd);
        }
        // else: no route → eject at this pipe when segment completes
    }

    private void validateNextHopAndMaybeReroute() {
        if (routeIndex < route.size()) {
            BlockPos next = route.get(routeIndex);
            if (!isValidPipeAt(next)) { rebuildRoute(); requestSyncNow(); }
        }
    }

    /** BFS to nearest terminal (excluding parent). If none, pick farthest node to break loops. */
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
            int forwardCount = 0;

            for (Direction d : Direction.values()) {
                BlockPos np = cur.relative(d);
                if (!isValidPipeAt(np)) continue;

                // avoid U-turn as first hop
                if (cur.equals(start) && fromDir != null && np.equals(start.relative(fromDir))) continue;

                if (par != null && np.equals(par)) continue;
                forwardCount++;

                if (visited.add(np)) {
                    parent.put(np, cur);
                    dist.put(np, dist.getOrDefault(cur, 0) + 1);
                    q.addLast(np);
                }
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

    // NeoForge helper
    private IItemHandler getHandler(BlockPos pos, Direction face) {
        if (level == null) return null;
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, be, face);
    }
}
