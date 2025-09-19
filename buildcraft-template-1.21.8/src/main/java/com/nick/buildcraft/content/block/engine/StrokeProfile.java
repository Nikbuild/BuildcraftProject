package com.nick.buildcraft.content.block.engine;

/** Computes next progress in [0..1] given current power and step sizes. */
public interface StrokeProfile {
    /**
     * @param current  current progress [0..1]
     * @param powered  redstone power at block
     * @param extend   step when extending (up)
     * @param retract  step when retracting (down)
     * @param state    optional single-cell boolean to store state (e.g., “going up”)
     * @return next progress [0..1]
     */
    float next(float current, boolean powered, float extend, float retract, boolean[] state);
}
