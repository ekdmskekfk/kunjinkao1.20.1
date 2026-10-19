package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 服务端战术 HUD 磁铁：吸取 50×50×50 立方体中的掉落物与经验球。 */
public final class TacticalHudMagnetHandler {

    public static final String MAGNET_KEY = "KunJinKaoHudMagnet";
    private static final double HALF_RANGE = 25.0D;
    private static final double PICKUP_DISTANCE_SQR = 2.25D;

    public static boolean toggle(ServerPlayer player) {
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            player.getPersistentData().remove(MAGNET_KEY);
            return false;
        }
        boolean enabled = !isEnabled(player);
        player.getPersistentData().putBoolean(MAGNET_KEY, enabled);
        return enabled;
    }

    public static boolean isEnabled(Player player) {
        return player.getPersistentData().getBoolean(MAGNET_KEY);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player) || player.level().isClientSide()
                || !isEnabled(player)) {
            return;
        }
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            player.getPersistentData().remove(MAGNET_KEY);
            return;
        }

        AABB area = player.getBoundingBox().inflate(HALF_RANGE);
        for (ItemEntity item : player.serverLevel().getEntitiesOfClass(ItemEntity.class, area,
                entity -> entity.isAlive() && !entity.getItem().isEmpty())) {
            pullTowardPlayer(item, player);
        }
        for (ExperienceOrb orb : player.serverLevel().getEntitiesOfClass(ExperienceOrb.class, area,
                Entity::isAlive)) {
            pullTowardPlayer(orb, player);
        }
    }

    private static void pullTowardPlayer(Entity entity, ServerPlayer player) {
        Vec3 target = player.position().add(0.0D, player.getBbHeight() * 0.45D, 0.0D);
        Vec3 offset = target.subtract(entity.position());
        double distanceSqr = offset.lengthSqr();
        if (distanceSqr <= PICKUP_DISTANCE_SQR) {
            if (entity instanceof ItemEntity item) {
                item.playerTouch(player);
            } else if (entity instanceof ExperienceOrb orb) {
                orb.playerTouch(player);
            }
            return;
        }

        double distance = Math.sqrt(distanceSqr);
        double pullSpeed = Math.min(1.15D, 0.12D + distance * 0.035D);
        Vec3 velocity = entity.getDeltaMovement().scale(0.28D).add(offset.scale(pullSpeed / distance));
        entity.setDeltaMovement(velocity);
        entity.hurtMarked = true;
    }
}
