package com.nick.buildcraft.content.block.pump;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Render-only "tube segment" block for the pump hose.
 *
 * We never actually place this in the world on purpose.
 * It's only registered so Minecraft bakes its model and stitches its texture,
 * and so the PumpRenderer can ask the block renderer to draw it.
 *
 * Properties:
 * - no collision
 * - no occlusion (doesn't block light / doesn't cull neighbors)
 * - no loot table
 * - basically behaves like air if someone somehow gets it
 */
public class PumpTubeSegmentBlock extends Block {

    public PumpTubeSegmentBlock(BlockBehaviour.Properties props) {
        super(props
                .noCollission()
                .noOcclusion()
                .strength(0.0F)
                .sound(SoundType.METAL)
                .noLootTable());
    }
}
