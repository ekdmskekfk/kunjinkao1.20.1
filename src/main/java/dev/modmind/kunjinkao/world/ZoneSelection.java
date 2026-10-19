package dev.modmind.kunjinkao.world;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 选区作业（127）里"正在框的那块区域"。
 * <p>
 * <b>刻意不做持久化。</b> 开发计划建议把选区存进 SavedData，但选区本质上是
 * "我正准备处理这一块"的临时状态，和剪贴板一样。把它存进存档的后果是：
 * 玩家框了一半就下线，几天后上线按一下执行键，一块 64×64 的地形就没了 ——
 * 那时他多半已经忘了自己框过什么。重启即清空反而是安全特性。
 * <p>
 * 记住维度：选区是绑在某个维度上的，玩家换维度后旧的角点不该被拿来计算。
 */
public final class ZoneSelection {

    private static final Map<UUID, Corners> SELECTIONS = new HashMap<>();

    /** 一名玩家当前的两个角点；未设置的那个为 null。 */
    public record Corners(ResourceLocation dimension, BlockPos a, BlockPos b) {
    }

    private ZoneSelection() {
    }

    /**
     * 按「A → B → 重新从 A 开始」的顺序设一个角点。
     * <p>
     * <b>顺序由已有角点推出来，不额外记一个"下一个是谁"的标志位。</b> 记标志位就要考虑
     * 它和实际角点不一致的情形（取消了一个角点、换了维度、作业跑完清空），
     * 而这里"A 空着就设 A、A 有了 B 空着就设 B、两个都有了就从 A 重来"本身就定义清楚了顺序，
     * 没有第二份状态可以走岔。
     *
     * @return 这次设下的是哪个角点：{@link #CORNER_A} 或 {@link #CORNER_B}
     */
    public static int setNextCorner(ServerPlayer player, BlockPos pos) {
        ResourceLocation dimension = player.level().dimension().location();
        Corners current = SELECTIONS.get(player.getUUID());
        if (current == null || !dimension.equals(current.dimension())) {
            // 换维度了：上一组角点在别的世界里，坐标没有可比性，直接重开一组。
            current = new Corners(dimension, null, null);
        }
        BlockPos cornerA = current.a();
        BlockPos cornerB = current.b();
        int corner;
        if (cornerA == null) {
            cornerA = pos;
            corner = CORNER_A;
        } else if (cornerB == null) {
            cornerB = pos;
            corner = CORNER_B;
        } else {
            // 两个角点都已经设过：第三次右键的意思是"重新框一块"，
            // 而不是把 A 挪走却留着 B —— 那会框出一块玩家没打算选的地。
            cornerA = pos;
            cornerB = null;
            corner = CORNER_A;
        }
        SELECTIONS.put(player.getUUID(), new Corners(dimension, cornerA, cornerB));
        return corner;
    }

    /** 角点序号：与 {@code ZoneStatusPayload} 里的 A/B 对应。 */
    public static final int CORNER_A = 0;
    public static final int CORNER_B = 1;

    /** 取当前角点；没有则返回 null。 */
    public static Corners get(ServerPlayer player) {
        return SELECTIONS.get(player.getUUID());
    }

    public static void clear(ServerPlayer player) {
        SELECTIONS.remove(player.getUUID());
    }

    /** 玩家退出时清掉，别为离线玩家长期持有坐标。 */
    public static void forget(UUID uuid) {
        SELECTIONS.remove(uuid);
    }

    // ===================== 缩放（木棍 + Shift + 滚轮）=====================

    /** 轴向代号：与 {@link #adjust} 的数组下标一致。 */
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /**
     * 一次缩放的结果。
     * <p>
     * <b>用枚举而不是 boolean。</b> 调用方对三种失败要说三句不同的话
     * （「还没框」「太大了」「框好了」），boolean 只会把它逼回去自己再判一遍角点与上限 ——
     * 那就是把同一套判定抄成两份，迟早有一边改了另一边没改。
     */
    public enum AdjustResult {
        /** 调好了（含「已经缩到最小、这次没动」）。 */
        OK,
        /** 两个角点没设全，或选区在别的维度。 */
        NO_CORNERS,
        /** 扩大后会超过服务器的单边上限，一个字都没改。 */
        TOO_BIG
    }

