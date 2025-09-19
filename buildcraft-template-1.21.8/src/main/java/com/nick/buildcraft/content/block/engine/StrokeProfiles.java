package com.nick.buildcraft.content.block.engine;

/** Built-in stroke behaviors for engines. */
public final class StrokeProfiles {
    private StrokeProfiles() {}

    /**
     * Redstone engine behavior:
     *  - While powered: one-tick “bang” to 1.0 when ARMED, then glide down.
     *  - While unpowered: glide down.
     *  - Re-arm automatically once fully retracted (current == 0), so it will bang again next powered tick.
     *
     * The boolean state[0] acts as the "armed" flag.
     */
    public static final StrokeProfile BANG_RETURN = (current, powered, extend, retract, state) -> {
        boolean armed = state != null && state.length > 0 && state[0];

        if (powered) {
            // Fire the bang if armed
            if (armed) {
                if (state != null && state.length > 0) state[0] = false; // consume the arm
                return 1.0f;
            }
            // Otherwise glide down
            float next = Math.max(0.0f, current - Math.max(retract, 0f));
            // Re-arm when fully down
            if (next == 0.0f && state != null && state.length > 0) state[0] = true;
            return next;
        } else {
            // Unpowered: glide down and arm when fully retracted
            float next = Math.max(0.0f, current - Math.max(retract, 0f));
            if (next == 0.0f && state != null && state.length > 0) state[0] = true;
            return next;
        }
    };

    /** Simple symmetric extend while powered; retract when unpowered. */
    public static final StrokeProfile SMOOTH = (current, powered, extend, retract, state) -> {
        if (powered) {
            return Math.min(1.0f, current + Math.max(extend, 0f));
        } else {
            return Math.max(0.0f, current - Math.max(retract, 0f));
        }
    };
}
