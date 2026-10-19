package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.network.PlacementSyncPayload;
import dev.modmind.kunjinkao.world.SwordAreaFields;
import dev.modmind.kunjinkao.world.SwordPlacementRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.UUID;

/**
 * 区域场的每 tick 执行部分：暂停场 + 范围加速。
 * <p>
 * 生物与玩家分开处理，原因见 {@link SwordAreaFields} 的类注释：
 * 生物直接取消 tick，玩家只能把位置钉回去（移动是网络包驱动的，不在 tick 里）。
 */
public final class SwordAreaFieldHandler {

    /**
     * 每服务器 tick 做三件事：
     * <ol>
     *   <li>刷新放置记录缓存 —— 查询（每个方块实体、每个生物每 tick 都会调）只扫缓存，不碰 SavedData；</li>
     *   <li>推进范围加速；</li>
     *   <li>把最新的表推给所有客户端，菜单第二页才有东西可显示。</li>
     * </ol>
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        SwordAreaFields.refreshCache(server);
        SwordAreaFields.tickAreaAcceleration(server);

        // 每 20 tick 推一次就够：表很小，但没必要每 tick 都发。
        if (server.getTickCount() % 20 == 0) {
            PlacementSyncPayload.broadcast(server);
        }
    }

    /**
     * 抢先处理 shift+右键：区域模式开着时，在这里立/撤区域场。
     * <p>
     * <b>必须挂在这个事件上，不能只挂 {@code onItemUseFirst}</b> ——
     * 后者在客户端就返回 SUCCESS，服务端根本收不到那次右键，表现就是"点了没反应"。
     * 加速那边同样靠这个事件兜底，所以它一直是好的，而区域场一开始漏了这一条。
     * <p>
     * 优先级取 HIGHEST，与 {@code SwordTimeAcceleration.onRightClickBlock} 同档：
     * 两者互斥 —— 区域模式关着时这里直接放行，加速那边照旧处理；
     * 开着时这里取消事件，加速那边看到 isCanceled 就返回。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (event.isCanceled() || player == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof KunJinKaoSwordItem)) {
            return;
        }
        if (KunJinKaoSwordItem.isWrenchEnabled(stack)) {
            return;
        }
        // 避雷针归 SwordTimeAcceleration 那条处理器，这里不插手，
        // 否则两边都想接管，谁先谁后要看注册顺序。
        if (KunJinKaoSwordItem.isLightningRodEnabled(stack)
                && event.getLevel().getBlockState(event.getPos()).getBlock()
                        instanceof net.minecraft.world.level.block.LightningRodBlock) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            return;
        }
        if (KunJinKaoSwordItem.getAreaMode(stack) == 0) {
            return;
        }
        event.setCanceled(true);
        // 建哪种、多大、多少倍一律交给 tryPlaceAreaField 决定，这里不再自己拼一份 ——
        // 之前就是在这里另抄了一遍，漏改之后写死了机器加速的倍率。
        KunJinKaoSwordItem.tryPlaceAreaField(player, event.getLevel(), event.getPos(), stack);
    }

    /** 服务器停了就清干净，免得状态跨局残留。 */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        SwordAreaFields.clearRuntime();
    }

    /**
     * 生物 / 掉落物 / 抛射物：落在暂停场里就整 tick 跳过。
     * <p>
     * 玩家不在这里取消 —— 取消玩家 tick 既挡不住移动，又会连带跳过背包与连接维护。
     */
    @SubscribeEvent
    public void onEntityTick(LivingEvent.LivingTickEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        if (SwordAreaFields.isPaused(entity)) {
            event.setCanceled(true);
        }
    }

    /**
     * 玩家：被冻住时把位置钉回进场的瞬间。
     * <p>
     * 只改位置不够 —— 必须同时清速度并标记 {@code hurtMarked}，
     * 否则客户端会继续按自己的预测往前跑，表现为"能走但一直被拉回来"的抖动。
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        if (!SwordAreaFields.shouldFreeze(player)) {
            if (SwordAreaFields.pinOf(id) != null) {
                SwordAreaFields.unpin(id);
            }
            return;
        }
        Vec3 pin = SwordAreaFields.pinOf(id);
        if (pin == null) {
            SwordAreaFields.pin(id, player.position());
            return;
        }
        if (player.position().distanceToSqr(pin) > 1.0E-4D) {
            player.connection.teleport(pin.x, pin.y, pin.z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
        }
    }
}
