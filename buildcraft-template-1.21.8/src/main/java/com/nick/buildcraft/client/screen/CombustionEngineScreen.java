package com.nick.buildcraft.client.screen;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.CombustionEngineMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Combustion Engine GUI Screen for NeoForge 1.21.8
 *
 * This implementation adapts BuildCraftcalen's (Minecraft 1.20.1 Forge) fluid rendering approach
 * to NeoForge 1.21.8, specifically solving the "tick marks on top of fluid" layering problem.
 *
 * KEY TECHNIQUE - Layered Rendering with Transparent Overlays:
 * ============================================================
 * Inspired by BCcalen's approach but adapted for NeoForge 1.21.8 where GuiGraphics.blit()
 * automatically handles alpha blending for transparent textures.
 *
 * In older Forge versions (1.20.1), you needed manual RenderSystem.enableBlend().
 * In NeoForge 1.21.8, blending is automatic - just ensure your overlay texture has:
 * - Transparent background (alpha = 0)
 * - Opaque tick marks (alpha = 255)
 *
 * RENDERING ORDER (3 Layers):
 * ============================
 * 1. Background GUI - Tank frames, inventory slots, etc.
 * 2. Fluid levels - Rendered with scissor clipping for bottom-to-top fill
 * 3. Tank overlay - Tick marks with transparency (automatic blending in NeoForge)
 *
 * DIFFERENCES FROM BCCALEN:
 * ==========================
 * - BCcalen uses full widget system (WidgetFluidTank, GuiIcon, GuiUtil.drawFluid)
 * - BCcalen uses manual vertex buffer tiling for fluids
 * - BCcalen manually enables/disables blend mode (Forge 1.20.1 requirement)
 * - This version uses simpler scissor clipping (built into GuiGraphics)
 * - This version relies on NeoForge's automatic blending (no manual RenderSystem calls)
 * - This version maintains the same visual result with less infrastructure
 *
 * ADAPTED FROM:
 * =============
 * BuildCraftcalen 1.20.1 (Mozilla Public License 2.0):
 * - buildcraft.energy.client.gui.GuiEngineIron_BC8 (3-layer rendering pattern)
 * - buildcraft.lib.gui.widget.WidgetFluidTank (fluid rendering concept)
 * - buildcraft.lib.gui.GuiIcon.drawCutInside() (overlay rendering pattern)
 * - buildcraft.lib.misc.GuiUtil.drawFluid() (bottom-to-top fill logic)
 */
public class CombustionEngineScreen extends AbstractContainerScreen<CombustionEngineMenu> {

    // ---- Background panel ----
    private static final ResourceLocation TEX_BG =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/combustion_engine_gui_old.png");
    private static final int TEX_W = 256, TEX_H = 256;
    private static final int BG_U = 0, BG_V = 0, BG_W = 176, BG_H = 166;

    // ---- Left tank FLUID window (FUEL) - GUI-local coordinates ----
    // These define where the fluid itself renders (the yellow/orange area)
    private static final int FUEL_FLUID_X = 104;     // Increase to move fluid RIGHT, decrease to move LEFT
    private static final int FUEL_FLUID_Y = 19;      // Increase to move fluid DOWN, decrease to move UP
    private static final int FUEL_FLUID_W = 16;      // Fluid width in pixels
    private static final int FUEL_FLUID_H = 58;      // Fluid height in pixels

    // ---- Right tank FLUID window (COOLANT) - GUI-local coordinates ----
    // These define where the fluid itself renders (the blue water area)
    private static final int COOLANT_FLUID_X = 122;  // Increase to move fluid RIGHT, decrease to move LEFT
    private static final int COOLANT_FLUID_Y = 19;   // Increase to move fluid DOWN, decrease to move UP
    private static final int COOLANT_FLUID_W = 16;   // Fluid width in pixels
    private static final int COOLANT_FLUID_H = 58;   // Fluid height in pixels

    // ---- Left tank OVERLAY position (FUEL tick marks) ----
    // These define where the tick marks render (independently from fluid)
    private static final int FUEL_OVERLAY_X = 103;   // Increase to move tick marks RIGHT, decrease to move LEFT
    private static final int FUEL_OVERLAY_Y = 19;    // Increase to move tick marks DOWN, decrease to move UP

    // ---- Right tank OVERLAY position (COOLANT tick marks) ----
    // These define where the tick marks render (independently from fluid)
    private static final int COOLANT_OVERLAY_X = 121; // Increase to move tick marks RIGHT, decrease to move LEFT
    private static final int COOLANT_OVERLAY_Y = 19;  // Increase to move tick marks DOWN, decrease to move UP

