package dev.modmind.my_mod.event;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import dev.modmind.my_mod.entity.DiamondProjectile;
import dev.modmind.my_mod.event.KunJinKaoDeathEventHandler;
import dev.modmind.my_mod.overwrite.KunJinKaoOverwriteHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * 锟斤拷之剑持有者保护：
 * 1. 背包任意位置（含副手）有剑时获得创造飞行，背包彻底无剑后（非创造/旁观）收回；
 * 2. 背包有剑时免疫一切伤害来源（物理/火焰/魔法/爆炸/虚空/摔落/kill 指令）；
 * 3. 万一仍触发死亡，取消死亡并立即回满血、清火作为兜底。
 */
public class KunJinKaoProtectionHandler {

    public static final String KILL_BY_OVERWRITE_KEY = "KunJinKaoKillByOverwrite";

    private static final double VOID_RESPAWN_Y = 320.0D;

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide()) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }

        boolean hasSword = hasSwordInInventory(player);
        var abilities = player.getAbilities();

        if (hasSword) {
            clearHarmfulEffects(player);
            if (!abilities.mayfly) {
                abilities.mayfly = true;
                player.onUpdateAbilities();
            }
            // 虚空兜底：持剑掉出世界底部时传送回高空，避免位置无限下降
            if (player.getY() < -64.0D) {
                player.teleportTo(player.getX(), VOID_RESPAWN_Y, player.getZ());
            }
        } else if (abilities.mayfly && !player.isCreative()) {
            abilities.mayfly = false;
            abilities.flying = false;
            player.onUpdateAbilities();
        }

        // 清理攻击未命中等情况下残留的断未/掉落标记，
        // 避免目标之后意外死亡时误穿透持剑保护或误触发掉落增强。
        CompoundTag data = player.getPersistentData();
        if (data.getBoolean(KILL_BY_OVERWRITE_KEY) && player.getHealth() > 0.0F) {
            data.remove(KILL_BY_OVERWRITE_KEY);
            data.remove(KunJinKaoDeathEventHandler.MARK_KEY);
            data.remove(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY);
        }
    }

    private static void clearHarmfulEffects(Player player) {
        // removeEffect 会修改玩家当前效果集合；先复制后再移除，避免 ConcurrentModificationException。
        for (MobEffectInstance effect : List.copyOf(player.getActiveEffects())) {
            if (effect.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
                player.removeEffect(effect.getEffect());
            }
        }
    }

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        LivingEntity target = event.getEntity();
        Entity direct = event.getSource().getDirectEntity();
        if (direct instanceof DiamondProjectile) {
            return;
        }
        if (!(direct instanceof LivingEntity livingAttacker) || !isHoldingKunJinKaoSword(livingAttacker)) {
            // 非锟斤拷之剑来源：持剑玩家保持完全免疫
            if (target instanceof Player player && hasSwordInInventory(player)) {
                LOGGER.info("[PROTECT-ATTACK] cancel damage source={} on sword-holding player", event.getSource().getMsgId());
                event.setCanceled(true);
            }
            return;
        }

        ItemStack sword = livingAttacker.getMainHandItem();
        // 开关打开 → 取消本次普通伤害，进入无条件覆写流程
        if (KunJinKaoSwordItem.isOverwriteEnabled(sword)) {
            LOGGER.info("[PROTECT-ATTACK] overwrite on -> cancel damage + startOverwrite target={}", target.getType());
            event.setCanceled(true);
            if (!target.level().isClientSide() && target.level() instanceof ServerLevel serverLevel) {
                KunJinKaoOverwriteHandler.startOverwrite(livingAttacker, target, sword, serverLevel);
            }
            return;
        }

        // 开关关闭 → 预写断未/掉落标记后放行本次伤害，由 hurtEnemy 的 target.kill() 完成瞬杀
        // - 保证瞬杀时掉落增强依旧生效；
        // - 保证持剑玩家目标在死亡事件中看到断未标记而放行保护。
        KunJinKaoSwordItem.applyKunJinKaoMark(target, sword);
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    private static boolean isHoldingKunJinKaoSword(LivingEntity living) {
        ItemStack held = living.getMainHandItem();
        return held.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(held);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            boolean overwriteKill = player.getPersistentData().getBoolean(KILL_BY_OVERWRITE_KEY);
            if (overwriteKill) {
                // 断未处决：持剑保护失效，直接放行死亡；立即清除标记避免复活后永久失去保护
                player.getPersistentData().remove(KILL_BY_OVERWRITE_KEY);
                return;
            }
            if (hasSwordInInventory(player)) {
                event.setCanceled(true);
                player.setHealth(player.getMaxHealth());
                player.setRemainingFireTicks(0);
            }
        }
    }

    /**
     * 判断玩家背包任意位置（含副手）是否持有锟斤拷之剑。
     */
    public static boolean hasSwordInInventory(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(stack)) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(stack)) {
                return true;
            }
        }
        return false;
    }
}
