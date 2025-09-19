package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.energy.Energy;

/** Concrete engines as data (stroke + power). */
public enum EngineType {
    REDSTONE(new EngineSpec(
            Energy.REDSTONE_ENGINE_WARMUP_TICKS,
            Energy.REDSTONE_ENGINE_GEN_COLD,
            Energy.REDSTONE_ENGINE_GEN_WARM,
            Energy.REDSTONE_ENGINE_GEN_HOT,
            /* steps */ 1.0f, 0.03f,              // bang up, glide down
            StrokeProfiles.BANG_RETURN,
            RingShapes.HOLLOW_FRAME
    )),
    STEAM(new EngineSpec(
            /* warmup */ 120,
            /* gen    */ 8, 16, 32,
            /* steps  */ 0.07f, 0.03f,
            StrokeProfiles.SMOOTH,
            RingShapes.SOLID_PLATE
    )),
    COMBUSTION(new EngineSpec(
            /* warmup */ 300,
            /* gen    */ 20, 60, 120,
            /* steps  */ 0.07f, 0.03f,
            StrokeProfiles.SMOOTH,
            RingShapes.SOLID_PLATE
    ));

    public final EngineSpec spec;
    EngineType(EngineSpec spec) { this.spec = spec; }
}
