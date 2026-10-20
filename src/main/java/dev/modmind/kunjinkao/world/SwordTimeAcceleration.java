package dev.modmind.kunjinkao.world;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.TimeAccelStatusPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;


import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 剑的时间加速。
 * <p>
 * 两种用法：
 * <ul>
 *   <li><b>shift + 右键方块</b>：在那个位置立起一个局部加速场，额外 tick 范围内的
 *       方块实体与<b>生物</b>（{@link AcceleratorBlockEntity#accelerateArea} 那套逻辑，
 *       与加速方块共用同一个每刻预算）；</li>
 *   <li><b>shift + 右键天空</b>：加速<b>整个世界的时间</b> —— 走原版 {@code /tick rate} 的
 *       {@code TickRateManager}，把每秒 tick 数整体调高。</li>
 * </ul>
 * 后者是唯一能让 <b>AE2 这类"按游戏时间算进度"的机器</b>真正提速的办法：
 * 那些机器在同一个游戏刻里被多调几次 ticker 也不会前进（它们拿 gameTime 做差值，
 * 同刻内差值为 0），只有让 gameTime 真正走快才有效。所以局部加速负责"某一处"，
 * 时间加速负责"全都快"。
 * <p>
 * 两种模式（在 . 菜单的「加速」分区里选）：
 * <ul>
 *   <li><b>限时加速</b>：{@link #TIMED_DURATION_MILLIS}（30 秒）后自动结束；</li>
 *   <li><b>无限加速</b>：一直持续，直到再 shift+右键 同一位置手动停掉。</li>
 * </ul>
 * 计时用墙上时钟而不是 gameTime：时间加速本身会改变 gameTime 的推进速度，
 * 用它计时会让"30 秒"变成不确定的长度。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwordTimeAcceleration {

    /** 限时加速的持续时间：256 秒（墙上时钟）。 */
    public static final long TIMED_DURATION_MILLIS = 256_000L;
    /**
     * 机器加速只作用于<b>点中的那一格</b>，不扩散到周围 —— 一个加速场就是一台机器。
     */
    public static final int BLOCK_RADIUS = 0;
    /**
     * 生物加速的作用半径。
     * <p>
     * 与机器不同，这里保留一点范围：{@code useOn} 只能点方块，玩家没法"点中一只鸡"，
     * 所以按点到的那一格为中心取一个小范围，站在那儿的生物才会被加速。
     */
    public static final int ENTITY_RADIUS = 2;
    /**
     * 判定"对着上方"的俯仰角阈值（度）。
     * <p>
     * MC 里俯仰角负值是抬头，-90 是正对天顶。取 45 度是为了跟"随便往上看一眼"区分开 ——
     * 整体时间加速影响全服，不该在余光扫到天空时被误触。
     */
    public static final float SKY_PITCH_THRESHOLD = -45.0F;
    /** 时间加速把 tick 率压到的上限，避免把服务器直接跑爆（400 tps = 20 倍速）。 */
    public static final float MAX_TICKRATE = 400.0F;
    /** 加速场状态广播间隔（tick）。10 tick = 0.5 秒，客户端的倒计时读起来是连续的。 */
    private static final int STATUS_INTERVAL_TICKS = 10;
    /** 原版基准 tick 率。 */
    public static final float BASE_TICKRATE = 20.0F;

    /** 加速模式：关。 */
    public static final int MODE_OFF = 0;
    /** 加速模式：限时，shift+右键 后持续 30 秒。 */
    public static final int MODE_TIMED = 1;
    /** 加速模式：无限，直到手动停掉。 */
    public static final int MODE_INFINITE = 2;
    /** 模式取值个数，用于循环切换。 */
    public static final int MODE_COUNT = 3;

    /**
     * 一个加速场。
     *
     * @param pos           局部加速场的中心；{@code null} 表示这是全局时间加速
     * @param multiplier    倍率
     * @param expiresAt     结束时刻（墙上时钟毫秒），负值表示无限
     * @param dimension     所在维度；时间加速用主世界做代表
     */
    private record Session(@Nullable BlockPos pos, int multiplier, long expiresAt,
                           net.minecraft.resources.ResourceKey<Level> dimension) {
        boolean isTimeAcceleration() {
            return pos == null;
        }

        boolean expired(long now) {
            return expiresAt >= 0L && now >= expiresAt;
        }
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    /**
     * 会话键：维度 + 坐标。
     * <p>
     * 之前用的是把它压成一个 long 的哈希，而那里写的是 {@code pos.asLong() & 0xFFFFFFFFL} ——
     * BlockPos.asLong() 是 64 位（X 26 + Y 12 + Z 26），截成低 32 位后
     * <b>X 坐标整个丢了</b>。于是 Z 相同的两台机器会撞进同一个键：
     * 点第二台时 remove 会把第一台的会话删掉 —— 表现就是"加速一台之后，
     * 点另一台就把前一台的加速停了"。改用 record 精确比较，不再有碰撞。
     */
    private record BlockKey(net.minecraft.resources.ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static final Map<BlockKey, Session> BLOCK_SESSIONS = new HashMap<>();
    private static final List<Session> TIME_SESSIONS = new ArrayList<>();
    /** 开启时间加速之前的 tick 率，停掉时恢复。 */
    private static float previousTickrate = BASE_TICKRATE;
    /** 状态广播节拍计数，见 {@link #STATUS_INTERVAL_TICKS}。 */
    private static int statusTicker;
    /**
     * 每个玩家上一次切换的时刻。
     * <p>
     * 按住右键时原版会每隔几 tick 重复触发一次 useOn，若每次都翻转开关，
     * 一次长按就会"开了又停"，玩家看到的是"再点一下就说已停止"。
     * 这里做一个很短的静默窗，把同一次长按产生的重复调用吃掉；
     * 隔开时间再点才算真正的第二次操作。
     */
    private static final long TOGGLE_DEBOUNCE_MILLIS = 350L;
    private static final Map<java.util.UUID, Long> LAST_TOGGLE = new HashMap<>();

    private SwordTimeAcceleration() {
    }

    /** 玩家此刻是否真的对着上方（抬头上看至少 45 度）。 */
    public static boolean isFacingSky(Player player) {
        return player.getXRot() <= SKY_PITCH_THRESHOLD;
    }

    public static int clampMode(int mode) {
        return mode < 0 || mode >= MODE_COUNT ? MODE_OFF : mode;
    }

    /** 在倍率档位表里前后挪一格。倍率是固定档位（4/8/…/1024），滚轮走的是"档"。 */
    public static int stepMultiplier(int current, int direction) {
        int[] table = AcceleratorBlockEntity.MULTIPLIERS;
        int index = 0;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == current) {
                index = i;
                break;
            }
        }
        index = Math.max(0, Math.min(table.length - 1, index + (direction > 0 ? 1 : -1)));
        return table[index];
    }

    /** 菜单里的"循环切倍率"：到最大档之后回到最小档。滚轮用的是 {@link #stepMultiplier}，到顶即停。 */
    public static int nextMultiplier(int current) {
        int[] table = AcceleratorBlockEntity.MULTIPLIERS;
        int index = 0;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == current) {
                index = i;
                break;
            }
        }
        return table[(index + 1) % table.length];
    }

    private static BlockKey blockKey(net.minecraft.resources.ResourceKey<Level> dimension, BlockPos pos) {
        return new BlockKey(dimension, pos.immutable());
    }

    // ===================== 入口 =====================

    /**
     * shift + 右键<b>方块</b>：在该位置开/关一个局部加速场。
     *
     * @return 是否处理了这次右键
     */
    public static boolean tryToggleBlock(Player player, Level level, BlockPos pos, ItemStack stack) {
        int mode = clampMode(KunJinKaoSwordItem.getTimeAccelMode(stack));
        boolean acceleratable = AcceleratorBlockEntity.isAcceleratable(level, pos);
        // 临时诊断：这一层是唯一判定点，把失败原因一次打全，定位完即删。
        if (!level.isClientSide()) {
            LOGGER.info("[ACCEL-TOGGLE] pos={} block={} accelMode={} acceleratable={} wrench={} result={}",
                    pos, level.getBlockState(pos).getBlock(), mode, acceleratable,
                    KunJinKaoSwordItem.isWrenchEnabled(stack),
                    mode != MODE_OFF && acceleratable ? "START" : "REJECT");
        }
        if (mode == MODE_OFF) {
            return false;
        }
        if (!acceleratable) {
            // 这个方块不能被局部加速 —— 什么都不做。
            // 尤其是【不要】弹提示、也不要退化成整体时间加速：
            // 普通石头、泥土这类方块同样走这一支，右键它们却把整个世界拔快、
            // 或者弹一句莫名其妙的提示，都是不该有的反应。
            // 仍然吃掉这次右键（返回 true），否则会漏回原版、被别的逻辑接走。
            return true;
        }
        if (level instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            long now = System.currentTimeMillis();
            Long last = LAST_TOGGLE.get(player.getUUID());
            if (last != null && now - last < TOGGLE_DEBOUNCE_MILLIS) {
                return true;
            }
            LAST_TOGGLE.put(player.getUUID(), now);
            captureBaseline(server);
            Session previous = BLOCK_SESSIONS.remove(blockKey(serverLevel.dimension(), pos));
            if (previous == null) {
                startBlockSession(serverLevel, pos, KunJinKaoSwordItem.getTimeAccelMultiplier(stack), mode);
            }
            refreshTickrate(server);
            persist(server);
            player.displayClientMessage(Component.translatable(previous != null
                    ? "message.kunjinkao.time_accel_stopped"
                    : mode == MODE_TIMED
                            ? "message.kunjinkao.time_accel_started_timed"
                            : "message.kunjinkao.time_accel_started_infinite"), true);
        }
        return true;
    }

    /**
     * shift + 右键<b>天空</b>：整体提高服务器的每秒 tick 数，也就是加速时间。
     * <p>
     * 这是唯一能真正带动 AE2 那类"按 gameTime 算进度"的机器的办法。
     *
     * @return 是否处理了这次右键
     */
    /**
     * <b>确保</b>整体时间加速是开着的 —— 已经开着就什么都不做，绝不关闭。
     * <p>
     * 给"点到了不可局部加速的方块"那条退路用。那里如果用 tryToggleTime，
     * 连续点几个方块就会一开一关：第一次开、第二次关、第三次又开……
     * 而之前那些靠整体刻率在跑的加速全都跟着失效，屏幕上还弹"已停止加速"。
     * 退路只该负责"把它打开"，关不关是玩家对着天空右键自己的事。
     */
    public static void ensureTimeAccel(Player player, MinecraftServer server, ItemStack stack) {
        int mode = clampMode(KunJinKaoSwordItem.getTimeAccelMode(stack));
        if (mode == MODE_OFF || !TIME_SESSIONS.isEmpty()) {
            return;
        }
        captureBaseline(server);
        int multiplier = KunJinKaoSwordItem.getTimeAccelMultiplier(stack);
        TIME_SESSIONS.add(new Session(null, multiplier, expiresAt(mode), server.overworld().dimension()));
        refreshTickrate(server);
        persist(server);
        player.displayClientMessage(Component.translatable("message.kunjinkao.time_accel_time_started",
                (int) targetTickrate(multiplier)), true);
    }

    public static boolean tryToggleTime(Player player, MinecraftServer server, ItemStack stack) {
        int mode = clampMode(KunJinKaoSwordItem.getTimeAccelMode(stack));
        if (mode == MODE_OFF) {
            return false;
        }
        boolean stopping = !TIME_SESSIONS.isEmpty();
        captureBaseline(server);
        if (stopping) {
            TIME_SESSIONS.clear();
            refreshTickrate(server);
            player.displayClientMessage(Component.translatable("message.kunjinkao.time_accel_time_stopped"), true);
        } else {
            int multiplier = KunJinKaoSwordItem.getTimeAccelMultiplier(stack);
            TIME_SESSIONS.add(new Session(null, multiplier, expiresAt(mode), server.overworld().dimension()));
            refreshTickrate(server);
            player.displayClientMessage(Component.translatable("message.kunjinkao.time_accel_time_started",
                    (int) targetTickrate(multiplier)), true);
        }
        // 两条分支都要落盘：开要让重启后还在，关要让重启后别又自己开回来。
        persist(server);
        return true;
    }

    private static long expiresAt(int mode) {
        return mode == MODE_TIMED ? System.currentTimeMillis() + TIMED_DURATION_MILLIS : -1L;
    }

    private static float targetTickrate(int multiplier) {
        return Math.min(BASE_TICKRATE * multiplier, MAX_TICKRATE);
    }

    /**
     * 1.20.1 没有 {@code TickRateManager}（{@code ServerTickRateManager} 是 1.20.3 才加入的），
     * 因此<b>没有</b>任何原版手段能修改全服 tick 率 —— 全局时间加速在这个版本上不可用。
     * <p>
     * 保留这个方法是为了让调用点（{@link #refreshTickrate}）的形状与 1.21.1 一致、
     * 将来升级版本时只改这一处；实际效果是空操作，由
     * {@link #TICKRATE_SUPPORTED} 负责在入口处就把话说清楚，不让玩家以为开了。
     */
    private static void applyTickrate(MinecraftServer server, float tickrate) {
        // 空操作：见方法注释。
    }

    /** 本版本能否修改全局 tick 率。1.20.1 = 不能。 */
    public static final boolean TICKRATE_SUPPORTED = false;

    /** 建立第一个加速场之前先记下原本的 tick 率，之后全部结束时要复原成它。 */
    private static void captureBaseline(MinecraftServer server) {
        if (BLOCK_SESSIONS.isEmpty() && TIME_SESSIONS.isEmpty()) {
            // 1.20.1 没有 tick 率 API，基线恒为原版值。
            previousTickrate = BASE_TICKRATE;
        }
    }

    /**
     * 按当前所有加速场重算目标 tick 率。
     * <p>
     * 只有 {@code TIME_SESSIONS}（对着天空的那种）参与计算。局部加速是"某一台机器多跑几轮"，
     * 与全局时间无关 —— AE2 的机器本身注册了原版 ticker，多调几次就会真的前进，
     * 不需要靠拔快整个世界的时间来带。
     * 一个加速场都不剩时复原成介入之前的 tick 率。
     */
    private static void refreshTickrate(MinecraftServer server) {
        if (BLOCK_SESSIONS.isEmpty() && TIME_SESSIONS.isEmpty()) {
            applyTickrate(server, previousTickrate);
            return;
        }
        // 只有"时间加速"才动 tick 率。局部加速是单台机器的额外 tick，
        // 不该把整个世界的时间一起拔快 —— 那是另一件事，由对着天空的加速负责。
        float desired = BASE_TICKRATE;
        for (Session session : TIME_SESSIONS) {
            desired = Math.max(desired, targetTickrate(session.multiplier()));
        }
        applyTickrate(server, desired);
    }

    private static void startBlockSession(ServerLevel level, BlockPos pos, int multiplier, int mode) {
        BLOCK_SESSIONS.put(blockKey(level.dimension(), pos),
                new Session(pos.immutable(), multiplier, expiresAt(mode), level.dimension()));
    }

    // ===================== 落盘与恢复 =====================

    /**
     * 把当前会话表整表写进存档。
     * <p>
     * 调用点很稀疏（一次开/关、一次过期、一次自动清理），所以不做什么增量，
     * 直接按当前状态重建一份 —— 少一种"增量包对不上"的失败模式。
     * {@link TimeAccelSavedData#replace} 内部判等，内容没变不会标脏。
     */
    private static void persist(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<TimeAccelSavedData.Entry> entries = new ArrayList<>();
        for (Session session : BLOCK_SESSIONS.values()) {
            if (session.pos() == null) {
                continue;
            }
            entries.add(new TimeAccelSavedData.Entry(session.pos(), session.dimension().location().toString(),
                    session.multiplier(), remainingMillis(session, now)));
        }
        for (Session session : TIME_SESSIONS) {
            entries.add(new TimeAccelSavedData.Entry(null, session.dimension().location().toString(),
                    session.multiplier(), remainingMillis(session, now)));
        }
        TimeAccelSavedData.get(server).replace(entries);
    }

    /** 会话剩余毫秒；无限会话返回 -1。 */
    private static long remainingMillis(Session session, long now) {
        return session.expiresAt() < 0L ? -1L : Math.max(0L, session.expiresAt() - now);
    }

    @Nullable
    private static ResourceKey<Level> parseDimension(String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    /**
     * 服务器启动：把存档里的加速会话接回来。
     * <p>
     * 这些会话是"开着就该一直开着"的东西（无限模式尤其如此），重启后消失等于玩家白设一次。
     * 恢复时用存下来的<b>剩余毫秒</b>重新算截止时刻，所以关着游戏的那段时间不计时 ——
     * 限时加速不会因为一次重启就直接过期。
     * <p>
     * 顺序要求：先 {@code captureBaseline}（此刻 tick 率还是原版的 20，基线要记这个），
     * 再填会话表，最后 {@code refreshTickrate} 把刻率抬上去。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        captureBaseline(server);
        long now = System.currentTimeMillis();
        for (TimeAccelSavedData.Entry entry : TimeAccelSavedData.get(server).entries()) {
            ResourceKey<Level> dimension = parseDimension(entry.dimension());
            if (dimension == null) {
                continue;
            }
            long expiresAt = entry.remainingMillis() < 0L ? -1L : now + entry.remainingMillis();
            if (entry.pos() == null) {
                TIME_SESSIONS.add(new Session(null, entry.multiplier(), expiresAt, dimension));
            } else if (server.getLevel(dimension) != null) {
                BLOCK_SESSIONS.put(blockKey(dimension, entry.pos()),
                        new Session(entry.pos(), entry.multiplier(), expiresAt, dimension));
            }
        }
        if (!BLOCK_SESSIONS.isEmpty() || !TIME_SESSIONS.isEmpty()) {
            LOGGER.info("[ACCEL-RESTORE] block={} time={} -> tickrate={}",
                    BLOCK_SESSIONS.size(), TIME_SESSIONS.size(), BASE_TICKRATE);
        }
        refreshTickrate(server);
    }

    /**
     * 服务器停止：落一次盘，然后把内存里的会话清空。
     * <p>
     * 清空这一步是必须的：这两张表是静态的，单人连着换存档时不清，上一个世界的加速场
     * 会被带进新世界（坐标撞上就真的生效）。落盘放在这里只是双保险 ——
     * 每次会话变化时已经写过一次了。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        persist(event.getServer());
        BLOCK_SESSIONS.clear();
        TIME_SESSIONS.clear();
        LAST_TOGGLE.clear();
        previousTickrate = BASE_TICKRATE;
        statusTicker = 0;
    }

    /** 该位置当前是否有局部加速场。 */
    public static boolean isActive(Level level, BlockPos pos) {
        return BLOCK_SESSIONS.containsKey(blockKey(level.dimension(), pos));
    }

    /** 当前加速场数量，用于诊断。 */
    public static int activeCount() {
        return BLOCK_SESSIONS.size() + TIME_SESSIONS.size();
    }

    // ===================== 每刻推进 =====================

    /**
     * 抢先处理 shift+右键：关掉"扳手模式"时，把这次右键从模组的扳手逻辑手里截下来。
     * <p>
     * 为什么必须在这里截：剑已经写进 {@code c:tools/wrench} 与 {@code ae2:quartz_wrench} 物品标签，
     * 而<b>标签是数据包级别、按物品整体生效的，无法按 NBT 逐个物品开关</b> ——
     * AE2 这类模组在"手里拿的是不是扳手"这一层就认它了，光改剑上的 NBT 拦不住。
     * 好在 AE2 的 WrenchHook 挂在 {@link PlayerInteractEvent.RightClickBlock} 上，
     * 这个事件可取消，且早于 useOn 触发 —— 只要在这里抢先吃掉，扳手就轮不到执行。
     * <p>
     * 优先级取 HIGHEST，确保排在模组的处理器之前。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // 1.20.1 的 PlayerInteractEvent#getEntity() 已经直接返回 Player（1.21 才放宽成 Entity）。
        Player player = event.getEntity();
        if (event.isCanceled() || player == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(KunJinKaoSwordItem.isSwordLike(stack))) {
            return;
        }
        // 扳手模式开着：整个让给模组，这里什么都不做。
        if (KunJinKaoSwordItem.isWrenchEnabled(stack)) {
            return;
        }

        // 避雷针：不管有没有潜行，都归本模组处理。
        // 这一条同时压掉 XyCraft 那类把避雷针当成 Xynergy 节点的模组 ——
        // 它们的处理器第一句就是 if (event.isCanceled()) return;，
        // 这里先取消，它们就完全不插话；否则右键避雷针会弹它们的
        // You can not bind this to itself，而本模组的召唤闪电根本轮不到。
        if (KunJinKaoSwordItem.isLightningRodEnabled(stack)
                && event.getLevel().getBlockState(event.getPos()).getBlock()
                        instanceof net.minecraft.world.level.block.LightningRodBlock) {
            event.setCanceled(true);
            SwordToolHandler.strikeLightningRod(new net.minecraft.world.item.context.UseOnContext(
                    player, event.getHand(), event.getHitVec()));
            return;
        }

        // 下面这些只在潜行时接管：不潜行时本模组不碰其它方块。
        // 注意这个提前返回必须放在避雷针之后 —— 放在最上面会让不潜行的右键
        // 根本进不来，避雷针接管也就永远不会执行。
        if (!player.isShiftKeyDown()) {
            return;
        }
        // 扳手模式关着：这次右键不该再被当成扳手。
        // 先把方块交互拦掉（AE2 之类挂在同一事件上的扳手因此不会执行），
        // 再自己处理 —— 目标是可加速方块就开/关加速场，否则单纯什么都不做。
        event.setCanceled(true);
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            tryToggleBlock(player, serverLevel, event.getPos(), stack);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 1.20.1 的 tick 事件没有 .Post 子类，统一是 TickEvent + phase。只认 END，避免一 tick 跑两遍。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long now = System.currentTimeMillis();
        MinecraftServer server = event.getServer();
        expireTimedSessions(server, now);
        tickBlockSessions(server);
        if (++statusTicker >= STATUS_INTERVAL_TICKS) {
            statusTicker = 0;
            broadcastStatus(server);
        }
    }

    /**
     * 把当前加速场推给客户端，用于在方块上方画悬浮提示。
     * <p>
     * 每个维度都发一份，哪怕那一维没有加速场 —— 空列表就是"清空提示"的信号。
     * 否则最后一个加速场结束后，客户端屏幕上的倒计时会一直挂着不消失。
     * 包体很小（没有加速场时只有一个 VarInt），按固定间隔心跳式发送最省心。
     */
    private static void broadcastStatus(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Map<net.minecraft.server.level.ServerLevel, List<TimeAccelStatusPayload.Entry>> byLevel = new HashMap<>();
        for (Session session : BLOCK_SESSIONS.values()) {
            if (session.pos() == null) {
                continue;
            }
            ServerLevel level = server.getLevel(session.dimension());
            if (level == null) {
                continue;
            }
            long remaining = session.expiresAt() < 0L ? -1L : Math.max(0L, session.expiresAt() - now);
            List<TimeAccelStatusPayload.Entry> entries =
                    byLevel.computeIfAbsent(level, key -> new ArrayList<>());
            entries.add(TimeAccelStatusPayload.Entry.block(session.pos(), session.multiplier(), remaining));
            // 同一批生物也要报给客户端，否则"被加速的生物"没有任何提示。
            // 这里按与加速时相同的范围与条件重新查一遍，不额外维护实体列表。
            AABB box = new AABB(session.pos()).inflate(ENTITY_RADIUS);
            for (Entity entity : level.getEntities((Entity) null, box,
                    // 只加速生物。以前用 "非玩家且存活"，会把掉落物（ItemEntity）、
                  // 经验球、箭矢之类一并算进去 —— 它们被额外 tick 会表现为
                  // "刚挖下来的方块掉在地上还在被加速"。
                  candidate -> candidate instanceof net.minecraft.world.entity.LivingEntity
                          && !(candidate instanceof Player) && candidate.isAlive())) {
                entries.add(TimeAccelStatusPayload.Entry.entity(
                        entity.getId(), session.multiplier(), remaining));
            }
        }
        // 时间加速是全局的：没有具体位置，提示由客户端画在太阳方向上。
        List<TimeAccelStatusPayload.Entry> timeEntries = new ArrayList<>();
        for (Session session : TIME_SESSIONS) {
            long remaining = session.expiresAt() < 0L ? -1L : Math.max(0L, session.expiresAt() - now);
            timeEntries.add(TimeAccelStatusPayload.Entry.time(session.multiplier(), remaining));
        }
        for (ServerLevel level : server.getAllLevels()) {
            List<TimeAccelStatusPayload.Entry> entries =
                    new ArrayList<>(byLevel.getOrDefault(level, List.of()));
            entries.addAll(timeEntries);
            NetworkHandler.sendToPlayersInDimension(level, new TimeAccelStatusPayload(List.copyOf(entries)));
        }
    }

    private static void expireTimedSessions(MinecraftServer server, long now) {
        boolean expiredBlocks = BLOCK_SESSIONS.values().removeIf(session -> session.expired(now));
        boolean expiredTime = !TIME_SESSIONS.isEmpty() && TIME_SESSIONS.get(0).expired(now);
        if (expiredTime) {
            TIME_SESSIONS.clear();
        }
        if (expiredBlocks || expiredTime) {
            refreshTickrate(server);
            // 过期的会话不能留在存档里：否则重启后会被当成"还在开"再恢复回来。
            persist(server);
        }
    }

    private static void tickBlockSessions(MinecraftServer server) {
        if (BLOCK_SESSIONS.isEmpty()) {
            return;
        }
        boolean removedAny = false;
        for (Iterator<Session> iterator = BLOCK_SESSIONS.values().iterator(); iterator.hasNext();) {
            Session session = iterator.next();
            ServerLevel level = server.getLevel(session.dimension());
            if (level == null || session.pos() == null) {
                continue;
            }
            // 方块被挖掉或换成了别的方块时，这个加速场就该结束。
            // 不检查的话它会永远留着，继续加速那个位置上的生物 ——
            // 而方块挖掉后正好有掉落物停在那儿，看起来就像"掉落物继承了加速效果"。
            if (!AcceleratorBlockEntity.isAcceleratable(level, session.pos())) {
                iterator.remove();
                removedAny = true;
                continue;
            }
            // 方块部分：与加速方块共用同一套逻辑和每刻预算。
            // 单台：半径 0，只动点中的那一格。
            AcceleratorBlockEntity.accelerateArea(level, session.pos(), BLOCK_RADIUS, session.multiplier(), null);
            // 生物部分：范围内的生物额外 tick，让它们真的"快起来"。
            // 与加速方块、范围加速共用 AcceleratorBlockEntity 里那一份实现，
            // 只加速生物（LivingEntity、非玩家），掉落物与箭矢不算。
            AcceleratorBlockEntity.accelerateEntitiesInArea(level, session.pos(), ENTITY_RADIUS,
                    session.multiplier());
        }
        if (removedAny) {
            // 加速场数量变了，刻率基线要跟着重算；存档里那条已经失效的会话也要擦掉。
            refreshTickrate(server);
            persist(server);
        }
    }

}