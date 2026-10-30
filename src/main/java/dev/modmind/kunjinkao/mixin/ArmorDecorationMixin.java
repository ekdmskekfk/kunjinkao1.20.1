package dev.modmind.kunjinkao.mixin;

import dev.modmind.kunjinkao.client.qol.QolClientState;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 盔甲装饰：开启「功能控制台 → 装饰 → 盔甲装饰」后，玩家身上所有盔甲都渲染成固定外观。
 *
 * <p><b>为什么拦 {@code getArmorResource}</b>：原版这一层唯一的贴图来源就是它 ——
 * 每件盔甲每层（内层 / 外层）都会问一次"该用哪张贴图"。在这里换掉返回值，
 * 就等于把外观整体替换，而 <b>物品、属性、耐久、附魔一概不动</b>，关掉开关立刻恢复原样。</p>
 *
 * <p><b>为什么用原版的 {@code leather_layer_1_overlay}</b>：它是 64×32 的标准人形盔甲布局
 * （六个面齐全），本身是皮革盔甲的染色遮罩层，白灰底色、带褶皱阴影；直接拿来当装饰贴图
 * 既不会错位，也不涉及第三方模组的资源授权。</p>
 *
 * <p><b>为什么只对玩家生效</b>：僵尸、骷髅也有盔甲层，但它们是别的实体类型；
 * 装饰是玩家自己的外观设置，不该影响怪物。</p>
 */
@Mixin(HumanoidArmorLayer.class)
public class ArmorDecorationMixin {

    /** 原版自带的 64×32 人形盔甲图层。 */
    private static final ResourceLocation DECORATION_TEXTURE =
            new ResourceLocation("minecraft", "textures/models/armor/leather_layer_1_overlay.png");

    @Inject(method = "getArmorResource", at = @At("RETURN"), cancellable = true, remap = false)
    private void kunjinkao$armorDecoration(Entity entity, ItemStack stack, EquipmentSlot slot,
                                           String type, CallbackInfoReturnable<ResourceLocation> cir) {
        if (!(entity instanceof Player)) {
            return;
        }
        if (!(stack.getItem() instanceof ArmorItem)) {
            return;
        }
        if (!QolClientState.isOn(QolSetting.ARMOR_DECORATION)) {
            return;
        }
        cir.setReturnValue(DECORATION_TEXTURE);
    }
}
