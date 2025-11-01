package com.nick.buildcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.buildcraft.content.block.pump.PumpBlockEntity;
import com.nick.buildcraft.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Animated pump hose renderer (no client-side easing).
 *
 * We draw the hose as if it's being extruded out of the pump head:
 *  - A partial-height slice directly under the pump block,
 *  - Then whole 1-block segments below that.
 *
 * We use the server-synced fractional hose tip Y (tubeHeadYExact),
 * so visual speed matches server payout speed from the first tick of power.
 *
 * pose starts with (0,0,0) == pumpPos in camera-space.
 */
public class PumpRenderer implements BlockEntityRenderer<PumpBlockEntity> {

    private final BlockRenderDispatcher brd;

    public PumpRenderer(BlockEntityRendererProvider.Context ctx) {
        this.brd = Minecraft.getInstance().getBlockRenderer();
    }

    @Override
    public void render(
            PumpBlockEntity be,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay,
            Vec3 camPos
    ) {
        Level level = be.getLevel();
        if (level == null || be.isRemoved()) return;

        // server-authoritative smooth tip Y. null => hose not deployed yet
        Double tipYExactD = be.getTubeRenderYExact();
        if (tipYExactD == null) return;

        BlockPos pumpPos = be.getBlockPos();
        int pumpY = pumpPos.getY();
        double tipYExact = tipYExactD;

        // don't draw if the tip isn't actually below pump
        if (tipYExact >= pumpY - 1e-6) return;

        // hose length in blocks = pumpY - tipYExact
        double lengthBlocksExact = pumpY - tipYExact;
        if (lengthBlocksExact <= 1.0e-6) return;

        // split length into [partial slice just under pump] + [full blocks]
        int fullCount = Mth.floor(lengthBlocksExact);       // whole segments
        double partialFrac = lengthBlocksExact - fullCount; // 0.. <1 of a segment right under pump

        var tubeState = ModBlocks.PUMP_TUBE_SEGMENT.get().defaultBlockState();

        // ---------- 1) partial slice (if any) ----------
        if (partialFrac > 1.0e-6) {
            double sliceHeight = partialFrac;
            double sliceBottomY = pumpY - sliceHeight;

            BlockPos lightPos = new BlockPos(
                    pumpPos.getX(),
                    Mth.floor(sliceBottomY),
                    pumpPos.getZ()
            );
            int segLight = LevelRenderer.getLightColor(level, lightPos);

            pose.pushPose();
            // pose origin y=0 == pumpY in world.
            // We want local y=0 to sit at world sliceBottomY.
            pose.translate(0.0, sliceBottomY - pumpY, 0.0);

            // scale Y down to sliceHeight so the cube occupies [0..sliceHeight]
            pose.scale(1.0f, (float) sliceHeight, 1.0f);

            brd.renderSingleBlock(
                    tubeState,
                    pose,
                    buffers,
                    segLight,
                    packedOverlay
            );
            pose.popPose();
        }

        // ---------- 2) full 1-block segments below ----------
        for (int i = 0; i < fullCount; i++) {
            double offsetDownStart = partialFrac + i;

            double worldTopY    = pumpY - offsetDownStart;
            double worldBottomY = worldTopY - 1.0;

            BlockPos segLightPos = new BlockPos(
                    pumpPos.getX(),
                    Mth.floor(worldBottomY),
                    pumpPos.getZ()
            );
            int segLight = LevelRenderer.getLightColor(level, segLightPos);

            pose.pushPose();
            // local y=0 should sit at worldBottomY
            pose.translate(0.0, worldBottomY - pumpY, 0.0);

            // render full cube unscaled
            brd.renderSingleBlock(
                    tubeState,
                    pose,
                    buffers,
                    segLight,
                    packedOverlay
            );
            pose.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public boolean shouldRender(PumpBlockEntity be, Vec3 cameraPos) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(PumpBlockEntity be) {
        Level lvl = be.getLevel();
        if (lvl == null) {
            return BlockEntityRenderer.super.getRenderBoundingBox(be);
        }

        BlockPos p = be.getBlockPos();
        int minY = lvl.dimensionType().minY();
        int maxY = lvl.dimensionType().height() + minY;

        // huge vertical column so we don't lose the hose to frustum culling
        return new AABB(
                p.getX(), minY, p.getZ(),
                p.getX() + 1, maxY, p.getZ() + 1
        ).inflate(0.5);
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
