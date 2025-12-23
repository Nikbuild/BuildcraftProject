package com.nick.buildcraft.content.block.quarry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Server-authoritative gantry controller implementing a deterministic FSM.
 *
 * Key design principles:
 * - Server owns ALL positional truth
 * - No randomness in movement calculations
 * - State transitions are explicit and logged
 * - Vertical movement always completes before horizontal traversal
 * - Obstacle scanning uses the new ObstacleScanner utility
 * - Minimal retraction height (just enough to clear obstacles)
 *
 * State machine:
 * IDLE -> SCANNING -> RETRACTING/TRAVERSING -> CLEARANCE_CHECK -> TRAVERSING -> CENTERING -> DEPLOYING -> MINING
 */
public class QuarryGantryManager {

    // Movement constants
    private static final double GANTRY_STEP_BASE = 0.00625; // Base horizontal step per tick
    private static final double VERTICAL_STEP_DIVISOR = 3.0; // Vertical moves slower than horizontal
    private static final double EPS = 0.001; // Position matching tolerance
    private static final double CENTERING_EPS = 0.01; // Looser tolerance for centering
    private static final double CLEARANCE_MARGIN = 0.5; // Safety margin above obstacles

    // Pathfinding constants
    private static final int REPATHER_COOLDOWN_TICKS = 10;
    private static final int UNREACHABLE_TTL = 200;

    /**
     * Main server tick entry point for gantry movement.
     * Implements the FSM with explicit state transitions.
     */
    public static void tickGantry(QuarryBlockEntity qbe, ServerLevel level, BlockPos pos, BlockState state) {
        // Validate bounds
        QuarryGeometryHelper.Bounds b = qbe.getBounds();
        if (b == null) return;

        Direction facing = qbe.getFacing();
        if (facing == null) facing = Direction.NORTH;

        // Initialize gantry position if needed
        initializeGantryIfNeeded(qbe, b, facing);

        // Clamp gantry to valid bounds
        qbe.gantryPos = clampToBounds(qbe.gantryPos, b);

        // Increment state timer
        qbe.ticksInState++;

        // Check for state timeout (failsafe)
        if (qbe.ticksInState > QuarryBlockEntity.MAX_TICKS_PER_STATE) {
            handleStateTimeout(qbe);
            return;
        }

        // Calculate movement speed based on energy
        float speedMultiplier = QuarryEnergyManager.getCurrentSpeed(qbe);
        double horizontalStep = GANTRY_STEP_BASE * speedMultiplier;
        double verticalStep = horizontalStep / VERTICAL_STEP_DIVISOR;

        // Execute current state
        switch (qbe.movementState) {
            case SCANNING -> tickScanning(qbe, level, b, facing);
            case RETRACTING -> tickRetracting(qbe, verticalStep);
            case CLEARANCE_CHECK -> tickClearanceCheck(qbe, level, b);
            case TRAVERSING -> tickTraversing(qbe, level, b, horizontalStep, verticalStep);
            case CENTERING -> tickCentering(qbe, horizontalStep);
            case DEPLOYING -> tickDeploying(qbe, level, b, verticalStep);
            case MINING -> tickMining(qbe);
            case IDLE -> tickIdle(qbe);
        }
    }

    // ========================================================================
    // STATE HANDLERS
    // ========================================================================

    /**
     * SCANNING: Evaluate next horizontal move, scan for obstacles.
     */
    private static void tickScanning(QuarryBlockEntity qbe, ServerLevel level,
                                      QuarryGeometryHelper.Bounds b, Direction facing) {
        // Ensure we have a target
        if (qbe.targetCol == null) {
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.IDLE);
            return;
        }

