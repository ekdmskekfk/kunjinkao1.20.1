package dev.modmind.kunjinkao.world;

import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 区域场：把「放置记录表」里的记录变成实际效果。
 * <p>
 * 两种记录共用同一套立方体几何与同一张表（{@link SwordPlacementRegistry}）：
 * <ul>
 *   <li>{@link SwordPlacementRegistry#KIND_PAUSE} —— 暂停场：生物与方块实体停止 tick，玩家被钉住；</li>
 *   <li>{@link SwordPlacementRegistry#KIND_AREA_ACCEL} —— 范围加速：立方体里的方块实体被加速。</li>
 * </ul>
 * <p>
 * <b>缓存</b>：{@link #isPaused} 会被每个方块实体、每个生物每 tick 调到，
 * 直接查 SavedData 太重。所以每服务器 tick 把整张表抓一份到静态缓存里，
 * 查询只扫缓存。
 */
public final class SwordAreaFields {

    /** 菜单里的四档，对应 3x3x3 / 5x5x5 / 7x7x7 / 9x9x9。 */
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 4;
    public static final int DEFAULT_RADIUS = 1;

    /** 同一个位置短时间内重复触发就忽略后一次，避免一次右键被两条路径各处理一遍。 */
    private static final long TOGGLE_DEBOUNCE_MILLIS = 300L;

    private static volatile List<SwordPlacementRegistry.Entry> cache = List.of();
    private static final Map<UUID, Long> lastToggle = new HashMap<>();
    /** 被冻住的玩家 -> 冻住那一刻的位置。每 tick 钉回去。 */
    private static final Map<UUID, Vec3> playerPins = new HashMap<>();

    private SwordAreaFields() {
    }

    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    /** 半径换算成显示用的边长：1 -> 3。 */
    public static int sideLength(int radius) {
        return clampRadius(radius) * 2 + 1;
    }

    /** 循环切到下一档（3 -> 5 -> 7 -> 9 -> 3）。 */
    public static int nextRadius(int current) {
        int clamped = clampRadius(current);
        return clamped >= MAX_RADIUS ? MIN_RADIUS : clamped + 1;
    }

    /** 每服务器 tick 刷新缓存。 */
    public static void refreshCache(MinecraftServer server) {
        cache = SwordPlacementRegistry.get(server).entries();
    }

    public static List<SwordPlacementRegistry.Entry> cachedEntries() {
        return cache;
    }

    // ===================== 几何判定 =====================

    /** 该位置是否落在某个指定类型的场里。 */
    public static boolean contains(int kind, ResourceKey<Level> dimension, double x, double y, double z) {
        if (cache.isEmpty()) {
            return false;
        }
        for (SwordPlacementRegistry.Entry entry : cache) {
            if (entry.kind() != kind || !entry.dimension().equals(dimension)) {
                continue;
            }
            BlockPos center = entry.pos();
            int half = entry.radius();
            if (x >= center.getX() - half && x < center.getX() + half + 1
                    && y >= center.getY() - half && y < center.getY() + half + 1
                    && z >= center.getZ() - half && z < center.getZ() + half + 1) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPaused(Level level, BlockPos pos) {
        if (cache.isEmpty() || level.isClientSide()) {
            return false;
        }
        return contains(SwordPlacementRegistry.KIND_PAUSE, level.dimension(),
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** 实体是否该停止 tick（不含玩家）。 */
    public static boolean isPaused(Entity entity) {
        Level level = entity.level();
        if (cache.isEmpty() || level.isClientSide()) {
            return false;
        }
        return contains(SwordPlacementRegistry.KIND_PAUSE, level.dimension(),
                entity.getX(), entity.getY(), entity.getZ());
    }

    /**
     * 这个玩家是否该被冻住。管理员与场主都豁免。
     */
    public static boolean shouldFreeze(Player player) {
        if (cache.isEmpty() || player.level().isClientSide()) {
            return false;
        }
        if (dev.modmind.kunjinkao.config.AdminToolConfig.isAuthorized(player.getUUID())) {
            return false;
        }
        if (!contains(SwordPlacementRegistry.KIND_PAUSE, player.level().dimension(),
                player.getX(), player.getY(), player.getZ())) {
            return false;
        }
        // 自己的场不冻自己
        for (SwordPlacementRegistry.Entry entry : cache) {
            if (entry.kind() != SwordPlacementRegistry.KIND_PAUSE) {
                continue;
            }
            if (!entry.owner().equals(player.getUUID())) {
                continue;
            }
            if (contains(entry, player.getX(), player.getY(), player.getZ())) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(SwordPlacementRegistry.Entry entry, double x, double y, double z) {
        BlockPos center = entry.pos();
        int half = entry.radius();
        return x >= center.getX() - half && x < center.getX() + half + 1
                && y >= center.getY() - half && y < center.getY() + half + 1
                && z >= center.getZ() - half && z < center.getZ() + half + 1;
    }

    // ===================== 玩家的"钉住"状态 =====================

    public static Vec3 pinOf(UUID playerId) {
        return playerPins.get(playerId);
    }

    public static void pin(UUID playerId, Vec3 pos) {
        playerPins.put(playerId, pos);
    }

    public static void unpin(UUID playerId) {
        playerPins.remove(playerId);
    }

    /** 服务器停了就清干净，免得状态跨局残留。 */
    public static void clearRuntime() {
        playerPins.clear();
        lastToggle.clear();
        cache = List.of();
    }

    // ===================== 立 / 撤 =====================

    /**
     * shift + 右键方块：在那一格立起或撤掉一个场。
     *
     * @param kind   见 {@link SwordPlacementRegistry}
     * @param radius 半边长，见 {@link #clampRadius}
     * @return 是否处理了这次右键（始终 true —— 吃掉这次右键，别漏回原版）
     */
    public static boolean tryToggle(Player player, Level level, BlockPos pos, int kind, int radius, int multiplier) {
        if (level.isClientSide()) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastToggle.get(player.getUUID());
        if (last != null && now - last < TOGGLE_DEBOUNCE_MILLIS) {
            return true;
        }
        lastToggle.put(player.getUUID(), now);

        MinecraftServer server = level.getServer();
        SwordPlacementRegistry registry = SwordPlacementRegistry.get(server);
        BlockPos immutable = pos.immutable();
        SwordPlacementRegistry.Entry previous = registry.find(kind, level.dimension(), immutable);

        if (previous == null) {
            registry.add(new SwordPlacementRegistry.Entry(
                    kind, level.dimension(), immutable, clampRadius(radius),
                    Math.max(1, multiplier), player.getUUID()));
            // 边长拼成 "3x3x3" 再作为一个参数传进去。
            // 原来只传了一个数字，而翻译串里有三个 %s，占位符替换不了，
            // 屏幕上就直接显示出 %sx%sx%s 了。
            int side = sideLength(radius);
            player.displayClientMessage(Component.translatable(
                    kind == SwordPlacementRegistry.KIND_PAUSE
                            ? "message.kunjinkao.pause_field_started"
                            : "message.kunjinkao.area_accel_started",
                    side + "x" + side + "x" + side), true);
        } else {
            registry.remove(kind, level.dimension(), immutable);
            player.displayClientMessage(Component.translatable(
                    kind == SwordPlacementRegistry.KIND_PAUSE
                            ? "message.kunjinkao.pause_field_stopped"
                            : "message.kunjinkao.area_accel_stopped"), true);
        }
        refreshCache(server);
        return true;
    }

    /** 从放置记录里收回一条（供菜单里的「收回」按钮走）。 */
    public static boolean revoke(MinecraftServer server, int kind, ResourceKey<Level> dimension, BlockPos pos) {
        boolean removed = SwordPlacementRegistry.get(server).remove(kind, dimension, pos);
        if (removed) {
            refreshCache(server);
        }
        return removed;
    }

    // ===================== 范围加速 =====================

    /**
     * 把缓存里所有「范围加速」记录生效一次。
     * <p>
     * 直接复用加速方块那套 {@link AcceleratorBlockEntity#accelerateArea}：
     * 它自带每刻的额外 tick 预算，不会因为一个 9x9x9 的场把服务器烧掉。
     */
    public static void tickAreaAcceleration(MinecraftServer server) {
        if (cache.isEmpty()) {
            return;
        }
        for (SwordPlacementRegistry.Entry entry : cache) {
            if (entry.kind() != SwordPlacementRegistry.KIND_AREA_ACCEL) {
                continue;
            }
            // 1.20.1：MinecraftServer#getLevel 已经直接返回 ServerLevel（1.21 才改成 Level），
            // 所以这里不能再用 instanceof 模式匹配 —— 那会被 javac 判为"表达式类型的子类型"。
            ServerLevel serverLevel = server.getLevel(entry.dimension());
            if (serverLevel == null) {
                continue;
            }
            // 逐格单独调 accelerateArea（半径 0），不要一次覆盖整个立方体。
            //
            // accelerateArea 内部有一个每刻 4096 的额外 tick 总预算
            // （MAX_EXTRA_TICKS_PER_TICK），而且它按 dx/dy/dz 从 -half 开始的顺序消耗。
            // 倍率 1024 时每台机器一口就吃掉 1023 —— 立方体里第 5 台往后什么都分不到，
            // 连中心那台都会被前面几格饿死（中心在 27 格里的第 14 格）。
            //
            // 这正是"单台加速有用、同一个立方体内的范围加速没用"的原因：
            // 单台时立方体里只有它自己，独吞 4096，拿满 1023 次额外 tick。
            //
            // 一格一格调，每台机器各自拿到完整预算，等价于在这个立方体里摆了 N 个单台加速器 ——
            // 这也正是"范围加速"该有的语义。
            //
            // 生物也一起加速：范围加速的语义是"这个立方体里的一切都变快"，
            // 以前只加速方块实体，怪走进去照常慢吞吞地跑，看起来像只加速了一半。
            // 放在逐格循环之前调用一次即可 —— 它自己按范围一次性查实体。
            AcceleratorBlockEntity.accelerateEntitiesInArea(serverLevel, entry.pos(), entry.radius(),
                    entry.multiplier());
            int half = entry.radius();
            for (int dx = -half; dx <= half; dx++) {
                for (int dy = -half; dy <= half; dy++) {
                    for (int dz = -half; dz <= half; dz++) {
                        BlockPos target = entry.pos().offset(dx, dy, dz);
                        if (!AcceleratorBlockEntity.isAcceleratable(serverLevel, target)) {
                            continue;
                        }
                        AcceleratorBlockEntity.accelerateArea(serverLevel, target, 0, entry.multiplier(), null);
                    }
                }
            }
        }
    }
}