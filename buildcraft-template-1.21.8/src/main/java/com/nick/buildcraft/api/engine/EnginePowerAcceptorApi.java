// src/main/java/com/nick/buildcraft/api/engine/EnginePowerAcceptorApi.java
package com.nick.buildcraft.api.engine;

import net.minecraft.core.Direction;

/** Neighbor blocks can implement this to receive an engine pulse. */
public interface EnginePowerAcceptorApi {
    /**
     * Called by an engine on the neighbor it's facing.
     * @param from  direction from the acceptor back toward the engine
     * @param power integer power value (e.g., engine’s getGenerationPerTick())
     */
    void acceptEnginePower(Direction from, int power);
}
