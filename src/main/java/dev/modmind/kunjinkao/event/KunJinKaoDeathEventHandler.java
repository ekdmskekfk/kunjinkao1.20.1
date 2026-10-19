package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 锟斤拷之剑掉落增强处理器：
 * 凡是带有锟斤拷击杀标记（KunJinKaoMark）且标记仍在时效内的生物，在产生掉落物时，
 * 根据实体上保存的抢夺等级（KunJinKaoLootingMode，存的是剑上那个 1..1000 的设置值）应用超高抢夺加成：
 * 每个已有掉落额外生成「设置值 − 1」个、用 withLuck 重新抽取一次原版战利品表、
 * 史莱姆（岩浆怪除外）额外补同样多个粘液球。
 */
public class KunJinKaoDeathEventHandler {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    public static final String MARK_KEY = "KunJinKaoMark";
    public static final String LOOTING_MODE_ENTITY_KEY = "KunJinKaoLootingMode";
    public static final String KILLER_UUID_KEY = "KunJinKaoKiller";

    /**
     * 标记写入时刻（gameTime）。
     * <p>
     * 击杀标记是"先写标记、再放行伤害"的，而 LivingEntity.hurt 的无敌帧判定可能把这次伤害整段丢弃，
     * 于是生物会带着标记继续活着；此后它因为任何无关原因死亡时，就会被误当成剑的击杀而复制几十份掉落。
     * 记下时刻后，死亡处理只认可 MARK_VALID_TICKS tick 内的标记，超时即视为无效并清掉，
     * 这样不需要每 tick 去扫描所有生物。
     */
    public static final String MARK_TICK_KEY = "KunJinKaoMarkTick";

    /** 击杀标记的有效期（tick）：超过它就说明那次攻击早就没打中。 */
    public static final int MARK_VALID_TICKS = 40;

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        // 指令杀（kill() 立即处决）的伤害来源可能没有攻击者实体，
        // 因此统一读取目标实体上的锟斤拷击杀标记来判断本次掉落是否由本剑触发。
        CompoundTag targetData = event.getEntity().getPersistentData();
        if (!targetData.getBoolean(MARK_KEY)) {
            return;
        }

        // 只认可 MARK_VALID_TICKS 内写入的标记：标记是攻击时预写的，可能因为无敌帧被整段丢弃，
        // 过期标记必须作废，否则之后的无关死亡也会按同样的等级复制掉落。
        // 缺失时刻键的标记来自覆写流程（overwrite 包）与断未抛射物，它们都是"写完标记立刻 kill()"，
        // 属于同一 tick 的合法写入，因此这里按有效处理。
        long now = event.getEntity().level().getGameTime();
        if (targetData.contains(MARK_TICK_KEY)) {
            long markAge = now - targetData.getLong(MARK_TICK_KEY);
            if (markAge > MARK_VALID_TICKS) {
                LOGGER.debug("[DEATH-DROPS] stale mark discarded on target={} age={}ticks",
                        event.getEntity().getType(), markAge);
                clearMarkKeys(targetData);
                return;
            }
        }

        LOGGER.debug("[DEATH-DROPS] mark found on target={} mode={}", event.getEntity().getType(),
                targetData.getInt(LOOTING_MODE_ENTITY_KEY));

        int stored = targetData.getInt(LOOTING_MODE_ENTITY_KEY);
        // 无论是否应用加成，都立即清除标记，避免影响后续事件或实体复活
        targetData.remove(MARK_KEY);
        targetData.remove(LOOTING_MODE_ENTITY_KEY);
        targetData.remove(MARK_TICK_KEY);
        // 断未开关与两个处决开关标记留给各自的死亡处理器（KunJinKaoProtectionHandler / UltimateDeathHandler）
        // 读取后自行清理：本事件与 LivingDeathEvent 的先后顺序不由这里决定，提前清掉有可能把一次合法处决取消掉；
        // 真正会泄漏的场景（标记已过期）已经在上面那个分支里整套清干净。

        // 存的是设置值 1..1000（1 = 无抢夺），参与计算的等级要减 1。
        // 写标记的几条路径现在都通过 getLootingLevelSetting 取值，所以这里不会收到旧版的 0/1/2。
        int lootingLevel = stored - 1;
        if (lootingLevel <= 0) {
            return; // 无抢夺：原版掉落，不干涉
        }

        LivingEntity entity = event.getEntity();
        List<ItemEntity> additionalDrops = new ArrayList<>();

