// src/main/java/com/nick/buildcraft/client/screen/StirlingEngineScreen.java
package com.nick.buildcraft.client.screen;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.StirlingEngineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

public class StirlingEngineScreen extends AbstractContainerScreen<StirlingEngineMenu> {

    // ---- Background panel ----
    private static final ResourceLocation TEX_BG =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/stirling_engine_gui.png");
    private static final int TEX_W = 256, TEX_H = 256;
    private static final int BG_U = 0, BG_V = 0, BG_W = 176, BG_H = 166;

    // ---- Filmstrip: 28 frames stacked vertically, each 112x112 ----
    private static final ResourceLocation TEX_FLAME_STRIP =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/stirling_flame_strip.png");
    private static final int FRAME_SRC_W = 112;
    private static final int FRAME_SRC_H = 112;
    private static final int FRAMES      = 28;

    // ---- 14×14 window on GUI ----
    private static final int FLAME_W = 14, FLAME_H = 14;
    private static final int FLAME_X = 81, FLAME_Y = 25;
    private static final int FRAME_T = 1; // border thickness to re-blit on top

    // ---- Split one 112px frame into three source columns (tune to your art) ----
    private static final int[] SRC_U  = {  6,  40,  74 }; // source X within the 112px frame
    private static final int[] SRC_WC = { 30,  32,  30 }; // source width of each flame column

    // ---- Place three narrow flames inside the 14×14 slot ----
    private static final int[] DST_OX = { 1,   5,   9 };  // destination X within the slot
    private static final int[] DST_W  = { 4,   4,   4 };  // destination width for each flame strip

    // ---- Liveliness model: mean-reverting offsets per flame ----
    // Visual amplitude in "burn fraction" units (0..1). 0.10 ≈ 10% of the bar.
    private static final double DEV_MAX = 0.10;
    // How often to pick a new wandering target (ticks)
    private static final int    NOISE_PERIOD_TICKS = 9;
    // Soft recenter pulse so they periodically align
    private static final int    SYNC_PERIOD_TICKS  = 80;
    // How quickly we chase the target each tick (0..1)
    private static final double LERP_TO_TARGET     = 0.22;
    // Passive decay toward 0 each tick (closer to 1 = slower decay)
    private static final double PASSIVE_DECAY      = 0.990;

    // State: per-flame offset (adds/subtracts from base burn fraction) and target
    private final double[] offset = new double[3];
    private final double[] target = new double[3];
    private long lastTick = Long.MIN_VALUE;

