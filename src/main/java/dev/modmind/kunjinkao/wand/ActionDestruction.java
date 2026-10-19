package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 破坏核心：把被点击那一格所在面上连成一片的同种方块挖掉（不掉落）。
 * 逐行对齐 Construction Wand KOTS 的 ActionDestruction。
 * <p>
 * 与建筑手杖的三处差别：
 * <ol>
 *   <li>起点是<b>被点击的那一格自己</b>（建筑是从面外侧那一格起）；</li>
 *   <li>每一格要求它<b>沿命中面往前那一格是透的</b> —— 参考实现用它保证
 *       "只挖露在外面的那一层"，不会掏进方块内部；</li>
 *   <li>扩散方向表一样，但锁的面换成对应的平面。</li>
 * </ol>
 */
public final class ActionDestruction implements IWandAction {

    @Override
    public List<ISnapshot> snapshots(Level level, Player player, BlockHitResult hit, ItemStack wand,
                                     IWandSupplier supplier, int limit) {
        List<ISnapshot> snapshots = new ArrayList<>();
        if (limit <= 0) {
            return snapshots;
        }
        LinkedList<BlockPos> candidates = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        Direction face = hit.getDirection();
        BlockPos start = hit.getBlockPos();
        BlockState targetBlock = level.getBlockState(start);

        if (face != Direction.UP && face != Direction.DOWN) {
            if (WandOptions.testLock(wand, WandOptions.Lock.HORIZONTAL)
                    || WandOptions.testLock(wand, WandOptions.Lock.VERTICAL)) {
                candidates.add(start);
            }
        } else if (WandOptions.testLock(wand, WandOptions.Lock.NORTHSOUTH)
                || WandOptions.testLock(wand, WandOptions.Lock.EASTWEST)) {
            candidates.add(start);
        }

        while (!candidates.isEmpty() && snapshots.size() < limit) {
            BlockPos current = candidates.removeFirst();
            if (!WandUtil.isBlockPermeable(level, current.relative(face))) {
                continue;
            }
            BlockState state = level.getBlockState(current);
            if (!WandOptions.matchBlocks(wand, targetBlock.getBlock(), state.getBlock()) || !visited.add(current)) {
                continue;
            }
            DestroySnapshot snapshot = DestroySnapshot.create(level, player, current);
            if (snapshot == null) {
                continue;
            }
            snapshots.add(snapshot);
            ActionConstruction.expandCandidates(wand, face, current, candidates);
        }
        return snapshots;
    }

    @Override
    public List<ISnapshot> snapshotsFromAir(Level level, Player player, BlockHitResult hit, ItemStack wand,
                                            IWandSupplier supplier, int limit) {
        return new ArrayList<>();
    }
}