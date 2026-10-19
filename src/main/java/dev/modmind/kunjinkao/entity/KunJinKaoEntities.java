package dev.modmind.kunjinkao.entity;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.SwordRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/**
 * 本模组自己的实体注册。
 * <p>
 * 目前只有一个：{@code kunjinkao:hand} —— 一只贴地蠕动的手（见 {@link KunJinKaoHandEntity}）。
 * 它<b>不参与自然生成</b>：没有注册 spawn placement、没有生物群系修饰符，
 * 所以只能靠刷怪蛋或 {@code /summon} 出现。理由是这东西的定位是"管理员能放出来的东西"，
 * 而不是往世界里塞一种新怪物 —— 那会改变普通玩家的体验，不该由一个管理员工具模组来决定。
 */
public final class KunJinKaoEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, KunJinKaoEntry.MOD_ID);

    /**
     * 君君（原名"锟斤拷之手"）。
     * <p>
     * 碰撞箱刻意压得很扁（0.7 × 0.45）：它是一只贴地爬的手，不是站着的人形，
     * 判定框高一点就会显得"浮在地上"。眼睛高度取 0.3，让它看向玩家时模型抬头幅度合理。
     * <p>
     * 分类用 CREATURE 不用 MONSTER：它现在是**被打的受害者**（不攻击、被打就跑），
     * 而且 CREATURE 类默认不按"离玩家太远"自动清除 —— 宠物语义。
     */
    public static final RegistryObject<EntityType<KunJinKaoHandEntity>> HAND =
            ENTITY_TYPES.register("hand", () -> EntityType.Builder
                    .of(KunJinKaoHandEntity::new, MobCategory.CREATURE)
                    .sized(0.7F, 0.45F)
                    .clientTrackingRange(8)
                    .build(KunJinKaoEntry.MOD_ID + ":hand"));

    /**
     * 刷怪蛋。
     * <p>
     * 主色/副色对着照片取：肤色 {@code 0xE9C9B8}、指甲那种偏冷的白 {@code 0xF6F1E7}。
     * 放在 {@link SwordRegistry#ITEMS} 这一份物品注册表里，是为了让它和剑、加速方块、
     * 世界门一起出现在同一个创造栏里，而不是再开一个注册表。
     */
    public static final RegistryObject<Item> HAND_SPAWN_EGG = SwordRegistry.ITEMS.register(
            "hand_spawn_egg",
            () -> new SpawnEggItem(HAND.get(), 0xE9C9B8, 0xF6F1E7, new Item.Properties()));

    private KunJinKaoEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        // 属性挂在 mod 总线上：EntityAttributeCreationEvent 是注册期事件，
        // 晚于它再 put 就不会生效了（实体一生成就会因为拿不到属性表而崩）。
        modEventBus.addListener(KunJinKaoEntities::onAttributes);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(HAND.get(), KunJinKaoHandEntity.createAttributes().build());
    }
}