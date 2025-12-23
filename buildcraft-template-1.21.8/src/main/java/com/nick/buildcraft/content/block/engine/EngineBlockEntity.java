// src/main/java/com/nick/buildcraft/content/block/engine/EngineBlockEntity.java
package com.nick.buildcraft.content.block.engine;

import com.nick.buildcraft.api.engine.EnginePulseAcceptorApi;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class EngineBlockEntity extends BaseEngineBlockEntity {
    private static final double PUSH_EPSILON = 1.0E-5;
    private static final int FE_PER_PULSE = com.nick.buildcraft.energy.Energy.ENGINE_ENERGY_PER_PUMP;

    // Base periods for BLUE phase (matched to original Buildcraft 1.6.4 speeds):
    // Redstone (Wood): BLUE=0.0167, GREEN=0.033, YELLOW=0.067, RED=0.133 (20% slower)
    // Stirling (Stone): BLUE=0.033, GREEN=0.067, YELLOW=0.133, RED=0.267 (20% slower)
    // Combustion (Iron): BLUE=0.04, GREEN=0.05, YELLOW=0.06, RED=0.07
    // Speed per tick = 2 / period, so period = 2 / speed
    private static final int PERIOD_REDSTONE   = 125;  // 0.0167/tick = 2/125 (was 100, now 20% slower)
    private static final int PERIOD_STIRLING   = 62;   // 0.033/tick = 2/62 (was 50, now 20% slower)
    private static final int PERIOD_COMBUSTION = 50;   // 0.04/tick = 2/50

    protected final EngineType type;

    private float progress  = 0.0f;
    private float progressO = 0.0f;

    private int  pumpTick     = 0;
    private int  cachedPeriod = 20;
    private boolean wasPowered = false;

    // ===== HEAT SYSTEM =====
    // Heat accumulation (0.0 = cool, 1.0 = RED phase, will explode)
    private float heat = 0.0f;

    // Heat accumulation rates per tick
    private static final float HEAT_WORKING_RATE = 0.004f;    // Heat up faster when engine is working (80 ticks to reach ORANGE at 0.75)
    private static final float HEAT_IDLE_RATE = 0.0005f;      // Heat up much slower when idle (400 ticks to reach RED at 1.0)
    private static final float HEAT_COOLDOWN_RATE = 0.01f;    // Cool down when powered off (100 ticks to go from RED to cool)

    // RED stage explosion mechanics
    private static final int RED_EXPLOSION_COUNTDOWN = 1200;  // 60 seconds at 20 ticks/sec
    private static final int RED_HISS_INTERVAL = 5;           // Hissing sound every 5 ticks in RED
    private int redCountdownTicks = 0;                         // Countdown timer when in RED stage

    // Healing/cooling mechanics
    private static final float SPEED_COOLDOWN_RATE = 0.02f;   // Slow down speed by 2% per tick when healing
    private float currentSpeedMultiplier = 1.0f;              // Current speed relative to normal (1.0 = full speed, 0.0 = stopped)
    private int lastSoundTick = 0;                            // Track when last healing sound played
    private static final float MIN_SPEED_MULTIPLIER = 0.04f;  // Minimum speed before reaching ORANGE (matches ORANGE speed)

    public EngineBlockEntity(EngineType type, BlockPos pos, BlockState state) {
        super(ModBlockEntity.ENGINE.get(), pos, state);
        this.type = type;
        this.cachedPeriod = basePeriodFor(type);
    }

    @Override
    protected boolean isActive(BlockState state) {
        return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
    }

    @Override
    protected int getGenerationPerTick() {
        int w = warmupTicks();
        var s = type.spec;
        if (w >= s.warmupTicks()) return s.genHot();
        if (w >= s.warmupTicks() / 2) return s.genWarm();
        return s.genCold();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        BaseEngineBlockEntity.serverTick(level, pos, state, be);
        be.tick(level, pos, state);
    }
    public static void clientTick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        be.tick(level, pos, state);
    }

    protected void tick(Level level, BlockPos pos, BlockState state) {
        boolean powered = isActive(state);
        cachedPeriod = basePeriodFor(type);

        // Calculate dynamic period based on current phase
        // Period determines animation speed: speed per tick = 2 / period
        BlockState worldState = level.getBlockState(pos);
        EngineBlock.Phase phase = worldState.getValue(EngineBlock.PHASE);

        float periodMultiplier = switch (phase) {
            case BLUE -> 1.0f;      // base speed
            case GREEN -> 0.5f;     // 2x faster
            case ORANGE -> 0.25f;   // 4x faster
            case RED -> 0.125f;     // 8x faster (but Combustion uses custom values)
        };

        // Special handling for Combustion engine (Iron) which has non-doubling speeds
        if (type == EngineType.COMBUSTION) {
            periodMultiplier = switch (phase) {
                case BLUE -> 1.0f;     // 0.04/tick = 2/50
                case GREEN -> 0.8f;    // 0.05/tick = 2/40
                case ORANGE -> 0.667f; // 0.06/tick = 2/33.3
                case RED -> 0.571f;    // 0.07/tick = 2/35
            };
        }

        int period = Math.max(1, (int)(cachedPeriod * periodMultiplier * currentSpeedMultiplier));

        // DEBUG: log period calculation
        if (!level.isClientSide && pumpTick == 1) {
            System.out.println("[ENGINE] " + type + " phase=" + phase + " cachedPeriod=" + cachedPeriod + " multiplier=" + periodMultiplier + " speedMult=" + currentSpeedMultiplier + " finalPeriod=" + period);
        }

        if (!powered) {
            // HARD stop: clear pulse, zero progress, freeze client & server
            if (!level.isClientSide && state.getBlock() instanceof EngineBlock eb && state.getValue(EngineBlock.PULSING)) {
                level.setBlock(pos, state.setValue(EngineBlock.PULSING, Boolean.FALSE), Block.UPDATE_CLIENTS);
            }
            pumpTick = 0;
            progress = progressO = 0f;
            wasPowered = false;
            // Cool down the engine when powered off
            if (!level.isClientSide) {
                heat = Math.max(0f, heat - HEAT_COOLDOWN_RATE);
                updatePhaseFromHeat(level, pos, state);
            }
            setChanged();
            return;
        }

        if (!wasPowered) {
            pumpTick = 0;
            progress = progressO = 0f;
            wasPowered = true;
        }

        // Update heat and phase system
        if (!level.isClientSide) {
            updateHeatAccumulation(level, pos, state);
            updatePhaseFromHeat(level, pos, state);
            handleRedExplosion(level, pos, state);
        }

        progressO = progress;

        pumpTick++;
        if (pumpTick > period) pumpTick = 1;

        int half = period / 2;
        float stroke = (pumpTick <= half)
                ? (pumpTick / (float)Math.max(1, half))
                : (1f - ((pumpTick - half) / (float)Math.max(1, period - half)));
        setProgress(level, pos, stroke);

        if (!level.isClientSide && pumpTick == half) {
            firePumpPulse(level, pos, state);
        }

        setChanged();
    }

    private void setProgress(Level level, BlockPos pos, float next) {
        double offPrev = offset(progress);
        double offNow  = offset(next);
        if (!level.isClientSide && offPrev != offNow) {
            pushLikePiston(level, pos, offPrev, offNow);
        }
        progress = next;
    }

    private void firePumpPulse(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof EngineBlock eb) {
            level.setBlock(pos, state.setValue(EngineBlock.PULSING, Boolean.TRUE), Block.UPDATE_CLIENTS);
            if (!level.isClientSide) {
                ((net.minecraft.server.level.ServerLevel) level).scheduleTick(pos, state.getBlock(), 2);
            }
        }

        Direction facing = state.getValue(EngineBlock.FACING);
        BlockPos neighborPos = pos.relative(facing);
        Direction from = facing.getOpposite();

        var be = level.getBlockEntity(neighborPos);
        if (be instanceof EnginePulseAcceptorApi acceptor) {
            acceptor.acceptEnginePulse(from);
        }

        var sink = net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK;
        var ie = level.getCapability(sink, neighborPos, from);
        if (ie != null) ie.receiveEnergy(FE_PER_PULSE, false);
    }

    private double offset(float prog) { return RingShapes.MAX_TRAVEL_BLOCKS * prog; }

    private void pushLikePiston(Level level, BlockPos pos, double offPrev, double offNow) {
        boolean movingUp = offNow > offPrev;
        double moveAmt   = Math.abs(offNow - offPrev);
        if (moveAmt <= 0) return;

        VoxelShape start = type.spec.ring().plateAt(offPrev);
        VoxelShape end   = type.spec.ring().plateAt(offNow);

        AABB a0 = start.bounds(), a1 = end.bounds();
        AABB sweep = new AABB(
                Math.min(a0.minX, a1.minX), Math.min(a0.minY, a1.minY), Math.min(a0.minZ, a1.minZ),
                Math.max(a0.maxX, a1.maxX), Math.max(a0.maxY, a1.maxY), Math.max(a0.maxZ, a1.maxZ)
        );

        List<Entity> candidates = level.getEntities(null, sweep);
        if (candidates.isEmpty()) return;

        List<AABB> partsStart = start.toAabbs();
        List<AABB> partsEnd   = end.toAabbs();
        List<AABB> sweptParts = new ArrayList<>(partsStart.size());
        for (int i = 0; i < partsStart.size(); i++) {
            AABB s = partsStart.get(i), e = partsEnd.get(i);
            sweptParts.add(new AABB(
                    s.minX, Math.min(s.minY, e.minY), s.minZ,
                    s.maxX, Math.max(s.maxY, e.maxY), s.maxZ
            ));
        }

        for (Entity ent : candidates) {
            if (ent.isSpectator() || ent.getPistonPushReaction() == PushReaction.IGNORE) continue;

            double needed = 0.0;
            AABB bb = ent.getBoundingBox();
            for (AABB sp : sweptParts) {
                if (!sp.intersects(bb)) continue;
                double pen = movingUp ? (sp.maxY - bb.minY) : (bb.maxY - sp.minY);
                if (pen > needed) needed = pen;
                if (needed >= moveAmt) break;
            }
            if (needed <= 0.0) continue;

            double push = Math.min(needed + PUSH_EPSILON, moveAmt);
            if (!movingUp) push = -push;

            Vec3 before = ent.position();
            ent.move(MoverType.PISTON, new Vec3(0.0, push, 0.0));
            ent.applyEffectsFromBlocks(before, ent.position());
            ent.fallDistance = 0.0f;
        }
    }

    public float  getRenderOffset(float partialTicks) { return (float)(Mth.lerp(partialTicks, progressO, progress) * RingShapes.MAX_TRAVEL_BLOCKS); }
    public double getCollisionOffset() { return offset(progress); }
    public boolean isMovingUpForCollision() { return progress > progressO && progress < 1.0f; }

    public float getHeat() { return heat; }
    public void setHeatClient(float h) { this.heat = h; }

    private static int basePeriodFor(EngineType type) {
        return switch (type) {
            case REDSTONE   -> PERIOD_REDSTONE;
            case STIRLING   -> PERIOD_STIRLING;
            case COMBUSTION -> PERIOD_COMBUSTION;
        };
    }

    /**
     * Updates the engine phase based on current heat level.
     * BLUE: 0.0-0.25 (cool)
     * GREEN: 0.25-0.50 (warm)
     * ORANGE: 0.50-0.75 (working/hot)
     * RED: 0.75-1.0 (critical, will explode)
     *
     * IMPORTANT: Once in RED with countdown active, phase is locked to RED until countdown finishes.
     * This prevents flickering when heat dips temporarily during countdown.
     */
    private void updatePhaseFromHeat(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof EngineBlock) {
            EngineBlock.Phase newPhase = switch ((int)(heat * 4)) {  // 0-1 heat * 4 = 0-4
                case 0, 1 -> EngineBlock.Phase.BLUE;       // 0.0-0.25
                case 2 -> EngineBlock.Phase.GREEN;         // 0.25-0.50
                case 3 -> EngineBlock.Phase.ORANGE;        // 0.50-0.75
                default -> EngineBlock.Phase.RED;          // 0.75+
            };

            BlockState worldState = level.getBlockState(pos);
            EngineBlock.Phase currentPhase = worldState.getValue(EngineBlock.PHASE);

            // HEALING STATE: When reconnected and countdown is active (0 < countdown < 1200)
            // Lock phase to RED even though speed is ORANGE - shows engine is recovering
            if (redCountdownTicks > 0 && redCountdownTicks < RED_EXPLOSION_COUNTDOWN && currentPhase == EngineBlock.Phase.RED) {
                newPhase = EngineBlock.Phase.RED;
            }

            // FULLY HEALED: When countdown reaches 1200 (fully recovered from RED state)
            // Force phase to ORANGE to show healing is complete
            if (redCountdownTicks >= RED_EXPLOSION_COUNTDOWN) {
                newPhase = EngineBlock.Phase.ORANGE;
            }

            if (newPhase != currentPhase) {
                level.setBlock(pos, worldState.setValue(EngineBlock.PHASE, newPhase), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Updates heat accumulation based on whether the engine is working or idle.
     * Working engines (connected to a load) heat up to ORANGE (0.75) and stay there.
     * Idle engines (no load) heat up slowly past ORANGE to RED (1.0).
     * Once in RED countdown: heat is controlled by countdown timer, not accumulation.
     */
    private void updateHeatAccumulation(Level level, BlockPos pos, BlockState state) {
        EngineBlock.Phase currentPhase = state.getValue(EngineBlock.PHASE);

        // If in RED phase with countdown, don't modify heat here - it's controlled by countdown
        if (currentPhase == EngineBlock.Phase.RED && redCountdownTicks > 0) {
            // Heat stays where countdown puts it
            return;
        }

        boolean isWorking = isEngineWorking(level, pos, state);

        if (isWorking) {
            // Heat up faster when working, but cap at ORANGE (0.75) - NEVER go higher
            heat = Math.min(0.75f, heat + HEAT_WORKING_RATE);
            // CRITICAL: if we're working, ALWAYS reset countdown to 0
            // Working engines should never explode, period
            redCountdownTicks = 0;
        } else {
            // Not working: idle heat accumulation
            if (redCountdownTicks == 0) {
                // Not in RED countdown yet: keep accumulating toward RED (1.0)
                heat = Math.min(1.0f, heat + HEAT_IDLE_RATE);
            }
            // Once countdown > 0, heat is controlled by countdown timer
        }
    }

    /**
     * Determines if the engine is currently working (connected to a load).
     * An engine is considered working if it's connected to a neighbor that COULD accept energy,
     * even if that neighbor is currently full. Connected = safe from explosion.
     */
    private boolean isEngineWorking(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(EngineBlock.FACING);
        BlockPos neighborPos = pos.relative(facing);

        // Check if there's an energy acceptor at the front
        // It's "working" if the capability exists (can receive), even if currently full
        var sink = net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK;
        var ie = level.getCapability(sink, neighborPos, facing.getOpposite());
        if (ie != null && ie.canReceive()) {
            return true;  // Connected to energy acceptor - working/safe
        }

        // Check if there's an engine pulse acceptor
        var be = level.getBlockEntity(neighborPos);
        if (be instanceof EnginePulseAcceptorApi) {
            return true;
        }

        return false;
    }

    /**
     * Handles RED stage explosion mechanics.
     * After 60 seconds in RED (with no load), engine explodes and is destroyed.
     * If engine is reconnected to a load while in RED:
     * - Countdown reverses (heals engine back to ORANGE)
     * - Speed gradually decreases by 2% per tick
     * - Heat is directly tied to countdown position for precise interactive feedback
     * - Sound intervals consistently widen as countdown decreases (fewer ticks = less frequent sound)
     */
    private void handleRedExplosion(Level level, BlockPos pos, BlockState state) {
        EngineBlock.Phase currentPhase = state.getValue(EngineBlock.PHASE);
        boolean isWorking = isEngineWorking(level, pos, state);

        if (currentPhase == EngineBlock.Phase.RED) {
            // When in RED phase, manage the countdown
            if (isWorking) {
                // Engine reconnected to load while in RED: REVERSE countdown (heal)
                // FIFTH STATE: HEALING - phase is RED but speed is ORANGE (0.25 multiplier)
                // This shows the engine is in critical condition but stable/improving

                // Increment countdown toward full recovery
                if (redCountdownTicks < RED_EXPLOSION_COUNTDOWN) {
                    redCountdownTicks++;
                    // Once countdown reaches 1200, we're fully healed back to ORANGE
                }

                // Always sync heat to countdown position so heat shows correct progress
                heat = (float) redCountdownTicks / RED_EXPLOSION_COUNTDOWN;

                // CRITICAL: During healing, maintain ORANGE speed (0.25 multiplier = MIN_SPEED_MULTIPLIER)
                // Don't gradually reduce - jump straight to ORANGE speed and maintain it
                currentSpeedMultiplier = MIN_SPEED_MULTIPLIER;  // Stay at ORANGE speed during healing

                // Sound and particle effects only while actively healing (countdown < 1200)
                if (redCountdownTicks < RED_EXPLOSION_COUNTDOWN) {
                    // Sound interval is directly tied to countdown position
                    // More ticks left = shorter interval (more frequent sound)
                    // Fewer ticks left = longer interval (less frequent sound)
                    float countdownRatio = (float) redCountdownTicks / RED_EXPLOSION_COUNTDOWN;
                    int soundInterval = Math.max(1, (int)(RED_HISS_INTERVAL / Math.max(0.1f, countdownRatio)));

                    if (lastSoundTick >= soundInterval) {
                        playHealingSound(level, pos);
                        spawnSmokeParticles(level, pos);
                        lastSoundTick = 0;
                    } else {
                        lastSoundTick++;
                    }
                } else {
                    // Fully healed - no more sounds or particles
                    currentSpeedMultiplier = 1.0f;  // Return to full speed
                    lastSoundTick = 0;
                }
            } else {
                // Engine idle in RED: countdown to explosion
                if (redCountdownTicks == 0) {
                    // Initialize countdown when first entering RED while idle
                    redCountdownTicks = RED_EXPLOSION_COUNTDOWN;
                }

                // Decrement countdown toward explosion
                redCountdownTicks--;

                // Always sync heat to countdown so visual matches timer
                heat = (float) redCountdownTicks / RED_EXPLOSION_COUNTDOWN;

                // Speed returns to normal when disconnected
                currentSpeedMultiplier = 1.0f;
                lastSoundTick = 0;

                // Play hissing sound every RED_HISS_INTERVAL ticks (every ~0.25 seconds)
                if (redCountdownTicks % RED_HISS_INTERVAL == 0) {
                    playHissingSound(level, pos);
                    spawnSmokeParticles(level, pos);
                }

                // Explode when countdown reaches 0
                if (redCountdownTicks <= 0) {
                    explodeEngine(level, pos, state);
                }
            }
        } else {
            // Reset when not in RED: speed returns to normal and sound tracking resets
            redCountdownTicks = 0;
            currentSpeedMultiplier = 1.0f;
            lastSoundTick = 0;
        }
    }

    private void playHissingSound(Level level, BlockPos pos) {
        // Use the sound of water hitting lava (extinguishing)
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f + (level.random.nextFloat() - 0.5f) * 0.2f);
    }

    private void playHealingSound(Level level, BlockPos pos) {
        // Use a cooling/recovery sound (note block ding at higher pitch)
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.5f + (level.random.nextFloat() - 0.5f) * 0.2f);
    }

    private void spawnSmokeParticles(Level level, BlockPos pos) {
        // Spawn smoke particles around the engine
        double x = pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 0.5;
        double y = pos.getY() + 0.5 + (level.random.nextDouble() - 0.5) * 0.5;
        double z = pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 0.5;

        for (int i = 0; i < 3; i++) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    x, y, z,
                    (level.random.nextDouble() - 0.5) * 0.1,
                    level.random.nextDouble() * 0.1,
                    (level.random.nextDouble() - 0.5) * 0.1);
        }
    }

    private void explodeEngine(Level level, BlockPos pos, BlockState state) {
        // Create explosion (2.0f blast radius)
        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                2.0f, Level.ExplosionInteraction.BLOCK);

        // Force destroy the engine block immediately (explosion may not destroy it)
        level.destroyBlock(pos, true);

        // Redundant safety: explicitly set to air if still there
        if (level.getBlockState(pos).getBlock() != net.minecraft.world.level.block.Blocks.AIR) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        // Reset countdown to prevent re-triggering
        redCountdownTicks = 0;
    }

    /**
     * Gets the RPM for this engine based on its current phase and fuel source.
     *
     * RPM design is based on fuel source intensity and work output equivalence:
     * - Redstone (electrical signal): weakest, intermittent nature -> 5-40 RPM (8x scaling)
     * - Stirling (coal combustion): moderate, sustained -> 12-96 RPM (8x scaling, 2.4x Redstone base)
     * - Combustion (liquid fuel): strongest, pre-optimized -> 32-50 RPM (1.56x scaling, 2.67x Stirling base)
     *
     * Visual animation speed still uses the period-based system for piston stroke,
     * but RPM values represent the work output of each engine type.
     */
    public int getRPM() {
        BlockState state;
        if (level != null) {
            state = level.getBlockState(worldPosition);
        } else {
            state = getBlockState();
        }
        EngineBlock.Phase phase = state.getValue(EngineBlock.PHASE);

        return switch (type) {
            case REDSTONE -> switch (phase) {
                case BLUE -> 5;
                case GREEN -> 10;
                case ORANGE -> 20;
                case RED -> 40;
            };
            case STIRLING -> switch (phase) {
                case BLUE -> 12;
                case GREEN -> 24;
                case ORANGE -> 48;
                case RED -> 96;
            };
            case COMBUSTION -> switch (phase) {
                case BLUE -> 32;
                case GREEN -> 38;
                case ORANGE -> 44;
                case RED -> 50;
            };
        };
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        this.heat = in.getFloatOr("Heat", 0.0f);
        this.redCountdownTicks = in.getIntOr("RedCountdown", 0);
        this.currentSpeedMultiplier = in.getFloatOr("SpeedMultiplier", 1.0f);
        this.progress = in.getFloatOr("Progress", 0.0f);
        this.progressO = in.getFloatOr("ProgressO", 0.0f);
        this.pumpTick = in.getIntOr("PumpTick", 0);
        this.wasPowered = in.getBooleanOr("WasPowered", false);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putFloat("Heat", heat);
        out.putInt("RedCountdown", redCountdownTicks);
        out.putFloat("SpeedMultiplier", currentSpeedMultiplier);
        out.putFloat("Progress", progress);
        out.putFloat("ProgressO", progressO);
        out.putInt("PumpTick", pumpTick);
        out.putBoolean("WasPowered", wasPowered);
    }
}
