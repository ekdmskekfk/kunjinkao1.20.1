package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 天使核心：把方块放到你所视方块的<b>背面</b>去。
 * 逐行对齐 Construction Wand KOTS 的 ActionAngel。
 * <ul>
 *   <li>对方块右键：从被点击的那一格开始，沿命中面反向走，<b>放下走到的第一个能放的位置</b>
 *       （最多走 limit 格）；所以它既能贴着背面放，也能穿过几格空气在墙后放。</li>
 *   <li>对空气右键：在"眼睛 + 视线×2"那一格直接放 —— 这就是拿它往脚下/空中垫方块的用法。
 *       生存模式下落超过 10 格时默认不允许，防止拿它从虚空里自救（配置 angelFalling 可开）。</li>
 * </ul>
 */
public final class ActionAngel implements IWandAction {

    @Override
    public List<ISnapshot> snapshots(Level level, Player player, BlockHitResult hit, ItemStack wand,
                                     IWandSupplier supplier, int limit) {
        List<ISnapshot> snapshots = new ArrayList<>();
        if (limit <= 0) {
            return snapshots;
        }
        Direction face = hit.getDirection();
        BlockPos current = hit.getBlockPos();
        BlockState supportingBlock = level.getBlockState(current);
        for (int step = 0; step < limit; step++) {
            current = current.relative(face.getOpposite());
            PlaceSnapshot snapshot = supplier.placeSnapshot(level, current, hit, supportingBlock);
            if (snapshot != null) {
                snapshots.add(snapshot);
                break;
            }
        }
        return snapshots;
    }

    @Override
    public List<ISnapshot> snapshotsFromAir(Level level, Player player, BlockHitResult hit, ItemStack wand,
                                            IWandSupplier supplier, int limit) {
        List<ISnapshot> snapshots = new ArrayList<>();
        if (!player.isCreative() && !AdminToolConfig.wandAngelFalling() && player.fallDistance > 10.0F) {
            return snapshots;
        }
        Vec3 place = WandUtil.entityPositionVec(player).add(player.getLookAngle().multiply(2.0D, 2.0D, 2.0D));
        PlaceSnapshot snapshot = supplier.placeSnapshot(level, WandUtil.posFromVec(place), hit, null);
        if (snapshot != null) {
            snapshots.add(snapshot);
        }
        return snapshots;
    }
}