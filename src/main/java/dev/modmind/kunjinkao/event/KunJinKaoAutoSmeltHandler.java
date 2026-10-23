package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 挖掘熔炼：把挖到的掉落自动过一遍熔炉配方。
 * <p>
 * 原实现挂在 NeoForge 的 {@code BlockDropsEvent} 上（能拿到"已经生成好的掉落实体"，
 * 直接就地替换它们的物品）。1.20.1 没有这个事件，所以改由
 * {@code SwordAutoPickupHandler#onBlockBreak} 接管整刀破坏时调用 {@link #smeltList}：
 * 先熔炼、再按矿石倍率展开，最后统一入包。
 * <p>
 * 另外两条本来就绕过 {@code BlockDropsEvent} 的路径也复用同一套换算：
 * <ul>
 *   <li>{@code KunJinKaoUnbreakableBlockHandler}（基岩/屏障/命令方块的自算掉落）；</li>
 *   <li>挖矿倍数补发（只在原版路径真的走到时才需要）。</li>
 * </ul>
 * <p>
 * 1.20.1 差异：getRecipeFor 直接返回 {@code Optional<T>}（1.21 是 {@code RecipeHolder}，多一层 .value()）；hasSilkTouch 用 {@link EnchantmentHelper}（附魔是静态字段，
 * 1.21 才改成 Holder + ItemEnchantments 组件）。
 */
public final class KunJinKaoAutoSmeltHandler {

    private KunJinKaoAutoSmeltHandler() {
    }

    /**
     * 开关是否该生效：手上是管理员剑、开了挖掘熔炼、且剑上没有精准采集。
     * <p>
     * 挖矿倍数补发的那条路（{@code KunJinKaoOreDropHandler}）也走这里，
     * 免得"正常掉落是锭、倍数补发却是原矿"。
     */
    public static boolean isActive(ItemStack tool) {
        return KunJinKaoSwordItem.isSwordLike(tool)
                && KunJinKaoSwordItem.isAutoSmeltEnabled(tool)
                && !hasSilkTouch(tool);
    }

    /**
     * 一整堆掉落的熔炼产物：数量 = 配方产量 × 输入数量。
     * 没有熔炼配方（或输入为空）返回 {@link ItemStack#EMPTY}，调用方保留原掉落。
     * <p>
     * 返回的堆叠数量可能超过单堆上限，由调用方决定怎么拆。
     */
    public static ItemStack smeltAll(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) {
            return ItemStack.EMPTY;
        }
        SimpleContainer recipeInput = new SimpleContainer(input);
        ItemStack result = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, recipeInput, level)
                .map(recipe -> recipe.assemble(recipeInput, level.registryAccess()))
                .orElse(ItemStack.EMPTY);
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        result.setCount(result.getCount() * input.getCount());
        return result;
    }

    /** 剑上是否真的挂了精准采集附魔 —— 菜单开关写的是真附魔，玩家自己附的也算。 */
    private static boolean hasSilkTouch(ItemStack stack) {
        // 1.20.1：附魔是静态字段 + Map，没有 ItemEnchantments 组件。
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
    }

    /**
     * 把一整批掉落换成熔炼产物，没有配方的原样保留；开关没生效时直接把原列表还回去（不做拷贝）。
     * <p>
     * 给两条绕开 BlockDropsEvent 的补发路径复用：挖矿倍数补发、
     * 不可破坏方块（基岩/屏障/命令方块）的自算掉落。少了这个，
     * 同一个开关在不同挖掘路径下的表现会不一致。
     */
    public static List<ItemStack> smeltList(ServerLevel level, ItemStack tool, List<ItemStack> drops) {
        if (!isActive(tool)) {
            return drops;
        }
        List<ItemStack> smelted = new ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            ItemStack result = smeltAll(level, drop);
            smelted.add(result.isEmpty() ? drop : result);
        }
        return smelted;
    }
}