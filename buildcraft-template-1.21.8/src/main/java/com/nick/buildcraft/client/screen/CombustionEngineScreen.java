package com.nick.buildcraft.client.screen;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.CombustionEngineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

public class CombustionEngineScreen extends AbstractContainerScreen<CombustionEngineMenu> {

    // ---- Background panel ----
    private static final ResourceLocation TEX_BG =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/combustion_engine_gui_old.png");
    private static final int TEX_W = 256, TEX_H = 256;
    private static final int BG_U = 0, BG_V = 0, BG_W = 176, BG_H = 166;

    // ---- Fuel tank (left side) ----
    // Based on texture inspection: fuel bar on left side, red/maroon colors
    private static final int FUEL_X = 15;
    private static final int FUEL_Y = 17;
    private static final int FUEL_W = 16;
    private static final int FUEL_H = 58;

    // ---- Coolant tank (right side) ----
    // Based on texture inspection: coolant bar on right side, blue colors
    private static final int COOLANT_X = 33;
    private static final int COOLANT_Y = 17;
    private static final int COOLANT_W = 16;
    private static final int COOLANT_H = 58;

    // ---- Tank texture coordinates ----
    // Assume tanks are in the texture atlas: empty and full variants
    private static final int TANK_EMPTY_U = 176;  // Texture atlas position for empty tank
    private static final int TANK_EMPTY_V = 0;
    private static final int FUEL_FULL_U = 192;   // Fuel tank full texture
    private static final int FUEL_FULL_V = 0;
    private static final int COOLANT_FULL_U = 208; // Coolant tank full texture
    private static final int COOLANT_FULL_V = 0;

    public CombustionEngineScreen(CombustionEngineMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.translatable("screen.buildcraft.combustion_engine"));
        this.imageWidth = BG_W;
        this.imageHeight = BG_H;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // 1) Background texture
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, this.leftPos, this.topPos,
                (float) BG_U, (float) BG_V, BG_W, BG_H, TEX_W, TEX_H);

        int baseX = this.leftPos;
        int baseY = this.topPos;

        // 2) Render fuel tank (red/maroon)
        float fuelPercent = this.menu.getFuelFillPercent();
        renderTankBar(g, baseX + FUEL_X, baseY + FUEL_Y, FUEL_W, FUEL_H, fuelPercent, 0xCC3333);

        // 3) Render coolant tank (blue)
        float coolantPercent = this.menu.getCoolantFillPercent();
        renderTankBar(g, baseX + COOLANT_X, baseY + COOLANT_Y, COOLANT_W, COOLANT_H, coolantPercent, 0x3366FF);

        // 4) Render heat indicator (optional: shows engine heat as an overlay)
        float heat = this.menu.getHeatLevel();
        renderHeatIndicator(g, baseX, baseY, heat);
    }

    /**
     * Renders a colored tank bar with liquid fill.
     * @param g GuiGraphics
     * @param x screen x position
     * @param y screen y position
     * @param width tank width
     * @param height tank height
     * @param fillPercent fill 0.0 to 1.0
     * @param color RGB color code
     */
    private void renderTankBar(GuiGraphics g, int x, int y, int width, int height, float fillPercent, int color) {
        // Draw empty background first (semi-transparent gray)
        g.fill(x, y, x + width, y + height, 0xFF8B8B8B);

        // Draw fill based on percentage
        fillPercent = Mth.clamp(fillPercent, 0.0f, 1.0f);
        int fillHeight = Math.round(height * fillPercent);
        int fillY = y + height - fillHeight;

        // Draw colored fill from bottom up
        g.fill(fillY, fillY, x + width, y + height, color);

        // Draw border
        g.fill(x, y, x + width, y, 0xFF000000);              // top
        g.fill(x, y + height, x + width, y + height, 0xFF000000); // bottom
        g.fill(x, y, x, y + height, 0xFF000000);              // left
        g.fill(x + width, y, x + width, y + height, 0xFF000000); // right
    }

    /**
     * Renders a heat indicator bar (optional visual feedback for engine heat).
     */
    private void renderHeatIndicator(GuiGraphics g, int baseX, int baseY, float heat) {
        // Heat indicator at the top: changes color based on heat level
        int heatX = baseX + 55;
        int heatY = baseY + 17;
        int heatW = 40;
        int heatH = 8;

        // Get color based on heat phase
        int color;
        if (heat < 0.25f) {
            color = 0x3333FF;  // BLUE: cool
        } else if (heat < 0.5f) {
            color = 0x33FF33;  // GREEN: warm
        } else if (heat < 0.75f) {
            color = 0xFFAA00;  // ORANGE: hot
        } else {
            color = 0xFF3333;  // RED: critical
        }

        // Draw background
        g.fill(heatX, heatY, heatX + heatW, heatY + heatH, 0xFF8B8B8B);

        // Draw heat fill
        int heatFill = Math.round(heatW * Mth.clamp(heat, 0.0f, 1.0f));
        g.fill(heatX, heatY, heatX + heatFill, heatY + heatH, color);

        // Draw border
        g.fill(heatX, heatY, heatX + heatW, heatY, 0xFF000000);              // top
        g.fill(heatX, heatY + heatH, heatX + heatW, heatY + heatH, 0xFF000000); // bottom
        g.fill(heatX, heatY, heatX, heatY + heatH, 0xFF000000);              // left
        g.fill(heatX + heatW, heatY, heatX + heatW, heatY + heatH, 0xFF000000); // right
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
