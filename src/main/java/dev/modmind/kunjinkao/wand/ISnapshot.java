package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 一次手杖作业里"对某一格做的事"，对齐 Construction Wand KOTS 的 ISnapshot。
 * <p>
 * 预览与执行共用同一个对象：预览阶段只收集快照、不执行；右键时才逐个 {@link #execute}。
 * 这样"预览画出来的格子"与"真正动到的格子"永远是同一份计算，不会各算一套。
 */
public interface ISnapshot {

    BlockPos getPos();

    BlockState getBlockState();

    /** 这一格需要消耗的材料（破坏快照返回空栈）。 */
    ItemStack getRequiredItems();

    /** 执行这一格。返回 false 表示没做成（此时调用方不该扣材料）。 */
    boolean execute(Level level, Player player, BlockHitResult hit);

    /** 撤销时这一格还能不能恢复（比如已经被别的方块占了就不行）。 */
    boolean canRestore(Level level, Player player);

    /** 撤销这一格。 */
    boolean restore(Level level, Player player);

    /** 无条件还原（材料没扣成时把已经放下去的撤掉）。 */
    void forceRestore(Level level);
}