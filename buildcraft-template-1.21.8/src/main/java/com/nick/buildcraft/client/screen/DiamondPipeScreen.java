// src/main/java/com/nick/buildcraft/client/screen/DiamondPipeScreen.java
package com.nick.buildcraft.client.screen;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.pipe.DiamondPipeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class DiamondPipeScreen extends AbstractContainerScreen<DiamondPipeMenu> {

    // Background texture: assets/buildcraft/textures/gui/diamond_pipe_filter_gui.png
    private static final ResourceLocation TEX_BG =
            ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/diamond_pipe_filter_gui.png");

    // BuildCraft diamond pipe panel: 175x225 at (0,0) on a 256x256 sheet
    private static final int TEX_W = 256, TEX_H = 256;
    private static final int BG_U = 0, BG_V = 0, BG_W = 175, BG_H = 225;

    public DiamondPipeScreen(DiamondPipeMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.translatable("screen.buildcraft.diamond_pipe"));
        this.imageWidth = BG_W;   // 175
        this.imageHeight = BG_H;  // 225
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(
                RenderPipelines.GUI_TEXTURED,
                TEX_BG,
                this.leftPos, this.topPos,
                (float) BG_U, (float) BG_V,
                BG_W, BG_H,
                TEX_W, TEX_H
        );
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Title at (8,6). Player inventory label at imageHeight - 97 (BC layout).
        g.drawString(this.font, this.title, 8, 6, 0x404040);
        g.drawString(this.font, this.playerInventoryTitle, 8, this.imageHeight - 97, 0x404040);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
