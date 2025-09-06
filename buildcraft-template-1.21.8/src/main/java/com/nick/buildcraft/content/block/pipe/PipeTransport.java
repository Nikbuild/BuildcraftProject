package com.nick.buildcraft.content.block.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;

/**
 * Tiny BFS router for the item pipe network.
 *
 * - Starts at a source pipe and explores adjacent pipe nodes (skipping the side we received from).
 * - The first adjacent non-pipe that exposes an ItemHandler on the touching face is treated as the sink.
 * - Returns the list of intermediate pipe nodes AFTER the start pipe, plus the sink position & face.
 *
 * Intentionally lightweight and server-side only.
 */
public final class PipeTransport {

    /** Result returned to the pipe. */
    public record Path(List<BlockPos> nodes, BlockPos sinkPos, Direction sinkFace) {}

    private static final int MAX_VISITED = 512; // simple safety

    private PipeTransport() {}

    /**
     * @param level The world (server).
     * @param start The pipe BE world position that is starting the trip.
     * @param receivedFrom Side of {@code start} where the item entered (avoid immediately going back).
     */
    public static Path findPath(Level level, BlockPos start, Direction receivedFrom) {
        if (level == null || level.isClientSide) return null;

        // BFS over connected pipe blocks
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Map<BlockPos, BlockPos> came = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();

        q.add(start);
        visited.add(start);

        while (!q.isEmpty() && visited.size() < MAX_VISITED) {
            BlockPos cur = q.pollFirst();
            BlockState curState = level.getBlockState(cur);
            if (!(curState.getBlock() instanceof BaseItemPipeBlock)) continue;

            // Explore 6 neighbors
            for (Direction d : Direction.values()) {
                // 1) Don’t immediately go back into the face we received from at the start
                if (cur.equals(start) && d == receivedFrom) continue;

                BlockPos nbr = cur.relative(d);

                // If neighbor is a pipe AND both sides have arms toward each other, expand BFS
                if (isPipe(level, nbr) && connects(curState, d) && connects(level.getBlockState(nbr), d.getOpposite())) {
                    if (visited.add(nbr)) {
                        came.put(nbr, cur);
                        q.addLast(nbr);
                    }
                    continue;
                }

                // Otherwise, if neighbor is NOT a pipe: check for an insertable handler on that face
                if (!isPipe(level, nbr)) {
                    IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, nbr, d.getOpposite());
                    if (handler != null) {
                        // Found a sink touching 'cur' via direction 'd'
                        List<BlockPos> route = reconstruct(came, start, cur);
                        return new Path(route, nbr.immutable(), d.getOpposite());
                    }
                }
            }
        }
        return null; // no route to an inventory found
    }

    /* -------------------------- helpers -------------------------- */

    private static boolean isPipe(Level level, BlockPos p) {
        return level.getBlockState(p).getBlock() instanceof BaseItemPipeBlock;
    }

    /** Use BaseItemPipeBlock’s boolean properties to verify an arm in that direction exists. */
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

    /** Rebuild the list of pipe nodes after 'start' up to and including 'lastPipe'. */
    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> came, BlockPos start, BlockPos lastPipe) {
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        BlockPos cur = lastPipe;
        while (cur != null && !cur.equals(start)) {
            stack.addFirst(cur);
            cur = came.get(cur);
        }
        return new ArrayList<>(stack); // nodes AFTER start
    }
}
