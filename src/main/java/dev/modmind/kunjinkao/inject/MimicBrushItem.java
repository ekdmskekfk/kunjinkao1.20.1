package dev.modmind.kunjinkao.inject;

import net.minecraft.world.item.BrushItem;

/**
 * 拟态刷的物品本体（1.20.1 Forge 版）：<b>直接继承原版 {@link BrushItem}</b>。
 *
 * <p>继承而不是给原版刷子挂 CustomModelData，是因为后者要靠覆写
 * {@code assets/minecraft/models/item/brush.json} 才能换模型 —— 那既会碰到别人的资源，
 * 又必须用「custom_model_data + brushing」的组合谓词才能挑出刷取中间帧，
 * 实测组合谓词在刷取过程中会漏匹配、回落到原版那三条 override（表现为刷取时
 * 物品栏图标变回棕色原版刷子）。</p>
 *
 * <p>换成自己的物品之后：刷取逻辑、刷取时长、刷取动画、音效全部继承自 {@code BrushItem}；
 * 模型与材质完全归 {@code kunjinkao:item/mimic_brush} 管，刷取三帧也写在自己的模型文件里，
 * 与原版刷子互不影响。</p>
 *
 * <p>这个物品<b>不进创造模式物品栏</b>，正常途径拿不到，只能由注入物品拟态而来。</p>
 */
public class MimicBrushItem extends BrushItem {

    public MimicBrushItem(Properties properties) {
        super(properties);
    }
}
