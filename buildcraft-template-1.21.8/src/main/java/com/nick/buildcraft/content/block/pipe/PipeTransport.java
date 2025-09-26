// src/main/java/com/nick/buildcraft/content/block/pipe/PipeTransport.java
package com.nick.buildcraft.content.block.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;

/**
 * Lightweight BFS router:
 *  - walks only through connected pipe blocks
 *  - the first adjacent non-pipe with an ItemHandler is the sink
 *  - returns intermediate pipe nodes AFTER the start, plus sink position/face
 *  - respects DiamondPipe filters (color rows -> directions)
 *  - NEW: respects border firewalls (won't step into a neighbor pipe that would reject entry)
 */
public final class PipeTransport {

    public record Path(List<BlockPos> nodes, BlockPos sinkPos, Direction sinkFace) {}

    private static final int MAX_VISITED = 512;

    private PipeTransport() {}

    /** Backwards-compatible wrapper (no filtering if stack unknown). */
    public static Path findPath(Level level, BlockPos start, Direction receivedFrom) {
        return findPath(level, start, receivedFrom, ItemStack.EMPTY);
    }

    /** Routing with knowledge of the moving stack (enables DiamondPipe filtering + border firewall). */
    public static Path findPath(Level level, BlockPos start, Direction receivedFrom, ItemStack movingStack) {
        if (level == null || level.isClientSide) return null;

        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Map<BlockPos, BlockPos> came = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();

        q.add(start);
        visited.add(start);

        while (!q.isEmpty() && visited.size() < MAX_VISITED) {
            BlockPos cur = q.pollFirst();
            BlockState curState = level.getBlockState(cur);
            if (!(curState.getBlock() instanceof BaseItemPipeBlock)) continue;

            // Allowed directions at this node (diamond filter constraint)
            EnumSet<Direction> allowedHere = allowedDirections(level, cur, movingStack);

            for (Direction d : Direction.values()) {
                // avoid instant U-turn at the entry pipe
                if (cur.equals(start) && d == receivedFrom) continue;
                // respect Diamond filter at this node
                if (!allowedHere.contains(d)) continue;

                BlockPos nbr = cur.relative(d);

                // Continue BFS into a pipe only if both sides connect AND the neighbor wouldn't reject entry
                if (isPipe(level, nbr) && connects(curState, d) && connects(level.getBlockState(nbr), d.getOpposite())) {
                    // BORDER FIREWALL: don't traverse into a pipe that would reject from this side
                    if (neighborRejectsAtBorder(level, nbr, d.getOpposite(), movingStack)) {
                        continue;
                    }
                    if (visited.add(nbr)) {
                        came.put(nbr, cur);
                        q.addLast(nbr);
                    }
                    continue;
                }

                // non-pipe neighbor: check for insertable item handler; also require this side is open
                if (!isPipe(level, nbr) && connects(curState, d)) {
                    IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, nbr, d.getOpposite());
                    if (handler != null) {
                        List<BlockPos> route = reconstruct(came, start, cur);
                        return new Path(route, nbr.immutable(), d.getOpposite());
                    }
                }
            }
        }
        return null;
    }

    /* ---------------- Diamond filter hook ---------------- */

    private static EnumSet<Direction> allowedDirections(Level level, BlockPos pos, ItemStack stack) {
        var be = level.getBlockEntity(pos);
        if (be instanceof DiamondPipeBlockEntity dp) {
            var allowed = dp.getAllowedDirections(stack);
            // empty = "no restriction" -> all dirs allowed
            if (!allowed.isEmpty()) return allowed;
        }
        return EnumSet.allOf(Direction.class);
    }

    /* ---------------- Border firewall hook ---------------- */

    /** True if the neighbor pipe would reject an item entering from the given face. */
    private static boolean neighborRejectsAtBorder(Level level, BlockPos neighborPos, Direction enterFrom, ItemStack stack) {
        var be = level.getBlockEntity(neighborPos);
        if (be instanceof StonePipeBlockEntity n) {
            return n.shouldRejectAtBorder(enterFrom, stack);
        }
        return false;
    }

    /* ---------------- helpers ---------------- */

    private static boolean isPipe(Level level, BlockPos p) {
        return level.getBlockState(p).getBlock() instanceof BaseItemPipeBlock;
    }

    private static boolean connects(BlockState state, Direction dir) {
        if (!(state.getBlock() instanceof BaseItemPipeBlock)) return false;
        return switch (dir) {
            case NORTH -> state.hasProperty(BaseItemPipeBlock.NORTH) && state.getValue(BaseItemPipeBlock.NORTH);
            case SOUTH -> state.hasProperty(BaseItemPipeBlock.SOUTH) && state.getValue(BaseItemPipeBlock.SOUTH);
            case EAST  -> state.hasProperty(BaseItemPipeBlock.EAST)  && state.getValue(BaseItemPipeBlock.EAST);
            case WEST  -> state.hasProperty(BaseItemPipeBlock.WEST)  && state.getValue(BaseItemPipeBlock.WEST);
            case UP    -> state.hasProperty(BaseItemPipeBlock.UP)    && state.getValue(BaseItemPipeBlock.UP);
            case DOWN  -> state.hasProperty(BaseItemPipeBlock.DOWN)  && state.getValue(BaseItemPipeBlock.DOWN);
        };
    }

    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> came, BlockPos start, BlockPos lastPipe) {
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        BlockPos cur = lastPipe;
        while (cur != null && !cur.equals(start)) {
            stack.addFirst(cur);
            cur = came.get(cur);
        }
        return new ArrayList<>(stack);
    }
}
