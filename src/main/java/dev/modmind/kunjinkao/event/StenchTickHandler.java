package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.entity.KunJinKaoEffects;
import dev.modmind.kunjinkao.entity.StenchEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 骚臭的每 tick 逻辑（1.20.1）。
 *
 * <p>为什么放在事件里而不是 {@code MobEffect#applyEffectTick}：1.20.1 那个方法
 * 返回 void、还要靠 {@code isDurationEffectTick} 门控，与 1.21.1 的签名不一致；
 * 放在 {@code LivingTickEvent} 里两边逻辑就能写成同一份，行为不会漂。</p>
 */
public final class StenchTickHandler {

    private StenchTickHandler() {
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        int elapsed = StenchEffect.elapsedTicks(entity);
        if (elapsed < 0) {
            return;
        }
        if (elapsed >= StenchEffect.DEATH_AFTER_TICKS) {
            // 先摘效果再打伤害，避免重复触发。
            entity.removeEffect(KunJinKaoEffects.STENCH.get());
            entity.hurt(StenchEffect.stenchSource(entity), Float.MAX_VALUE);
            return;
        }
        if (elapsed % 10 == 0) {
            StenchEffect.scareNearbyMobs(entity);
        }
        if (elapsed % 5 == 0) {
            StenchEffect.spawnStenchParticles(entity);
        }
    }
}
