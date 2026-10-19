package dev.modmind.kunjinkao.wand;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 随机取材器，对齐 Construction Wand KOTS 的 SupplierRandom。
 * <p>
 * 与默认取材器的唯一差别在"能用哪几种"：它不看点击的方块，而是把<b>快捷栏 + 副手</b>里
 * 所有可放置方块都收进池子，之后每格随机挑一种能用得上的。所以开着手杖的 random 选项时，
 * 一堵墙会变成花花绿绿的混搭 —— 这正是它的用途（拿它快速消耗杂料）。
 */
public final class SupplierRandom extends SupplierInventory {

    public SupplierRandom(Player player, ItemStack wand) {
        super(player, wand);
    }

    @Override
    public void getSupply(@Nullable BlockItem target) {
        counts.clear();
        pool = new RandomPool<>(player.getRandom());
        for (ItemStack stack : WandUtil.hotbarWithOffhand(player)) {
            if (stack.getItem() instanceof BlockItem blockItem) {
                addItem(blockItem);
            }
        }
    }
}