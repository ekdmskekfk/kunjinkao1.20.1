package dev.modmind.kunjinkao.client.qol;

import dev.modmind.kunjinkao.network.ZoneStatusPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 选区挖掘（127）在客户端的只读镜像：服务端推来什么就存什么。
 * <p>
 * <b>这里不做任何权威判定。</b> 它只回答"框该画在哪、进度到哪了"，
 * 角点能不能设、作业能不能开，全由服务端说了算 —— 就算有人改了客户端内存把框画到别处，
 * 服务端那边该不该挖还是照旧。
 * <p>
 * 用 volatile 而不是加锁：写入发生在网络包的处理线程，读取发生在渲染线程，
 * 这里每次只读一个引用/一个坐标，读到一个稍旧的整值不会画出错误的东西，
 * 拼不出半个状态（角点与维度分开存的时候例外，那种一致性由 accept 里的写入顺序保证）。
 */
@OnlyIn(Dist.CLIENT)
public final class ZoneClientState {

    /** 最新一条状态回执。running/done/total 只从这里读。 */
    private static volatile ZoneStatusPayload latest;

    /**
     * 角点单独存，而不是每次从 latest 里现取。
     * <p>
     * 原因是服务端一开跑就把自己的选区清掉了（见 {@code KunJinKaoQolHandler#handleZoneExecute}
     * 里的 {@code ZoneSelection.clear}），于是作业期间每一条进度回执里一个角点都没有。
     * 若客户端跟着清，外框与进度渐变会在作业进行中整个消失 —— 而那恰恰是最该看见框的时候。
     * 所以角点只在"服务端明确表示没有选区、也没在跑"时才丢。
     */
    private static volatile BlockPos cornerA;
    private static volatile BlockPos cornerB;
    /** 两个角点所属的维度；没有角点时是 null。凭空造一个维度出来会让渲染"总有框可画"。 */
    private static volatile ResourceLocation cornerDimension;

    private ZoneClientState() {
    }

    public static void accept(ZoneStatusPayload status) {
        if (status.hasCornerA() || status.hasCornerB()) {
            // 有新角点就整组覆盖：服务端一次发的是全量，不存在只更新一个角点的情形。
            cornerA = status.hasCornerA() ? status.cornerA() : null;
            cornerB = status.hasCornerB() ? status.cornerB() : null;
            cornerDimension = status.dimension();
        } else if (!status.running()) {
            // 没有角点、也没在跑：这是取消或挖完之后的收尾包，旧框必须一起丢掉，
            // 否则一个已经作废的框会一直留在世界里，看起来像"还在选区"。
            cornerA = null;
            cornerB = null;
            cornerDimension = null;
        }
        // 剩下那种"没有角点但 running=true"的进度包：角点原样留着，只换进度。
        latest = status;
    }

    /** 两个角点都在才为 true；只有一个角点时是 false，渲染那边另行画单格小框。 */
    public static boolean hasCorners() {
        return cornerA != null && cornerB != null;
    }

    /** 角点 A；没设过或已被清掉返回 null。 */
    public static BlockPos cornerA() {
        return cornerA;
    }

    /** 角点 B；没设过或已被清掉返回 null。 */
    public static BlockPos cornerB() {
        return cornerB;
    }

    /** 当前角点所属的维度；没有角点时返回 null（渲染据此不画）。 */
    public static ResourceLocation dimension() {
        return cornerDimension;
    }

    public static boolean isRunning() {
        ZoneStatusPayload status = latest;
        return status != null && status.running();
    }

    public static int done() {
        ZoneStatusPayload status = latest;
        return status == null ? 0 : status.done();
    }

    public static int total() {
        ZoneStatusPayload status = latest;
        return status == null ? 0 : status.total();
    }

    /**
     * 退出世界/断开连接时清空。
     * <p>
     * 不清的话，退出去再进另一个世界，只要维度还是主世界，上一局的框就会在原地继续画 ——
     * 维度判定挡不住这种情形（维度确实一样，只是存档换了）。
     */
    public static void clear() {
        latest = null;
        cornerA = null;
        cornerB = null;
        cornerDimension = null;
    }
}