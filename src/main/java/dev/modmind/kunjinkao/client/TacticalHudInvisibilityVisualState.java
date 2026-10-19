package dev.modmind.kunjinkao.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Client-side server-synced state used to hide true-invisible player equipment. */
public final class TacticalHudInvisibilityVisualState {

    private static final Set<UUID> TRUE_INVISIBLE_PLAYERS = new HashSet<>();

    private TacticalHudInvisibilityVisualState() {
    }

    public static void setTrueInvisible(UUID playerUuid, boolean enabled) {
        if (enabled) {
            TRUE_INVISIBLE_PLAYERS.add(playerUuid);
        } else {
            TRUE_INVISIBLE_PLAYERS.remove(playerUuid);
        }
    }

    public static boolean isTrueInvisible(UUID playerUuid) {
        return TRUE_INVISIBLE_PLAYERS.contains(playerUuid);
    }

    /**
     * 断线/退出世界时清空真隐形视觉集合：集合只会增/改不会减，
     * 不清理会让上个存档里处于真隐形的 UUID 在新世界继续被当成隐形目标。
     */
    public static void reset() {
        TRUE_INVISIBLE_PLAYERS.clear();
    }
}
