package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.EngineBlock;
import com.nick.buildcraft.content.block.engine.EngineBlockEntity;
import com.nick.buildcraft.content.block.engine.EngineRingMovingBlockEntity;
import com.nick.buildcraft.content.block.engine.EngineType;
import com.nick.buildcraft.content.block.engine.StirlingEngineBlock;
import com.nick.buildcraft.content.block.engine.StirlingEngineBlockEntity;
import com.nick.buildcraft.content.block.miningwell.MiningWellBlockEntity;
import com.nick.buildcraft.content.block.pipe.DiamondPipeBlock;
import com.nick.buildcraft.content.block.pipe.DiamondPipeBlockEntity;
import com.nick.buildcraft.content.block.pipe.StonePipeBlockEntity;
import com.nick.buildcraft.content.block.pump.PumpBlockEntity;
import com.nick.buildcraft.content.block.quarry.QuarryBlockEntity;
import com.nick.buildcraft.content.block.tank.TankBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

/**
 * Central registry for all BuildCraft BlockEntityTypes.
 *
 * Notes:
 * - Some BlockEntityTypes are polymorphic (ENGINE, STONE_PIPE) and branch their factory
 *   based on the placed block's runtime class.
 * - Others are 1:1 with a single block (PUMP, TANK, QUARRY_CONTROLLER, etc).
 */
public final class ModBlockEntity {

    /** Neoforge deferred register backing all our BE types. */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);

    // Alias kept for backwards compatibility with old code that referred to BLOCK_ENTITIES.
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = BLOCK_ENTITY_TYPES;

    /* --------------------------------------------------------------------- */
    /* Quarry                                                                */
    /* --------------------------------------------------------------------- */

    /**
     * Quarry controller:
     * - server authoritative logic for gantry, frame build, mining, output queue, etc.
     * - must ONLY be attached to ModBlocks.QUARRY_CONTROLLER.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuarryBlockEntity>> QUARRY_CONTROLLER =
            BLOCK_ENTITY_TYPES.register(
                    "quarry_controller",
                    () -> new BlockEntityType<>(
                            QuarryBlockEntity::new,
                            Set.of(ModBlocks.QUARRY_CONTROLLER.get())
                    )
            );

    /* --------------------------------------------------------------------- */
    /* Pipes                                                                  */
    /* --------------------------------------------------------------------- */

    /**
     * All pipe blocks share one BlockEntityType.
     *
     * The factory returns:
     *  - DiamondPipeBlockEntity if the placed block is a DiamondPipeBlock
     *  - StonePipeBlockEntity   for all other pipe blocks (stone, cobble, wood, etc.)
     *
     * This keeps networking / sync simple and avoids needing a separate BE type
     * for every single pipe variant.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StonePipeBlockEntity>> STONE_PIPE =
            BLOCK_ENTITY_TYPES.register(
                    "stone_pipe",
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
                            )
                    )
            );

    /* --------------------------------------------------------------------- */
    /* Engines                                                                */
    /* --------------------------------------------------------------------- */

    /**
     * All engines (redstone / stirling / combustion) share one BlockEntityType.
     *
     * Factory chooses subclass:
     *  - StirlingEngineBlockEntity if it's specifically a StirlingEngineBlock
     *    (has burnable fuel slot)
     *  - EngineBlockEntity otherwise, with an EngineType inferred from the block
     *    if possible. Fallback is REDSTONE.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EngineBlockEntity>> ENGINE =
            BLOCK_ENTITY_TYPES.register(
                    "engine",
                    () -> new BlockEntityType<>(
                            (pos, state) -> {
                                if (state.getBlock() instanceof StirlingEngineBlock) {
                                    return new StirlingEngineBlockEntity(pos, state);
                                }
                                if (state.getBlock() instanceof EngineBlock eb) {
                                    return new EngineBlockEntity(eb.engineType(), pos, state);
                                }
                                // ultra-safe fallback
                                return new EngineBlockEntity(EngineType.REDSTONE, pos, state);
                            },
                            Set.of(
                                    ModBlocks.REDSTONE_ENGINE.get(),
                                    ModBlocks.STIRLING_ENGINE.get(),
                                    ModBlocks.COMBUSTION_ENGINE.get()
                            )
                    )
            );

    /**
     * Temporary piston-style BE used during the animated "moving ring"
     * phase of the engine. This should never exist as an item in the world.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EngineRingMovingBlockEntity>> ENGINE_RING_MOVING =
            BLOCK_ENTITY_TYPES.register(
                    "engine_ring_moving",
                    () -> new BlockEntityType<>(
                            EngineRingMovingBlockEntity::new,
                            Set.of(ModBlocks.MOVING_ENGINE_RING.get())
                    )
            );

    /* --------------------------------------------------------------------- */
    /* Tank                                                                   */
    /* --------------------------------------------------------------------- */

    /**
     * Fluid tank block entity.
     * Holds internal FluidTank, exposes fluid capability to pipes.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TankBlockEntity>> TANK =
            BLOCK_ENTITY_TYPES.register(
                    "tank",
                    () -> new BlockEntityType<>(
                            TankBlockEntity::new,
                            Set.of(ModBlocks.TANK.get())
                    )
            );

    /* --------------------------------------------------------------------- */
    /* Mining Well                                                            */
    /* --------------------------------------------------------------------- */

    /**
     * Mining Well controller:
     * - builds/extends the MiningPipeBlock straight down
     * - breaks blocks into items, etc.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MiningWellBlockEntity>> MINING_WELL =
            BLOCK_ENTITY_TYPES.register(
                    "mining_well",
                    () -> new BlockEntityType<>(
                            MiningWellBlockEntity::new,
                            Set.of(ModBlocks.MINING_WELL.get())
                    )
            );

    /* --------------------------------------------------------------------- */
    /* Pump                                                                   */
    /* --------------------------------------------------------------------- */

    /**
     * Pump controller:
     * - accepts engine pulses / FE,
     * - scans downward for source fluid,
     * - "drains" that source into an internal tank,
     * - tells the client how long the hanging suction tube should be.
     *
     * This BE type must match ModBlocks.PUMP only.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PumpBlockEntity>> PUMP =
            BLOCK_ENTITY_TYPES.register(
                    "pump",
                    () -> new BlockEntityType<>(
                            PumpBlockEntity::new,
                            Set.of(ModBlocks.PUMP.get())
                    )
            );

    private ModBlockEntity() {
        // no instantiation
    }
}
