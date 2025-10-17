// src/main/java/com/nick/buildcraft/registry/ModBlockEntity.java
package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.EngineBlock;
import com.nick.buildcraft.content.block.engine.EngineBlockEntity;
import com.nick.buildcraft.content.block.engine.EngineRingMovingBlockEntity;
import com.nick.buildcraft.content.block.engine.EngineType;
import com.nick.buildcraft.content.block.engine.StirlingEngineBlock;
import com.nick.buildcraft.content.block.engine.StirlingEngineBlockEntity;
import com.nick.buildcraft.content.block.pipe.DiamondPipeBlock;
import com.nick.buildcraft.content.block.pipe.DiamondPipeBlockEntity;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import com.nick.buildcraft.content.block.tank.TankBlockEntity; // ← NEW
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntity {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = BLOCK_ENTITY_TYPES;

    /** Quarry controller block entity */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuarryBlockEntity>> QUARRY_CONTROLLER =
            BLOCK_ENTITY_TYPES.register("quarry_controller",
                    () -> new BlockEntityType<>(
                            QuarryBlockEntity::new,
                            Set.of(ModBlocks.QUARRY_CONTROLLER.get()))
            );

    /**
     * Single BE type for all pipes. Factory branches by block to return the correct subclass.
     * - Diamond pipe -> DiamondPipeBlockEntity
     * - Others       -> StonePipeBlockEntity
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StonePipeBlockEntity>> STONE_PIPE =
            BLOCK_ENTITY_TYPES.register("stone_pipe",
                    () -> new BlockEntityType<>(
                            (pos, state) -> {
                                if (state.getBlock() instanceof DiamondPipeBlock) {
                                    return new DiamondPipeBlockEntity(pos, state);
                                }
                                return new StonePipeBlockEntity(pos, state);
                            },
                            Set.of(
                                    ModBlocks.STONE_PIPE.get(),
                                    ModBlocks.COBBLE_PIPE.get(),
                                    ModBlocks.GOLD_PIPE.get(),
                                    ModBlocks.IRON_PIPE.get(),
                                    ModBlocks.DIAMOND_PIPE.get(),
                                    ModBlocks.WOOD_PIPE.get()
                            ))
            );

    /**
     * Engines share one BE type. The factory returns the correct subclass:
     * - Stirling -> StirlingEngineBlockEntity (has fuel slot)
     * - Others   -> EngineBlockEntity (shared logic)
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EngineBlockEntity>> ENGINE =
            BLOCK_ENTITY_TYPES.register("engine",
                    () -> new BlockEntityType<>(
                            (pos, state) -> {
                                if (state.getBlock() instanceof StirlingEngineBlock) {
                                    return new StirlingEngineBlockEntity(pos, state);
                                }
                                if (state.getBlock() instanceof EngineBlock eb) {
                                    return new EngineBlockEntity(eb.engineType(), pos, state);
                                }
                                // Fallback (defensive)
                                return new EngineBlockEntity(EngineType.REDSTONE, pos, state);
                            },
                            Set.of(
                                    ModBlocks.REDSTONE_ENGINE.get(),
                                    ModBlocks.STIRLING_ENGINE.get(),
                                    ModBlocks.COMBUSTION_ENGINE.get()
                            ))
            );

    /** Temporary piston-style BE for the moving ring (used by MovingEngineRingBlock). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EngineRingMovingBlockEntity>> ENGINE_RING_MOVING =
            BLOCK_ENTITY_TYPES.register("engine_ring_moving",
                    () -> new BlockEntityType<>(
                            EngineRingMovingBlockEntity::new,
                            Set.of(ModBlocks.MOVING_ENGINE_RING.get()))
            );

    /** Tank block entity registration */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TankBlockEntity>> TANK =
            BLOCK_ENTITY_TYPES.register("tank",
                    () -> new BlockEntityType<>(
                            TankBlockEntity::new,
                            Set.of(ModBlocks.TANK.get()))
            );

    private ModBlockEntity() {}
}
