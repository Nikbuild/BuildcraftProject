package com.nick.buildcraft.content.block.quarry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.IdentityHashMap;
import java.util.Map;

/** World-level load controller for all quarries in a dimension. */
public final class QuarryBalancer {

    private static final class State {
        long lastTick = Long.MIN_VALUE;
        int tokens;
        int phases = 4;
        int activeThisTick;
        int activeLastTick;
    }

    private static final Map<ServerLevel, State> STATES = new IdentityHashMap<>();
    private static State s(ServerLevel lvl){ return STATES.computeIfAbsent(lvl,k->new State()); }

    /** Call once per tick (first quarry that runs) to set budget for this world tick. */
    public static void beginTick(ServerLevel lvl) {
        State st = s(lvl);
        long gt = lvl.getGameTime();
        if (st.lastTick == gt) return;

        // Adaptive token budget based on server load
        int baseTokens = 8; // tune
        double mspt = lvl.getServer().getCurrentSmoothedTickTime(); // ms per tick (~50 good)
        double factor = Mth.clamp(1.0 - ((mspt - 50.0) / 25.0), 0.20, 1.0);
        st.tokens = Math.max(1, (int)Math.floor(baseTokens * factor));

        // Spread heavy work across phases based on recent activity
        int minPhases = 2, maxPhases = 16;
        int desired = Math.max(1, (int)Math.ceil((double) st.activeLastTick / Math.max(1, st.tokens)));
        st.phases = Mth.clamp(desired, minPhases, maxPhases);

        st.activeLastTick = (int)Math.round(st.activeLastTick * 0.6 + st.activeThisTick * 0.4);
        st.activeThisTick = 0;
        st.lastTick = gt;
    }

    /** Should this quarry run heavy stepMining this tick? */
    public static boolean phaseGate(ServerLevel lvl, net.minecraft.core.BlockPos pos) {
        State st = s(lvl);
        st.activeThisTick++;
        int my = Math.floorMod(pos.hashCode(), Math.max(1, st.phases));
        int now = (int)(lvl.getGameTime() % Math.max(1, st.phases));
        return my == now;
    }

    /** Try to spend one mining token from the world budget. */
    public static boolean tryConsumeToken(ServerLevel lvl) {
        State st = s(lvl);
        if (st.tokens <= 0) return false;
        st.tokens--;
        return true;
    }
}
