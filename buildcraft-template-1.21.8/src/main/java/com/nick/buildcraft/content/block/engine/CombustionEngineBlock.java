package com.nick.buildcraft.content.block.engine;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;
import com.nick.buildcraft.registry.ModBlockEntity;

import java.util.Locale;

/** Combustion engine block with model-splitting PART (BASE/RING/BELLOWS). */
public class CombustionEngineBlock extends EngineBlock {
    public enum Part implements StringRepresentable {
        BASE, RING, BELLOWS;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public static final MapCodec<CombustionEngineBlock> CODEC = Block.simpleCodec(CombustionEngineBlock::new);
    @Override protected MapCodec<CombustionEngineBlock> codec() { return CODEC; }

    public CombustionEngineBlock(BlockBehaviour.Properties props) {
        super(EngineType.COMBUSTION, props);
        this.registerDefaultState(this.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, Boolean.FALSE)
                .setValue(PART, Part.BASE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b); // POWERED + FACING
        b.add(PART);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                         Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        // Handle bucket interactions (same as TankBlock)
        if (stack.getItem() instanceof BucketItem bucketItem && bucketItem.content != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CombustionEngineBlockEntity cbe) {
                IFluidHandler handler = level.getCapability(
                        net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, null);
                if (handler != null) {
                    FluidStack fluidStack = new FluidStack(bucketItem.content, 1000);
                    int filled = handler.fill(fluidStack, IFluidHandler.FluidAction.EXECUTE);
                    if (filled > 0) {
                        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
                        ItemStack emptyBucket = new ItemStack(Items.BUCKET);
                        if (!player.getAbilities().instabuild) {
                            stack.shrink(1);
                            if (stack.isEmpty()) {
                                player.setItemInHand(hand, emptyBucket);
                            } else {
                                player.addItem(emptyBucket);
                            }
                        }
                        return InteractionResult.CONSUME;
                    }
                }
            }
        }
        // Handle empty bucket (drain)
        else if (stack.getItem() == Items.BUCKET) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CombustionEngineBlockEntity cbe) {
                IFluidHandler handler = level.getCapability(
                        net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, null);
                if (handler != null && handler.getTanks() > 0) {
                    FluidStack drained = handler.drain(1000, IFluidHandler.FluidAction.EXECUTE);
                    if (!drained.isEmpty()) {
                        BucketItem filledBucket = (BucketItem) drained.getFluid().getBucket();
                        ItemStack bucket = new ItemStack(filledBucket);
                        level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
                        if (!player.getAbilities().instabuild) {
                            stack.shrink(1);
                            if (stack.isEmpty()) {
                                player.setItemInHand(hand, bucket);
                            } else {
                                player.addItem(bucket);
                            }
                        }
                        return InteractionResult.CONSUME;
                    }
                }
            }
        }

        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CombustionEngineBlockEntity be && player instanceof ServerPlayer sp) {
            sp.openMenu(be, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level lvl, BlockState st, BlockEntityType<T> beType) {
        if (beType != ModBlockEntity.ENGINE.get()) return null;
        BlockEntityTicker<EngineBlockEntity> ticker =
                lvl.isClientSide ? CombustionEngineBlockEntity::clientTick : CombustionEngineBlockEntity::serverTick;
        // TODO: Generic type cast required by Minecraft API - BlockEntityTicker<EngineBlockEntity> -> BlockEntityTicker<T>
        // This is unavoidable due to Java's type erasure. At runtime, T will always be CombustionEngineBlockEntity when beType matches.
        return (BlockEntityTicker<T>) ticker;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CombustionEngineBlockEntity(pos, state);
    }
}
