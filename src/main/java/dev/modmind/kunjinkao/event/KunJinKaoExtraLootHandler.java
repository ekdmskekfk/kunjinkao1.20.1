package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用管理员剑击杀生物时的额外掉落：斩首与刷怪蛋。
 * <p>
 * 挂在 {@link LivingDropsEvent} 上而不是 {@code LivingDeathEvent}，因为前者直接给出
 * <b>本次掉落的可变列表</b>：往里面加条目就等于"这只生物多掉了东西"，
 * 原版掉落、抢夺加成、经验与进度全都不受影响，也不需要自己生成实体。
 * <p>
 * 头颅只覆盖原版里有对应头颅的生物；没头颅的生物（比如牛）自然什么都不掉，
 * 而不是硬塞一个不存在的物品。刷怪蛋走 {@link SpawnEggItem#byId}，
 * 所以只有真正注册了刷怪蛋的生物才会掉 —— 模组生物也一样，有就掉、没有就不掉。
 */
public final class KunJinKaoExtraLootHandler {

    /** 有对应头颅的原版生物。变种（尸壳/溺尸/流浪者）沿用同一颗头。 */

    private static final Map<EntityType<?>, Item> HEADS = Map.ofEntries(
            Map.entry(EntityType.ZOMBIE, Items.ZOMBIE_HEAD),
            Map.entry(EntityType.HUSK, Items.ZOMBIE_HEAD),
            Map.entry(EntityType.DROWNED, Items.ZOMBIE_HEAD),
            Map.entry(EntityType.ZOMBIFIED_PIGLIN, Items.ZOMBIE_HEAD),
            Map.entry(EntityType.SKELETON, Items.SKELETON_SKULL),
            Map.entry(EntityType.STRAY, Items.SKELETON_SKULL),
            Map.entry(EntityType.CREEPER, Items.CREEPER_HEAD),
            Map.entry(EntityType.WITHER_SKELETON, Items.WITHER_SKELETON_SKULL),
            Map.entry(EntityType.PIGLIN, Items.PIGLIN_HEAD),
            Map.entry(EntityType.PIGLIN_BRUTE, Items.PIGLIN_HEAD),
            Map.entry(EntityType.ENDER_DRAGON, Items.DRAGON_HEAD));

    /**
     * 额外掉落：斩首与刷怪蛋。
     * <p>
     * <b>优先级必须是 LOW</b>：{@code KunJinKaoDeathEventHandler} 的"掉落增强"在同一事件里
     * 复制"当时已经存在的掉落"，优先级 NORMAL、比这里先跑。把它排在后面有两个好处：
     * <ol>
     *   <li>顺序由注解定死，不再依赖 {@code KunJinKaoEntry} 里的注册次序 ——
     *       以前两者都是 NORMAL，谁先跑全看注册顺序，而"头到底吃不吃抢夺"正好取决于它；</li>
     *   <li>这里补的头不会被那一遍再复制一次，颗数完全由下面的算法决定，不会翻倍。</li>
     * </ol>
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim == null || victim.level().isClientSide()) {
            return;
        }
        Player killer = resolveKiller(victim, event.getSource());
        if (killer == null) {
            return;
        }

        // 机器共鸣（119）单独一条分支，且<b>先判假玩家</b>。
        // 假玩家手里通常没有本模组的剑，所以它必须排在"手持剑"那个判定之前，
        // 否则这一项永远不会触发；而真人那一套（斩首/刷怪蛋）也一概不参与 ——
        // 计划里写明"真人掉落逻辑一律不动"，就是怕在这里顺手改变真人的掉落。
        if (killer instanceof FakePlayer) {
            applyMachineLootMultiplier(event);
            return;
        }

        ItemStack sword = killer.getMainHandItem();
        if (!(sword.getItem() instanceof KunJinKaoSwordItem)) {
            return;
        }

        if (KunJinKaoSwordItem.isBeheadingEnabled(sword)) {
            Item head = HEADS.get(victim.getType());
            if (head != null) {
                // 斩首也吃抢夺等级：和"掉落增强"对其它掉落用的是同一套读法 ——
                // 等级 L 表示"额外 L 份"，所以总共 1 + L 颗头（设置值 1 = 等级 0 = 只掉原来那一颗）。
                // 上面那个处理器排在前面、看不到这里补的头，所以不会和它叠加成两倍。
                addDrop(victim, event, new ItemStack(head),
                        1 + KunJinKaoSwordItem.getLootingLevel(sword));
            }
        }
        if (KunJinKaoSwordItem.isSpawnEggDropEnabled(sword)) {
            SpawnEggItem egg = SpawnEggItem.byId(victim.getType());
            if (egg != null) {
                // 刷怪蛋不吃抢夺等级：它是"这一项开着就必定掉一个"的开关语义，
                // 按等级复制会让 1000 级的剑一次砍出十六栈刷怪蛋。
                addDrop(victim, event, new ItemStack(egg), 1);
            }
        }
    }

    /**
     * 机器共鸣：把已有的掉落按服务器倍率补足。
     * <p>
     * 做法与矿物掉落倍数一致 —— 倍率 N 表示"总数为原来的 N 倍"，也就是给每个已有掉落
     * 再补 N-1 份，而不是把 N 当成"额外几份"。两个倍率用同一种读法，
     * 玩家才不会在菜单里看到两个含义不同的数字。
     * <p>
     * 倍率来自 {@code AdminToolConfig [qol] machineLootMultiplier}，1 表示关闭。
     */
    private static void applyMachineLootMultiplier(LivingDropsEvent event) {
        int multiplier = AdminToolConfig.qolServerValue(QolSetting.MACHINE_LOOT);
        if (multiplier <= 1) {
            return;
        }
        List<ItemEntity> extra = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int remaining = stack.getCount() * (multiplier - 1);
            int maxSize = stack.getMaxStackSize();
            while (remaining > 0) {
                int count = Math.min(remaining, maxSize);
                ItemStack copy = stack.copy();
                copy.setCount(count);
                ItemEntity item = new ItemEntity(drop.level(), drop.getX(), drop.getY(), drop.getZ(), copy);
                item.setDefaultPickUpDelay();
                extra.add(item);
                remaining -= count;
            }
        }
        // 先攒完再一次性加：直接边遍历 event.getDrops() 边 add 会抛
        // ConcurrentModificationException（LivingDropsEvent 给的是可变的 ArrayList）。
        event.getDrops().addAll(extra);
    }

    /**
     * 找出该为这一刀负责的玩家。
     * <p>
     * 先看 {@code getEntity()}（伤害的归属者），再看 {@code getDirectEntity()}
     * —— 箭矢、三叉戟这类投掷物的直接命中者是弹射物本身，归属者才是玩家。
     */
    private static Player resolveKiller(LivingEntity victim, DamageSource source) {
        // 常规路径：伤害来源本身就带着玩家（近战、箭矢、三叉戟都走这里）。
        if (source.getEntity() instanceof Player player) {
            return player;
        }
        if (source.getDirectEntity() instanceof Player player) {
            return player;
        }
        // 秒杀路径：剑是在 AttackEntityEvent 里取消攻击后直接 kill() 的，
        // 走 genericKill、且受害者从未真正受过伤 —— 所以 killCredit 与 lastHurtByMob
        // 都是 null，只能读剑在动手那一刻写下的击杀者 UUID。
        net.minecraft.nbt.CompoundTag data = victim.getPersistentData();
        if (data.hasUUID(KunJinKaoDeathEventHandler.KILLER_UUID_KEY)) {
            Player recorded = victim.level()
                    .getPlayerByUUID(data.getUUID(KunJinKaoDeathEventHandler.KILLER_UUID_KEY));
            if (recorded != null) {
                return recorded;
            }
        }
        // 兜底：非秒杀路径（普通伤害、箭矢等）这里仍然拿得到人。
        if (victim.getKillCredit() instanceof Player player) {
            return player;
        }
        return victim.getLastHurtByMob() instanceof Player player ? player : null;
    }

    /**
     * 往本次掉落里补 {@code totalCount} 个物品，超出堆叠上限自动拆成多份。
     * <p>
     * 拆堆的写法与"掉落增强"一致（那边是每个已有掉落追加等级份）：
     * 1000 级的抢夺不该生成一个 1000 颗头的物品栈，原版也接不住。
     */
    private static void addDrop(LivingEntity victim, LivingDropsEvent event, ItemStack stack, int totalCount) {
        Level level = victim.level();
        int maxSize = stack.getMaxStackSize();
        int remaining = totalCount;
        while (remaining > 0) {
            int chunk = Math.min(remaining, maxSize);
            ItemStack copy = stack.copy();
            copy.setCount(chunk);
            ItemEntity item = new ItemEntity(level, victim.getX(),
                    victim.getY() + victim.getEyeHeight() * 0.5D, victim.getZ(), copy);
            item.setDefaultPickUpDelay();
            event.getDrops().add(item);
            remaining -= chunk;
        }
    }
}
