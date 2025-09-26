// src/main/java/com/nick/buildcraft/content/item/WrenchItem.java
package com.nick.buildcraft.content.item;

import com.nick.buildcraft.api.wrench.Wrenchable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;   // <-- add this import

public class WrenchItem extends Item {
    public WrenchItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        var level = ctx.getLevel();
        var pos   = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof Wrenchable w) {
            // Reconstruct a hit result from public context methods
            BlockHitResult hit = new BlockHitResult(
                    ctx.getClickLocation(),     // Vec3 of the click
                    ctx.getClickedFace(),       // face clicked
                    ctx.getClickedPos(),        // block position
                    false                       // "inside" flag; ok to pass false here
                    // If your MC mappings expose ctx.isInside(), you can pass that instead.
            );
            return w.onWrench(state, level, pos, ctx.getPlayer(), hit);
        }
        return InteractionResult.PASS;
    }
}
