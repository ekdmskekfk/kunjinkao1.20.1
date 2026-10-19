package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.network.TimeAccelStatusPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 客户端保存的加速场快照。
 * <p>
 * 服务端每隔若干 tick 推一次（含发送时刻的剩余毫秒），客户端记下<b>收到的时间</b>，
 * 渲染时用本地时钟往前推 —— 这样倒计时是逐帧平滑走的，而不是每收到一次包才跳一格。
 */
@OnlyIn(Dist.CLIENT)
public final class TimeAccelClientState {

    private static volatile List<TimeAccelStatusPayload.Entry> entries = List.of();
    private static volatile long receivedAtMillis;

    private TimeAccelClientState() {
    }

    public static void accept(List<TimeAccelStatusPayload.Entry> incoming) {
        entries = List.copyOf(incoming);
        receivedAtMillis = System.currentTimeMillis();
    }

    public static void clear() {
        entries = List.of();
    }

    public static List<TimeAccelStatusPayload.Entry> entries() {
        return entries;
    }

    /** 按本地时钟推算的剩余毫秒；无限加速返回负值。 */
    public static long remainingMillis(TimeAccelStatusPayload.Entry entry) {
        if (entry.remainingMillis() < 0L) {
            return -1L;
        }
        long elapsedSincePacket = System.currentTimeMillis() - receivedAtMillis;
        return Math.max(0L, entry.remainingMillis() - elapsedSincePacket);
    }
}