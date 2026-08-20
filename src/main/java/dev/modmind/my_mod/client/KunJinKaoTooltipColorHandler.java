package dev.modmind.my_mod.client;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.awt.Color;
import java.util.List;

/**
 * 客户端 Tooltip 颜色流动处理器：
 * 在现有 Tooltip 处理器（NORMAL 优先级）之后以 LOWEST 优先级运行，
 * 每帧按系统时间计算 HSV 色相，仅将锟斤拷之剑 Tooltip 中的 ∞ 染成彩虹色，
 * 实现真正的颜色循环流动（非 OBFUSCATED 乱码）。
 */
@Mod.EventBusSubscriber(modid = "my_mod", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KunJinKaoTooltipColorHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltipLowest(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof KunJinKaoSwordItem)) {
            return;
        }
        if (KunJinKaoSwordItem.isDisguised(event.getItemStack())) {
            return; // 伪装状态显示原版 Tooltip，不染色
        }

        float hue = (System.currentTimeMillis() % 3600L) / 3600.0F;
        int rgb = Color.HSBtoRGB(hue, 1.0F, 1.0F) & 0xFFFFFF;
        TextColor color = TextColor.fromRgb(rgb);

        List<Component> tooltip = event.getToolTip();
        for (int i = 0; i < tooltip.size(); i++) {
            Component line = tooltip.get(i);
            if (containsInfinity(line)) {
                tooltip.set(i, recolorInfinity(line, color));
            }
        }
    }

    private static boolean containsInfinity(Component component) {
        if (component.getString().contains("∞")) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsInfinity(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static Component recolorInfinity(Component component, TextColor color) {
        String text = component.getString();
        if ("∞".equals(text)) {
            return Component.literal("∞").withStyle(style -> style.withColor(color).withObfuscated(false));
        }
        MutableComponent result = component.copy();
        List<Component> siblings = result.getSiblings();
        for (int i = 0; i < siblings.size(); i++) {
            Component sibling = siblings.get(i);
            if (containsInfinity(sibling)) {
                siblings.set(i, recolorInfinity(sibling, color));
            }
        }
        return result;
    }
}
