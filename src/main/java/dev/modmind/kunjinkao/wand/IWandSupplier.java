package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 取材器，对齐 Construction Wand KOTS 的 IWandSupplier。
 * <p>
 * 它同时干两件相关的活：决定"这次能用哪几种方块"（{@link #getSupply}），
 * 以及为每一格挑一种能用得上的（{@link #placeSnapshot}）。
 * 挑成功一次就把它记的余量减一 —— 所以<b>材料只剩 32 个时，第 33 格自然拿不到快照</b>，
 * 预览与执行因此都不会超出真实材料量。
 */
public interface IWandSupplier {

    /** 决定这次的可选材料。target 是命中处方块对应的物品（可能是 null）。 */
    void getSupply(@Nullable BlockItem target);

    @Nullable
    PlaceSnapshot placeSnapshot(Level level, BlockPos pos, BlockHitResult hit, @Nullable BlockState supportingBlock);

    /** 扣掉这一叠。返回<b>还差多少</b>，0 表示扣成功。 */
    long take(ItemStack stack);
}