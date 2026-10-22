package dev.modmind.kunjinkao.inject;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 「拟态」：开启刷子功能时，把注入物品变成<b>真正的原版刷子</b>（1.20.1 Forge 版）。
 *
 * <p>为什么必须换物品：刷取走的是 {@code Item#startUsingItem} + {@code getUseDuration} +
 * {@code onUseTick} + {@code getUseAnimation}，全是<b>物品方法</b>。任意物品的 use-duration
 * 由它自己的类决定，注入改变不了。只有让手里的东西真的变成 {@code minecraft:brush}，
 * 原版那套长按刷取、动画、音效才会原生跑起来。</p>
 *
 * <p>换了物品之后仍然"是它自己"：注入标记整个随 NBT 搬过去，全部能力、注释、属性都在；
 * 再靠 {@code CustomModelData} 换成 {@code kunjinkao:item/mimic_brush} 的青蓝模型，
 * 配专属名称与简介。关掉刷子时按记下的原始物品 id 还原（含原始自定义名称）。</p>
 *
 * <p>1.20.1 与 1.21.1 的差别只在存取方式：这边是根 NBT（{@code CustomModelData} 整数、
 * {@code display.Name} 文本、{@code ItemStack#setHoverName}），那边是 DataComponents。</p>
 */
public final class MimicBrush {

    /** 原版刷子模型里 {@code custom_model_data} 的判别值，与 brush.json 的 overrides 对应。 */
    public static final int MODEL_DATA = 20251;

    /** 记原始物品 id（还原时用）。 */
    public static final String ORIGINAL_ID_KEY = "KunjinKaoMimicOriginalId";
    /** 记原始自定义名称的 JSON（原本没改名就不写）。 */
    public static final String ORIGINAL_NAME_KEY = "KunjinKaoMimicOriginalName";

    private MimicBrush() {
    }

    /** 是不是我们造出来的拟态刷。 */
    public static boolean isMimic(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.BRUSH)) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(ORIGINAL_ID_KEY);
    }

    /**
     * 刷子开关变化时调用（服务端）。
     *
     * <p>只对「注入过、不是剑本体、且是单件」的物品生效：剑本体自带刷子动画；
     * 刷子不能堆叠，整组换过去会吞掉多余数量。</p>
     */
    public static void applyForToggle(ServerPlayer player, InteractionHand hand, boolean enabled) {
        ItemStack stack = player.getItemInHand(hand);
        if (!InjectionData.isInjected(stack)) {
            return;
        }
        if (stack.getItem() instanceof KunJinKaoSwordItem) {
            return;
        }
        if (stack.getCount() != 1) {
            return;
        }
        if (enabled) {
            morphIntoBrush(player, hand, stack);
        } else {
            restoreOriginal(player, hand, stack);
        }
    }

    private static void morphIntoBrush(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        if (isMimic(stack)) {
            return;
        }
        CompoundTag tag = stack.getTag() != null ? stack.getTag().copy() : new CompoundTag();
        tag.putString(ORIGINAL_ID_KEY, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (stack.hasCustomHoverName()) {
            tag.putString(ORIGINAL_NAME_KEY, Component.Serializer.toJson(stack.getHoverName()));
        }
        // 刷子模型要靠这个整数切到我们自己的模型。
        tag.putInt("CustomModelData", MODEL_DATA);

        ItemStack mimic = new ItemStack(Items.BRUSH);
        mimic.setTag(tag);
        mimic.setHoverName(Component.translatable("item.kunjinkao.mimic_brush"));
        // 新栈没有原物品的注释/属性/无法破坏，重新落一遍（幂等）。
        InjectionData.apply(mimic);
        appendMimicLore(mimic);

        player.setItemInHand(hand, mimic);
        syncHand(player);
    }

    private static void restoreOriginal(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        if (!isMimic(stack)) {
            return;
        }
        CompoundTag tag = stack.getTag().copy();
        String id = tag.getString(ORIGINAL_ID_KEY);
        String nameJson = tag.contains(ORIGINAL_NAME_KEY) ? tag.getString(ORIGINAL_NAME_KEY) : null;
        tag.remove(ORIGINAL_ID_KEY);
        tag.remove(ORIGINAL_NAME_KEY);
        tag.remove("CustomModelData");

        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? Items.DIRT : BuiltInRegistries.ITEM.get(key);
        ItemStack back = new ItemStack(item);
        back.setTag(tag);
        if (nameJson != null) {
            Component parsed = Component.Serializer.fromJson(nameJson);
            if (parsed != null) {
                back.setHoverName(parsed);
            }
        }
        InjectionData.apply(back);
        removeMimicLore(back);

        player.setItemInHand(hand, back);
        syncHand(player);
    }

    /** 换物品后要让客户端看到新栈，否则手里还显示旧的。 */
    private static void syncHand(ServerPlayer player) {
        player.containerMenu.broadcastChanges();
        player.getInventory().setChanged();
    }

    // ===== 拟态专属简介 =====

    private static void appendMimicLore(ItemStack stack) {
        InjectionData.appendLoreLine(stack, Component.translatable("lore.kunjinkao.mimic_brush.1"));
        InjectionData.appendLoreLine(stack, Component.translatable("lore.kunjinkao.mimic_brush.2"));
    }

    private static void removeMimicLore(ItemStack stack) {
        InjectionData.removeLoreLine(stack, Component.translatable("lore.kunjinkao.mimic_brush.1"));
        InjectionData.removeLoreLine(stack, Component.translatable("lore.kunjinkao.mimic_brush.2"));
    }
}
