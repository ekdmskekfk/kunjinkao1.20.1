package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.SwordRegistry;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 白名单玩家进入冒险模式时，服务端自动补发一把管理员剑。 */
public final class AdminAdventureSwordGrantHandler {
    private final Map<UUID, Boolean> wasInAdventure = new HashMap<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player) || player.level().isClientSide()
                ) {
            return;
        }
        boolean inAdventure = player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE;
        if (inAdventure && !wasInAdventure.getOrDefault(player.getUUID(), false)) {
            grantIfAuthorized(player);
        }
        wasInAdventure.put(player.getUUID(), inAdventure);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        wasInAdventure.remove(event.getEntity().getUUID());
    }

    public static void grantIfAuthorized(ServerPlayer player) {
        if (!AdminToolConfig.isAuthorized(player.getUUID())
                || KunJinKaoProtectionHandler.hasSwordInInventory(player)) {
            return;
        }
        ItemStack sword = new ItemStack(SwordRegistry.KUN_JIN_KAO_SWORD.get());
        if (!player.getInventory().add(sword)) {
            player.drop(sword, false);
        }
        player.containerMenu.broadcastChanges();
        player.displayClientMessage(Component.literal("§b已发放管理员剑：冒险模式授权生效"), true);
    }
}