        // Check if already at target
        BlockPos currentCell = getCurrentCell(qbe, b);
        if (currentCell.getX() == qbe.targetCol.getX() && currentCell.getZ() == qbe.targetCol.getZ()) {
            // At target column, transition to centering/deploying
            if (qbe.gantryLiftY > EPS) {
                // Still lifted, need to deploy first
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.DEPLOYING);
            } else {
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.CENTERING);
            }
            return;
        }

        // Update pathfinding if needed
        updatePathfinding(qbe, level, b, facing, currentCell);

        // Get next position in path
        BlockPos nextPos = getNextPathPosition(qbe);
        if (nextPos == null) {
            // No path available, try to find alternative target
            if (!findAlternativeTarget(qbe, level, b, facing, currentCell)) {
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.IDLE);
            }
            return;
        }

        // Scan for obstacles between current position and next waypoint
        int currentDrillBase = getDrillBaseY(qbe, level, b, currentCell);
        double requiredLift = ObstacleScanner.calculateRequiredLift(
                level, b,
                currentCell.getX(), currentCell.getZ(),
                nextPos.getX(), nextPos.getZ(),
                currentDrillBase
        );

        if (requiredLift > qbe.gantryLiftY + EPS) {
            // Need to retract before moving
            qbe.targetLiftY = requiredLift;
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.RETRACTING);
        } else {
            // Path is clear, can traverse
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.TRAVERSING);
        }
    }

    /**
     * RETRACTING: Raise gantry incrementally until target lift reached.
     */
    private static void tickRetracting(QuarryBlockEntity qbe, double verticalStep) {
        double delta = qbe.targetLiftY - qbe.gantryLiftY;

        if (Math.abs(delta) <= EPS) {
            // Reached target lift, verify clearance
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.CLEARANCE_CHECK);
            return;
        }

        // Move toward target (always up in RETRACTING)
        double step = Math.min(verticalStep, Math.abs(delta));
        qbe.gantryLiftY += step;
    }

    /**
     * CLEARANCE_CHECK: Verify clearance after retraction (catches dynamic obstacles).
     */
    private static void tickClearanceCheck(QuarryBlockEntity qbe, ServerLevel level,
                                            QuarryGeometryHelper.Bounds b) {
        BlockPos currentCell = getCurrentCell(qbe, b);
        BlockPos nextPos = getNextPathPosition(qbe);

        if (nextPos == null) {
            // Path invalidated, return to scanning
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.SCANNING);
            return;
        }

        // Re-scan for obstacles (might have changed during retraction)
        int currentDrillBase = getDrillBaseY(qbe, level, b, currentCell);
        double requiredLift = ObstacleScanner.calculateRequiredLift(
                level, b,
                currentCell.getX(), currentCell.getZ(),
                nextPos.getX(), nextPos.getZ(),
                currentDrillBase
        );

        if (requiredLift > qbe.gantryLiftY + EPS) {
            // New obstacle appeared, need to retract further
            qbe.targetLiftY = Math.max(qbe.targetLiftY, requiredLift);
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.RETRACTING);
        } else {
            // Confirmed clear, can traverse
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.TRAVERSING);
        }
    }

    /**
     * TRAVERSING: Execute horizontal movement after clearance confirmed.
     */
    private static void tickTraversing(QuarryBlockEntity qbe, ServerLevel level,
                                        QuarryGeometryHelper.Bounds b,
                                        double horizontalStep, double verticalStep) {
        if (qbe.targetCol == null) {
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.IDLE);
            return;
        }

        // Get actual sub-block position cell (the cell the gantry is physically in right now)
        int actualCellX = Mth.floor(qbe.gantryPos.x);
        int actualCellZ = Mth.floor(qbe.gantryPos.z);

        BlockPos nextWaypoint = getNextPathPosition(qbe);
        if (nextWaypoint == null) {
            nextWaypoint = qbe.targetCol;
        }

        // Safety check: scan from ACTUAL position to waypoint
        // This catches obstacles in the cell we're currently occupying
        int currentDrillBase = getDrillBaseY(qbe, level, b, new BlockPos(actualCellX, b.y0, actualCellZ));
        double requiredLift = ObstacleScanner.calculateRequiredLift(
                level, b,
                actualCellX, actualCellZ,
                nextWaypoint.getX(), nextWaypoint.getZ(),
                currentDrillBase
        );

        if (requiredLift > qbe.gantryLiftY + EPS) {
            // Obstacle in path, abort and retract
            qbe.targetLiftY = requiredLift;
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.RETRACTING);
            return;
        }

        // DON'T lower while traversing - only lower in DEPLOYING state
        // This prevents clipping when moving over uneven obstacle terrain

        // Calculate horizontal movement
        double targetX = nextWaypoint.getX() + 0.5;
        double targetZ = nextWaypoint.getZ() + 0.5;
        double deltaX = targetX - qbe.gantryPos.x;
        double deltaZ = targetZ - qbe.gantryPos.z;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance > EPS) {
            // Move toward waypoint
            double moveAmount = Math.min(horizontalStep, distance);
            double nx = deltaX / distance;
            double nz = deltaZ / distance;

            double newX = qbe.gantryPos.x + nx * moveAmount;
            double newZ = qbe.gantryPos.z + nz * moveAmount;

            // Clamp to bounds
            newX = Mth.clamp(newX, b.x0 + 1 + 0.01, b.x1 - 1 + 0.99);
            newZ = Mth.clamp(newZ, b.z0 + 1 + 0.01, b.z1 - 1 + 0.99);

            qbe.gantryPos = new Vec3(newX, qbe.gantryPos.y, newZ);
        } else {
            // Reached waypoint, pop from path
            if (!qbe.path.isEmpty()) {
                qbe.path.pollFirst();
            }

            // Check if at final target
            BlockPos newCell = getCurrentCell(qbe, b);
            if (newCell.getX() == qbe.targetCol.getX() && newCell.getZ() == qbe.targetCol.getZ()) {
                // Reached target column
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.CENTERING);
            } else {
                // More waypoints to go, return to scanning
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.SCANNING);
            }
        }
    }

    /**
     * CENTERING: Fine-tune XZ position to exact block center.
     */
    private static void tickCentering(QuarryBlockEntity qbe, double horizontalStep) {
        if (qbe.targetCol == null) {
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.IDLE);
            return;
        }

        double targetX = qbe.targetCol.getX() + 0.5;
        double targetZ = qbe.targetCol.getZ() + 0.5;
        double deltaX = targetX - qbe.gantryPos.x;
        double deltaZ = targetZ - qbe.gantryPos.z;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance > CENTERING_EPS) {
            // Fine-tune position (slower for precision)
            double moveAmount = Math.min(horizontalStep * 0.5, distance);
            double nx = deltaX / distance;
            double nz = deltaZ / distance;

            qbe.gantryPos = new Vec3(
                    qbe.gantryPos.x + nx * moveAmount,
                    qbe.gantryPos.y,
                    qbe.gantryPos.z + nz * moveAmount
            );
        } else {
            // Centered, check if need to deploy
            if (qbe.gantryLiftY > EPS) {
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.DEPLOYING);
            } else {
                // Already at ground level
                transitionTo(qbe, QuarryBlockEntity.GantryMovementState.MINING);
            }
        }
    }

    /**
     * DEPLOYING: Smooth descent back toward working Y after clearing obstacles.
     */
    private static void tickDeploying(QuarryBlockEntity qbe, ServerLevel level,
                                       QuarryGeometryHelper.Bounds b, double verticalStep) {
        if (qbe.gantryLiftY <= EPS) {
            // Fully lowered
            qbe.gantryLiftY = 0.0;
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.MINING);
            return;
        }

        // Check for obstacles below before descending
        BlockPos currentCell = getCurrentCell(qbe, b);
        int checkY = (int)(b.y0 + qbe.gantryLiftY - verticalStep);

        if (ObstacleScanner.isObstacle(level, b, currentCell.getX(), checkY, currentCell.getZ())) {
            // Can't descend further, stay here
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.MINING);
            return;
        }

        // Descend
        qbe.gantryLiftY = Math.max(0.0, qbe.gantryLiftY - verticalStep);
    }

    /**
     * MINING: At target position, ready to mine.
     * QuarryMiningManager handles actual block breaking while we're in this state.
     * When it finishes mining, it sets a NEW targetCol and sets atTarget=false.
     * We detect this and transition back to SCANNING.
     */
    private static void tickMining(QuarryBlockEntity qbe) {
        if (qbe.targetCol == null) {
            qbe.atTarget = false;
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.IDLE);
            return;
        }

        // Check if we're still at target position
        double targetX = qbe.targetCol.getX() + 0.5;
        double targetZ = qbe.targetCol.getZ() + 0.5;

        boolean stillAtTarget = Math.abs(targetX - qbe.gantryPos.x) <= CENTERING_EPS &&
                                Math.abs(targetZ - qbe.gantryPos.z) <= CENTERING_EPS;

        if (stillAtTarget) {
            // We're at target - signal to QuarryMiningManager that it can mine
            qbe.atTarget = true;
        } else {
            // Target changed (QuarryMiningManager set a new targetCol after mining)
            // OR we somehow drifted - transition back to SCANNING to move to new target
            qbe.atTarget = false;
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.SCANNING);
        }
    }

    /**
     * IDLE: Quarry complete, paused, or awaiting target.
     */
    private static void tickIdle(QuarryBlockEntity qbe) {
        qbe.atTarget = false;
        // If we have a target, transition to scanning
        if (qbe.targetCol != null) {
            transitionTo(qbe, QuarryBlockEntity.GantryMovementState.SCANNING);
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private static void transitionTo(QuarryBlockEntity qbe, QuarryBlockEntity.GantryMovementState newState) {
        qbe.movementState = newState;
        qbe.ticksInState = 0;
        qbe.setChanged();
    }

    private static void handleStateTimeout(QuarryBlockEntity qbe) {
        // Force transition to SCANNING to recover
        transitionTo(qbe, QuarryBlockEntity.GantryMovementState.SCANNING);
    }

    private static void initializeGantryIfNeeded(QuarryBlockEntity qbe, QuarryGeometryHelper.Bounds b, Direction facing) {
        if (qbe.gantryPos == null) {
            BlockPos start = nearRightInterior(b, facing);
            int yCeil = b.y1 - 1;
            qbe.gantryPos = new Vec3(start.getX() + 0.5, yCeil + 0.5, start.getZ() + 0.5);
            qbe.targetCol = start.immutable();
            qbe.movementState = QuarryBlockEntity.GantryMovementState.IDLE;
        }

        if (qbe.targetCol == null) {
            qbe.targetCol = nearRightInterior(b, facing);
            qbe.movementState = QuarryBlockEntity.GantryMovementState.IDLE;
        }
    }

    private static Vec3 clampToBounds(Vec3 pos, QuarryGeometryHelper.Bounds b) {
        double minX = b.x0 + 1 + 0.01;
        double maxX = b.x1 - 1 + 0.99;
        double minZ = b.z0 + 1 + 0.01;
        double maxZ = b.z1 - 1 + 0.99;

        return new Vec3(
                Mth.clamp(pos.x, minX, maxX),
                pos.y,
                Mth.clamp(pos.z, minZ, maxZ)
        );
    }

    private static BlockPos getCurrentCell(QuarryBlockEntity qbe, QuarryGeometryHelper.Bounds b) {
        if (qbe.gantryPos == null) {
            return new BlockPos(b.x0 + 1, b.y0, b.z0 + 1);
        }

        int cx = Mth.floor(qbe.gantryPos.x);
        int cz = Mth.floor(qbe.gantryPos.z);
        return new BlockPos(
                Mth.clamp(cx, b.x0 + 1, b.x1 - 1),
                b.y0,
                Mth.clamp(cz, b.z0 + 1, b.z1 - 1)
        );
    }

    private static int getDrillBaseY(QuarryBlockEntity qbe, Level level, QuarryGeometryHelper.Bounds b, BlockPos cell) {
        int columnTop = ObstacleScanner.findColumnTop(level, b, cell.getX(), cell.getZ());
        return Math.max(columnTop + 1, b.y0);
    }

    private static BlockPos nearRightInterior(QuarryGeometryHelper.Bounds b, Direction facing) {
        int xMin = b.x0 + 1, xMax = b.x1 - 1;
        int zMin = b.z0 + 1, zMax = b.z1 - 1;
        return switch (facing) {
            case NORTH -> new BlockPos(xMax, b.y0, zMax);
            case SOUTH -> new BlockPos(xMin, b.y0, zMin);
            case EAST  -> new BlockPos(xMin, b.y0, zMax);
            case WEST  -> new BlockPos(xMax, b.y0, zMin);
            default    -> new BlockPos(xMax, b.y0, zMax);
        };
    }

    // ========================================================================
    // PATHFINDING
    // ========================================================================

    private static void updatePathfinding(QuarryBlockEntity qbe, ServerLevel level,
                                           QuarryGeometryHelper.Bounds b, Direction facing,
                                           BlockPos currentCell) {
        boolean needPlan = false;

        if (qbe.pathTarget == null || !qbe.pathTarget.equals(qbe.targetCol)) {
            needPlan = true;
        } else if (--qbe.repathCooldown <= 0) {
            needPlan = true;
        } else if (!qbe.path.isEmpty()) {
            BlockPos next = qbe.path.peekFirst();
            if (next != null && next.equals(currentCell)) {
                qbe.path.pollFirst();
                if (qbe.path.isEmpty()) {
                    needPlan = true;
                }
            }
        } else if (!currentCell.equals(qbe.targetCol)) {
            needPlan = true;
        }

        if (needPlan) {
            qbe.path = planPath(level, b, currentCell, qbe.targetCol);
            qbe.pathTarget = qbe.targetCol;
            qbe.repathCooldown = REPATHER_COOLDOWN_TICKS;
        }
    }

    private static BlockPos getNextPathPosition(QuarryBlockEntity qbe) {
        if (qbe.path != null && !qbe.path.isEmpty()) {
            return qbe.path.peekFirst();
        }
        return qbe.targetCol;
    }

    private static boolean findAlternativeTarget(QuarryBlockEntity qbe, ServerLevel level,
                                                   QuarryGeometryHelper.Bounds b, Direction facing,
                                                   BlockPos currentCell) {
        // Mark current target as temporarily unreachable
        long now = level.getGameTime();
        pruneTempSkip(qbe, now);
        qbe.tempSkip.put(qbe.targetCol.immutable(), now + UNREACHABLE_TTL);

        // Try zigzag forward first
        int miningY = qbe.layerY != null ? qbe.layerY : (b.y0 - 1);
        BlockPos zigzagNext = QuarryMiningManager.findNextOnLayerForward(
                level, b, facing, qbe.targetCol, miningY, qbe);

        while (zigzagNext != null && isSkipped(qbe, zigzagNext, now)) {
            zigzagNext = QuarryMiningManager.findNextOnLayerForward(
                    level, b, facing, zigzagNext, miningY, qbe);
        }

        if (zigzagNext != null) {
            BlockPos newTarget = new BlockPos(zigzagNext.getX(), b.y0, zigzagNext.getZ());
            ArrayDeque<BlockPos> testPath = planPath(level, b, currentCell, newTarget);
            if (!testPath.isEmpty()) {
                qbe.targetCol = newTarget;
                qbe.path = testPath;
                qbe.pathTarget = newTarget;
                return true;
            }
        }

        // Expanding ring search for any reachable block
        BlockPos closest = findClosestReachable(level, b, currentCell, qbe, now, miningY);
        if (closest != null) {
            qbe.targetCol = closest;
            qbe.path = planPath(level, b, currentCell, closest);
            qbe.pathTarget = closest;
            return true;
        }

        return false;
    }

    private static BlockPos findClosestReachable(ServerLevel level, QuarryGeometryHelper.Bounds b,
                                                   BlockPos start, QuarryBlockEntity qbe,
                                                   long now, int miningY) {
        int xMin = b.x0 + 1, xMax = b.x1 - 1;
        int zMin = b.z0 + 1, zMax = b.z1 - 1;
        int maxRadius = Math.max(xMax - xMin, zMax - zMin);

        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) != radius) continue;

                    int x = start.getX() + dx;
                    int z = start.getZ() + dz;

                    if (x < xMin || x > xMax || z < zMin || z > zMax) continue;

                    BlockPos candidate = new BlockPos(x, b.y0, z);
                    if (isSkipped(qbe, candidate, now)) continue;
                    if (ObstacleScanner.isColumnBlockedAtCeiling(level, b, x, z)) continue;

                    BlockPos minePos = new BlockPos(x, miningY, z);
                    if (level.isEmptyBlock(minePos)) continue;
                    if (!QuarryMiningManager.shouldMine(level, minePos)) continue;

                    ArrayDeque<BlockPos> testPath = planPath(level, b, start, candidate);
                    if (!testPath.isEmpty()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A* pathfinding implementation.
     */
    private static ArrayDeque<BlockPos> planPath(ServerLevel level, QuarryGeometryHelper.Bounds b,
                                                   BlockPos start, BlockPos goal) {
        ArrayDeque<BlockPos> empty = new ArrayDeque<>();

        if (start == null || goal == null) return empty;

        int xMin = b.x0 + 1, xMax = b.x1 - 1;
        int zMin = b.z0 + 1, zMax = b.z1 - 1;

        // Validate goal is within bounds
        if (goal.getX() < xMin || goal.getX() > xMax ||
            goal.getZ() < zMin || goal.getZ() > zMax) {
            return empty;
        }

        // Skip if goal column is impassable
        if (ObstacleScanner.isColumnImpassable(level, b, goal.getX(), goal.getZ(), b.y0)) {
            return empty;
        }

        record Node(int x, int z) {}

        Map<Node, Integer> g = new HashMap<>();
        Map<Node, Integer> f = new HashMap<>();
        Map<Node, Node> came = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingInt(n -> f.getOrDefault(n, Integer.MAX_VALUE)));

        Node s = new Node(start.getX(), start.getZ());
        Node t = new Node(goal.getX(), goal.getZ());

        g.put(s, 0);
        f.put(s, manhattanDistance(s, t));
        open.add(s);

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!open.isEmpty()) {
            Node current = open.poll();

            if (current.equals(t)) {
                // Reconstruct path
                ArrayDeque<BlockPos> path = new ArrayDeque<>();
                Node node = t;
                while (!node.equals(s)) {
                    path.addFirst(new BlockPos(node.x, b.y0, node.z));
                    node = came.get(node);
                    if (node == null) {
                        return empty; // Path reconstruction failed
                    }
                }
                return path;
            }

            int currentG = g.get(current);

            for (int[] dir : directions) {
                int nx = current.x + dir[0];
                int nz = current.z + dir[1];

                // Bounds check
                if (nx < xMin || nx > xMax || nz < zMin || nz > zMax) continue;

                // Skip impassable columns
                if (ObstacleScanner.isColumnImpassable(level, b, nx, nz, b.y0)) continue;

                Node neighbor = new Node(nx, nz);
                int tentativeG = currentG + 1;

                if (tentativeG < g.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                    came.put(neighbor, current);
                    g.put(neighbor, tentativeG);
                    f.put(neighbor, tentativeG + manhattanDistance(neighbor, t));

                    // Update priority queue
                    open.remove(neighbor);
                    open.add(neighbor);
                }
            }
        }

        return empty; // No path found
    }

    private static int manhattanDistance(Object n1, Object n2) {
        record Node(int x, int z) {}
        if (n1 instanceof Node a && n2 instanceof Node b) {
            return Math.abs(a.x - b.x) + Math.abs(a.z - b.z);
        }
        return 0;
    }

    private static void pruneTempSkip(QuarryBlockEntity qbe, long now) {
        qbe.tempSkip.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private static boolean isSkipped(QuarryBlockEntity qbe, BlockPos p, long now) {
        if (qbe.tempSkip == null || p == null) return false;
        Long until = qbe.tempSkip.get(p);
        return until != null && until > now;
    }

    // ========================================================================
    // PUBLIC API (for other managers)
    // ========================================================================

    /**
     * Check if a column is blocked at ceiling level.
     * Used by QuarryMiningManager and QuarrySweepManager.
     */
    public static boolean columnBlockedAtCeiling(Level level, QuarryGeometryHelper.Bounds b, int x, int z) {
        return ObstacleScanner.isColumnBlockedAtCeiling(level, b, x, z);
    }

    /**
     * Find the top Y of obstacles in a column.
     * Used by QuarryRenderer for drill positioning.
     */
    public static int findObstacleTopY(Level level, QuarryGeometryHelper.Bounds b, int x, int z) {
        return ObstacleScanner.findHighestObstacle(level, b, x, z, b.y0);
    }
}
