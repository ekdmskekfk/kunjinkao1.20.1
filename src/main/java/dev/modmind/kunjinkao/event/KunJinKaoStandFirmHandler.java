package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;

/**
 * 屹立不倒（204）的服务端半边：取消击退、取消换维度。
 * <p>
 * 客户端那一半（{@code StandFirmClient}）负责"位置不被挪走"，这里负责两件客户端管不了的事：
 * <ul>
 *   <li><b>击退</b>：{@link LivingKnockBackEvent} 是所有攻击/技能击退的统一入口，
 *       在这里取消比"事后把速度清零"更早也更干净 —— 速度包根本不会发出去，
 *       画面不会先飞出去再被拉回来。</li>
 *   <li><b>换维度</b>：{@link EntityTravelToDimensionEvent} 覆盖传送门、末地传送门、
 *       跨维度 /tp 与用标准 API 换维度的模组 ——
 *       {@code ServerPlayer#teleportTo(ServerLevel, ...)} 与 {@code changeDimension}
 *       最终都走这里，取消之后那次传送整个不发生（返回 null，不会传一半）。</li>
 * </ul>
 * 注意爆炸推力与水流/活塞推动不走事件（它们直接改速度或坐标），那些由客户端那一半收回。
 */
public final class KunJinKaoStandFirmHandler {

    @SubscribeEvent
    public void onKnockBack(LivingKnockBackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isOn(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isOn(player)) {
            event.setCanceled(true);
        }
    }

    private static boolean isOn(ServerPlayer player) {
        return KunJinKaoQolHandler.isOn(player, QolSetting.STAND_FIRM);
    }
}