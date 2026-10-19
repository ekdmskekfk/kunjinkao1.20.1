package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 破坏某一格的快照，对齐 Construction Wand KOTS 的 DestroySnapshot。
 * <p>
 * 与放置快照对称：能破坏的才进列表，而"能破坏"的口径就是参考实现的 isBlockRemovable
 * （世界边界内、有权限、距离内；生存模式还要求硬度不为负且没有方块实体）。
 * <p>
 * 撤销时要还原方块，所以先判断这一格是不是已经又被别人占了（canRestore）。
 */
public final class DestroySnapshot implements ISnapshot {

    private final BlockState state;
    private final BlockPos pos;

    private DestroySnapshot(BlockState state, BlockPos pos) {
        this.state = state;
        this.pos = pos;
    }

    @Nullable
    public static DestroySnapshot create(Level level, Player player, BlockPos pos) {
        if (!WandUtil.isBlockRemovable(level, player, pos)) {
            return null;
        }
        return new DestroySnapshot(level.getBlockState(pos), pos);
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
        return ItemStack.EMPTY;
    }

    @Override
    public boolean execute(Level level, Player player, BlockHitResult hit) {
        return WandUtil.removeBlock(level, player, state, pos);
    }

    @Override
    public boolean canRestore(Level level, Player player) {
        if (!level.isInWorldBounds(pos) || !level.mayInteract(player, pos)) {
            return false;
        }
        if (player.isCreative()) {
            return true;
        }
        boolean occupied = !level.isEmptyBlock(pos) && !level.getBlockState(pos).canBeReplaced(Fluids.EMPTY);
        return !occupied && !WandUtil.entitiesCollidingWithBlock(level, state, pos);
    }

    @Override
    public boolean restore(Level level, Player player) {
        return WandUtil.placeBlock(level, player, state, pos, null);
    }

    @Override
    public void forceRestore(Level level) {
        level.setBlockAndUpdate(pos, state);
    }
}