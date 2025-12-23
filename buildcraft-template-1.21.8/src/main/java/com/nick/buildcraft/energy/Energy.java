package com.nick.buildcraft.energy;

/** Balance constants for engines & machines. */
public final class Energy {
    private Energy() {}

    /* Generic engine buffer/IO */
    public static final int ENGINE_BUFFER = 10_000; // FE
    public static final int ENGINE_MAX_IO = 80;     // FE/t
    public static final int ENGINE_ENERGY_PER_PUMP = 50; // FE per pump cycle

    /* Redstone Engine */
    public static final int REDSTONE_ENGINE_GEN_COLD = 1;   // FE/t at start
    public static final int REDSTONE_ENGINE_GEN_WARM = 2;   // FE/t mid
    public static final int REDSTONE_ENGINE_GEN_HOT  = 4;   // FE/t steady
    public static final int REDSTONE_ENGINE_WARMUP_TICKS = 20 * 10; // ~10s

    /* Quarry */
    public static final int QUARRY_BUFFER = 500;      // FE buffer capacity
    public static final int QUARRY_DRAIN_PER_TICK = 10; // FE consumed per tick when running (was 50)
}