    // ---- Fuel texture for level rendering ----
    // Using Tekkit's original fuel_flow.png texture (copied from BuildCraft 1.6.4)
    private static final ResourceLocation TEX_FUEL_COMBUSTION =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/fluids/fuel_flow.png");
    private static final int FUEL_TEX_W = 16;
    private static final int FUEL_TEX_H = 16;

    // ---- Coolant texture - uses Minecraft's water texture from block atlas (Tekkit approach) ----
    // Water texture is fetched dynamically from Fluids.WATER using the block texture atlas
    // This matches the Tekkit BuildCraft 1.6.4 approach exactly
    private static final int COOLANT_TEX_W = 16;
    private static final int COOLANT_TEX_H = 16;

    // ---- Tank overlay (tick marks) - BCcalen style ----
    // This region contains ONLY the tick marks with transparent background
    // Extracted from the GUI texture at UV coordinates (176, 0)
    // Same overlay texture is reused for both fuel and coolant tanks
    private static final int OVERLAY_U = 176;
    private static final int OVERLAY_V = 0;
    private static final int OVERLAY_W = 16;  // Width per tank (matches FUEL_TANK_W and COOLANT_TANK_W)
    private static final int OVERLAY_H = 58;  // Height of tick mark overlay (slightly taller than tank for visual effect)

    public CombustionEngineScreen(CombustionEngineMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.translatable("screen.buildcraft.combustion_engine"));
        this.imageWidth = BG_W;
        this.imageHeight = BG_H;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int baseX = this.leftPos;
        int baseY = this.topPos;

