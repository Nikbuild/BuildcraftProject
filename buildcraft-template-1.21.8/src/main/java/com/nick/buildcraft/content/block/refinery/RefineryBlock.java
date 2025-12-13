package com.nick.buildcraft.content.block.refinery;

import com.mojang.serialization.MapCodec;
import com.nick.buildcraft.content.block.pipe.WoodPipeBlock;
import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/**
 * Refinery block with magnets.
 * Uses PART property for multipart rendering of base + magnet cuboids.
 */
public class RefineryBlock extends Block implements EntityBlock {

    public enum Part implements StringRepresentable {
        BASE, MAGNET_LEFT, MAGNET_RIGHT;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public static final MapCodec<RefineryBlock> CODEC = Block.simpleCodec(RefineryBlock::new);
    @Override
    public MapCodec<RefineryBlock> codec() {
        return CODEC;
    }

    public RefineryBlock(BlockBehaviour.Properties props) {
        super(props);
        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(BlockStateProperties.FACING, Direction.NORTH)
                        .setValue(PART, Part.BASE)
        );
    }

    @Override
    public VoxelShape getVisualShape(BlockState state,
                                     BlockGetter getter,
                                     BlockPos pos,
                                     net.minecraft.world.phys.shapes.CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return getShapeForFacing(state.getValue(BlockStateProperties.FACING));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return getShapeForFacing(state.getValue(BlockStateProperties.FACING));
    }

    /**
     * Returns the collision shape for the refinery based on facing direction.
     * Uses three separate tank shapes forming a T-shape.
     */
    private static VoxelShape getShapeForFacing(Direction facing) {
        // Three separate tank shapes (NORTH facing):
        // Tank 1 (back left): x [0-0.5], z [-0.001-0.5] (extends backward to include visible back face)
        VoxelShape tank1 = Shapes.box(0, 0, -0.001, 0.5, 1, 0.5);
        // Tank 2 (back right): x [0.5-1.0], z [-0.001-0.5] (extends backward to include visible back face)
        VoxelShape tank2 = Shapes.box(0.5, 0, -0.001, 1, 1, 0.5);
        // Tank 3 (front center): x [0.25-0.75], z [0.5-1.0]
        VoxelShape tank3 = Shapes.box(0.25, 0, 0.5, 0.75, 1, 1);

        // Combine all three tanks
        VoxelShape baseShape = Shapes.or(tank1, tank2, tank3);

        // Rotate based on facing direction
        return switch (facing) {
            case NORTH -> baseShape;
            case EAST -> rotateShapeCW(baseShape);
            case SOUTH -> rotateShapeCW(rotateShapeCW(baseShape));
            case WEST -> rotateShapeCW(rotateShapeCW(rotateShapeCW(baseShape)));
            default -> baseShape;
        };
    }

