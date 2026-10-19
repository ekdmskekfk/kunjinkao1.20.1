package dev.modmind.kunjinkao.recipe;

import dev.modmind.kunjinkao.SwordRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 「锟斤拷之剑」的官方配方：3x3 里 8 根木棍 + 中间一块圆石。
 * <p>
 * 产物带一个 {@code kunjinkao:pending_admin_sword_craft} 标记，由
 * {@code AdminSwordCraftingHandler} 在合成事件里校验授权后再决定留不留。
 * <p>
 * 1.20.1 差异（配方 API 换代，语义不变）：
 * <ul>
 *   <li>{@code CraftingInput} → {@code CraftingContainer}
 *       （{@code width()/height()/size()} → {@code getWidth()/getHeight()/getContainerSize()}）；</li>
 *   <li>{@code assemble(…, HolderLookup.Provider)} → {@code assemble(…, RegistryAccess)}；</li>
 *   <li>{@code CustomRecipe} 的构造器多一个 {@code ResourceLocation id}；</li>
 *   <li>标记仍在物品 NBT 上（1.20.1 没有 DataComponent）。</li>
 * </ul>
 */
public final class AdminSwordRecipe extends CustomRecipe {

    public static final String PENDING_CRAFT_TAG = "kunjinkao:pending_admin_sword_craft";

    public static final SimpleCraftingRecipeSerializer<AdminSwordRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(AdminSwordRecipe::new);

    public AdminSwordRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer crafting, Level level) {
        return isPattern(crafting);
    }

    public static boolean isPattern(CraftingContainer crafting) {
        if (crafting.getWidth() != 3 || crafting.getHeight() != 3) {
            return false;
        }
        for (int slot = 0; slot < crafting.getContainerSize(); slot++) {
            ItemStack stack = crafting.getItem(slot);
            if (slot == 4) {
                if (!stack.is(Items.COBBLESTONE)) {
                    return false;
                }
            } else if (!stack.is(Items.STICK)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingContainer crafting, RegistryAccess registries) {
        ItemStack result = new ItemStack(SwordRegistry.KUN_JIN_KAO_SWORD.get());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(PENDING_CRAFT_TAG, true);
        result.setTag(tag);
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width == 3 && height == 3;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    public static boolean isPendingAdminSword(ItemStack stack) {
        return stack.is(SwordRegistry.KUN_JIN_KAO_SWORD.get())
                && stack.hasTag() && stack.getTag().getBoolean(PENDING_CRAFT_TAG);
    }

    public static void clearPendingCraftTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(PENDING_CRAFT_TAG);
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }
}