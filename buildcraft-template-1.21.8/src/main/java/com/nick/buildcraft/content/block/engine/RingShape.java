package com.nick.buildcraft.content.block.engine;

import net.minecraft.world.phys.shapes.VoxelShape;

public interface RingShape {
    /** Hollow/visible frame (used for outline/collision when at rest). */
    VoxelShape frameAt(double offsetBlocks);
    /** Solid plate used for pushing (piston-like) while moving. */
    VoxelShape plateAt(double offsetBlocks);
}
