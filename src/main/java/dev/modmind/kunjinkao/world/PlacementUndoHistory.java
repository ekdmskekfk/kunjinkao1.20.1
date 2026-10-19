package dev.modmind.kunjinkao.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 放置 / 破坏操作的撤销历史，按玩家分别记录，最多保留 {@link #MAX_STEPS} 步。
 * <p>
 * 一次连续放置、或一次连带破坏算作「一步」。撤销从最近一步往回恢复，
 * 两个方向共用同一条记录 —— {@code previousState} 既可能是放置前的空气，
 * 也可能是被破坏前的那个方块，恢复动作都是同一个 setBlock：
 * <ul>
 *   <li><b>放置</b>：还原成放置前的状态，并把方块物品还给玩家；</li>
 *   <li><b>破坏</b>：把被破坏的方块原样恢复。破坏本就不掉落，所以没有物品要退。</li>
 * </ul>
 * 恢复时按记录倒序处理：同一格在一次操作里被反复改动时，只有倒序才能得到正确结果。
 */
public final class PlacementUndoHistory {

    /** 最多能撤销多少步（需求：10 步以内）。 */
    public static final int MAX_STEPS = 10;

    private static final Map<UUID, Deque<List<Change>>> HISTORY = new HashMap<>();

    /**
     * 一步操作里对单个方块位置的改动。
     *
     * @param pos           被改动的坐标
     * @param previousState 改动之前的状态（放置前多半是空气，破坏前是被破坏的方块）
     * @param returned      放置时消耗掉的方块物品；破坏时为空，表示没有物品要退
     */
    public record Change(BlockPos pos, BlockState previousState, ItemStack returned) {
        public Change {
            returned = returned.copy();
        }
    }

    private PlacementUndoHistory() {
    }

    /** 记录一步操作。空列表不入栈，避免按一次撤销键却什么都没发生。 */
    public static void push(Player player, List<Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        Deque<List<Change>> steps = HISTORY.computeIfAbsent(player.getUUID(), key -> new ArrayDeque<>());
        steps.addLast(List.copyOf(changes));
        while (steps.size() > MAX_STEPS) {
            steps.removeFirst();
        }
    }

    /**
     * 撤销最近一步。
     *
     * @return 被还原的方块数；没有可撤销的操作时返回 0
     */
    public static int undoLast(Player player) {
        Deque<List<Change>> steps = HISTORY.get(player.getUUID());
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        List<Change> changes = steps.removeLast();
        if (steps.isEmpty()) {
            HISTORY.remove(player.getUUID());
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        int restored = 0;
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i);
            level.setBlock(change.pos(), change.previousState(), 3);
            if (!change.returned().isEmpty()) {
                ItemStack refund = change.returned().copy();
                if (!player.getInventory().add(refund)) {
                    // 背包满了就掉在脚下，别把方块吞掉。
                    player.drop(refund, false);
                }
            }
            restored++;
        }
        return restored;
    }

    /** 当前可撤销的步数。 */
    public static int stepsAvailable(Player player) {
        Deque<List<Change>> steps = HISTORY.get(player.getUUID());
        return steps == null ? 0 : steps.size();
    }

    /** 玩家退出时清掉记录，避免为离线玩家长期持有方块状态。 */
    public static void forget(UUID uuid) {
        HISTORY.remove(uuid);
    }
}