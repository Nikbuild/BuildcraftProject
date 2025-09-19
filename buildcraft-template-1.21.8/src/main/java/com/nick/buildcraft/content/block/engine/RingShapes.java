package com.nick.buildcraft.content.block.engine;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Stock ring shapes. Tune as desired. */
public final class RingShapes {
    private RingShapes() {}

    // Constants match your redstone ring
    private static final double PX = 1.0 / 16.0;
    private static final double TH = 4 * PX;           // ring slab thickness
    private static final double OUT0 = 1 * PX, OUT1 = 15 * PX;
    private static final double CORE_MIN = 5 * PX, CORE_MAX = 11 * PX;
    private static final double TOP_MIN = 8 * PX;      // retracted top (same as Redstone)
    private static final double EPS = 1.0 / 1024.0;

    /** Hollow frame around a core hole, like your Redstone ring. */
    public static final RingShape HOLLOW_FRAME = new RingShape() {
        @Override public VoxelShape frameAt(double off) {
            double top = TOP_MIN + off, bot = top - TH;
            double hMin = CORE_MIN, hMax = CORE_MAX;

            VoxelShape v = Shapes.empty();
            // West / East bars
            v = Shapes.joinUnoptimized(v, Shapes.create(OUT0, bot, OUT0, hMin,  top, OUT1), BooleanOp.OR);
            v = Shapes.joinUnoptimized(v, Shapes.create(hMax, bot, OUT0, OUT1,  top, OUT1), BooleanOp.OR);
            // North / South bars
            v = Shapes.joinUnoptimized(v, Shapes.create(hMin, bot, OUT0, hMax,  top, hMin), BooleanOp.OR);
            v = Shapes.joinUnoptimized(v, Shapes.create(hMin, bot, hMax, hMax,  top, OUT1), BooleanOp.OR);
            return v;
        }

        @Override public VoxelShape plateAt(double off) {
            double top = TOP_MIN + off, bot = top - TH;
            VoxelShape outer = Shapes.create(EPS, bot, EPS, 1.0 - EPS, top, 1.0 - EPS);
            VoxelShape inner = Shapes.create(CORE_MIN, bot, CORE_MIN, CORE_MAX, top, CORE_MAX);
            return Shapes.joinUnoptimized(outer, inner, BooleanOp.ONLY_FIRST);
        }
    };

    /** Simple solid plate. */
    public static final RingShape SOLID_PLATE = new RingShape() {
        @Override public VoxelShape frameAt(double off) {
            double top = TOP_MIN + off, bot = top - TH;
            return Shapes.create(OUT0, bot, OUT0, OUT1, top, OUT1);
        }
        @Override public VoxelShape plateAt(double off) { return frameAt(off); }
    };

    /** Travel range (used by EngineBlockEntity). */
    public static final double MAX_TRAVEL_BLOCKS = (16.0 / 16.0) - TOP_MIN; // 1.0 - 0.5 = 0.5
}
