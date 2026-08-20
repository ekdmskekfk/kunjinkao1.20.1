package dev.modmind.my_mod.event;

import dev.modmind.my_mod.config.AdminToolConfig;
import dev.modmind.my_mod.recipe.AdminSwordRecipe;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Server-side authorization for the hidden 3x3 admin-sword recipe. */
public final class AdminSwordCraftingHandler {
    private AdminSwordCraftingHandler() {
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        if (!(event.getInventory() instanceof CraftingContainer crafting) || !AdminSwordRecipe.isPattern(crafting)) {
            return;
        }

        if (AdminToolConfig.isAuthorized(player.getUUID())) {
            clearPendingCraftTags(player);
            AdminSwordRecipe.clearPendingCraftTag(event.getCrafting());
            return;
        }

        // ItemCraftedEvent fires before ResultSlot consumes the crafting grid. Shift
        // crafting passes an empty result stack here, so remove marker-tagged output
        // from the inventory as well as clearing the direct result stack.
        event.getCrafting().setCount(0);
        removePendingCraftedSwords(player);
        restoreIngredients(player, crafting);
        player.containerMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable("message.my_mod.admin_sword_not_authorized"), true);
    }

    private static void restoreIngredients(Player player, CraftingContainer crafting) {
        for (int slot = 0; slot < crafting.getContainerSize(); slot++) {
            ItemStack ingredient = crafting.removeItemNoUpdate(slot);
            if (!ingredient.isEmpty() && !player.getInventory().add(ingredient)) {
                player.drop(ingredient, false);
            }
        }
        crafting.setChanged();
    }

    private static void removePendingCraftedSwords(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (AdminSwordRecipe.isPendingAdminSword(stack)) {
                stack.setCount(0);
            }
        }

        ItemStack carried = player.containerMenu.getCarried();
        if (AdminSwordRecipe.isPendingAdminSword(carried)) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    private static void clearPendingCraftTags(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (AdminSwordRecipe.isPendingAdminSword(stack)) {
                AdminSwordRecipe.clearPendingCraftTag(stack);
            }
        }

        ItemStack carried = player.containerMenu.getCarried();
        if (AdminSwordRecipe.isPendingAdminSword(carried)) {
            AdminSwordRecipe.clearPendingCraftTag(carried);
        }
    }
}
