package com.nick.buildcraft.content.block.tank;

import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

public class TankBlock extends Block implements EntityBlock {

    public static final BooleanProperty JOINED_BELOW = BooleanProperty.create("joined_below");
    public static final BooleanProperty JOINED_ABOVE  = BooleanProperty.create("joined_above");

    private static final double INSET_PX = 2.0;
    private static final VoxelShape SOLID_SHAPE =
            Block.box(INSET_PX, 0.0D, INSET_PX, 16.0D - INSET_PX, 16.0D, 16.0D - INSET_PX);

    public TankBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(JOINED_BELOW, false)
                .setValue(JOINED_ABOVE,  false));
    }

    /* -------- Block entity -------- */

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TankBlockEntity(pos, state);
    }

    /* -------- Blockstate props -------- */

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(JOINED_BELOW, JOINED_ABOVE);
    }

    private static boolean isTank(BlockState s) { return s.getBlock() instanceof TankBlock; }

    // Join only if neighbor is a tank *and* compatible (logic resides in BE)
    private static boolean isCompatibleJoin(LevelReader level, BlockPos selfPos, BlockPos otherPos) {
        if (!(level instanceof Level real)) return isTank(level.getBlockState(otherPos));
        BlockEntity a = real.getBlockEntity(selfPos);
        BlockEntity b = real.getBlockEntity(otherPos);
        if (a instanceof TankBlockEntity ta && b instanceof TankBlockEntity tb) {
            return TankBlockEntity.areCompatible(ta, tb);
        }
        return false;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level lvl = ctx.getLevel();
        BlockPos p = ctx.getClickedPos();
        return this.defaultBlockState()
                .setValue(JOINED_BELOW, isCompatibleJoin(lvl, p, p.below()))
                .setValue(JOINED_ABOVE,  isCompatibleJoin(lvl, p, p.above()));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTickAccess,
                                     BlockPos pos, Direction dir, BlockPos neighborPos,
                                     BlockState neighborState, RandomSource random) {
        if (dir == Direction.DOWN) state = state.setValue(JOINED_BELOW, isCompatibleJoin(level, pos, neighborPos));
        if (dir == Direction.UP)   state = state.setValue(JOINED_ABOVE,  isCompatibleJoin(level, pos, neighborPos));

        if (level instanceof Level real) {
            BlockEntity be = real.getBlockEntity(pos);
            if (be instanceof TankBlockEntity tbe) {
                tbe.onNeighborChanged();
                if (!real.isClientSide && (dir.getAxis() == Direction.Axis.Y)) tbe.compactDownwardServer();
            }
            real.invalidateCapabilities(pos);
            real.sendBlockUpdated(pos, state, state, UPDATE_CLIENTS);
        }
        return state;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide) {
            level.invalidateCapabilities(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TankBlockEntity tbe) tbe.compactDownwardServer();
        }
    }

    @Override
    public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
        super.destroy(level, pos, state);
        if (level instanceof Level real) {
            real.invalidateCapabilities(pos);
            real.sendBlockUpdated(pos, state, state, UPDATE_CLIENTS);
            for (BlockPos p : new BlockPos[] { pos.above(), pos.below() }) {
                BlockState s = real.getBlockState(p);
                real.sendBlockUpdated(p, s, s, UPDATE_CLIENTS);
                BlockEntity be = real.getBlockEntity(p);
                if (be instanceof TankBlockEntity tbe) {
                    tbe.onNeighborChanged();
                    if (!real.isClientSide) tbe.compactDownwardServer();
                    real.invalidateCapabilities(p);
                }
            }
        }
    }

    /* -------- Interaction logic (now supports milk bucket too) -------- */

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TankBlockEntity tankBe)) return InteractionResult.PASS;

        IFluidHandler handlerColumn = tankBe.getColumnHandler();

        // --- FILL with any BucketItem (works for water/lava + modded bucket fluids) ---
        if (stack.getItem() instanceof BucketItem bucketItem) {
            Fluid fluid = bucketItem.content;
            if (fluid != null && fluid != Fluids.EMPTY) {
                int filled = handlerColumn.fill(new net.neoforged.neoforge.fluids.FluidStack(fluid, 1000),
                        IFluidHandler.FluidAction.EXECUTE);
                if (filled == 1000) {
                    if (!player.isCreative()) player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                    level.playSound(null, pos,
                            fluid.isSame(Fluids.LAVA) ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY,
                            SoundSource.BLOCKS, 1.0F, 1.0F);
                    be.setChanged();
                    level.sendBlockUpdated(pos, state, state, 3);
                    level.invalidateCapabilities(pos);
                    return InteractionResult.SUCCESS;
                }
            }
        }

        // --- FILL with MILK BUCKET (vanilla milk isn't a BucketItem) ---
        if (stack.is(Items.MILK_BUCKET)) {
            Fluid milk = findMilkFluid(level);
            if (milk != Fluids.EMPTY) {
                int filled = handlerColumn.fill(new net.neoforged.neoforge.fluids.FluidStack(milk, 1000),
                        IFluidHandler.FluidAction.EXECUTE);
                if (filled == 1000) {
                    if (!player.isCreative()) player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                    level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    be.setChanged();
                    level.sendBlockUpdated(pos, state, state, 3);
                    level.invalidateCapabilities(pos);
                    return InteractionResult.SUCCESS;
                }
            }
            // If no milk fluid is registered by any mod, do nothing (PASS).
        }

        // --- DRAIN with empty bucket ---
        if (stack.getItem() == Items.BUCKET) {
            FluidStack drained = handlerColumn.drain(1000, IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty() && drained.getAmount() == 1000) {
                Item resultBucket = bucketFor(level, drained);
                if (!player.isCreative()) player.setItemInHand(hand, new ItemStack(resultBucket));
                level.playSound(null, pos,
                        drained.getFluid().isSame(Fluids.LAVA) ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL,
                        SoundSource.BLOCKS, 1.0F, 1.0F);
                be.setChanged();
                level.sendBlockUpdated(pos, state, state, 3);
                level.invalidateCapabilities(pos);
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS;
    }

    /** Chooses correct bucket item for drained fluid (now includes milk if present). */
    private static Item bucketFor(Level level, FluidStack fs) {
        Fluid f = fs.getFluid();

        // BuildCraft fluids
        if (f.isSame(ModFluids.OIL.get()) || f.isSame(ModFluids.FLOWING_OIL.get())) return ModFluids.BUCKET_OIL.get();
        if (f.isSame(ModFluids.FUEL.get()) || f.isSame(ModFluids.FLOWING_FUEL.get())) return ModFluids.BUCKET_FUEL.get();

        // Vanilla
        if (f.isSame(Fluids.WATER) || f.isSame(Fluids.FLOWING_WATER)) return Items.WATER_BUCKET;
        if (f.isSame(Fluids.LAVA)  || f.isSame(Fluids.FLOWING_LAVA))  return Items.LAVA_BUCKET;

        // Milk (from any mod registering a milk fluid)
        Fluid milk = findMilkFluid(level);
        if (milk != Fluids.EMPTY && f.isSame(milk)) return Items.MILK_BUCKET;

        // Fallback
        return Items.BUCKET;
    }

    /* -------- Milk helpers -------- */

    /**
     * Tries to resolve a "milk" Fluid from common IDs used by mods / NeoForge.
     * If none is present, returns Fluids.EMPTY (and milk buckets won't be accepted).
     */
    private static Fluid findMilkFluid(Level level) {
        try {
            var reg = level.registryAccess().lookupOrThrow(Registries.FLUID);
            // Try common names in order of likelihood
            ResourceLocation[] candidates = new ResourceLocation[] {
                    ResourceLocation.parse("neoforge:milk"),
                    ResourceLocation.parse("forge:milk"),
                    ResourceLocation.parse("create:milk"),
                    ResourceLocation.parse("minecraft:milk") // just in case some pack adds it
            };
            for (ResourceLocation rl : candidates) {
                var opt = reg.get(rl);
                if (opt.isPresent()) {
                    Fluid f = opt.get().value();
                    if (f != null && f != Fluids.EMPTY) return f;
                }
            }
        } catch (Exception ignored) {}
        return Fluids.EMPTY;
    }

    /* -------- Shapes / rendering -------- */

    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SOLID_SHAPE; }
    @Override protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SOLID_SHAPE; }
    @Override protected VoxelShape getBlockSupportShape(BlockState s, BlockGetter l, BlockPos p) { return SOLID_SHAPE; }
    @Override protected VoxelShape getInteractionShape(BlockState s, BlockGetter l, BlockPos p) { return SOLID_SHAPE; }

    /** Hide faces only when actually joined (so red ring shows at mixed seams). */
    @Override
    protected boolean skipRendering(BlockState thisState, BlockState otherState, Direction side) {
        if (otherState.getBlock() instanceof TankBlock && side.getAxis() == Direction.Axis.Y) {
            if (side == Direction.UP)   return thisState.getValue(JOINED_ABOVE);
            if (side == Direction.DOWN) return thisState.getValue(JOINED_BELOW);
        }
        return super.skipRendering(thisState, otherState, side);
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
}
