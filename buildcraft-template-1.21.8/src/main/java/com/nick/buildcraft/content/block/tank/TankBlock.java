package com.nick.buildcraft.content.block.tank;

import com.nick.buildcraft.registry.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
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

/**
 * A glass tank that can stack vertically and act as one large column.
 * Each tank holds 16 buckets (16,000 mB).
 * Works with any fluid that has a registered bucket (water, lava, oil, fuel, etc.)
 */
public class TankBlock extends Block implements EntityBlock {

    public static final BooleanProperty JOINED_BELOW = BooleanProperty.create("joined_below");
    public static final BooleanProperty JOINED_ABOVE = BooleanProperty.create("joined_above");

    private static final double INSET_PX = 2.0;
    private static final VoxelShape SOLID_SHAPE =
            Block.box(INSET_PX, 0.0D, INSET_PX, 16.0D - INSET_PX, 16.0D, 16.0D - INSET_PX);

    public TankBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(JOINED_BELOW, false)
                .setValue(JOINED_ABOVE, false));
    }

    /* -------- Block entity -------- */

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TankBlockEntity(pos, state);
    }

    /* -------- Blockstate props -------- */

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(JOINED_BELOW, JOINED_ABOVE);
    }

    private static boolean isTank(BlockState s) {
        return s.getBlock() instanceof TankBlock;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level lvl = ctx.getLevel();
        BlockPos p = ctx.getClickedPos();
        return this.defaultBlockState()
                .setValue(JOINED_BELOW, isTank(lvl.getBlockState(p.below())))
                .setValue(JOINED_ABOVE, isTank(lvl.getBlockState(p.above())));
    }

    /** Updates neighbor connectivity (stacked tanks share faces). */
    @Override
    protected BlockState updateShape(BlockState state,
                                     LevelReader level,
                                     ScheduledTickAccess scheduledTickAccess,
                                     BlockPos pos,
                                     Direction direction,
                                     BlockPos neighborPos,
                                     BlockState neighborState,
                                     RandomSource random) {
        if (direction == Direction.DOWN) state = state.setValue(JOINED_BELOW, isTank(neighborState));
        if (direction == Direction.UP)   state = state.setValue(JOINED_ABOVE, isTank(neighborState));

        if (level instanceof Level real && !real.isClientSide) {
            BlockEntity be = real.getBlockEntity(pos);
            if (be instanceof TankBlockEntity tbe) tbe.onNeighborChanged();
        }
        return state;
    }

    /* -------- Interaction logic (universal bucket support) -------- */

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TankBlockEntity tankBe)) return InteractionResult.PASS;

        IFluidHandler handler = tankBe.getColumnHandler();

        // --- FILL with any fluid bucket ---
        if (stack.getItem() instanceof BucketItem bucketItem) {
            Fluid fluid = bucketItem.content;
            if (fluid != null && fluid != Fluids.EMPTY) {
                int filled = handler.fill(new FluidStack(fluid, 1000), IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    if (!player.isCreative()) {
                        player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                    }
                    level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    be.setChanged();
                    level.sendBlockUpdated(pos, state, state, 3);
                    // 👇 Fix for first-fill invisibility
                    level.sendBlockUpdated(pos, state, state, 3);
                    level.invalidateCapabilities(pos); // optional: re-sync fluid caps if needed
                    return InteractionResult.SUCCESS;
                }
            }
        }

        // --- DRAIN with empty bucket ---
        if (stack.getItem() == Items.BUCKET) {
            FluidStack drained = handler.drain(1000, IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) {
                Item resultBucket = bucketFor(drained);
                if (!player.isCreative()) {
                    player.setItemInHand(hand, new ItemStack(resultBucket));
                }
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                be.setChanged();
                level.sendBlockUpdated(pos, state, state, 3);
                // 👇 Ensure visuals sync after draining
                level.sendBlockUpdated(pos, state, state, 3);
                level.invalidateCapabilities(pos); // optional: re-sync fluid caps if needed
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS;
    }

    /** Chooses correct bucket item for drained fluid. */
    private static Item bucketFor(FluidStack fs) {
        Fluid f = fs.getFluid();

        // BuildCraft fluids
        if (f.isSame(ModFluids.OIL.get()) || f.isSame(ModFluids.FLOWING_OIL.get())) return ModFluids.BUCKET_OIL.get();
        if (f.isSame(ModFluids.FUEL.get()) || f.isSame(ModFluids.FLOWING_FUEL.get())) return ModFluids.BUCKET_FUEL.get();

        // Vanilla
        if (f.isSame(Fluids.WATER) || f.isSame(Fluids.FLOWING_WATER)) return Items.WATER_BUCKET;
        if (f.isSame(Fluids.LAVA) || f.isSame(Fluids.FLOWING_LAVA)) return Items.LAVA_BUCKET;

        // Fallback
        return Items.BUCKET;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    /* -------- Shapes / rendering -------- */

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SOLID_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SOLID_SHAPE;
    }

    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SOLID_SHAPE;
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SOLID_SHAPE;
    }

    /** Hide top/bottom faces when stacked to make glass columns look continuous. */
    @Override
    protected boolean skipRendering(BlockState thisState, BlockState otherState, Direction side) {
        if (otherState.getBlock() instanceof TankBlock && side.getAxis() == Direction.Axis.Y) {
            return true;
        }
        return super.skipRendering(thisState, otherState, side);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
