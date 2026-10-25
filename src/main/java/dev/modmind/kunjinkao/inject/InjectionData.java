package dev.modmind.kunjinkao.inject;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 「注入」标记与其数据表现（1.20.1 Forge 版）。
 *
 * <p>与 1.21.1 的差别只在存取方式：那边是 {@code DataComponents}（CUSTOM_DATA / LORE /
 * ATTRIBUTE_MODIFIERS / UNBREAKABLE），这边是原版 NBT（{@code getOrCreateTag} /
 * {@code display.Lore} / {@code ItemStack#addAttributeModifier} / {@code Unbreakable}）。
 * <b>标记的存在形式不同，语义完全一致</b>：注入状态跟着物品栈本身走。</p>
 *
 * <p>刻意不依赖 {@code KunJinKaoSwordItem.dataTag}：那个方法读的是剑自己的设置子标签，
 * 而注入标记是"任何物品都可能带"的，放在根标签更直观，也免得剑以后改存储布局时
 * 把注入状态一起带崩。</p>
 */
public final class InjectionData {

    /** 注入标记的 NBT 键名（根标签）。 */
    public static final String INJECTED_KEY = "KunjinKaoInjected";

    /** 注入物品多出来的那一行注释。 */
    public static final Component LORE_LINE =
            Component.translatable("lore.kunjinkao.injected");

    /**
     * 管理员剑的攻击力加成：{@code SwordItem.createAttributes(Tiers.DIAMOND, 3, -2.4F)}
     * 里钻石阶 +3 再额外 +3 = +6（玩家基础 1 → 面板 7，与钻石剑一致）。
     */
    private static final double INJECTED_ATTACK_DAMAGE = 6.0D;

    /** 攻击速度加成，来自原剑的 -2.4（ADDITION，即面板 1.6）。 */
    private static final double INJECTED_ATTACK_SPEED = -2.4D;

    /*
     * 1.20.1 的 AttributeModifier 用 UUID 认身份（1.21 起才改成 ResourceLocation），
     * 所以这里用固定字符串派生稳定的 UUID —— 固定就意味着重复注入不会叠出第二条修饰符。
     */
    private static final UUID DAMAGE_MODIFIER_ID =
            UUID.nameUUIDFromBytes("kunjinkao:injected_attack_damage".getBytes(StandardCharsets.UTF_8));
    private static final UUID SPEED_MODIFIER_ID =
            UUID.nameUUIDFromBytes("kunjinkao:injected_attack_speed".getBytes(StandardCharsets.UTF_8));
    private static final String DAMAGE_MODIFIER_NAME = "kunjinkao.injected.damage";
    private static final String SPEED_MODIFIER_NAME = "kunjinkao.injected.speed";

    private InjectionData() {
    }

    /**
     * 这个物品栈是否被注入过。
     *
     * <p>刻意不检查物品类型：任何物品都可能被注入。空栈永远不算。
     */
    public static boolean isInjected(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(INJECTED_KEY);
    }

    /**
     * 把注入效果落到物品上：标记 + 注释 + 数值 + 无法破坏。
     *
     * <p>幂等：重复调用不会叠加第二行注释或第二条属性修饰符。
     *
     * @return true 表示这次确实写入了（之前没被注入过）
     */
    public static boolean apply(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        boolean already = isInjected(stack);

        // 1.20.1 的 ItemStack#copy() 是"共用同一个 CompoundTag 引用"（setTag 只赋引用）。
        // 直接从 getOrCreateTag() 上改，会把同一组里的其它副本一起改掉 —— 表现就是
        // 「注入 1 个，整组 64 个全变成注入物，拿哪个都能开菜单」。这里先复制一份再写回，
        // 只让这一个栈拿到新标签。
        CompoundTag isolated = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        stack.setTag(isolated);
        stack.getOrCreateTag().putBoolean(INJECTED_KEY, true);
        appendLore(stack);
        applyCombatStats(stack);
        // 原剑的 isDamageable 恒为 false 且挂着 Unbreakable，等价于"永远不掉耐久"。
        stack.getOrCreateTag().putBoolean("Unbreakable", true);
        return !already;
    }

    /** 撤掉注入效果（给未来的"解除注入"留的入口，目前没有调用点）。 */
    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(INJECTED_KEY);
        tag.remove("Unbreakable");
        removeLore(stack);
        removeCombatStats(stack);
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }

    // ===== 注释（display.Lore） =====

    private static String loreJson() {
        // 惰性求值：Component.Serializer 依赖注册表，不能在类初始化阶段调用。
        return Component.Serializer.toJson(LORE_LINE);
    }

    private static void appendLore(ItemStack stack) {
        appendLoreLine(stack, LORE_LINE);
    }

    /** 追加任意一行注释（拟态刷的专属简介也走这里）。已有同样文案就不重复加。 */
    public static void appendLoreLine(ItemStack stack, Component line) {
        String json = Component.Serializer.toJson(line);
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = display.getList("Lore", Tag.TAG_STRING);
        for (int i = 0; i < lore.size(); i++) {
            if (json.equals(lore.getString(i))) {
                return;
            }
        }
        lore.add(StringTag.valueOf(json));
        display.put("Lore", lore);
    }

    private static void removeLore(ItemStack stack) {
        removeLoreLine(stack, LORE_LINE);
    }

    /** 去掉指定的那一行注释；其它注释保持原样、原顺序。 */
    public static void removeLoreLine(ItemStack stack, Component target) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("display", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag display = tag.getCompound("display");
        if (!display.contains("Lore", Tag.TAG_LIST)) {
            return;
        }
        String json = Component.Serializer.toJson(target);
        ListTag lore = display.getList("Lore", Tag.TAG_STRING);
        ListTag kept = new ListTag();
        boolean removed = false;
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.getString(i);
            if (!removed && json.equals(line)) {
                removed = true;
                continue;
            }
            kept.add(StringTag.valueOf(line));
        }
        if (!removed) {
            return;
        }
        if (kept.isEmpty()) {
            display.remove("Lore");
        } else {
            display.put("Lore", kept);
        }
        if (display.isEmpty()) {
            tag.remove("display");
        }
    }

    // ===== 数值（AttributeModifiers） =====

    private static void applyCombatStats(ItemStack stack) {
        // addAttributeModifier 是追加语义，所以先按固定 UUID 把旧的摘掉，避免重复。
        removeCombatStats(stack);
        stack.addAttributeModifier(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(DAMAGE_MODIFIER_ID, DAMAGE_MODIFIER_NAME,
                        INJECTED_ATTACK_DAMAGE, AttributeModifier.Operation.ADDITION),
                EquipmentSlot.MAINHAND);
        stack.addAttributeModifier(Attributes.ATTACK_SPEED,
                new AttributeModifier(SPEED_MODIFIER_ID, SPEED_MODIFIER_NAME,
                        INJECTED_ATTACK_SPEED, AttributeModifier.Operation.ADDITION),
                EquipmentSlot.MAINHAND);
    }

    private static void removeCombatStats(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("AttributeModifiers", Tag.TAG_LIST)) {
            return;
        }
        ListTag list = tag.getList("AttributeModifiers", Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String name = entry.getString("AttributeName");
            // 只摘我们自己加的那两条，原物品自带的修饰符原样保留。
            if (DAMAGE_MODIFIER_NAME.equals(name) || SPEED_MODIFIER_NAME.equals(name)) {
                continue;
            }
            String modifierName = entry.getString("Name");
            if (DAMAGE_MODIFIER_NAME.equals(modifierName) || SPEED_MODIFIER_NAME.equals(modifierName)) {
                continue;
            }
            kept.add(entry);
        }
        if (kept.isEmpty()) {
            tag.remove("AttributeModifiers");
        } else {
            tag.put("AttributeModifiers", kept);
        }
    }
}
