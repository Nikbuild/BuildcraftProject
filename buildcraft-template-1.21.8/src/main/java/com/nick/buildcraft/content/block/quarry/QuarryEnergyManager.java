package com.nick.buildcraft.content.block.quarry;

/**
 * Energy-based speed control for the quarry.
 * Gantry speed scales with energy inflow rate from engines.
 * More engines or higher RPM engines = faster quarry movement.
 */
public class QuarryEnergyManager {

    // Speed tiers based on energy inflow (FE/tick) - LOWERED for better feel
    private static final int ENERGY_TIER_1 = 5;    // Tiny bit of power (min speed)
    private static final int ENERGY_TIER_2 = 15;   // Single slow engine (base speed)
    private static final int ENERGY_TIER_3 = 40;   // 1-2 engines (2x speed)
    private static final int ENERGY_TIER_4 = 80;   // 2-3 engines (4x speed - max)

    // HIGH multipliers for ultra-fine steps - maximum smoothness with good speed!
    private static final float SPEED_MIN = 8.0f;     // 8x speed at minimum power (~20 updates/block)
    private static final float SPEED_BASE = 16.0f;   // 16x speed at tier 2 (~10 updates/block)
    private static final float SPEED_FAST = 32.0f;   // 32x speed at tier 3 (~5 updates/block)
    private static final float SPEED_MAX = 48.0f;    // 48x speed at tier 4 (~3 updates/block)

    /**
     * Check if quarry has energy to work.
     */
    public static boolean hasWorkPower(QuarryBlockEntity qbe) {
        return qbe.energy.getEnergyStored() > 0;
    }

    /**
     * Update quarry speed based on energy inflow rate.
     * Called every tick to track energy changes and adjust target speed.
     */
    public static void updateSpeed(QuarryBlockEntity qbe) {
        int currentEnergy = qbe.energy.getEnergyStored();

        // Calculate energy inflow this tick (could be negative if draining faster than filling)
        int energyDelta = currentEnergy - qbe.energyLastTick;
        qbe.energyLastTick = currentEnergy;

        // Calculate target speed based on inflow rate
        // Ignore negative delta (we're always draining 50 FE/tick, so add that back)
        int inflowRate = energyDelta + com.nick.buildcraft.energy.Energy.QUARRY_DRAIN_PER_TICK;
        inflowRate = Math.max(0, inflowRate); // Clamp to 0 minimum

        // Map inflow rate to speed multiplier
        if (inflowRate <= 0) {
            qbe.targetSpeed = 0.0f; // No power = stop
        } else if (inflowRate < ENERGY_TIER_1) {
            // Very low power: interpolate from 0 to SPEED_MIN
            qbe.targetSpeed = SPEED_MIN * (inflowRate / (float)ENERGY_TIER_1);
        } else if (inflowRate < ENERGY_TIER_2) {
            // Low power: interpolate from SPEED_MIN to SPEED_BASE
            float t = (inflowRate - ENERGY_TIER_1) / (float)(ENERGY_TIER_2 - ENERGY_TIER_1);
            qbe.targetSpeed = SPEED_MIN + t * (SPEED_BASE - SPEED_MIN);
        } else if (inflowRate < ENERGY_TIER_3) {
            // Medium power: interpolate from SPEED_BASE to SPEED_FAST
            float t = (inflowRate - ENERGY_TIER_2) / (float)(ENERGY_TIER_3 - ENERGY_TIER_2);
            qbe.targetSpeed = SPEED_BASE + t * (SPEED_FAST - SPEED_BASE);
        } else if (inflowRate < ENERGY_TIER_4) {
            // High power: interpolate from SPEED_FAST to SPEED_MAX
            float t = (inflowRate - ENERGY_TIER_3) / (float)(ENERGY_TIER_4 - ENERGY_TIER_3);
            qbe.targetSpeed = SPEED_FAST + t * (SPEED_MAX - SPEED_FAST);
        } else {
            // Max power: cap at SPEED_MAX
            qbe.targetSpeed = SPEED_MAX;
        }

        // Accelerate/decelerate toward target speed
        // Acceleration rate scales with target speed (more power = faster acceleration!)
        if (qbe.currentSpeed < qbe.targetSpeed) {
            // Accelerate - rate scales with how much power you have
            // Base rate: 0.02 at low power, up to 0.15 at max power
            float baseAccel = 0.02f + (qbe.targetSpeed / SPEED_MAX) * 0.13f;

            // Extra slow start from dead stop for realism
            if (qbe.currentSpeed < SPEED_MIN * 0.5f) {
                baseAccel *= 0.5f; // 50% slower when starting from stop
            }

            qbe.currentSpeed = Math.min(qbe.currentSpeed + baseAccel, qbe.targetSpeed);
        } else if (qbe.currentSpeed > qbe.targetSpeed) {
            // Decelerate - faster deceleration (braking is easier than accelerating)
            float decelRate = 0.1f;
            qbe.currentSpeed = Math.max(qbe.currentSpeed - decelRate, qbe.targetSpeed);
        }
    }

    /**
     * Get current speed multiplier for gantry movement.
     * @return Speed multiplier (0.0 = stopped, 1.0 = base speed, 4.0 = max speed)
     */
    public static float getCurrentSpeed(QuarryBlockEntity qbe) {
        return qbe.currentSpeed;
    }
}
