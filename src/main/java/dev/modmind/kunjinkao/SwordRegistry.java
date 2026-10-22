package dev.modmind.kunjinkao;

import dev.modmind.kunjinkao.recipe.AdminSwordRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品 / 创意标签页 / 配方序列化器的注册表。
 * <p>
 * 1.20.1 差异：
 * <ul>
 *   <li>{@code DeferredRegister.Items#createItems} → {@code DeferredRegister<Item>#create(ForgeRegistries.ITEMS, …)}；</li>
 *   <li>{@code DeferredItem}/{@code DeferredHolder} → {@code RegistryObject}；</li>
 *   <li>{@code BuiltInRegistries.RECIPE_SERIALIZER} → {@code Registries.RECIPE_SERIALIZER}。</li>
 * </ul>
 */
public class SwordRegistry {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, KunJinKaoEntry.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, KunJinKaoEntry.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, KunJinKaoEntry.MOD_ID);

    public static final RegistryObject<Item> KUN_JIN_KAO_SWORD = ITEMS.register("kun_jin_kao",
            () -> new KunJinKaoSwordItem(Tiers.DIAMOND, new Item.Properties()));

    /**
     * 拟态刷：注入物品开启刷子功能时换成的物品。继承原版 BrushItem，
     * 所以刷取行为与动画完全是原版的；刻意不放进创造模式物品栏。
     */
    public static final RegistryObject<Item> MIMIC_BRUSH = ITEMS.register("mimic_brush",
            () -> new dev.modmind.kunjinkao.inject.MimicBrushItem(new Item.Properties()));

    /** 尿液瓶：拿玻璃瓶右键君君获得，用于「双君仪式」。 */
    public static final RegistryObject<Item> URINE_BOTTLE = ITEMS.register("urine_bottle",
            () -> new Item(new Item.Properties()));

    /** 除臭剂：右键使用解除自己身上的「骚臭」。 */
    public static final RegistryObject<Item> DEODORANT = ITEMS.register("deodorant",
            () -> new dev.modmind.kunjinkao.item.DeodorantItem(new Item.Properties()));
    public static final RegistryObject<RecipeSerializer<AdminSwordRecipe>> ADMIN_SWORD_RECIPE =
            RECIPE_SERIALIZERS.register("admin_sword", () -> AdminSwordRecipe.SERIALIZER);

    public static final RegistryObject<CreativeModeTab> KUN_JIN_KAO_TAB = CREATIVE_MODE_TABS.register("kun_jin_kao_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.kunjinkao.kun_jin_kao"))
                    .icon(() -> new ItemStack(KUN_JIN_KAO_SWORD.get()))
                    .displayItems((params, output) -> {
                        output.accept(KUN_JIN_KAO_SWORD.get());
                        output.accept(AcceleratorRegistry.ACCELERATOR_ITEM.get());
                        output.accept(WorldGateRegistry.WORLD_GATE_ITEM.get());
                        // 锟斤拷之手的刷怪蛋（实体在 KunJinKaoEntities 注册；引用写在 lambda 里，
                        // 不参与本类的静态初始化，所以没有初始化顺序问题）。
                        output.accept(dev.modmind.kunjinkao.entity.KunJinKaoEntities.HAND_SPAWN_EGG.get());
                        output.accept(URINE_BOTTLE.get());
                        output.accept(DEODORANT.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
    }
}