    /**
     * Rotates a VoxelShape 90 degrees clockwise around the Y axis.
     */
    private static VoxelShape rotateShapeCW(VoxelShape shape) {
        VoxelShape result = Shapes.empty();
        for (net.minecraft.world.phys.AABB box : shape.toAabbs()) {
            // Rotate 90° CW: new_x = 1 - old_z, new_z = old_x
            net.minecraft.world.phys.AABB rotated = new net.minecraft.world.phys.AABB(
                1 - box.maxZ, box.minY, box.minX,
                1 - box.minZ, box.maxY, box.maxX
            );
            result = Shapes.or(result, Shapes.create(rotated));
        }
        return result;
    }


    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.FACING);
        builder.add(PART);
    }

    /* -------------------------------- placement -------------------------------- */

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Refinery facing is determined purely by where the player clicked, never auto-rotates after placement
        Direction facing = facingFromHit(ctx);
        return this.stateDefinition.any().setValue(BlockStateProperties.FACING, facing).setValue(PART, Part.BASE);
    }

    /** Purely position-driven facing selection (all 6 directions). */
    private static Direction facingFromHit(BlockPlaceContext ctx) {
        final Direction face = ctx.getClickedFace();          // face of the block you clicked
        final BlockPos placePos = ctx.getClickedPos();        // where this block will go
        final Vec3 hit = ctx.getClickLocation();

        final double hx = hit.x - placePos.getX();
        final double hy = hit.y - placePos.getY();
        final double hz = hit.z - placePos.getZ();

        switch (face) {
            case UP -> {
                final double dx = Math.abs(hx - 0.5), dz = Math.abs(hz - 0.5), C = 0.22;
                if (dx < C && dz < C) return Direction.UP;
                return dx > dz ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hz > 0.5 ? Direction.SOUTH : Direction.NORTH);
            }
            case DOWN -> {
                final double dx = Math.abs(hx - 0.5), dz = Math.abs(hz - 0.5), C = 0.22;
                if (dx < C && dz < C) return Direction.DOWN;
                return dx > dz ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hz > 0.5 ? Direction.SOUTH : Direction.NORTH);
            }
            case NORTH -> {
                final double dx = Math.abs(hx - 0.5), dy = Math.abs(hy - 0.5);
                return dx > dy ? (hx > 0.5 ? Direction.EAST : Direction.WEST)
                        : (hy > 0.5 ? Direction.UP   : Direction.DOWN);
            }
            case SOUTH -> {
                final double dx = Math.abs(hx - 0.5), dy = Math.abs(hy - 0.5);
                return dx > dy ? (hx > 0.5 ? Direction.WEST : Direction.EAST)
                        : (hy > 0.5 ? Direction.UP   : Direction.DOWN);
            }
            case WEST -> {
                final double dz = Math.abs(hz - 0.5), dy = Math.abs(hy - 0.5);
                return dz > dy ? (hz > 0.5 ? Direction.SOUTH : Direction.NORTH)
                        : (hy > 0.5 ? Direction.UP    : Direction.DOWN);
            }
            case EAST -> {
                final double dz = Math.abs(hz - 0.5), dy = Math.abs(hy - 0.5);
                return dz > dy ? (hz > 0.5 ? Direction.NORTH : Direction.SOUTH)
                        : (hy > 0.5 ? Direction.UP    : Direction.DOWN);
            }
        }
        return Direction.NORTH;
    }


    /* -------------------------------- bucket interaction -------------------------------- */

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RefineryBlockEntity refinery)) return InteractionResult.PASS;

        // Determine which tank was clicked based on exact hit location within the block
        Direction facing = state.getValue(BlockStateProperties.FACING);
        IFluidHandler handler = getTankForHitLocation(hit, facing, refinery);
        if (handler == null) return InteractionResult.PASS;

        // Try bucket interaction
        ItemStack held = player.getItemInHand(hand);
        if (tryBucketInteraction(level, pos, player, hand, held, handler)) {
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    /**
     * Determines which tank was clicked based on the exact hit location within the block.
     * Uses precise coordinate checking to determine which tank geometry was hit.
     */
    @Nullable
    private static IFluidHandler getTankForHitLocation(BlockHitResult hit, Direction facing, RefineryBlockEntity refinery) {
        // Get hit location relative to block
        Vec3 hitLoc = hit.getLocation();
        BlockPos blockPos = hit.getBlockPos();
        float hx = (float) (hitLoc.x - blockPos.getX());
        float hz = (float) (hitLoc.z - blockPos.getZ());

        // Clamp to valid range, but allow for the extended back face at z = [-0.001, 0]
        hx = Math.max(0, Math.min(1, hx));
        hz = Math.max(-0.001f, Math.min(1, hz));

        // First, transform hit location to NORTH-facing orientation
        // This accounts for the block's rotation
        float normX = hx;
        float normZ = hz;

        switch (facing) {
            case EAST -> {
                // Rotate 90° CCW to convert EAST coords to NORTH coords
                // new_x = old_z, new_z = 1 - old_x
                normX = hz;
                normZ = 1.0f - hx;
            }
            case SOUTH -> {
                // Rotate 180° to convert SOUTH coords to NORTH coords
                // new_x = 1 - old_x, new_z = 1 - old_z
                normX = 1.0f - hx;
                normZ = 1.0f - hz;
            }
            case WEST -> {
                // Rotate 90° CW to convert WEST coords to NORTH coords
                // new_x = 1 - old_z, new_z = old_x
                normX = 1.0f - hz;
                normZ = hx;
            }
            default -> {} // NORTH: no transformation needed
        }

        // Now apply NORTH-facing tank logic to the normalized coordinates
        IFluidHandler result;
        if (normZ < 0.5f) {
            // Back tanks: Tank 1 (left) or Tank 2 (right)
            result = normX < 0.5f ? refinery.getOilTank1() : refinery.getOilTank2();
        } else if (normX >= 0.25f && normX <= 0.75f) {
            // Front tank main body
            result = refinery.getFuelTank();
        } else if (normZ >= 0.5f) {
            // Exposed side edges of front tank (left and right sides)
            // x [0-0.25] = left side of front tank -> fill rear left (tank1)
            // x [0.75-1.0] = right side of front tank -> fill rear right (tank2)
            result = normX < 0.5f ? refinery.getOilTank1() : refinery.getOilTank2();
        } else {
            result = null;
        }

        // Fallback: if click is in gap between tanks, return the closest tank
        if (result == null) {
            result = refinery.getFuelTank();
        }
        return result;
    }

    /**
     * Handles bucket fill/drain interactions with the tank.
     * Returns true if interaction succeeded.
     */
    private boolean tryBucketInteraction(Level level, BlockPos pos, Player player,
                                         InteractionHand hand, ItemStack held,
                                         IFluidHandler tankHandler) {
        // Get bucket's fluid capability
        IFluidHandlerItem bucketCap = held.getCapability(Capabilities.FluidHandler.ITEM);
        if (bucketCap == null) return false;

        // Get the block entity for syncing
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RefineryBlockEntity refinery)) return false;

        // Try to drain from tank into bucket
        FluidStack drained = tankHandler.drain(1000, IFluidHandler.FluidAction.SIMULATE);
        if (!drained.isEmpty()) {
            int filled = bucketCap.fill(drained, IFluidHandler.FluidAction.SIMULATE);
            if (filled > 0) {
                // Actually drain from tank
                FluidStack actualDrain = tankHandler.drain(filled, IFluidHandler.FluidAction.EXECUTE);
                // Actually fill bucket
                bucketCap.fill(actualDrain, IFluidHandler.FluidAction.EXECUTE);

                // Replace item in hand
                ItemStack result = bucketCap.getContainer();
                if (!player.isCreative()) {
                    if (held.getCount() == 1) {
                        player.setItemInHand(hand, result);
                    } else {
                        held.shrink(1);
                        if (!player.addItem(result)) {
                            player.drop(result, false);
                        }
                    }
                }

                // Sync the block entity to all clients
                refinery.setChanged();
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);

                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
                return true;
            }
        }

        // Try to fill tank from bucket
        FluidStack inBucket = bucketCap.drain(1000, IFluidHandler.FluidAction.SIMULATE);
        if (!inBucket.isEmpty()) {
            int filled = tankHandler.fill(inBucket, IFluidHandler.FluidAction.SIMULATE);
            if (filled > 0) {
                // Actually drain bucket
                FluidStack actualDrain = bucketCap.drain(filled, IFluidHandler.FluidAction.EXECUTE);
                // Actually fill tank
                tankHandler.fill(actualDrain, IFluidHandler.FluidAction.EXECUTE);

                // Replace item in hand
                ItemStack result = bucketCap.getContainer();
                if (!player.isCreative()) {
                    if (held.getCount() == 1) {
                        player.setItemInHand(hand, result);
                    } else {
                        held.shrink(1);
                        if (!player.addItem(result)) {
                            player.drop(result, false);
                        }
                    }
                }

                // Sync the block entity to all clients
                refinery.setChanged();
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);

                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
                return true;
            }
        }

        return false;
    }

    /* --------------------------------- ticker ---------------------------------- */

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> beType) {
        if (beType != ModBlockEntity.REFINERY.get()) return null;
        BlockEntityTicker<RefineryBlockEntity> ticker =
                level.isClientSide ? RefineryBlockEntity::clientTick : RefineryBlockEntity::serverTick;
        return (BlockEntityTicker<T>) ticker;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RefineryBlockEntity(pos, state);
    }
}
