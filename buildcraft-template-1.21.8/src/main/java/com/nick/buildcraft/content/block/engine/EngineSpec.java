package com.nick.buildcraft.content.block.engine;

/** Tunables for an engine type. */
public record EngineSpec(
        int warmupTicks,
        int genCold, int genWarm, int genHot,
        float extendStep, float retractStep,
        StrokeProfile stroke,
        RingShape ring
) {
    public int warmupTicks()   { return warmupTicks; }
    public int genCold()       { return genCold; }
    public int genWarm()       { return genWarm; }
    public int genHot()        { return genHot; }
    public float extendStep()  { return extendStep; }
    public float retractStep() { return retractStep; }
    public StrokeProfile stroke() { return stroke; }
    public RingShape ring()    { return ring; }
}
