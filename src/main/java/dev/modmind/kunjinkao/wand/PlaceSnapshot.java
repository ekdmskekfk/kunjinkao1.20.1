package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 放置某一格的快照，对齐 Construction Wand KOTS 的 PlaceSnapshot。
 * <p>
 * 能不能放由这五道检查决定（参考实现一模一样）：
 * {@code canPlace()} → {@code getStateForPlacement} → {@code canSurvive} →
 * 方块实体允许 → 碰撞箱里没有实体。任何一道不过，这一格就<b>不进列表</b>，
 * 于是预览里也不会画它 —— 预览与执行用的是同一份结果。
 * <p>
 * direction=TARGET 时，放下去的方块会跟随<b>依托方块</b>的朝向（朝向、轴、半砖、楼梯形等），
 * 这就是参考实现里"法杖能顺着墙的方向铺"的来源。
 */
public final class PlaceSnapshot implements ISnapshot {

    private BlockState state;
    private final BlockPos pos;
    private final BlockItem item;
    private final BlockState supportingBlock;
    private final boolean targetMode;

    private PlaceSnapshot(BlockState state, BlockPos pos, BlockItem item,
                          @Nullable BlockState supportingBlock, boolean targetMode) {
        this.state = state;
        this.pos = pos;
        this.item = item;
        this.supportingBlock = supportingBlock;
        this.targetMode = targetMode;
    }

    @Nullable
    public static PlaceSnapshot create(Level level, Player player, BlockHitResult hit, BlockPos pos,
                                       BlockItem item, @Nullable BlockState supportingBlock, ItemStack wand) {
        boolean targetMode = supportingBlock != null && WandOptions.direction(wand) == WandOptions.Direction.TARGET;
        BlockState state = resolveState(level, player, hit, pos, item, supportingBlock, targetMode);
        return state == null ? null : new PlaceSnapshot(state, pos, item, supportingBlock, targetMode);
    }

    @Override
    public BlockPos getPos() {
        return pos;
    }

    @Override
    public BlockState getBlockState() {
        return state;
    }

    @Override
    public ItemStack getRequiredItems() {
        return new ItemStack(item);
    }

    @Override
    public boolean execute(Level level, Player player, BlockHitResult hit) {
        state = resolveState(level, player, hit, pos, item, supportingBlock, targetMode);
        return state != null && WandUtil.placeBlock(level, player, state, pos, item);
    }

    @Override
    public boolean canRestore(Level level, Player player) {
        return true;
    }

    @Override
    public boolean restore(Level level, Player player) {
        return WandUtil.removeBlock(level, player, state, pos);
    }

    @Override
    public void forceRestore(Level level) {
        level.removeBlock(pos, false);
    }

    @Nullable
    private static BlockState resolveState(Level level, Player player, BlockHitResult hit, BlockPos pos,
                                           BlockItem item, @Nullable BlockState supportingBlock, boolean targetMode) {
        BlockPlaceContext context = new WandItemUseContext(level, player, hit, pos, item);
        if (!context.canPlace()) {
            return null;
        }
        BlockState state = item.getBlock().getStateForPlacement(context);
        if (state == null || !state.canSurvive(level, pos)) {
            return null;
        }
        if (!WandUtil.isTEAllowed(state)) {
            return null;
        }
        if (WandUtil.entitiesCollidingWithBlock(level, state, pos)) {
            return null;
        }
        if (targetMode && supportingBlock != null) {
            state = applySupportingProperties(state, supportingBlock);
        }
        return state;
    }

    /**
     * 把依托方块的朝向类属性抄到要放的方块上。
     * <p>
     * 属性表与参考实现逐条一致；半砖单独处理，因为 DOUBLE 抄过去会变成双层砖，
     * 那是把一块变两块，参考实现也刻意跳过。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applySupportingProperties(BlockState state, BlockState supporting) {
        Property[] properties = new Property[]{
                BlockStateProperties.HORIZONTAL_FACING,
                BlockStateProperties.FACING,
                BlockStateProperties.FACING_HOPPER,
                BlockStateProperties.ROTATION_16,
                BlockStateProperties.AXIS,
                BlockStateProperties.HALF,
                BlockStateProperties.STAIRS_SHAPE
        };
        BlockState result = state;
        for (Property property : properties) {
            if (supporting.hasProperty(property) && result.hasProperty(property)) {
                result = result.setValue(property, supporting.getValue(property));
            }
        }
        if (supporting.hasProperty(BlockStateProperties.SLAB_TYPE) && result.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType slab = supporting.getValue(BlockStateProperties.SLAB_TYPE);
            if (slab != SlabType.DOUBLE) {
                result = result.setValue(BlockStateProperties.SLAB_TYPE, slab);
            }
        }
        return result;
    }
}