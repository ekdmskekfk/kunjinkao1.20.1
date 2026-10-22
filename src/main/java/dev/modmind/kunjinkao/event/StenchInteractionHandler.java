package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.SwordRegistry;
import dev.modmind.kunjinkao.entity.KunJinKaoEntities;
import dev.modmind.kunjinkao.entity.KunJinKaoHandEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 与君君的两条交互（1.20.1）：挤尿（玻璃瓶 → 尿液瓶）和「双君仪式」。
 *
 * <p>仪式是两步式：先对一只用尿液瓶标记，30 秒内对 16 格内的另一只用，
 * 两只聚到中点并原地生成 50 只。做成"先标记再配对"而不是同时点两只，
 * 是因为后者需要额外的选择 UI，而且第二只稍微挪动就会失败。</p>
 */
public final class StenchInteractionHandler {

    private static final double MARK_RANGE = 16.0D;
    private static final long MARK_TIMEOUT_TICKS = 20L * 30L;
    private static final int RITUAL_SPAWN_COUNT = 50;

    private record Mark(net.minecraft.resources.ResourceKey<Level> dimension, Vec3 pos, long gameTime) {
    }

    private static final Map<UUID, Mark> MARKS = new HashMap<>();

    private StenchInteractionHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof KunJinKaoHandEntity hand)) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();

        if (stack.is(Items.GLASS_BOTTLE)) {
            if (!(hand.level() instanceof ServerLevel)) {
                return;
            }
            stack.shrink(1);
            ItemStack urine = new ItemStack(SwordRegistry.URINE_BOTTLE.get());
            if (!player.getInventory().add(urine)) {
                player.drop(urine, false);
            }
            player.displayClientMessage(
                    Component.translatable("message.kunjinkao.urine_collected"), true);
            cancel(event);
            return;
        }

        if (stack.is(SwordRegistry.URINE_BOTTLE.get())) {
            if (!(hand.level() instanceof ServerLevel serverLevel)) {
                return;
            }
            handleUrineOnHand(serverLevel, player, stack, hand);
            cancel(event);
        }
    }

    private static void handleUrineOnHand(ServerLevel level, Player player, ItemStack stack,
                                          KunJinKaoHandEntity hand) {
        long now = level.getGameTime();
        UUID partner = findPartner(level, hand, now);

        if (partner == null) {
            MARKS.put(hand.getUUID(), new Mark(level.dimension(), hand.position(), now));
            player.displayClientMessage(
                    Component.translatable("message.kunjinkao.urine_marked_first"), true);
            spawnParticles(level, hand.position().add(0.0D, 0.4D, 0.0D), 12);
            consumeOne(player, stack);
            return;
        }

        MARKS.remove(partner);
        MARKS.remove(hand.getUUID());
        Entity other = level.getEntity(partner);
        Vec3 mid = hand.position();
        if (other != null) {
            mid = mid.add(other.position()).scale(0.5D);
        }
        hand.moveTo(mid.x, mid.y, mid.z, hand.getYRot(), hand.getXRot());
        if (other instanceof KunJinKaoHandEntity otherHand) {
            otherHand.moveTo(mid.x, mid.y, mid.z, otherHand.getYRot(), otherHand.getXRot());
        }

        spawnParticles(level, mid.add(0.0D, 0.6D, 0.0D), 80);
        for (int i = 0; i < RITUAL_SPAWN_COUNT; i++) {
            KunJinKaoHandEntity born = KunJinKaoEntities.HAND.get().create(level);
            if (born == null) {
                continue;
            }
            double dx = (level.random.nextDouble() - 0.5D) * 8.0D;
            double dz = (level.random.nextDouble() - 0.5D) * 8.0D;
            born.moveTo(mid.x + dx, mid.y, mid.z + dz,
                    level.random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(born);
        }
        level.playSound(null, mid.x, mid.y, mid.z,
                net.minecraft.sounds.SoundEvents.SLIME_SQUISH, net.minecraft.sounds.SoundSource.HOSTILE,
                1.0F, 0.6F);
        player.displayClientMessage(
                Component.translatable("message.kunjinkao.urine_ritual_done"), true);
        consumeOne(player, stack);
    }

    private static UUID findPartner(ServerLevel level, KunJinKaoHandEntity hand, long now) {
        Iterator<Map.Entry<UUID, Mark>> it = MARKS.entrySet().iterator();
        UUID found = null;
        while (it.hasNext()) {
            Map.Entry<UUID, Mark> entry = it.next();
            Mark mark = entry.getValue();
            if (now - mark.gameTime() > MARK_TIMEOUT_TICKS) {
                it.remove();
                continue;
            }
            if (entry.getKey().equals(hand.getUUID())) {
                continue;
            }
            if (!mark.dimension().equals(level.dimension())) {
                continue;
            }
            if (mark.pos().distanceToSqr(hand.position()) > MARK_RANGE * MARK_RANGE) {
                continue;
            }
            found = entry.getKey();
        }
        return found;
    }

    private static void spawnParticles(ServerLevel level, Vec3 pos, int count) {
        level.sendParticles(ParticleTypes.FALLING_HONEY, pos.x, pos.y, pos.z, count, 0.6D, 0.4D, 0.6D, 0.02D);
    }

    private static void consumeOne(Player player, ItemStack stack) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    private static void cancel(PlayerInteractEvent.EntityInteract event) {
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        event.setCanceled(true);
    }
}
