package com.nick.buildcraft.api.engine;

import net.minecraft.core.Direction;

/**
 * Receives a single mechanical stroke pulse from an engine.
 * One pulse MUST cause at most one extraction/operation.
 * No energy semantics are implied by this interface.
 */
public interface EnginePulseAcceptorApi {
    /**
     * @param from Side on the acceptor that faces the engine.
     * @return true if the pulse was consumed (even if no work was ultimately done).
     */
    boolean acceptEnginePulse(Direction from);
}