    public StirlingEngineScreen(StirlingEngineMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.translatable("screen.buildcraft.stirling_engine"));
        this.imageWidth = BG_W;
        this.imageHeight = BG_H;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // 1) Background
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, this.leftPos, this.topPos,
                (float) BG_U, (float) BG_V, BG_W, BG_H, TEX_W, TEX_H);

        // 2) Three independently-indexed flames composited into the 14×14 window
        int burnPx = this.menu.flamePixels(FLAME_H); // 0..14
        if (burnPx > 0) {
            // Base burn fraction 1.0 (full) -> 0.0 (empty)
            double frac = burnPx / (double) FLAME_H;

            // Update per-flame wandering offsets once per game tick
            long gt = (this.minecraft != null && this.minecraft.level != null) ? this.minecraft.level.getGameTime() : 0L;
            if (gt != lastTick) {
                updateOffsets(gt, frac);
                lastTick = gt;
            }

            // Base index from the global burn (0 = full frame at top; FRAMES-1 = lowest)
            int baseIdx = Mth.clamp((int)Math.floor((FRAMES - 1) * (1.0 - frac)), 0, FRAMES - 1);

            int dstBaseX = this.leftPos + FLAME_X;
            int dstY     = this.topPos  + FLAME_Y;

            for (int i = 0; i < 3; i++) {
                // Apply this flame's offset to the burn fraction, then map to a frame index
                double localFrac = Mth.clamp(frac + offset[i], 0.0, 1.0);
                int idx = Mth.clamp((int)Math.floor((FRAMES - 1) * (1.0 - localFrac)), 0, FRAMES - 1);

                int srcV = idx * FRAME_SRC_H;   // vertical frame row
                int srcU = SRC_U[i];
                int srcW = SRC_WC[i];

                int dx = dstBaseX + DST_OX[i];
                int dw = DST_W[i];

                g.blit(
                        RenderPipelines.GUI_TEXTURED,
                        TEX_FLAME_STRIP,
                        dx, dstY,
                        (float) srcU, (float) srcV,
                        dw, FLAME_H,                   // destination in the 14×14 window
                        srcW, FRAME_SRC_H,             // one column source size
                        FRAME_SRC_W, FRAME_SRC_H * FRAMES
                );
            }
        }

        // 3) Socket border on top (keeps the cutout look)
        blitSocketFrameOnTop(g);
    }

    /** Mean-reverting "wander" that diverges a bit then catches up again. */
    private void updateOffsets(long gt, double baseFrac) {
        // Scale amplitude down when nearly empty/full so it doesn't look weird
        double edgeScale = Mth.clamp(baseFrac * 1.2, 0.0, 1.0) * Mth.clamp((1.0 - baseFrac) * 1.2, 0.0, 1.0);
        double maxDev = DEV_MAX * (0.6 + 0.4 * edgeScale); // keep some motion across range

        // Every NOISE_PERIOD_TICKS, pick new targets; every SYNC_PERIOD_TICKS, bias to 0 (catch up)
        boolean pick = (gt % NOISE_PERIOD_TICKS) == 0;
        boolean sync = (gt % SYNC_PERIOD_TICKS)  == 0;

        for (int i = 0; i < 3; i++) {
            if (pick) {
                double n = pseudoNoise(gt, i);              // 0..1
                double rnd = (n * 2.0 - 1.0) * maxDev;       // -maxDev..+maxDev
                target[i] = sync ? 0.0 : rnd;                // periodic catch-up impulse
            }

            // Smoothly chase the target and gently decay toward 0
            offset[i] += (target[i] - offset[i]) * LERP_TO_TARGET;
            offset[i] *= PASSIVE_DECAY;

            // Clamp safety
            offset[i] = Mth.clamp(offset[i], -maxDev, maxDev);
        }
    }

    // Deterministic, cheap "noise" without allocating Random
    private static double pseudoNoise(long t, int i) {
        double x = (t * (17 + i * 13)) * 0.123456789; // arbitrary irrational-ish factor
        return (Math.sin(x) * 0.5) + 0.5;             // 0..1
    }

    private void blitSocketFrameOnTop(GuiGraphics g) {
        int dx = this.leftPos + FLAME_X - FRAME_T;
        int dy = this.topPos  + FLAME_Y - FRAME_T;
        int su = BG_U + FLAME_X - FRAME_T;
        int sv = BG_V + FLAME_Y - FRAME_T;

        int ow = FLAME_W + FRAME_T * 2;
        int oh = FLAME_H + FRAME_T * 2;

        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, dx, dy, (float) su, (float) sv, ow, FRAME_T, TEX_W, TEX_H);                         // top
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, dx, dy + oh - FRAME_T, (float) su, (float) (sv + oh - FRAME_T), ow, FRAME_T, TEX_W, TEX_H); // bottom
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, dx, dy + FRAME_T, (float) su, (float) (sv + FRAME_T), FRAME_T, FLAME_H, TEX_W, TEX_H);      // left
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, dx + ow - FRAME_T, dy + FRAME_T,
                (float) (su + ow - FRAME_T), (float) (sv + FRAME_T), FRAME_T, FLAME_H, TEX_W, TEX_H);                                                   // right
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, 8, 6, 0x404040);
        g.drawString(this.font, this.playerInventoryTitle, 8, this.imageHeight - 96 + 2, 0x404040);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
