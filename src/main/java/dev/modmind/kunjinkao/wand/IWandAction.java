package dev.modmind.kunjinkao.wand;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * 一种手杖动作（建筑 / 破坏 / 天使），对齐 Construction Wand KOTS 的 IWandAction。
 * <p>
 * 动作只负责"算出这次会动哪些格子"，不负责执行、不负责扣材料：
 * 执行与扣材料都在 {@link WandJob} 里，预览与右键走的是同一份结果。
 */
public interface IWandAction {

    /** 对着方块右键。 */
    List<ISnapshot> snapshots(Level level, Player player, BlockHitResult hit, ItemStack wand,
                              IWandSupplier supplier, int limit);

    /** 对着空气右键（目前只有天使核心实现了它）。 */
    List<ISnapshot> snapshotsFromAir(Level level, Player player, BlockHitResult hit, ItemStack wand,
                                     IWandSupplier supplier, int limit);
}