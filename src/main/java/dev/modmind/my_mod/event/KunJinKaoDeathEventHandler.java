package dev.modmind.my_mod.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 锟斤拷之剑掉落增强处理器：
 * 凡是带有锟斤拷击杀标记（KunJinKaoMark）的生物，在产生掉落物时，
 * 根据实体上保存的抢夺模式（KunJinKaoLootingMode）应用超高抢夺加成：
 * 已有掉落翻倍、重新抽取战利品表、史莱姆粘液球额外奖励。
 */
public class KunJinKaoDeathEventHandler {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    public static final String MARK_KEY = "KunJinKaoMark";
    public static final String LOOTING_MODE_ENTITY_KEY = "KunJinKaoLootingMode";
    public static final String KILLER_UUID_KEY = "KunJinKaoKiller";

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        // 指令杀（kill() 立即处决）的伤害来源可能没有攻击者实体，
        // 因此统一读取目标实体上的锟斤拷击杀标记来判断本次掉落是否由本剑触发。
        CompoundTag targetData = event.getEntity().getPersistentData();
        if (!targetData.getBoolean(MARK_KEY)) {
            return;
        }
        LOGGER.info("[DEATH-DROPS] mark found on target={} mode={}", event.getEntity().getType(),
                targetData.getInt(LOOTING_MODE_ENTITY_KEY));

        int mode = targetData.getInt(LOOTING_MODE_ENTITY_KEY);
        // 无论是否应用加成，都立即清除标记，避免影响后续事件或实体复活
        targetData.remove(MARK_KEY);
        targetData.remove(LOOTING_MODE_ENTITY_KEY);

        if (mode == 0) {
            return; // 无抢夺：原版掉落，不干涉
        }

        LivingEntity entity = event.getEntity();
        int lootingLevel = (mode == 1) ? 25 : 50;
        List<ItemEntity> additionalDrops = new ArrayList<>();

        // 1. 已有掉落物数量翻倍：每个掉落物追加 lootingLevel 个，超出 64 的部分自动拆分堆叠
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

        // 3. 史莱姆特殊处理：粘液球不走战利品表，按模式明确增加 25/50 个
        if (entity instanceof Slime) {
            ItemStack slimeBall = new ItemStack(Items.SLIME_BALL, lootingLevel);
            additionalDrops.add(new ItemEntity(entity.level(), entity.getX(), entity.getY() + 0.2D, entity.getZ(), slimeBall));
            System.out.println("[KunJinKao] Slime drops enhanced: +" + lootingLevel + " slime balls");
        }

        event.getDrops().addAll(additionalDrops);
        System.out.println("[KunJinKao] Drops enhanced: mode=" + mode + " lootingLevel=" + lootingLevel
                + " totalDrops=" + event.getDrops().size());
    }

    /**
     * 以高幸运值重新抽取实体的原版战利品表，制造类似海量抢夺的稀有掉落概率。
     * 1.20.1 官方映射 API 修正：
     * EntityType#getDefaultLootTable() 返回 ResourceLocation，
     * LootDataManager#getLootTable(ResourceLocation) 直接接受该值。
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