        // LAYER 1: Render background GUI (tank frames, inventory slots, etc.)
        // This can include the full background - overlay will render on top
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG, baseX, baseY,
                (float) BG_U, (float) BG_V, BG_W, BG_H, TEX_W, TEX_H);

        // LAYER 2: Render fluid levels (scissor-clipped to fill from bottom)
        renderFluidTanks(g);

        // LAYER 3: Render tick marks overlay ON TOP with transparency
        // Uses BCcalen's blending technique to ensure proper alpha rendering
        renderTankOverlay(g);
    }

    /**
     * Renders the tank overlays (tick marks) with transparency.
     *
     * Renders two separate overlays - one for each tank - using the same tick mark texture.
     * This approach aligns each overlay precisely with its corresponding tank.
     *
     * In NeoForge 1.21.8, GuiGraphics.blit() automatically handles alpha blending,
     * so no manual RenderSystem calls are needed (unlike Forge 1.20.1).
     *
     * TEXTURE REQUIREMENT: The overlay region at UV (176, 0) in your GUI texture must have:
     * - Transparent background (alpha channel = 0)
     * - Opaque tick marks (alpha channel = 255)
     * - Size: 16x58 pixels (matches tank width, slightly taller than tank height)
     *
     * This allows the tick marks to render on top while the fluid shows through.
     *
     * Adapted from BuildCraftcalen 1.20.1:
     * - buildcraft.lib.gui.GuiIcon.drawCutInside() (overlay pattern)
     * - buildcraft.energy.client.gui.GuiEngineIron_BC8 (layering concept)
     */
    private void renderTankOverlay(GuiGraphics g) {
        int baseX = this.leftPos;
        int baseY = this.topPos;

        // Note: In NeoForge 1.21.8, GuiGraphics.blit() automatically handles blending
        // for transparent textures, so we don't need manual RenderSystem.enableBlend()
        // The key is that your overlay texture at UV (176, 0) must have:
        // - Transparent background (alpha = 0)
        // - Opaque tick marks (alpha = 255)

        // Render overlay for FUEL tank (left) - tick marks position
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG,
                baseX + FUEL_OVERLAY_X, baseY + FUEL_OVERLAY_Y,  // Tick marks position (independent from fluid)
                (float) OVERLAY_U,           // UV X: 176 (overlay region in texture)
                (float) OVERLAY_V,           // UV Y: 0
                OVERLAY_W, OVERLAY_H,        // Size: 16x58 (per tank)
                TEX_W, TEX_H);               // Texture dimensions: 256x256

        // Render overlay for COOLANT tank (right) - tick marks position
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_BG,
                baseX + COOLANT_OVERLAY_X, baseY + COOLANT_OVERLAY_Y,  // Tick marks position (independent from fluid)
                (float) OVERLAY_U,           // UV X: 176 (same overlay texture)
                (float) OVERLAY_V,           // UV Y: 0
                OVERLAY_W, OVERLAY_H,        // Size: 16x58 (per tank)
                TEX_W, TEX_H);               // Texture dimensions: 256x256
    }

    /**
     * Renders both tank fluid levels using scissor clipping.
     *
     * Scissor clipping is used to show only the filled portion of each tank,
     * creating a bottom-to-top fill effect. This is simpler than BCcalen's
     * vertex buffer approach but achieves the same visual result.
     *
     * Tanks:
     * - Left tank: Fuel (combustion fuel texture)
     * - Right tank: Coolant (water texture)
     *
     * Adapted from BCcalen's GuiUtil.drawFluid() concept, but using
     * NeoForge's built-in scissor clipping instead of manual vertex buffers.
     */
    private void renderFluidTanks(GuiGraphics g) {
        int baseX = this.leftPos;
        int baseY = this.topPos;

        // Animate texture scroll (optional: creates flowing effect)
        long gameTime = (this.minecraft != null && this.minecraft.level != null)
                ? this.minecraft.level.getGameTime()
                : 0L;
        int scrollV = (int) (gameTime * 2) % FUEL_TEX_H;

        // Get fill percentages from synced tank data
        float fuelPercent = this.menu.getFuelFillPercent();
        float coolantPercent = this.menu.getCoolantFillPercent();

        // Render LEFT tank (FUEL) - Tekkit tiling approach
        renderFuelTankTekkit(g, baseX, baseY, FUEL_FLUID_X, FUEL_FLUID_Y, FUEL_FLUID_W, FUEL_FLUID_H,
                fuelPercent, scrollV);

        // Render RIGHT tank (COOLANT) - Tekkit approach using block atlas sprite
        renderWaterTankTekkit(g, baseX, baseY, COOLANT_FLUID_X, COOLANT_FLUID_Y, COOLANT_FLUID_W, COOLANT_FLUID_H,
                coolantPercent, scrollV);
    }

    /**
     * Renders a single fluid tank with level indicator.
     *
     * This method uses scissor clipping to show only the filled portion of the tank,
     * creating a bottom-to-top fill effect. The approach is simpler than BCcalen's
     * manual vertex buffer tiling but achieves the same visual result.
     *
     * Process:
     * 1. Calculate filled height based on fill ratio
     * 2. Enable scissor test to clip to filled region only
     * 3. Render full texture (scissor automatically hides unfilled portion)
     * 4. Disable scissor test
     *
     * @param g GuiGraphics context
     * @param baseX GUI left position (screen X)
     * @param baseY GUI top position (screen Y)
     * @param tankX Tank X offset from GUI origin
     * @param tankY Tank Y offset from GUI origin
     * @param tankW Tank width in pixels
     * @param tankH Tank height in pixels
     * @param fillRatio Fill percentage (0.0 to 1.0)
     * @param texture Fluid texture to render
     * @param texW Texture width
     * @param texH Texture height
     * @param scrollV Vertical scroll offset for animation
     */
    private void renderTankFluid(GuiGraphics g, int baseX, int baseY,
                                  int tankX, int tankY, int tankW, int tankH,
                                  float fillRatio, ResourceLocation texture, int texW, int texH, int scrollV) {
        // Nothing to render if empty
        if (fillRatio <= 0) return;

        // Clamp to valid range [0.0, 1.0]
        fillRatio = Math.min(1.0f, Math.max(0.0f, fillRatio));

        // Convert GUI-local tank coordinates to screen space
        int screenX = baseX + tankX;
        int screenY = baseY + tankY;
        int screenBottomY = screenY + tankH;

        // Calculate filled height in pixels
        int fillHeightPx = Math.round(tankH * fillRatio);
        if (fillHeightPx <= 0) return;

        // For bottom-anchored fill: calculate top Y position
        // (fluid fills from bottom, so top moves down as tank fills)
        int fillTopY = screenBottomY - fillHeightPx;

        // Define scissor rectangle: only show the filled portion at bottom
        int scissorLeft = screenX;
        int scissorTop = fillTopY;
        int scissorRight = screenX + tankW;
        int scissorBottom = screenBottomY;

        // Enable scissor test (GPU-based clipping)
        // This is more efficient than BCcalen's manual vertex tiling
        g.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);

        // Render full texture from tank top
        // Scissor test automatically clips to show only filled portion
        g.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                screenX, screenY,                // Render from tank top-left
                0.0f, (float) scrollV,           // U=0, V=scroll offset for animation
                tankW, tankH,                    // Draw full tank area (scissor clips the rest)
                texW, texH
        );

        // Restore normal rendering (disable scissor test)
        g.disableScissor();
    }

    /**
     * Renders fuel tank using water texture with yellow/orange tint.
     * This avoids the vertical animation issue of fuel_flow.png by using
     * the same horizontal water texture with different color.
     */
    private void renderFuelTankTekkit(GuiGraphics g, int baseX, int baseY,
                                      int tankX, int tankY, int tankW, int tankH,
                                      float fillRatio, int scrollV) {
        // Nothing to render if empty
        if (fillRatio <= 0) return;

        // Clamp to valid range [0.0, 1.0]
        fillRatio = Math.min(1.0f, Math.max(0.0f, fillRatio));

        // Use water sprite (EXACT same as water tank) but with yellow/orange tint
        IClientFluidTypeExtensions attrs = IClientFluidTypeExtensions.of(Fluids.WATER);
        ResourceLocation waterSpriteLocation = attrs.getStillTexture();
        if (waterSpriteLocation == null) {
            waterSpriteLocation = attrs.getFlowingTexture();
        }
        if (waterSpriteLocation == null) return;

        // Fetch sprite from block texture atlas
        TextureAtlasSprite waterSprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(waterSpriteLocation);
        if (waterSprite == null) return;

        // Convert GUI-local tank coordinates to screen space
        int screenX = baseX + tankX;
        int screenY = baseY + tankY;
        int screenBottomY = screenY + tankH;

        // Calculate filled height in pixels
        int fillHeightPx = Math.round(tankH * fillRatio);
        if (fillHeightPx <= 0) return;

        // Tekkit's tiling approach: render 16x16 sprites from bottom up
        // Bright yellow tint applied to water texture for horizontal appearance
        int fuelYellow = 0xFFFFFF00;  // ARGB: Bright yellow tint

        int start = 0;
        int remainingHeight = fillHeightPx;

        // Tile sprites from bottom to top (EXACT same loop as water)
        while (remainingHeight > 0) {
            int sliceHeight = Math.min(16, remainingHeight);
            int renderY = screenBottomY - start - sliceHeight;

            // Render one 16x16 tile using blitSprite with yellow tint
            g.blitSprite(RenderPipelines.GUI_TEXTURED, waterSprite,
                    screenX, renderY, tankW, sliceHeight, fuelYellow);

            start += sliceHeight;
            remainingHeight -= sliceHeight;
        }
    }

    /**
     * Renders water tank using Tekkit BuildCraft 1.6.4 approach:
     * - Fetches water sprite from block texture atlas dynamically
     * - Uses GuiGraphics.blitSprite() to render the sprite directly
     * - Matches the displayGauge() method from GuiCombustionEngine.java (Tekkit)
     */
    private void renderWaterTankTekkit(GuiGraphics g, int baseX, int baseY,
                                       int tankX, int tankY, int tankW, int tankH,
                                       float fillRatio, int scrollV) {
        // Nothing to render if empty
        if (fillRatio <= 0) return;

        // Clamp to valid range [0.0, 1.0]
        fillRatio = Math.min(1.0f, Math.max(0.0f, fillRatio));

        // Get water's still icon from block atlas (Tekkit line 68: fluid.getStillIcon())
        IClientFluidTypeExtensions attrs = IClientFluidTypeExtensions.of(Fluids.WATER);
        ResourceLocation waterSpriteLocation = attrs.getStillTexture();
        if (waterSpriteLocation == null) {
            waterSpriteLocation = attrs.getFlowingTexture();
        }
        if (waterSpriteLocation == null) return;

        // Fetch sprite from block texture atlas (Tekkit line 70: bind BLOCK_TEXTURE)
        TextureAtlasSprite waterSprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(waterSpriteLocation);
        if (waterSprite == null) return;

        // Convert GUI-local tank coordinates to screen space
        int screenX = baseX + tankX;
        int screenY = baseY + tankY;
        int screenBottomY = screenY + tankH;

        // Calculate filled height in pixels
        int fillHeightPx = Math.round(tankH * fillRatio);
        if (fillHeightPx <= 0) return;

        // Tekkit's tiling approach (lines 79-91): render 16x16 sprites from bottom up
        // Water blue tint matching Minecraft 1.6.4 water color (dark blue)
        int waterBlue = 0xFF2F6ECE;  // ARGB: Minecraft 1.6.4 water blue

        int start = 0;
        int remainingHeight = fillHeightPx;

        // Tile sprites from bottom to top (Tekkit's do-while loop)
        while (remainingHeight > 0) {
            int sliceHeight = Math.min(16, remainingHeight);
            int renderY = screenBottomY - start - sliceHeight;

            // Render one 16x16 tile of water
            g.blitSprite(RenderPipelines.GUI_TEXTURED, waterSprite,
                    screenX, renderY, tankW, sliceHeight, waterBlue);

            start += sliceHeight;
            remainingHeight -= sliceHeight;
        }
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
