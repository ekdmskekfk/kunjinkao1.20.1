package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 去掉"在空中挖"与"在水里挖"这两条减速惩罚。
 * <p>
 * 原版把这两条写在 {@code Player.getDigSpeed} 里，叠在工具速度之后：
 * <pre>
 *   if (this.isEyeInFluid(FluidTags.WATER)) {
 *       f *= (float) this.getAttribute(Attributes.SUBMERGED_MINING_SPEED).getValue();   // 默认 0.2，即 /5
 *   }
 *   if (!this.onGround()) {
 *       f /= 5.0F;                                                                     // 空中 /5
 *   }
 * </pre>
 * 手持本模组的剑时，这两条都不该生效 —— 它的挖掘速度是直接设定的一档数值
 * （{@code KunJinKaoSwordItem.getDestroySpeed}），不该再被原版的处境系数打折。
 * <p>
 * 做法是在 {@link PlayerEvent.BreakSpeed} 里按【同样的系数】乘回去，
 * 而不是硬写一个 ×5：
 * <ul>
 *   <li>水下：除以当前 {@code SUBMERGED_MINING_SPEED} 的值 —— 这样装了水下速掘附魔的玩家
 *       不会被算成"又乘了一次"；属性被别的模组改过也一样对得上。</li>
 *   <li>空中：乘 5。</li>
 * </ul>
 * 只有手持本模组的剑（或开了凌空采掘）才处理；其它情况完全不动，原版行为不受影响。
 */
public final class SwordMiningPenaltyHandler {

    public SwordMiningPenaltyHandler() {
    }

    @SubscribeEvent
    public void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        // 凌空采掘（功能面板 83）：不开剑也能免掉处境惩罚。
        // 两个来源是"或"的关系 —— 手持本模组剑本来就是它的能力，83 只是把这个能力发给别的工具。
        boolean holdsSword = KunJinKaoSwordItem.isSwordLike(player.getMainHandItem());
        boolean flyMining = player instanceof ServerPlayer serverPlayer
                && KunJinKaoQolHandler.isOn(serverPlayer, QolSetting.FLY_MINING);
        if (!holdsSword && !flyMining) {
            return;
        }
        float speed = event.getNewSpeed();
        boolean changed = false;

        if (player.isEyeInFluid(FluidTags.WATER)) {
            float submerged = (float) player.getAttributeValue(net.minecraftforge.common.ForgeMod.SWIM_SPEED.get());
            // 属性为 0（被改坏了）时不做除法，否则会得到无穷大。
            if (submerged > 0.0F) {
                speed /= submerged;
                changed = true;
            }
        }
        if (!player.onGround()) {
            speed *= 5.0F;
            changed = true;
        }

        if (changed && speed != event.getNewSpeed()) {
            event.setNewSpeed(speed);
        }
    }
}