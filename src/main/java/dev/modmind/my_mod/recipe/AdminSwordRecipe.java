package dev.modmind.my_mod.recipe;

import dev.modmind.my_mod.SwordRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

public final class AdminSwordRecipe extends CustomRecipe {
    public static final String PENDING_CRAFT_TAG = "my_mod:pending_admin_sword_craft";
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

        for (int column = 0; column < 3; column++) {
            if (crafting.getItem(column).is(Items.AMETHYST_SHARD)
                    && crafting.getItem(column + 3).is(Items.DIAMOND_SWORD)
                    && crafting.getItem(column + 6).is(Items.ECHO_SHARD)
                    && allOtherSlotsEmpty(crafting, column)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allOtherSlotsEmpty(CraftingContainer crafting, int recipeColumn) {
        for (int slot = 0; slot < crafting.getContainerSize(); slot++) {
            if (slot % 3 != recipeColumn && !crafting.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingContainer crafting, RegistryAccess registries) {
        ItemStack result = new ItemStack(SwordRegistry.KUN_JIN_KAO_SWORD.get());
        result.getOrCreateTag().putBoolean(PENDING_CRAFT_TAG, true);
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
        CompoundTag tag = stack.getTag();
        return stack.is(SwordRegistry.KUN_JIN_KAO_SWORD.get()) && tag != null && tag.getBoolean(PENDING_CRAFT_TAG);
    }

    public static void clearPendingCraftTag(ItemStack stack) {
        if (stack.hasTag()) {
            stack.getTag().remove(PENDING_CRAFT_TAG);
        }
    }
}
