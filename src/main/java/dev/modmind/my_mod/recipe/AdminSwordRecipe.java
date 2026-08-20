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
