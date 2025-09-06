package com.nick.buildcraft.content.entity.laser;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class LaserEntity extends Entity {

    private static final EntityDataAccessor<Integer> COLOR =
            SynchedEntityData.defineId(LaserEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> START =
            SynchedEntityData.defineId(LaserEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> END =
            SynchedEntityData.defineId(LaserEntity.class, EntityDataSerializers.VECTOR3);

    public LaserEntity(EntityType<? extends LaserEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(COLOR, 0xFF0000);
        b.define(START, new Vector3f(0f, 0f, 0f));
        b.define(END,   new Vector3f(0f, 0f, 0f));
    }

    public void setColor(int color) { this.entityData.set(COLOR, color); }
    public int  getColor()          { return this.entityData.get(COLOR); }

    public void setEndpoints(Vec3 start, Vec3 end) {
        this.entityData.set(START, toV3f(start));
        this.entityData.set(END,   toV3f(end));

        // keep the entity's logical center at the segment midpoint
        this.setPos(
                (start.x + end.x) * 0.5,
                (start.y + end.y) * 0.5,
                (start.z + end.z) * 0.5
        );

        // expand the entity's culling/visibility box to cover the whole beam
        // (inflate a bit so a perfectly axis-aligned, zero-thickness line doesn’t vanish)
        this.setBoundingBox(new AABB(start, end).inflate(0.15));
    }

    public void setStart(Vec3 start) { setEndpoints(start, getEnd()); }
    public void setEnd(Vec3 end)     { setEndpoints(getStart(), end); }

    public Vec3 getStart() { return toVec3(this.entityData.get(START)); }
    public Vec3 getEnd()   { return toVec3(this.entityData.get(END));   }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        setColor(in.getIntOr("Color", 0xFF0000));
        Vec3 s = new Vec3(in.getDoubleOr("StartX", 0), in.getDoubleOr("StartY", 0), in.getDoubleOr("StartZ", 0));
        Vec3 e = new Vec3(in.getDoubleOr("EndX", 0),   in.getDoubleOr("EndY", 0),   in.getDoubleOr("EndZ", 0));
        setEndpoints(s, e);
    }


    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.putInt("Color", getColor());
        Vec3 s = getStart(), e = getEnd();
        out.putDouble("StartX", s.x); out.putDouble("StartY", s.y); out.putDouble("StartZ", s.z);
        out.putDouble("EndX",   e.x); out.putDouble("EndY",   e.y); out.putDouble("EndZ",   e.z);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, serverEntity);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    private static Vector3f toV3f(Vec3 v) { return new Vector3f((float)v.x, (float)v.y, (float)v.z); }
    private static Vec3 toVec3(Vector3f v) { return new Vec3(v.x, v.y, v.z); }
}
