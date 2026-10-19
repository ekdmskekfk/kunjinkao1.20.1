package dev.modmind.kunjinkao.client;

import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client-side expiry state for the short shield flash shown after a mob attack. */
public final class ShieldHitVisualState {

    private static final long SHIELD_DURATION_TICKS = 20L;
    private static final int MAX_ACTIVE_SHIELDS_PER_PLAYER = 12;
    private static final Map<UUID, List<ShieldHit>> ACTIVE_SHIELDS = new HashMap<>();

    private ShieldHitVisualState() {
    }

    public static void trigger(UUID playerUuid, float relativeImpactYaw, float impactHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            // 没有世界就没有可比较的 gameTime。此处必须清空而不是直接 return：
            // 上个存档留下的条目会继续留在 ACTIVE_SHIELDS 里，等到新世界用更小的 gameTime
            // 读取时永不过期（见 isStale），表现为蜂巢护盾被定格显示。
            reset();
            return;
        }
        long now = minecraft.level.getGameTime();
        List<ShieldHit> shields = ACTIVE_SHIELDS.computeIfAbsent(playerUuid, ignored -> new ArrayList<>());
        shields.removeIf(shield -> isStale(shield, now));
        if (shields.size() >= MAX_ACTIVE_SHIELDS_PER_PLAYER) {
            shields.remove(0);
        }
        shields.add(new ShieldHit(now, now + SHIELD_DURATION_TICKS, relativeImpactYaw, impactHeight));
    }

    public static List<ShieldVisual> getVisuals(UUID playerUuid) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            // 与 trigger 同理：没有世界时无法判断过期，必须清空，否则旧条目会等到新世界才被读到。
            reset();
            return List.of();
        }
        List<ShieldHit> shields = ACTIVE_SHIELDS.get(playerUuid);
        if (shields == null) {
            return List.of();
        }
        long now = minecraft.level.getGameTime();
        shields.removeIf(shield -> isStale(shield, now));
        if (shields.isEmpty()) {
            ACTIVE_SHIELDS.remove(playerUuid);
            return List.of();
        }

        List<ShieldVisual> visuals = new ArrayList<>(shields.size());
        for (ShieldHit shield : shields) {
            // 下界钳制：即使因为时钟回退等原因漏判了 isStale，也不允许出现负年龄或 alpha=1.0 的定格帧。
            long remaining = Math.max(0L, shield.expiryTick() - now);
            float alpha = Math.max(0.0F, Math.min(1.0F, remaining / (float) SHIELD_DURATION_TICKS));
            long ageTicks = Math.max(0L, now - shield.startTick());
            visuals.add(new ShieldVisual(shield.relativeImpactYaw(), shield.impactHeight(), alpha, ageTicks));
        }
        return visuals;
    }

    /** 断线/退出世界时清空全部受击闪光状态，由客户端统一清理入口调用。 */
    public static void reset() {
        ACTIVE_SHIELDS.clear();
    }

    /**
     * 判断条目是否属于"当前世界之外"的残留：gameTime 每个存档独立推进，
     * 切到 gameTime 更小的世界后旧的 expiryTick 会大于 now，条目永不过期，
     * 表现为 alpha 被钳到 1.0、ageTicks 为负、护盾定格。
     */
    private static boolean isStale(ShieldHit shield, long now) {
        return shield.expiryTick() <= now
                || shield.startTick() > now
                || shield.expiryTick() > now + SHIELD_DURATION_TICKS;
    }

    private record ShieldHit(long startTick, long expiryTick, float relativeImpactYaw, float impactHeight) {
    }

    public record ShieldVisual(float relativeImpactYaw, float impactHeight, float alpha, long ageTicks) {
    }
}
