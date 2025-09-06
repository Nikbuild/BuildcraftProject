package com.nick.buildcraft.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;

/** Data copied from the entity for rendering. Coords are ENTITY-LOCAL. */
public class LaserEntityRenderState extends EntityRenderState {
    public int color;  // RGB
    public Vec3 start; // local to entity origin
    public Vec3 end;   // local to entity origin
}
