package com.nick.buildcraft.content.block.pump;

import com.nick.buildcraft.registry.ModBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * BuildCraft Pump controller block.
 *
 * Responsibilities:
 * - Owns PumpBlockEntity, which actually does the scanning/draining logic.
 * - Has a horizontal FACING like the Mining Well.
 * - Ticks server-side so the pump logic runs.
 *
 * The "tube" below it is NOT a block. It's rendered by PumpRenderer
 * using data synced from the PumpBlockEntity.
 */
public class PumpBlock extends Block implements EntityBlock {

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    public PumpBlock(Properties props) {
        super(props);
        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(FACING, Direction.NORTH)
        );
    }

    /* ------------------------------------------------------------ */
    /* Blockstate setup / placement                                 */
    /* ------------------------------------------------------------ */

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Face the same way the player is looking horizontally.
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    /* ------------------------------------------------------------ */
    /* BlockEntity hookup                                           */
    /* ------------------------------------------------------------ */

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBlockEntity.PUMP.get().create(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        // Only tick on the server. Client just renders.
        return (!level.isClientSide && type == ModBlockEntity.PUMP.get())
                ? (lvl, p, st, be) -> PumpBlockEntity.serverTick(lvl, p, st, (PumpBlockEntity) be)
                : null;
    }
}