    /**
     * 把当前选区沿一根轴扩大或缩小。
     * <p>
     * <b>两个角点都设好才动得了。</b> 只有一个角点时「沿某轴扩大」根本没有另一侧可扩，
     * 与其猜一个默认尺寸，不如把「先去框第二个角点」这件事交回给调用方说清楚。
     * <p>
     * <b>方向由调用方给定，本类不读玩家朝向。</b> {@code direction} 是「动哪一侧」：
     * 正数动 max 那一侧（朝坐标轴正向扩），负数动 min 那一侧（朝负向扩）。
     * 玩家抬头/低头/面朝哪一边的判定留在 {@code KunJinKaoQolHandler}，
     * 那里才有 {@code getLookAngle} 的上下文，而这里只做纯几何 —— 于是这一段可以被单独读懂。
     * <p>
     * <b>两条硬约束。</b> 单边最小 1 格：缩小不能把某一轴缩成 0 或负数，缩到头就停在 1。
     * 单边最大 {@code zoneMaxSide}：扩大后若越线，就<b>一个字都不改</b>地返回
     * {@link AdjustResult#TOO_BIG}。刻意不做「扩到上限为止」的截断 —— 玩家滚一格却只涨半格，
     * 会让「滚轮格数」和「尺寸变化」从此对不上，而他没有任何办法知道自己撞到了墙。
     * <p>
     * 缩小时不查上限：上限是给「变大」设的，而选区可能在服主调小上限之前就已经框得比它大了，
     * 这时拦住缩小等于把玩家锁死在一个他再也改不动的选区里。
     *
     * @param axis      要动的轴，见 {@link #AXIS_X} / {@link #AXIS_Y} / {@link #AXIS_Z}
     * @param direction 动哪一侧：正数动 max、负数动 min
     * @param amount    正数扩大、负数缩小，绝对值是要动的格数
     */
    public static AdjustResult adjust(ServerPlayer player, int axis, int direction, int amount) {
        if (axis < AXIS_X || axis > AXIS_Z || direction == 0 || amount == 0) {
            // 参数不合法只可能来自本模组自己的调用点（轴与符号都不走网络），当作「无事发生」。
            return AdjustResult.OK;
        }
        ResourceLocation dimension = player.level().dimension().location();
        Corners current = SELECTIONS.get(player.getUUID());
        if (current == null || current.a() == null || current.b() == null
                || !dimension.equals(current.dimension())) {
            return AdjustResult.NO_CORNERS;
        }
        BlockPos a = current.a();
        BlockPos b = current.b();
        // 先把两个角点化成 min/max：角点的先后顺序只表示「先点的哪一个」，
        // 与几何上的上下左右无关，直接拿它们加减会把方向搞反。
        int[] low = {Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())};
        int[] high = {Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())};
        int oldSize = high[axis] - low[axis] + 1;

        if (direction > 0) {
            high[axis] += amount;
        } else {
            low[axis] -= amount;
        }

        int size = high[axis] - low[axis] + 1;
        if (size > oldSize && size > AdminToolConfig.qolServerValue(QolSetting.ZONE_MAX_SIDE)) {
            return AdjustResult.TOO_BIG;
        }
        if (size < 1) {
            // 缩过头了：把被推的那一侧停回「与另一侧正好相邻」，也就是 1 格。
            // 不这么做的话 low 会跑到 high 上面去，两个角点交叉，之后所有尺寸都算成负数。
            if (direction > 0) {
                high[axis] = low[axis];
            } else {
                low[axis] = high[axis];
            }
        }

        // 写回时统一成「min 在前、max 在后」：选区只在别处被当作两端取 min/max 用，
        // 保持这个次序让下一次调整（以及日志里看到的坐标）都是可读的。
        SELECTIONS.put(player.getUUID(), new Corners(dimension,
                new BlockPos(low[AXIS_X], low[AXIS_Y], low[AXIS_Z]),
                new BlockPos(high[AXIS_X], high[AXIS_Y], high[AXIS_Z])));
        return AdjustResult.OK;
    }
}
