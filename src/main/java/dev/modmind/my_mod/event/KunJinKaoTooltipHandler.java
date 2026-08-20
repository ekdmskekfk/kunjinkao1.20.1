package dev.modmind.my_mod.event;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * 客户端 Tooltip 处理器：删除原版 “主手时：攻击伤害/攻击速度” 区块。
 * 自定义两行（∞ 攻击伤害 / -2.4 攻击速度）由
 * {@link KunJinKaoSwordItem#appendHoverText} 添加，但原版属性区仍会被
 * ItemStack.getTooltipLines 自动追加，因此这里在最终列表上删除原版区块。
 */
public class KunJinKaoTooltipHandler {

    private static final String MAINHAND_KEY = "item.modifiers.mainhand";
    private static final String ATTRIBUTE_PREFIX = "attribute.modifier.";

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof KunJinKaoSwordItem)) {
            return;
        }
        if (KunJinKaoSwordItem.isDisguised(stack)) {
            return; // 伪装状态显示原版钻石剑 Tooltip
        }
        removeVanillaMainhandAttributeSection(event.getToolTip());
    }

    private void removeVanillaMainhandAttributeSection(List<Component> tooltip) {
        int vanillaHeaderIndex = -1;
        for (int i = 0; i < tooltip.size(); i++) {
            if (isTranslationKey(tooltip.get(i), MAINHAND_KEY) && isFollowedByVanillaAttributes(tooltip, i)) {
                vanillaHeaderIndex = i;
                break;
            }
        }
        if (vanillaHeaderIndex < 0) {
            return;
        }

        int startRemove = (vanillaHeaderIndex > 0 && isEmptyLine(tooltip.get(vanillaHeaderIndex - 1)))
                ? vanillaHeaderIndex - 1
                : vanillaHeaderIndex;
        int endRemove = vanillaHeaderIndex + 1;
        while (endRemove < tooltip.size() && (isEmptyLine(tooltip.get(endRemove)) || isAttributeModifierLine(tooltip.get(endRemove)))) {
            endRemove++;
        }
        while (endRemove < tooltip.size() && isEmptyLine(tooltip.get(endRemove))) {
            endRemove++;
        }

        tooltip.subList(startRemove, endRemove).clear();
    }

    private boolean isFollowedByVanillaAttributes(List<Component> tooltip, int headerIndex) {
        int next = headerIndex + 1;
        while (next < tooltip.size() && isEmptyLine(tooltip.get(next))) {
            next++;
        }
        return next < tooltip.size() && isAttributeModifierLine(tooltip.get(next));
    }

    private boolean isTranslationKey(Component component, String key) {
        return component.getContents() instanceof TranslatableContents contents && key.equals(contents.getKey());
    }

    private boolean isAttributeModifierLine(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                && contents.getKey().startsWith(ATTRIBUTE_PREFIX);
    }

    private boolean isEmptyLine(Component component) {
        return Component.empty().equals(component);
    }
}
