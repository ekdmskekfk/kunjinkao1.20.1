package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.SwordDrawAnimationPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 服务端检测“未持剑→持剑”的切换，并同步第三人称动画给跟踪者。 */
public final class SwordDrawAnimationSyncHandler {
    private final Map<UUID, Boolean> wasHolding = new HashMap<>();

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

        InteractionHand hand = findVisibleSwordHand(player);
        boolean holding = hand != null;
        boolean previouslyHolding = wasHolding.getOrDefault(player.getUUID(), false);
        if (holding && !previouslyHolding) {
            NetworkHandler.sendToAllTracking(player,
                    new SwordDrawAnimationPayload(player.getUUID(), hand));
        }
        wasHolding.put(player.getUUID(), holding);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        wasHolding.remove(event.getEntity().getUUID());
    }

    private static InteractionHand findVisibleSwordHand(ServerPlayer player) {
        if (isVisibleSword(player.getMainHandItem())) {
            return InteractionHand.MAIN_HAND;
        }
        return isVisibleSword(player.getOffhandItem()) ? InteractionHand.OFF_HAND : null;
    }

    private static boolean isVisibleSword(ItemStack stack) {
        return stack.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(stack);
    }
}
