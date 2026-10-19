package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.network.AdminEyeStatePayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Synchronizes whitelist display state to every connected client. */
public final class AdminEyeSyncHandler {
    private AdminEyeSyncHandler() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joining)) {
            return;
        }
        // 只把其它玩家的状态单独发给新玩家；新玩家自己的状态由下面的 sendToAll 广播一次就够了。
        // 原来循环包含新玩家本人，再叠加 sendToAll，会让新玩家收到自己的状态两次。
        for (ServerPlayer player : joining.server.getPlayerList().getPlayers()) {
            if (player == joining) {
                continue;
            }
            NetworkHandler.sendToPlayer(joining,
                    new AdminEyeStatePayload(player.getUUID(), AdminToolConfig.isAuthorized(player.getUUID())));
        }
        NetworkHandler.sendToAll(
                new AdminEyeStatePayload(joining.getUUID(), AdminToolConfig.isAuthorized(joining.getUUID())));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer leaving) {
            NetworkHandler.sendToAll(new AdminEyeStatePayload(leaving.getUUID(), false));
        }
    }
}
