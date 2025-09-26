package com.nick.buildcraft.api.engine;

import net.minecraft.core.Direction;

/**
 * Instantaneous energy sink. Implement for machines like the Quarry
 * that do not buffer energy but accelerate while energy is being fed.
 */
public interface InstantEnergySinkApi {
    /**
     * @param from   Side on the sink that faces the engine.
     * @param amount Energy units in this packet.
     * @return amount actually accepted (0..amount).
     */
    int acceptInstantEnergy(Direction from, int amount);
}
