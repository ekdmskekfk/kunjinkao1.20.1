package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.qol.QolSetting;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.AnvilUpdateEvent;

/**
 * 不朽铭文（功能 77）：铁砧左右放两个相同物品，直接合出带【无法破坏】的成品。
 * <p>
 * <b>接管条件写得很窄，这是有意的。</b> 铁砧上的"同物品"分支本来就有原版用途
 * （两把残镐合起来修耐久），我们把它换成"变成无法破坏"，等于覆盖了那条路径 ——
 * 所以要确认这确实是"两个一模一样的物品"，而不是附魔不同、数量不同、
 * 或者根本是"用材料修装备"那几种情况，那些一律放行原版。
 * <p>
 * 开关与经验成本都是服务器级（只有 OP 能改），存在 {@code AdminToolConfig [qol]}。
 */
public final class KunJinKaoAnvilHandler {

    @SubscribeEvent
    public void onAnvilUpdate(AnvilUpdateEvent event) {
        if (AdminToolConfig.qolServerValue(QolSetting.ANVIL_UNBREAKABLE) <= 0) {
            return;
        }
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (left.isEmpty() || right.isEmpty()) {
            return;
        }
        // 右侧一次只放一个：放一整摞通常意味着玩家想干别的（原版的批量修理就不认这个），
        // 我们不去猜他的意图。
        if (right.getCount() != 1) {
            return;
        }
        // 用 isSameItemSameComponents 而不是"物品类型相同"：附魔不同的两把镐子组件不同，
        // 原版也不把它们当同一个物品来修，这里跟着保持一致。
        if (!ItemStack.isSameItemSameTags(left, right)) {
            return;
        }
        // 没有耐久概念的东西（圆石、食物）合起来本来什么都不该发生，别白送一个无法破坏。
        if (!left.isDamageableItem()) {
            return;
        }
        // 已经是无法破坏的再合一次等于白花经验，直接不动，让原版照常处理。
        if (left.hasTag() && left.getTag().getBoolean("Unbreakable")) {
            return;
        }

        ItemStack output = left.copy();
        output.getOrCreateTag().putBoolean("Unbreakable", true);
        event.setOutput(output);
        // 成本必须显式设置：不设的话原版会按"修理"那条路算，最坏情况是 0 级白送 ——
        // 那样创造模式以外也能零代价刷出一堆无法破坏的工具。
        event.setCost(Math.max(1, AdminToolConfig.qolServerValue(QolSetting.ANVIL_XP_COST)));
        event.setMaterialCost(1);
    }
}