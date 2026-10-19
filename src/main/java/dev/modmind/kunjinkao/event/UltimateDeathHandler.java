package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.UltimateDeathSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;

/**
 * 管理员剑的两个处决开关：
 * <ul>
 *   <li><b>退出打击</b>：用剑打死玩家后，该玩家被立即踢出服务器，但仍可重新进入。</li>
 *   <li><b>终极死亡</b>：在踢出的同时把玩家加入排除名单，之后每次登录都会被拒绝，
 *       直到管理员在战术 HUD 的排除列表里解除。</li>
 * </ul>
 * 使用 {@link EventPriority#LOWEST} 是为了排在 {@link KunJinKaoProtectionHandler} 之后：
 * 持剑玩家的死亡会被保护逻辑取消，那种情况下不应触发处决。
 */
public final class UltimateDeathHandler {

    /** 记录本次击杀是否启用了终极死亡 / 退出打击。 */
    private record KillFlags(boolean ultimateDeath, boolean quitStrike) {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        MinecraftServer server = victim.server;
        if (server == null) {
            return;
        }

        KillFlags flags = resolveFlags(victim, event.getSource());
        if (flags == null) {
            return;
        }
        if (flags.ultimateDeath()) {
            UltimateDeathSavedData.get(server).exclude(victim.getUUID(), victim.getGameProfile().getName());
            victim.connection.disconnect(Component.translatable("message.kunjinkao.ultimate_death_kick"));
        } else if (flags.quitStrike()) {
            victim.connection.disconnect(Component.translatable("message.kunjinkao.quit_strike_kick"));
        }
    }

    /** 排除名单中的玩家无法进入服务器，直到管理员解除。 */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        if (UltimateDeathSavedData.get(server).isExcluded(player.getUUID())) {
            player.connection.disconnect(Component.translatable("message.kunjinkao.ultimate_death_blocked"));
        }
    }

    /**
     * 先看剑的击杀路径是否在受害者身上留下了标记（范围清除、断未处决等路径没有可用的攻击者），
     * 没有标记时再回溯伤害来源手上那把剑（普通伤害致死的情形）。
     */
    @Nullable
    private static KillFlags resolveFlags(ServerPlayer victim, DamageSource source) {
        CompoundTag data = victim.getPersistentData();
        if (data.contains(KunJinKaoSwordItem.ULTIMATE_DEATH_MARK)) {
            KillFlags flags = new KillFlags(
                    data.getBoolean(KunJinKaoSwordItem.ULTIMATE_DEATH_MARK),
                    data.getBoolean(KunJinKaoSwordItem.QUIT_STRIKE_MARK));
            // 读完即清，避免复活后残留导致下一次死亡被误判。
            data.remove(KunJinKaoSwordItem.ULTIMATE_DEATH_MARK);
            data.remove(KunJinKaoSwordItem.QUIT_STRIKE_MARK);
            return flags;
        }
        if (source.getEntity() instanceof ServerPlayer killer) {
            ItemStack sword = heldSword(killer);
            if (sword != null) {
                return new KillFlags(KunJinKaoSwordItem.isUltimateDeathEnabled(sword),
                        KunJinKaoSwordItem.isQuitStrikeEnabled(sword));
            }
        }
        return null;
    }

    @Nullable
    private static ItemStack heldSword(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(stack)) {
                return stack;
            }
        }
        return null;
    }
}