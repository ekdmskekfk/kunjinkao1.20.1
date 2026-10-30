package dev.modmind.kunjinkao.mixin;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.inject.InjectionData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入物品的挖掘速度：直接改 {@code ItemStack#getDestroySpeed} 的返回值。
 *
 * <p><b>为什么不再走 {@code PlayerEvent.BreakSpeed}</b>：那个事件在整合包里会被后面的
 * 处理器反复覆盖 —— 优先级提到 HIGHEST 太早、降到 LOWEST 太晚，最终值仍然不是设定值
 * （实测设 100 仍然挖得很慢）。而 {@code Player#getDigSpeed} 的第一句就是
 * {@code inventory.getDestroySpeed(state)} → {@code stack.getDestroySpeed(state)}，
 * 在物品层给出正确数字，后面的乘数（水下/药剂/效率）照常生效，不会被谁覆盖。</p>
 *
 * <p>只处理"注入过的、且不是原版管理员剑"的栈：原剑自己有 {@code getDestroySpeed} 重写。</p>
 */
@Mixin(ItemStack.class)
public class ItemStackDestroySpeedMixin {

    @Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
    private void kunjinkao$injectedMiningSpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (self.isEmpty() || !InjectionData.isInjected(self)) {
            return;
        }
        if (self.getItem() instanceof KunJinKaoSwordItem) {
            return;
        }
        cir.setReturnValue((float) KunJinKaoSwordItem.getMiningSpeed(self));
    }
}
