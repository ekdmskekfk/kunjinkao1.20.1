package dev.modmind.kunjinkao.mixin;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import dev.modmind.kunjinkao.config.QolStateSavedData;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 烈焰不侵（功能 84）：开了这一项的玩家完全免疫火焰。
 * <p>
 * 改的是 {@code Entity#fireImmune()} 的返回值。挑这个方法而不是去逐个堵伤害来源，
 * 是因为原版自己就把"免不免疫火"这件事挂在这里：
 * 着火判定、火焰伤害、以及 {@code displayFireAnimation}（身上那层火焰的显示）全都读它，
 * 改一处就三处一起对，不用分别处理"不掉血但身上还在烧"这种半吊子状态。
 * <p>
 * <b>两边都要判。</b> 这个方法客户端也会调（画火焰动画），而客户端拿的是
 * {@code QolStatePayload} 推来的副本，所以服务端读存档、客户端走
 * {@link ClientHooks#isQolEnabledOnClient} —— mixin 是 common 的，
 * 不能直接引用 {@code client} 包里的类，否则专用服务器加载时就会炸。
 * <p>
 * {@code require = 0}：目标是外部类 {@code Entity}，注入不上应当退化成原版行为。
 */
@Mixin(Entity.class)
public abstract class EntityFireImmuneMixin {

    @Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true, require = 0)
    private void kunjinkao$fireProtect(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player player)) {
            return;
        }
        boolean enabled;
        if (player instanceof ServerPlayer serverPlayer) {
            enabled = QolStateSavedData.get(serverPlayer.server)
                    .getValue(serverPlayer.getUUID(), QolSetting.FIRE_IMMUNE) > 0;
        } else {
            enabled = ClientHooks.isQolEnabledOnClient(QolSetting.FIRE_IMMUNE);
        }
        if (enabled) {
            cir.setReturnValue(true);
        }
    }
}