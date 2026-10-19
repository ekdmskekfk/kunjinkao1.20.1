package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 手杖专用的放置上下文，对齐 Construction Wand KOTS 的 WandItemUseContext。
 * <p>
 * 它做两件事：
 * <ol>
 *   <li>把命中点<b>换算到真正要放的那一格</b>上 —— 命中向量加上"命中的方块"与"目标格"的差值。
 *       不换算的话，第 20 格算出来的朝向会按原始命中点算，朝向就全错了。</li>
 *   <li>把 {@code canPlace()} 收窄成 {@code replaceClicked}：目标格可替换才算能放。</li>
 * </ol>
 */
public class WandItemUseContext extends BlockPlaceContext {

    public WandItemUseContext(Level level, Player player, BlockHitResult hit, BlockPos pos, BlockItem item) {
        super(level, player, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(hitVec(hit, pos), hit.getDirection(), pos, false));
    }

    private static Vec3 hitVec(BlockHitResult hit, BlockPos pos) {
        Vec3 location = hit.getLocation();
        Vec3 delta = WandUtil.blockPosVec(hit.getBlockPos()).subtract(WandUtil.blockPosVec(pos));
        return delta.add(location);
    }

    public boolean canPlace() {
        return this.replaceClicked;
    }
}