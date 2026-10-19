package dev.modmind.kunjinkao.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 同步给其他客户端的第三人称取剑动画计时状态。 */
public final class RemoteSwordDrawVisualState {
    public static final int COMPILE_TICKS = 48;
    public static final int GRAB_TICKS = 14;
    private static final Map<UUID, DrawAnimation> ACTIVE = new HashMap<>();

    private RemoteSwordDrawVisualState() {
    }

    public static void trigger(UUID playerUuid, InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && playerUuid != null) {
            ACTIVE.put(playerUuid, new DrawAnimation(minecraft.level.getGameTime(), hand));
        }
    }

    /**
     * 断线/退出世界时清空远端取剑动画计时。
     * 计时以 level.getGameTime() 为基准，跨存档保留会与上个存档的起始 tick 进行比较。
     */
    public static void clear() {
        ACTIVE.clear();
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clear();
            return;
        }
        long now = minecraft.level.getGameTime();
        ACTIVE.entrySet().removeIf(entry -> now - entry.getValue().startTick() >= COMPILE_TICKS + GRAB_TICKS);
    }

    public static boolean isCompiling(UUID playerUuid) {
        return elapsed(playerUuid) >= 0 && elapsed(playerUuid) < COMPILE_TICKS;
    }

    public static boolean isReachActive(UUID playerUuid) {
        int elapsed = elapsed(playerUuid);
        return elapsed > 25 && elapsed < COMPILE_TICKS;
    }

    public static float getReachProgress(UUID playerUuid) {
        int elapsed = elapsed(playerUuid);
        float linear = Math.max(0.0F, Math.min(1.0F, (elapsed - 25.0F) / 21.0F));
        return linear * linear * (3.0F - 2.0F * linear);
    }

    public static float getCompileProgress(UUID playerUuid) {
        int elapsed = elapsed(playerUuid);
        return Math.max(0.0F, Math.min(1.0F, (elapsed + 1.0F) / COMPILE_TICKS));
    }

    public static InteractionHand getHand(UUID playerUuid) {
        DrawAnimation animation = ACTIVE.get(playerUuid);
        return animation == null ? InteractionHand.MAIN_HAND : animation.hand();
    }

    private static int elapsed(UUID playerUuid) {
        Minecraft minecraft = Minecraft.getInstance();
        DrawAnimation animation = ACTIVE.get(playerUuid);
        if (minecraft.level == null || animation == null) {
            return -1;
        }
        return (int) (minecraft.level.getGameTime() - animation.startTick());
    }

    private record DrawAnimation(long startTick, InteractionHand hand) {
    }
}
