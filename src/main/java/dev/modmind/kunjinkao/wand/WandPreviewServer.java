package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.WandPreviewResultPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端的预览计算入口。
 * <p>
 * 参考实现是"客户端发请求 → 服务端算一遍 → 把坐标发回去"，本模组照搬这个结构，
 * 但补了两处参考实现没有的东西：
 * <ul>
 *   <li><b>限流</b>：参考实现里预览集合小于 2 格时客户端会逐帧发包，等于包风暴；
 *       这里每秒最多放行 {@link #MAX_REQUESTS_PER_SECOND} 次。</li>
 *   <li><b>校验手持</b>：参考实现直接相信客户端发来的物品栈，被伪造就会走进强制转换异常；
 *       这里一律用服务端自己那一份主手物品，拿的不是剑就直接忽略。</li>
 * </ul>
 * 预览只是"算一遍会动哪些格子"，<b>不改动世界</b> —— 材料消耗在 {@link WandJob#execute()} 里。
 */
public final class WandPreviewServer {

    private static final int MAX_REQUESTS_PER_SECOND = 20;
    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();

    private WandPreviewServer() {
    }

    /** 这一秒还允许这名玩家请求预览吗。 */
    public static boolean allow(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Window window = WINDOWS.computeIfAbsent(player.getUUID(), key -> new Window());
        synchronized (window) {
            if (now - window.startMillis >= 1000L) {
                window.startMillis = now;
                window.count = 0;
            }
            window.count++;
            return window.count <= MAX_REQUESTS_PER_SECOND;
        }
    }

    public static void forget(UUID playerId) {
        WINDOWS.remove(playerId);
    }

    /**
     * 算一遍并把坐标发回请求方。
     *
     * @return 是否真的算了（手持不是剑、或当前没有开启任何核心时返回 false）
     */
    public static boolean serve(ServerPlayer player, int seq, net.minecraft.world.phys.BlockHitResult hit) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof dev.modmind.kunjinkao.KunJinKaoSwordItem)) {
            return false;
        }
        WandJob job = new WandJob(player, player.level(), hit, stack);
        if (!job.isActive()) {
            return false;
        }
        job.compute();
        NetworkHandler.sendToPlayer(player, new WandPreviewResultPayload(seq, job.blockPositions()));
        return true;
    }

    private static final class Window {
        private long startMillis;
        private int count;
    }
}