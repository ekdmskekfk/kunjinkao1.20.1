package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.network.HudTrueInvisibilityVisualPayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Server-authoritative true-invisibility implementation for the tactical HUD. */
public final class TacticalHudTrueInvisibilityHandler {

    public static final String TRUE_INVISIBILITY_KEY = "KunJinKaoHudTrueInvisibility";
    private static final int EFFECT_DURATION_TICKS = 20 * 60;
    private static final int REAPPLY_THRESHOLD_TICKS = 20 * 10;
    private static final double AGGRO_CLEAR_RADIUS = 128.0D;

    public static boolean toggle(ServerPlayer player) {
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            disable(player);
            return false;
        }

        boolean enabled = !isEnabled(player);
        if (enabled) {
            player.getPersistentData().putBoolean(TRUE_INVISIBILITY_KEY, true);
            applyInvisibleEffect(player);
            clearHostility(player, null);
            broadcastVisualState(player, true);
        } else {
            disable(player);
        }
        return enabled;
    }

    public static boolean isEnabled(Player player) {
        return player.getPersistentData().getBoolean(TRUE_INVISIBILITY_KEY);
    }

    private static void disable(ServerPlayer player) {
        player.getPersistentData().remove(TRUE_INVISIBILITY_KEY);
        player.removeEffect(MobEffects.INVISIBILITY);
        broadcastVisualState(player, false);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player) || player.level().isClientSide()
                ) {
            return;
        }

        if (!isEnabled(player)) {
            return;
        }
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            disable(player);
            return;
        }

        MobEffectInstance effect = player.getEffect(MobEffects.INVISIBILITY);
        if (effect == null || effect.getDuration() <= REAPPLY_THRESHOLD_TICKS) {
            applyInvisibleEffect(player);
        }
        clearHostility(player, null);
    }

    /** Clear retaliation after damage has been applied, not merely before the attack. */
    @SubscribeEvent
    public void onLivingHurt(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof ServerPlayer player) || !isEnabled(player)
                || !AdminToolConfig.isAuthorized(player.getUUID())) {
            return;
        }

        clearHostility(player, event.getEntity() instanceof Mob mob ? mob : null);
    }

    @SubscribeEvent
    public void onPlayerStartsTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer observer)
                || !(event.getTarget() instanceof ServerPlayer target)) {
            return;
        }
        sendVisualState(observer, target, isEnabled(target));
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer observer)) {
            return;
        }
        for (ServerPlayer target : observer.server.getPlayerList().getPlayers()) {
            sendVisualState(observer, target, isEnabled(target));
        }
    }

    private static void applyInvisibleEffect(ServerPlayer player) {
        // No ambient particles, no visible particles and no effect icon.
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_DURATION_TICKS,
                0, false, false, false));
    }

    private static void clearHostility(ServerPlayer player, Mob directlyAttacked) {
        if (directlyAttacked != null) {
            clearMobHostility(directlyAttacked, player);
        }

        for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(AGGRO_CLEAR_RADIUS))) {
            if (mob.getTarget() == player || mob.getLastHurtByMob() == player) {
                clearMobHostility(mob, player);
            }
        }
    }

    private static void clearMobHostility(Mob mob, LivingEntity player) {
        if (mob.getTarget() == player) {
            mob.setTarget(null);
        }
        if (mob.getLastHurtByMob() == player) {
            mob.setLastHurtByMob(null);
        }
    }

    private static void broadcastVisualState(ServerPlayer player, boolean enabled) {
        NetworkHandler.sendToAllTracking(player,
                new HudTrueInvisibilityVisualPayload(player.getUUID(), enabled));
    }

    private static void sendVisualState(ServerPlayer observer, ServerPlayer target, boolean enabled) {
        NetworkHandler.sendToPlayer(observer,
                new HudTrueInvisibilityVisualPayload(target.getUUID(), enabled));
    }
}