        // 1. 已有掉落物：每个掉落物再额外生成 lootingLevel 个，超出 64 的部分自动拆分堆叠。
        //    注意实现是"每个已有掉落固定追加 lootingLevel 份"，不是把原掉落翻倍。
        for (ItemEntity drop : event.getDrops()) {
            ItemStack dropStack = drop.getItem();
            int maxSize = dropStack.getMaxStackSize();
            int added = 0;
            while (added < lootingLevel) {
                int chunk = Math.min(lootingLevel - added, maxSize);
                ItemStack extra = dropStack.copy();
                extra.setCount(chunk);
                additionalDrops.add(new ItemEntity(
                    entity.level(),
                    drop.getX(), drop.getY(), drop.getZ(),
                    extra
                ));
                added += chunk;
            }
        }

        // 2. 重新抽取一次原版战利品表（高 luck + 二次抽取显著提升稀有掉落概率）
        if (entity.level() instanceof ServerLevel serverLevel) {
            List<ItemStack> rerolled = rollEntityLootTable(serverLevel, entity, lootingLevel, event.getSource());
            for (ItemStack stack : rerolled) {
                if (!stack.isEmpty()) {
                    additionalDrops.add(new ItemEntity(
                        entity.level(),
                        entity.getX(), entity.getY() + 0.2D, entity.getZ(),
                        stack
                    ));
                }
            }
        }

        // 3. 史莱姆特殊处理：粘液球不走战利品表，按等级明确增加 lootingLevel 个。
        //    岩浆怪（MagmaCube）同样继承自 Slime，但它的额外掉落应该是岩浆膏而不是粘液球，必须排除。
        if (entity instanceof Slime && !(entity instanceof MagmaCube)) {
            ItemStack slimeBall = new ItemStack(Items.SLIME_BALL, lootingLevel);
            additionalDrops.add(new ItemEntity(entity.level(), entity.getX(), entity.getY() + 0.2D, entity.getZ(), slimeBall));
            LOGGER.debug("[KunJinKao] Slime drops enhanced: +" + lootingLevel + " slime balls");
        }

        event.getDrops().addAll(additionalDrops);
        LOGGER.debug("[KunJinKao] Drops enhanced: setting=" + stored + " lootingLevel=" + lootingLevel
                + " totalDrops=" + event.getDrops().size());
    }

    /**
     * 清掉一整套击杀标记，避免只清一半留下"半个状态"。
     */
    private static void clearMarkKeys(CompoundTag data) {
        data.remove(MARK_KEY);
        data.remove(LOOTING_MODE_ENTITY_KEY);
        data.remove(MARK_TICK_KEY);
        data.remove(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY);
        // 两个处决开关标记同样清掉：这里已经判定标记失效，残留的开关会让之后的死亡被误判成套了处决
        // （最坏情况是把持剑玩家当成处决目标直接踢出服务器）。
        data.remove(KunJinKaoSwordItem.ULTIMATE_DEATH_MARK);
        data.remove(KunJinKaoSwordItem.QUIT_STRIKE_MARK);
    }

    /**
     * 用 withLuck(lootingLevel) 重新抽取一次实体的原版战利品表，
     * 让稀有掉落按该等级幸运重新算一次概率。
     * 1.21.1 官方映射：EntityType#getDefaultLootTable() 返回 ResourceKey&lt;LootTable&gt;，
     * 战利品表由 serverLevel.getServer().getServerResources().managers().fullRegistries() 提供。
     */
    private List<ItemStack> rollEntityLootTable(ServerLevel serverLevel, LivingEntity entity, int lootingLevel, DamageSource damageSource) {
        ResourceLocation lootTableKey = entity.getType().getDefaultLootTable();
        LootTable lootTable = serverLevel.getServer().getLootData().getLootTable(lootTableKey);

        if (lootTable == null || lootTable == LootTable.EMPTY) {
            return List.of();
        }

        LootParams params = new LootParams.Builder(serverLevel)
            .withParameter(LootContextParams.THIS_ENTITY, entity)
            .withParameter(LootContextParams.ORIGIN, entity.position())
            .withParameter(LootContextParams.DAMAGE_SOURCE, damageSource)
            .withOptionalParameter(LootContextParams.KILLER_ENTITY, damageSource.getEntity())
            .withOptionalParameter(LootContextParams.DIRECT_KILLER_ENTITY, damageSource.getDirectEntity())
            .withLuck(lootingLevel)
            .create(LootContextParamSets.ENTITY);

        return lootTable.getRandomItems(params);
    }
}