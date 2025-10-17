// src/main/java/com/nick/buildcraft/content/worldgen/OilSpoutFeature.java
package com.nick.buildcraft.content.worldgen;

import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Simple BuildCraft-style oil spout: a thin vertical column with a small puddle at the base. */
public class OilSpoutFeature extends Feature<NoneFeatureConfiguration> {
    public OilSpoutFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource rand = ctx.random();
        BlockPos origin = ctx.origin();

        // Find surface at this x/z using the world-surface heightmap
        int x = origin.getX();
        int z = origin.getZ();
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (surfaceY <= 0) return false; // very defensive

        BlockPos base = new BlockPos(x, surfaceY, z);
        BlockState oil = ModFluids.OIL_BLOCK.get().defaultBlockState();

        // Puddle radius 1–2 (small disc around base)
        int radius = 1 + rand.nextInt(2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((dx * dx + dz * dz) <= (radius * radius)) {
                    BlockPos p = base.offset(dx, 0, dz);
                    level.setBlock(p, oil, 2);
                }
            }
        }

        // Spout height 4–9 blocks; stop if we hit something solid
        int height = 4 + rand.nextInt(6);
        for (int dy = 1; dy <= height; dy++) {
            BlockPos column = base.above(dy);
            if (!level.isEmptyBlock(column)) break;
            level.setBlock(column, oil, 2);
        }

        return true;
    }
}
