package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.network.HudShieldHitPayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.event.KunJinKaoDeathEventHandler;
import dev.modmind.kunjinkao.overwrite.KunJinKaoOverwriteHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
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
    private static final String ADVENTURE_BUILD_GRANTED_KEY = "KunJinKaoAdventureBuildGranted";

    private static final double VOID_RESPAWN_Y = 320.0D;
    private static final int SATURATION_DURATION_TICKS = 20 * 15;
    private static final int SATURATION_REFRESH_THRESHOLD_TICKS = 20;

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1 的 tick 事件没有 .Post 子类：同样是 START/END 两趟，只认 END。
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
            maintainSaturation(player);
            maintainAdventureBuildPermission(player);
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
        clearAdventureBuildPermission(player, hasSword);

        // 清理攻击未命中等情况下残留的断未/掉落标记，
        // 避免目标之后意外死亡时误穿透持剑保护或误触发掉落增强。
        CompoundTag data = player.getPersistentData();
        if (data.getBoolean(KILL_BY_OVERWRITE_KEY) && player.getHealth() > 0.0F) {
            data.remove(KILL_BY_OVERWRITE_KEY);
            data.remove(KunJinKaoDeathEventHandler.MARK_KEY);
            data.remove(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY);
            // 处决开关标记也要一起清掉，否则这次没杀成的标记会残留到之后的某次无关死亡上。
            data.remove(KunJinKaoSwordItem.ULTIMATE_DEATH_MARK);
            data.remove(KunJinKaoSwordItem.QUIT_STRIKE_MARK);
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

    /** Keeps hunger full while the administrator sword remains in the inventory. */
    private static void maintainSaturation(Player player) {
        MobEffectInstance saturation = player.getEffect(MobEffects.SATURATION);
        if (saturation == null || saturation.getDuration() <= SATURATION_REFRESH_THRESHOLD_TICKS) {
            // No ambient particles, effect particles, or status icon.
            player.addEffect(new MobEffectInstance(MobEffects.SATURATION, SATURATION_DURATION_TICKS,
                    0, false, false, false));
        }
    }

    /**
     * 原版冒险模式会在破坏、放置和大部分方块交互前检查 mayBuild。
     * 背包中有管理员剑时，由服务端临时授予该能力；这样玩家切换到方块或其他物品后，
     * 仍可实际放置和使用它们，而不会因不再把剑放在主手而立刻失效。
     */
    private static void maintainAdventureBuildPermission(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || serverPlayer.gameMode.getGameModeForPlayer() != GameType.ADVENTURE
                || player.getAbilities().mayBuild) {
            return;
        }
        player.getAbilities().mayBuild = true;
        player.getPersistentData().putBoolean(ADVENTURE_BUILD_GRANTED_KEY, true);
        player.onUpdateAbilities();
    }

    private static void clearAdventureBuildPermission(Player player, boolean hasSword) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !player.getPersistentData().getBoolean(ADVENTURE_BUILD_GRANTED_KEY)
                || (hasSword && serverPlayer.gameMode.getGameModeForPlayer() == GameType.ADVENTURE)) {
            return;
        }
        if (serverPlayer.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
            player.getAbilities().mayBuild = false;
            player.onUpdateAbilities();
        }
        player.getPersistentData().remove(ADVENTURE_BUILD_GRANTED_KEY);
    }

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        LivingEntity target = event.getEntity();
        Entity direct = event.getSource().getDirectEntity();
        if (target instanceof net.minecraft.server.level.ServerPlayer player
                && event.getSource().getEntity() instanceof net.minecraft.world.entity.Mob attacker
                && hasSwordInInventory(player)) {
            sendHitShield(player, direct != null ? direct : attacker);
        }

        if (!(direct instanceof LivingEntity livingAttacker) || !isHoldingKunJinKaoSword(livingAttacker)) {
            // 非锟斤拷之剑来源：持剑玩家保持完全免疫。
            // 例外：该玩家已被剑打上处决标记时不能拦——target.kill() 用的 genericKill 没有来源实体，
            // 正好落在这个分支里；拦下来会让「砍中即秒杀」对持剑玩家完全失效。
            if (target instanceof Player player && hasSwordInInventory(player)
                    && !player.getPersistentData().getBoolean(KILL_BY_OVERWRITE_KEY)
                    && isInvincibleEnabled(player)) {
                LOGGER.debug("[PROTECT-ATTACK] cancel damage source={} on sword-holding player", event.getSource().getMsgId());
                event.setCanceled(true);
            }
            return;
        }

        // 副手持剑、主手拿别的东西时同样算"用剑攻击"：主手不是管理员剑就回落到副手，
        // 与 overwrite 包的 findSword 语义一致，避免伤害上限/开关判定读错手。
        ItemStack sword = findSword(livingAttacker);
        if (sword.isEmpty()
                || KunJinKaoSwordItem.getAttackDamageLimit(sword) < KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT) {
            return;
        }
        // 玩家目标：砍中即秒杀，不走 40 tick 的覆写流程。
        // 这里只打处决标记并放行本次伤害，真正的处决由 hurtEnemy 的 target.kill() 完成。
        if (target instanceof Player) {
            KunJinKaoSwordItem.applyExecutionMark(target, sword);
            return;
        }
        // 开关打开 → 取消本次普通伤害，进入无条件覆写流程
        if (KunJinKaoSwordItem.isOverwriteEnabled(sword)) {
            LOGGER.debug("[PROTECT-ATTACK] overwrite on -> cancel damage + startOverwrite target={}", target.getType());
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

    @SubscribeEvent
    public void onLivingHurt(LivingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        // 与其它判定共用 findSword：主手优先、主手不是剑时看副手，
        // 否则副手持剑、主手拿别的东西时这次的伤害上限会被整段漏掉。
        ItemStack sword = findSword(attacker);
        if (!sword.isEmpty()) {
            int limit = KunJinKaoSwordItem.getAttackDamageLimit(sword);
            if (limit < KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT) {
                event.setAmount(limit);
            }
        }
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    /**
     * 取本次攻击实际使用的管理员剑：主手优先，主手不是（未伪装的）管理员剑时再看副手。
     * <p>
     * 与 overwrite 包 {@code findSword} 的语义保持一致：副手持剑、主手拿别的东西同样算用剑攻击，
     * 这样伤害上限、覆写开关与断未标记都不会因为读错手而失效。
     *
     * @return 管理员剑的物品栈；两只手都没有时返回 {@link ItemStack#EMPTY}
     */
    private static ItemStack findSword(LivingEntity living) {
        ItemStack mainHand = living.getMainHandItem();
        if (isAdminSword(mainHand)) {
            return mainHand;
        }
        ItemStack offHand = living.getOffhandItem();
        return isAdminSword(offHand) ? offHand : ItemStack.EMPTY;
    }

    private static boolean isAdminSword(ItemStack stack) {
        // isInert 同时排除"伪装中"与"未验证的合成成品"：后者是安全闸门，
        // 否则未授权玩家靠 Crafter 造出的 pending 剑在这一 tick 内仍能触发持剑免疫与覆写。
        return KunJinKaoSwordItem.isSwordLike(stack) && !KunJinKaoSwordItem.isInert(stack);
    }

    private static boolean isHoldingKunJinKaoSword(LivingEntity living) {
        // 复用 findSword，保证"这次算不算用剑攻击"与后面读取剑设置的判定使用同一套手部规则。
        return !findSword(living).isEmpty();
    }

    private static void sendHitShield(net.minecraft.server.level.ServerPlayer player, Entity impactSource) {
        Vec3 direction = impactSource.position().subtract(player.position());
        if (direction.horizontalDistanceSqr() < 1.0E-4D) {
            direction = player.getLookAngle();
        }
        float impactYaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x));
        float playerForwardYaw = 90.0F - player.getYRot();
        float relativeImpactYaw = Mth.wrapDegrees(impactYaw - playerForwardYaw);
        float impactHeight = Mth.clamp((float) (impactSource.getY() + impactSource.getBbHeight() * 0.5D - player.getY()),
                0.20F, 1.35F);
        NetworkHandler.sendToAllTracking(player,
                new HudShieldHitPayload(player.getUUID(), relativeImpactYaw, impactHeight));
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
            if (KunJinKaoSwordItem.isSwordLike(stack) && !KunJinKaoSwordItem.isDisguised(stack)) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (KunJinKaoSwordItem.isSwordLike(stack) && !KunJinKaoSwordItem.isDisguised(stack)) {
                return true;
            }
        }
        return false;
    }
    /** 「金刚不坏」是否生效（功能面板里的开关）。 */
    private static boolean isInvincibleEnabled(Player player) {
        return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && KunJinKaoQolHandler.isOn(serverPlayer, dev.modmind.kunjinkao.qol.QolSetting.INVINCIBLE);
    }
}
