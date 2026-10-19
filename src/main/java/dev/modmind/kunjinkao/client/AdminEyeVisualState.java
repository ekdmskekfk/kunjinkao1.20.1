package dev.modmind.kunjinkao.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client copy of the server-authoritative administrator eye-display state. */
public final class AdminEyeVisualState {
    private static final Set<UUID> ENABLED_PLAYERS = ConcurrentHashMap.newKeySet();

    private AdminEyeVisualState() {
    }

    public static void setEnabled(UUID playerUuid, boolean enabled) {
        if (enabled) {
            ENABLED_PLAYERS.add(playerUuid);
        } else {
            ENABLED_PLAYERS.remove(playerUuid);
        }
    }

    public static boolean isEnabled(UUID playerUuid) {
        return ENABLED_PLAYERS.contains(playerUuid);
    }

    /**
     * 断线/退出世界时清空客户端缓存的管理员眼部名单。
     * 该集合只会增/改不会减，不清理会把上个存档（或上个服务器）的 UUID 带到下一个世界。
     */
    public static void reset() {
        ENABLED_PLAYERS.clear();
    }
}
