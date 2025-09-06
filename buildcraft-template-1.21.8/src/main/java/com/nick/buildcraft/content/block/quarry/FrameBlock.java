package com.nick.buildcraft.content.block.quarry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FrameBlock extends Block {

    // ----- State -----
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    public enum Corner implements StringRepresentable {
        NONE("none"),
        XMIN_ZMIN_BOTTOM("xmin_zmin_bottom"),
        XMIN_ZMIN_TOP   ("xmin_zmin_top"),
        XMIN_ZMAX_BOTTOM("xmin_zmax_bottom"),
        XMIN_ZMAX_TOP   ("xmin_zmax_top"),
        XMAX_ZMIN_BOTTOM("xmax_zmin_bottom"),
        XMAX_ZMIN_TOP   ("xmax_zmin_top"),
        XMAX_ZMAX_BOTTOM("xmax_zmax_bottom"),
        XMAX_ZMAX_TOP   ("xmax_zmax_top");

        private final String id;
        Corner(String id) { this.id = id; }
        @Override public String getSerializedName() { return id; }
    }
    public static final EnumProperty<Corner> CORNER = EnumProperty.create("corner", Corner.class);

    // ----- FX -----
    private static final DustParticleOptions SPARK = new DustParticleOptions(0xFF9900, 1.0f);

    // ----- Shapes (0..16) -----
    // Rods now use 5..11 cross-section so they meet the corner arms (no floating gap).
    private static final VoxelShape ROD_X = Block.box(0, 5, 5, 16, 11, 11);
    private static final VoxelShape ROD_Y = Block.box(5, 0, 5, 11, 16, 11);
    private static final VoxelShape ROD_Z = Block.box(5, 5, 0, 11, 11, 16);

    // Horizontal arms toward faces (half blocks)
    private static final VoxelShape XMIN_ARM = Block.box(0, 5, 5,  8, 11, 11);
    private static final VoxelShape XMAX_ARM = Block.box(8, 5, 5, 16, 11, 11);
    private static final VoxelShape ZMIN_ARM = Block.box(5, 5, 0, 11, 11,  8);
    private static final VoxelShape ZMAX_ARM = Block.box(5, 5, 8, 11, 11, 16);

    // Vertical arms: TOP corners -> DOWN; BOTTOM corners -> UP
    private static final VoxelShape Y_DOWN_ARM = Block.box(5, 0, 5, 11, 11, 11);
    private static final VoxelShape Y_UP_ARM   = Block.box(5, 5, 5, 11, 16, 11);

    // Bottom corners (upward vertical)
    private static final VoxelShape CORNER_XMIN_ZMIN_BOTTOM = Shapes.or(XMAX_ARM, ZMAX_ARM, Y_UP_ARM);
    private static final VoxelShape CORNER_XMIN_ZMAX_BOTTOM = Shapes.or(XMAX_ARM, ZMIN_ARM, Y_UP_ARM);
    private static final VoxelShape CORNER_XMAX_ZMIN_BOTTOM = Shapes.or(XMIN_ARM, ZMAX_ARM, Y_UP_ARM);
    private static final VoxelShape CORNER_XMAX_ZMAX_BOTTOM = Shapes.or(XMIN_ARM, ZMIN_ARM, Y_UP_ARM);

    // Top corners (downward vertical)
    private static final VoxelShape CORNER_XMIN_ZMIN_TOP = Shapes.or(XMAX_ARM, ZMAX_ARM, Y_DOWN_ARM);
    private static final VoxelShape CORNER_XMIN_ZMAX_TOP = Shapes.or(XMAX_ARM, ZMIN_ARM, Y_DOWN_ARM);
    private static final VoxelShape CORNER_XMAX_ZMIN_TOP = Shapes.or(XMIN_ARM, ZMAX_ARM, Y_DOWN_ARM);
    private static final VoxelShape CORNER_XMAX_ZMAX_TOP = Shapes.or(XMIN_ARM, ZMIN_ARM, Y_DOWN_ARM);

    public FrameBlock(BlockBehaviour.Properties props) {
        super(props.strength(-1.0F, 3_600_000.0F).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(AXIS, Direction.Axis.Y)
                .setValue(CORNER, Corner.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(AXIS, CORNER);
    }

    @Override public RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) {
        return getCollisionShape(s, g, p, c);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) {
        Corner corner = s.getValue(CORNER);
        if (corner == Corner.NONE) {
            return switch (s.getValue(AXIS)) {
                case X -> ROD_X;
                case Y -> ROD_Y;
                case Z -> ROD_Z;
            };
        }
        return switch (corner) {
            // bottom
            case XMIN_ZMIN_BOTTOM -> CORNER_XMIN_ZMIN_BOTTOM;
            case XMIN_ZMAX_BOTTOM -> CORNER_XMIN_ZMAX_BOTTOM;
            case XMAX_ZMIN_BOTTOM -> CORNER_XMAX_ZMIN_BOTTOM;
            case XMAX_ZMAX_BOTTOM -> CORNER_XMAX_ZMAX_BOTTOM;
            // top
            case XMIN_ZMIN_TOP    -> CORNER_XMIN_ZMIN_TOP;
            case XMIN_ZMAX_TOP    -> CORNER_XMIN_ZMAX_TOP;
            case XMAX_ZMIN_TOP    -> CORNER_XMAX_ZMIN_TOP;
            case XMAX_ZMAX_TOP    -> CORNER_XMAX_ZMAX_TOP;
            case NONE             -> Shapes.empty();
        };
    }

    @Override
    public void animateTick(BlockState s, Level level, BlockPos pos, RandomSource r) {
        if (!level.isClientSide) return;
        for (int i = 0; i < 2; i++) {
            double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
            if (s.getValue(CORNER) == Corner.NONE) {
                double t = r.nextDouble();
                switch (s.getValue(AXIS)) {
                    case X -> x = pos.getX() + t;
                    case Y -> y = pos.getY() + t;
                    case Z -> z = pos.getZ() + t;
                }
            }
            level.addParticle(SPARK, x, y, z, 0, 0, 0);
        }
    }
}
