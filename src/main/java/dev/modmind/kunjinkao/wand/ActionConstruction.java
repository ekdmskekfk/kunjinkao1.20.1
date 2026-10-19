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
 * 建筑手杖：在被点击面的平面上，把连成一片的同种面铺满方块。
 * 逐行对齐 Construction Wand KOTS 的 ActionConstruction。
 * <p>
 * 三个要点：
 * <ol>
 *   <li>起点是"点击方块沿命中面外侧那一格"，不是点击方块自己；</li>
 *   <li>每一格都要看它<b>身后</b>（沿 -命中面）那一格与最初点击的方块是否同种
 *       （口径由 match 选项决定）—— 这就是"沿着同一种材质的面延伸"；</li>
 *   <li>扩散方向由 lock 选项决定：NOLOCK 时 8 个方向全扩散，锁了某个面就只在该面平面内扩散，
 *       两个都锁时才补上对角线。</li>
 * </ol>
 */
public final class ActionConstruction implements IWandAction {

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
        BlockState targetBlock = level.getBlockState(hit.getBlockPos());
        BlockPos start = hit.getBlockPos().relative(face);

        // 种子：面朝向决定哪两个 lock 方向是"这个平面内的"。
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
            BlockPos support = current.relative(face.getOpposite());
            BlockState supportState = level.getBlockState(support);
            if (!WandOptions.matchBlocks(wand, targetBlock.getBlock(), supportState.getBlock())
                    || !visited.add(current)) {
                continue;
            }
            PlaceSnapshot snapshot = createSnapshot(level, player, hit, current, supportState, wand, supplier);
            if (snapshot == null) {
                continue;
            }
            snapshots.add(snapshot);
            expandCandidates(wand, face, current, candidates);
        }
        return snapshots;
    }

    @Override
    public List<ISnapshot> snapshotsFromAir(Level level, Player player, BlockHitResult hit, ItemStack wand,
                                            IWandSupplier supplier, int limit) {
        return new ArrayList<>();
    }

    /**
     * 造一个候选快照。
     * <p>
     * 参考实现在这一层套了一个 catch(Exception) 空吞异常 —— 因为有些模组的方块在
     * {@code getStateForPlacement} 里会对外部状态抛异常，一个怪方块不该让整次右键炸掉。
     * 这里保留同样的保护，但只包住这一格。
     */
    private static PlaceSnapshot createSnapshot(Level level, Player player, BlockHitResult hit, BlockPos pos,
                                                BlockState supportState, ItemStack wand, IWandSupplier supplier) {
        try {
            return supplier.placeSnapshot(level, pos, hit, supportState);
        } catch (RuntimeException weirdBlock) {
            return null;
        }
    }

    /**
     * 把这一格的邻居加进候选。
     * <p>
     * 方向表与参考实现逐条一致：先加该平面内的两个正向，再按 lock 决定要不要加，
     * 两个 lock 都开了才补四条对角线（少了对角线，拐角处就扩散不过去）。
     */
    static void expandCandidates(ItemStack wand, Direction face, BlockPos current, LinkedList<BlockPos> candidates) {
        boolean horizontal = WandOptions.testLock(wand, WandOptions.Lock.HORIZONTAL);
        boolean vertical = WandOptions.testLock(wand, WandOptions.Lock.VERTICAL);
        switch (face) {
            case UP, DOWN -> {
                boolean northSouth = WandOptions.testLock(wand, WandOptions.Lock.NORTHSOUTH);
                boolean eastWest = WandOptions.testLock(wand, WandOptions.Lock.EASTWEST);
                if (northSouth) {
                    candidates.add(current.relative(Direction.NORTH));
                    candidates.add(current.relative(Direction.SOUTH));
                }
                if (eastWest) {
                    candidates.add(current.relative(Direction.EAST));
                    candidates.add(current.relative(Direction.WEST));
                }
                if (northSouth && eastWest) {
                    diagonals(candidates, current, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
                }
            }
            case NORTH, SOUTH -> {
                addHorizontalAndVertical(candidates, current, horizontal, vertical, Direction.EAST, Direction.WEST);
            }
            case EAST, WEST -> {
                addHorizontalAndVertical(candidates, current, horizontal, vertical, Direction.NORTH, Direction.SOUTH);
            }
        }
    }

    private static void addHorizontalAndVertical(LinkedList<BlockPos> candidates, BlockPos current,
                                                 boolean horizontal, boolean vertical,
                                                 Direction alongHorizontal, Direction againstHorizontal) {
        if (horizontal) {
            candidates.add(current.relative(alongHorizontal));
            candidates.add(current.relative(againstHorizontal));
        }
        if (vertical) {
            candidates.add(current.relative(Direction.UP));
            candidates.add(current.relative(Direction.DOWN));
        }
        if (horizontal && vertical) {
            diagonals(candidates, current, Direction.UP, Direction.DOWN, alongHorizontal, againstHorizontal);
        }
    }

    private static void diagonals(LinkedList<BlockPos> candidates, BlockPos current,
                                  Direction first, Direction second, Direction third, Direction fourth) {
        candidates.add(current.relative(first).relative(third));
        candidates.add(current.relative(first).relative(fourth));
        candidates.add(current.relative(second).relative(third));
        candidates.add(current.relative(second).relative(fourth));
    }
}