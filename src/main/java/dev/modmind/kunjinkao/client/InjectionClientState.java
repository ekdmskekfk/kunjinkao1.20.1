package dev.modmind.kunjinkao.client;

import net.minecraft.world.InteractionHand;

/**
 * 客户端侧的注入进度镜像（1.20.1 Forge 版，与 1.21.1 完全一致）。
 *
 * <p>时间基准用墙钟而不是 tick 计数：要的是"看起来匀速的 3 秒"，而客户端 tick 在卡顿时
 * 会掉、在暂停时不动。这个类只负责画，成败仍由服务端的 {@code ABORT}/{@code DONE} 决定。</p>
 */
public final class InjectionClientState {

    /** 完成后的成功提示保留多久（毫秒）。 */
    private static final long DONE_FLASH_MS = 900L;

    private static boolean active;
    private static long startMs;
    private static long durationMs;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;

    private static boolean flashVisible;
    private static long flashStartMs;

    private InjectionClientState() {
    }

    public static void start(int durationTicks, InteractionHand hand) {
        active = true;
        startMs = System.currentTimeMillis();
        durationMs = Math.max(1, durationTicks) * 50L;
        InjectionClientState.hand = hand;
    }

    public static void abort() {
        active = false;
    }

    public static void complete() {
        active = false;
        flashVisible = true;
        flashStartMs = System.currentTimeMillis();
    }

    public static boolean isActive() {
        return active;
    }

    public static InteractionHand hand() {
        return hand;
    }

    /** 0..1，按墙钟时间插值并夹紧。 */
    public static float progress() {
        if (!active) {
            return 0.0F;
        }
        long elapsed = System.currentTimeMillis() - startMs;
        float raw = durationMs <= 0L ? 1.0F : (float) elapsed / (float) durationMs;
        return Math.max(0.0F, Math.min(1.0F, raw));
    }

    /** 完成提示是否还在显示期内。 */
    public static boolean isFlashVisible() {
        if (!flashVisible) {
            return false;
        }
        return System.currentTimeMillis() - flashStartMs < DONE_FLASH_MS;
    }

    /** 完成提示的淡出系数（1 → 0）。 */
    public static float flashAlpha() {
        if (!flashVisible) {
            return 0.0F;
        }
        long elapsed = System.currentTimeMillis() - flashStartMs;
        float raw = 1.0F - (float) elapsed / (float) DONE_FLASH_MS;
        return Math.max(0.0F, Math.min(1.0F, raw));
    }

    /** 每 client tick 调一次：负责让完成提示按时消失。 */
    public static void tick() {
        if (flashVisible && !isFlashVisible()) {
            flashVisible = false;
        }
    }

    public static void reset() {
        active = false;
        flashVisible = false;
    }
}
