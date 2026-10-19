package dev.modmind.kunjinkao.mixin;

import dev.modmind.kunjinkao.config.QolStateSavedData;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/**
 * 万载不磨（功能 70）：开了这一项的玩家，物品不再掉耐久。
 * <p>
 * 作用点是 {@code ItemStack#hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)} 的
 * {@code amount} 参数，把它改成 0 —— 于是"扣耐久"这件事从源头不发生，
 * 所有调用方（攻击、挖掘、盔甲磨损…）一并覆盖，不需要逐个去堵。
 * <p>
 * 三条收窄措施，缺一不可：
 * <ul>
 *   <li>{@code require = 0}：目标是外部类 {@code ItemStack}，别的模组改过它的方法体就会让注入失败。
 *       "注入不上"应当退化成原版行为，而不是启动崩溃。</li>
 *   <li>只认 {@link ServerPlayer}：客户端也有一份物品栈，在客户端把伤害改成 0
 *       只会让两边的耐久显示不一致，而客户端本来就不是权威。</li>
 *   <li>只认玩家的存档设置：{@code hurtAndBreak} 的影响面是<b>全物品</b>，
 *       判定必须窄到一眼能说清。这个开关是"玩家自己的"而不是"手上拿什么"——
 *       按物品判定的话，换一下主手就会时灵时不灵。</li>
 * </ul>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

    @ModifyVariable(
            method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1,
            require = 0)
    // 1.21 的目标是 hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)；
    // 1.20.1 没有 ServerLevel 那一位，所以加工参数也要跟着少一个。
    private int kunjinkao$negateDurabilityDamage(int amount, LivingEntity entity, Consumer<Item> onBreak) {
        if (amount <= 0 || !(entity instanceof ServerPlayer player)) {
            return amount;
        }
        boolean protectedFromWear = QolStateSavedData.get(player.server)
                .getValue(player.getUUID(), QolSetting.DURABILITY_PROTECTION) > 0;
        return protectedFromWear ? 0 : amount;
    }
}