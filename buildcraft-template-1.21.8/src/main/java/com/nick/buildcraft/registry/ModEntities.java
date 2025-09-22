package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.entity.laser.LaserEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BuildCraft.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<LaserEntity>> LASER =
            ENTITIES.register("laser",
                    () -> EntityType.Builder.<LaserEntity>of(LaserEntity::new, MobCategory.MISC)
                            .sized(0.1f, 0.1f)
                            .build(ResourceKey.create(
                                    Registries.ENTITY_TYPE,
                                    ResourceLocation.fromNamespaceAndPath(BuildCraft.MODID, "laser")
                            )));
}
