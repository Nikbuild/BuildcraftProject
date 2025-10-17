// src/main/java/com/nick/buildcraft/registry/ModFeatures.java
package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.worldgen.OilSpoutFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModFeatures {
    private ModFeatures() {}

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, BuildCraft.MODID);

    public static final DeferredHolder<Feature<?>, Feature<NoneFeatureConfiguration>> OIL_SPOUT =
            FEATURES.register("oil_spout", OilSpoutFeature::new);
}
