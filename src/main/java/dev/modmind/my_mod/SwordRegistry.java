package dev.modmind.my_mod;

import dev.modmind.my_mod.entity.DiamondProjectile;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import dev.modmind.my_mod.recipe.AdminSwordRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class SwordRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ModMindEntry.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModMindEntry.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ModMindEntry.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, ModMindEntry.MOD_ID);

    public static final RegistryObject<Item> KUN_JIN_KAO_SWORD = ITEMS.register("kun_jin_kao",
        () -> new KunJinKaoSwordItem(Tiers.DIAMOND, 3, -2.4F, new Item.Properties())
    );

    public static final RegistryObject<RecipeSerializer<AdminSwordRecipe>> ADMIN_SWORD_RECIPE =
            RECIPE_SERIALIZERS.register("admin_sword", () -> AdminSwordRecipe.SERIALIZER);

    public static final RegistryObject<EntityType<DiamondProjectile>> DIAMOND_PROJECTILE =
        ENTITY_TYPES.register("diamond_projectile", () -> EntityType.Builder.<DiamondProjectile>of(DiamondProjectile::new, MobCategory.MISC)
            .sized(0.25F, 0.25F)
            .clientTrackingRange(4)
            .updateInterval(10)
            .build("diamond_projectile"));

    public static final RegistryObject<CreativeModeTab> KUN_JIN_KAO_TAB = CREATIVE_MODE_TABS.register("kun_jin_kao_tab",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.my_mod.kun_jin_kao"))
            .icon(() -> new ItemStack(KUN_JIN_KAO_SWORD.get()))
            .displayItems((params, output) -> {
                output.accept(KUN_JIN_KAO_SWORD.get());
                output.accept(AcceleratorRegistry.ACCELERATOR_ITEM.get());
            })
            .build()
    );

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
    }
}
