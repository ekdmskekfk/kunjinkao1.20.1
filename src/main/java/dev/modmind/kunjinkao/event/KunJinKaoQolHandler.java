package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoPalette;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.config.QolStateSavedData;
import dev.modmind.kunjinkao.network.CosmeticStatePayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.QolStatePayload;
import dev.modmind.kunjinkao.network.ZoneStatusPayload;
import dev.modmind.kunjinkao.qol.QolSetting;
import dev.modmind.kunjinkao.qol.QolSettings;
import dev.modmind.kunjinkao.world.ZoneExcavationJob;
import dev.modmind.kunjinkao.world.ZoneSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能面板（QoL）的服务端权威实现：接收改动、校验、落库、生效、回执。
 * <p>
 * <b>所有判定都在这里。</b> 客户端只发"我想改成什么"，授权、取值范围、前置依赖
 * 一律在服务端重算一遍 —— 面板上的置灰只是提示，不是防线。
 * <p>
 * 生效策略分两类：
 * <ul>
 *   <li><b>状态型</b>（夜视、村庄英雄、长臂善舞）：把状态挂在玩家身上，
 *       由 {@link #onPlayerTick} 定期校正。这样死亡重生、换维度、被别的模组清掉之后
 *       都能自己回来，不需要为每种情况写一条恢复逻辑。</li>
 *   <li><b>查询型</b>（凌空采掘）：没有"挂上去"的动作，由消费方
 *       （{@code SwordMiningPenaltyHandler}）在读的时候查一次当前值。</li>
 * </ul>
 */
public final class KunJinKaoQolHandler {

    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");

    // ===== 星瞳夜视（80）=====
    /**
     * 我们自己施加的夜视时长（tick）。
     * <p>
     * 这个数值同时是"这条夜视是不是我们放的"的判据：剩余时长比它长，
     * 就说明是玩家自己喝的药水或别的模组给的，我们只续期不覆盖、关闭时也不摘。
     * <p>
     * <b>它和 {@link #NIGHT_VISION_RENEW_BELOW}、{@link #NIGHT_VISION_INTERVAL} 三者必须满足
     * 「续期阈值 − 检查间隔 &gt; 0」且留足余量。</b> 这里 1200 − 100 = 1100 的余量下，
     * 效果最低也有 700 tick，永远不会掉到 0。
     */
    private static final int NIGHT_VISION_DURATION = 1200;
    /**
     * 剩余时长低于这个值就补一次。
     * <p>
     * <b>必须显著大于检查间隔</b>：早先这两个值都是 100，于是每次检查时剩余时长刚好等于阈值、
     * 判定不成立，下一次检查时它已经掉到 0 —— 表现就是夜视每隔一会儿真的消失一下再被加上，
     * 客户端看到「移除 + 重新施加」两个包，图标闪一下。这不是显示问题，是效果真的断过。
     */
    private static final int NIGHT_VISION_RENEW_BELOW = 800;

    // ===== 万民敬仰（88）=====
    private static final int HERO_DURATION = 300;
    private static final int HERO_RENEW_BELOW = 100;
    /** 参考实现换算：等级 N 对应 amplifier = N × 4 - 1（原版每级 +4 点好感）。 */
    private static final int HERO_AMPLIFIER_PER_LEVEL = 4;

    // ===== 长臂善舞（89）=====
    // 1.20.1 的 AttributeModifier 用 UUID 标识（1.21 才改成 ResourceLocation）。用固定字节派生，会话内稳定。
    private static final java.util.UUID REACH_BLOCK_MODIFIER = java.util.UUID.nameUUIDFromBytes("kunjinkao:reach_block".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final java.util.UUID REACH_ENTITY_MODIFIER = java.util.UUID.nameUUIDFromBytes("kunjinkao:reach_entity".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** 校正周期（tick）。20 是 1 秒，够快又完全不值得优化。 */
    private static final int RECONCILE_INTERVAL = 20;
    /** 夜视续期周期：比 {@link #RECONCILE_INTERVAL} 慢一档，因为它的续期窗口本来就很长。 */
    private static final int NIGHT_VISION_INTERVAL = 100;
    /** 改服务器级设置所需的最低权限等级。2 = 原版 OP，与"能用命令方块"同一档。 */
    private static final int SERVER_SETTING_PERMISSION_LEVEL = 2;

    // ===== 选区作业（127）=====
    /**
     * 两次按键之间的确认窗口（毫秒）。
     * <p>
     * 二次确认做成"再按一次同一个键"而不是弹一个界面：这个操作本来就是键盘操作，
     * 中间插一个 GUI 反而会让人下意识地点"确定"。窗口给 10 秒 —— 够读一遍提示，
     * 又短到不会在他已经忘了这回事的时候突然开始挖。
     */
    private static final long ZONE_CONFIRM_WINDOW_MS = 10_000L;
    /** 角点最远能设多远（格）。防止改过的客户端把角点设到天边，框出一整片地形。 */
    private static final double ZONE_MAX_CORNER_DISTANCE = 128.0D;

    /** 待确认的执行请求：第一次按的时刻，以及那次请求的是哪种作业。 */
    private record PendingConfirm(long at, int mode) {
    }

    /** 待确认的执行请求，按玩家存。 */
    private static final Map<UUID, PendingConfirm> ZONE_PENDING_CONFIRM = new ConcurrentHashMap<>();

    // ===================== 管理员装饰（201–203）=====================
    /*
     * 三项装饰（乱码称号 / 浮游挂件 / 光环拖尾）**完全由客户端渲染**，服务端只做两件事：
     *   1. 算"谁戴着哪几项"，用 CosmeticStatePayload 整表推给所有客户端；
     *   2. 反击的伤害判定（谁打了我、打到第几 tick、还剩几颗心）。
     * 画面（三把剑的位置与刺击、称号牌文字、光环与拖尾粒子）由每个客户端用
     * **自己那份插值过的玩家位置**算出来 —— 这就是零延迟的来源。
     *
     * 为什么不做成服务端实体（曾经是隐形盔甲架）：实体的位置必然绕一圈网络
     * （服务端拿到玩家坐标 → 算位置 → 再发回客户端），玩家一动就拖在后面。
     * 那个延迟在服务端消不掉，只能按延迟做预测补偿，永远差一点；
     * 而"谁戴着什么"这点状态是稳定的，同步它的代价几乎为零。
     *
     * 代价：没装模组的客户端看不见装饰。但本模组注册了物品，专用服务器本来就要求
     * 双端都装 —— 这个代价是空的（真到了需要兼容原版客户端那天，只能退回服务端实体方案）。
     *
     * 反击的时间参数（持续时长、飞行时间、伤害间隔）定义在 CosmeticStatePayload 里：
     * 服务端按它们扣血、客户端按它们做刺击动画，两边必须完全一致。
     */

    /** 每一击的伤害点数。 */
    private static final float STRIKE_DAMAGE = 1.0F;
    /**
     * 低于这个血量就不再打。
     * <p>
     * 3.0 = 一颗心（2.0）+ 这一击（1.0）：打完至少还剩一颗心。
     * 这套东西是"打管理员会还手"的惩罚，不是处决。
     */
    private static final float STRIKE_MIN_HEALTH = 3.0F;

    /** 反击的命中火花。光环与拖尾的粒子现在由客户端自己发，这一份是服务端发的伤害反馈。 */
    private static final DustParticleOptions STRIKE_SPARK = new DustParticleOptions(new Vector3f(
            KunJinKaoPalette.red(KunJinKaoPalette.COSMETIC_AURA),
            KunJinKaoPalette.green(KunJinKaoPalette.COSMETIC_AURA),
            KunJinKaoPalette.blue(KunJinKaoPalette.COSMETIC_AURA)), 1.05F);

    /** 正在进行的反击，键是挨打的管理员 UUID。 */
    private static final Map<UUID, Strike> STRIKES = new ConcurrentHashMap<>();
    /** 上一次广播出去的装饰旗帜，用来判断"要不要再推一次"。 */
    private static final Map<UUID, Integer> LAST_FLAGS = new ConcurrentHashMap<>();

    /** 一次反击的进度。剑的位置由客户端自己算，服务端只记时间。 */
    private static final class Strike {
        /** 打的是谁。 */
        UUID target;
        /** 已经打了多少 tick。客户端拿它显示刺击节奏。 */
        int ticks;
        /** 下一击在 ticks 到达这个值时落地。 */
        int nextDamageTick;
    }
    // ===================== 网络入口 =====================

    /**
     * 客户端请求改一项设置。
     * <p>
     * 未知 settingId 直接忽略：新旧端版本号已经隔开了，但多一层防御不花钱，
     * 免得将来有人手改包体时在这里抛异常。
     */
    public static void handleSettingChange(Player source, int settingId, int value) {
        if (!(source instanceof ServerPlayer player)) {
            return;
        }
        QolSetting setting = QolSetting.byId(settingId);
        if (setting == null) {
            return;
        }

        if (setting.scope() == QolSetting.Scope.SERVER) {
            // 服务器级：只有 OP（权限等级 2）能改，值直接写回 toml。
            // 注意这里不是"个人设置的另一种存法" —— 它影响全服，所以既不进玩家存档，也不做全量广播之外的事。
            if (!player.hasPermissions(SERVER_SETTING_PERMISSION_LEVEL)) {
                player.displayClientMessage(Component.translatable("message.kunjinkao.qol_denied_op"), true);
                sendState(player, setting, false);
                return;
            }
            int clamped = QolSettings.clamp(setting, value);
            AdminToolConfig.setQolServerValue(setting, clamped);
            // 服务器级值所有人看到的都一样，改完要广播：别人开着的面板上那一行也得跟着变。
            broadcastState(setting);
            return;
        }

        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            // 未授权：拒绝改动，但仍然回一条状态，让面板把这一行刷新成服务端的真实值，
            // 而不是停在客户端自己以为的那个值上。
            player.displayClientMessage(Component.translatable("message.kunjinkao.qol_denied"), true);
            sendState(player, setting, false);
            return;
        }

        int clamped = QolSettings.clamp(setting, value);
        QolStateSavedData.get(player.server).setValue(player.getUUID(), setting, clamped);
        applyTo(player, setting);
        sendState(player, setting, true);
    }

    // ===================== 选区作业（127）=====================

    /**
     * 设一个角点（木棍右键的唯一入口）。
     * <p>
     * 四道判定都在这里：总闸、授权、作业占用、距离。抽成一个入口而不是各处各判一次，
     * 是因为"改客户端就能框到天边"这种事只要漏掉一处就等于没有防线；
     * 复制出来的第二份判定迟早会有一边改了另一边没改。
     * <p>
     * 坐标不信任客户端：改过的客户端可以把角点设到任意远处，框出一整片地形再执行。
     * 这里按玩家眼睛到目标方块的距离卡一道，和原版交互距离同一个量级但宽得多
     * （框选本来就要点远处的方块）。
     *
     * @return 是否真的设下了角点；被拒绝时原因已经发给玩家
     */
    public static boolean trySetZoneCorner(ServerPlayer player, BlockPos pos) {
        if (!canUseZoneExcavation(player)) {
            return false;
        }
        if (ZoneExcavationJob.isRunning(player)) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_busy"), true);
            return false;
        }
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))
                > ZONE_MAX_CORNER_DISTANCE * ZONE_MAX_CORNER_DISTANCE) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_too_far"), true);
            return false;
        }
        int corner = ZoneSelection.setNextCorner(player, pos.immutable());
        // 改了选区就作废上一次的待确认 —— 否则"框 A 按一次、改成框 B 再按一次"
        // 会在玩家以为只是改了选区的时候直接开工。
        ZONE_PENDING_CONFIRM.remove(player.getUUID());
        player.displayClientMessage(Component.translatable(
                corner == ZoneSelection.CORNER_A
                        ? "message.kunjinkao.zone_set_corner_a"
                        : "message.kunjinkao.zone_set_corner_b",
                pos.toShortString()), true);
        sendZoneStatus(player);
        return true;
    }

    /**
     * 请求执行。
     * <p>
     * 第一次按只给一句确认提示，窗口内再按一次才真的开始。地形是不可逆的，
     * 所以这里宁可让玩家多按一次，也不做"按了就挖"。
     * <p>
     * 确认文案按当前模式分开：破坏与放置的后果完全不同（一个是地形没了，
     * 一个是材料没了），用同一句话会让玩家在按第二下之前还想确认一次自己选的是哪个。
     */
    public static void handleZoneExecute(Player source, int mode) {
        if (!(source instanceof ServerPlayer player) || !canUseZoneExcavation(player)) {
            return;
        }
        if (ZoneExcavationJob.isRunning(player)) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_busy"), true);
            return;
        }
        ZoneSelection.Corners corners = currentCorners(player);
        if (corners == null || corners.a() == null || corners.b() == null) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_need_corners"), true);
            // 再说一句"怎么框"：角点已经不是按键设的了，玩家按下执行键时最缺的就是这句。
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_need_stick"), true);
            return;
        }
        long now = System.currentTimeMillis();
        PendingConfirm pending = ZONE_PENDING_CONFIRM.get(player.getUUID());
        // 确认必须"同一个键连按两次"：破坏与放置各有自己的执行键，
        // 不比对模式的话，先按破坏再按放置会被当成对破坏的确认 —— 玩家以为要填墙，结果墙没了。
        boolean confirmed = pending != null
                && pending.mode() == mode
                && now - pending.at() <= ZONE_CONFIRM_WINDOW_MS;
        if (!confirmed) {
            ZONE_PENDING_CONFIRM.put(player.getUUID(), new PendingConfirm(now, mode));
            int sizeX = Math.abs(corners.a().getX() - corners.b().getX()) + 1;
            int sizeY = Math.abs(corners.a().getY() - corners.b().getY()) + 1;
            int sizeZ = Math.abs(corners.a().getZ() - corners.b().getZ()) + 1;
            player.displayClientMessage(Component.translatable(
                    mode == ZoneExcavationJob.MODE_PLACE
                            ? "message.kunjinkao.zone_confirm_place"
                            : "message.kunjinkao.zone_confirm",
                    sizeX, sizeY, sizeZ, sizeX * sizeY * sizeZ), false);
            return;
        }
        ZONE_PENDING_CONFIRM.remove(player.getUUID());
        if (ZoneExcavationJob.start(player, corners.a(), corners.b(), mode)) {
            ZoneSelection.clear(player);
            sendZoneStatus(player);
        }
    }

    /** 取消：正在跑就停作业，否则清掉当前选区。 */
    public static void handleZoneCancel(Player source) {
        if (!(source instanceof ServerPlayer player)) {
            return;
        }
        ZONE_PENDING_CONFIRM.remove(player.getUUID());
        if (ZoneExcavationJob.cancel(player)) {
            sendZoneStatus(player);
            return;
        }
        if (currentCorners(player) != null) {
            ZoneSelection.clear(player);
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_cleared"), true);
            sendZoneStatus(player);
        }
    }

    /**
     * 缩放当前选区：木棍 + Shift + 滚轮。
     * <p>
     * <b>轴向与正负由服务端按玩家朝向算，不信客户端。</b> 客户端只发「往上滚了几格」，
     * 这里取 {@code getLookAngle()}：绝对值最大的那个分量决定动哪根轴，
     * 它的正负决定动哪一侧 —— 抬头看 = 往上扩、低头看 = 往下扩，朝 +X 看 = 往 +X 扩，
     * 上/下/左/右/前/后都从这一条规则里自然长出来，不需要为每个方向各写一个分支。
     * <p>
     * 权限与总闸复用 {@link #canUseZoneExcavation}：缩放改的是同一块选区，
     * 判定标准必须和执行键完全一致，否则会出现「框得动、挖不动」或者反过来。
     * <p>
     * <b>作业在跑时不让改。</b> 选区在 {@code ZoneExcavationJob.start} 那一刻就已经快照进作业了，
     * 这时改角点只会让屏幕上的框和实际在挖的范围变成两回事 —— 玩家看着框调整，
     * 以为挖的是新范围，实际挖的还是旧范围。
     *
     * @param delta 滚轮格数：正数扩大、负数缩小
     */
    public static void handleZoneAdjust(Player source, int delta) {
        if (!(source instanceof ServerPlayer player) || !canUseZoneExcavation(player)) {
            return;
        }
        if (delta == 0) {
            return;
        }
        if (ZoneExcavationJob.isRunning(player)) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_busy"), true);
            return;
        }

        Vec3 look = player.getLookAngle();
        double absX = Math.abs(look.x);
        double absY = Math.abs(look.y);
        double absZ = Math.abs(look.z);
        int axis;
        double along;
        if (absY >= absX && absY >= absZ) {
            // 抬头看天 = 往上扩、低头看地 = 往下扩。俯仰优先于水平，是因为「往上抬一点」
            // 在屏幕上就是高度在变，而水平分量此刻本来就很小。
            axis = ZoneSelection.AXIS_Y;
            along = look.y;
        } else if (absX >= absZ) {
            axis = ZoneSelection.AXIS_X;
            along = look.x;
        } else {
            axis = ZoneSelection.AXIS_Z;
            along = look.z;
        }
        // 被选中的分量不可能为 0（除非朝向向量整个是零向量），真出现 0 时按正向算，
        // 至少给出一个确定的行为而不是随机的。
        int direction = along < 0.0D ? -1 : 1;

        ZoneSelection.AdjustResult result = ZoneSelection.adjust(player, axis, direction, delta);
        switch (result) {
            case NO_CORNERS -> {
                player.displayClientMessage(
                        Component.translatable("message.kunjinkao.zone_adjust_need_corners"), true);
                return;
            }
            case TOO_BIG -> {
                // 报出上限本身：只说「太大了」的话，玩家会以为是自己滚得太快，
                // 于是继续滚、继续被拒，而真正的原因（服务器的单边上限）他看不到。
                player.displayClientMessage(Component.translatable(
                        "message.kunjinkao.zone_adjust_too_big",
                        AdminToolConfig.qolServerValue(QolSetting.ZONE_MAX_SIDE)), true);
                return;
            }
            case OK -> {
            }
        }

        ZoneSelection.Corners corners = currentCorners(player);
        if (corners != null && corners.a() != null && corners.b() != null) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_adjusted",
                    Math.abs(corners.a().getX() - corners.b().getX()) + 1,
                    Math.abs(corners.a().getY() - corners.b().getY()) + 1,
                    Math.abs(corners.a().getZ() - corners.b().getZ()) + 1), false);
        }
        // 线框立刻跟着变：不然玩家滚了半天，屏幕上的框还停在旧尺寸，只能靠猜。
        sendZoneStatus(player);
    }

    /** 权限与总闸。两道都过了才谈得上框选。 */
    private static boolean canUseZoneExcavation(ServerPlayer player) {
        if (AdminToolConfig.qolServerValue(QolSetting.ZONE_EXCAVATE_SERVER) <= 0) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_disabled"), true);
            return false;
        }
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.qol_denied"), true);
            return false;
        }
        return true;
    }

    /** 取当前维度下的选区；选区绑在别的维度时视为没有（坐标没有可比性）。 */
    private static ZoneSelection.Corners currentCorners(ServerPlayer player) {
        ZoneSelection.Corners corners = ZoneSelection.get(player);
        if (corners == null || !corners.dimension().equals(player.level().dimension().location())) {
            return null;
        }
        return corners;
    }

    /** 把选区与进度推给本人。别人不需要知道他框了哪块地。 */
    public static void sendZoneStatus(ServerPlayer player) {
        ZoneSelection.Corners corners = currentCorners(player);
        boolean hasA = corners != null && corners.a() != null;
        boolean hasB = corners != null && corners.b() != null;
        int[] progress = ZoneExcavationJob.progress(player);
        NetworkHandler.sendToPlayer(player, new ZoneStatusPayload(
                hasA, hasA ? corners.a() : BlockPos.ZERO,
                hasB, hasB ? corners.b() : BlockPos.ZERO,
                progress != null, progress == null ? 0 : progress[0], progress == null ? 0 : progress[1],
                player.level().dimension().location()));
    }

    /** 打开面板时把每一行都发一遍。 */    public static void sendFullState(ServerPlayer player) {
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        for (QolSetting setting : QolSetting.values()) {
            sendState(player, setting, authorized);
        }
    }

    /**
     * 客户端请求全量状态（打开面板时发一次）。
     * <p>
     * 这里没有额外鉴权：全量状态里只有"这一项现在开着还是关着、值是多少"，本来就允许玩家看到自己的设置；
     * 未授权时每条回执里的 {@code authorized} 是 false，客户端据此把整行置灰。
     * 真正的拦截在 {@link #handleSettingChange} —— 看得见不等于改得动。
     */
    public static void handleRequestFullState(Player source) {
        if (source instanceof ServerPlayer player) {
            sendFullState(player);
        }
    }

    /** 发某一行的状态。范围和作用域取自服务端的表，客户端算不出来（上限含服务器配置）。 */    public static void sendState(ServerPlayer player, QolSetting setting, boolean authorized) {
        int value = valueOf(player, setting);
        NetworkHandler.sendToPlayer(player, QolStatePayload.of(
                setting, value > 0, value, setting.min(), QolSettings.maxOf(setting), authorized));
    }

    /** 按 UUID 读当前值；没写过就是该项默认值。服务器级项读的是 toml。 */
    public static int valueOf(ServerPlayer player, QolSetting setting) {
        if (setting.scope() == QolSetting.Scope.SERVER) {
            // 服务器级与玩家无关，每个人看到的都该是同一个值。
            return AdminToolConfig.qolServerValue(setting);
        }
        return QolStateSavedData.get(player.server).getValue(player.getUUID(), setting);
    }

    /**
     * 把某一行的新状态广播给所有在线玩家。
     * <p>
     * 只有服务器级项需要：它的值是全服共享的，一个人改了别人面板上的数字就旧了。
     * 个人级项不广播 —— 那是别人的隐私设置，而且广播一遍纯属浪费。
     */
    private static void broadcastState(QolSetting setting) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            sendState(online, setting, setting.scope() == QolSetting.Scope.SERVER
                    ? online.hasPermissions(SERVER_SETTING_PERMISSION_LEVEL)
                    : AdminToolConfig.isAuthorized(online.getUUID()));
        }
    }

    public static boolean isOn(ServerPlayer player, QolSetting setting) {
        return valueOf(player, setting) > 0;
    }

    /**
     * 夜视开关的共享入口：战术 HUD 的按钮与功能面板的那一行都走这里。
     * <p>
     * 两个入口必须操作同一份状态（存在 QolStateSavedData 里），否则会出现
     * "HUD 说开着、面板说关着"这种两套开关各记一份的局面。
     *
     * @return 切换后的状态
     */
    public static boolean toggleNightVision(ServerPlayer player) {
        boolean now = !isOn(player, QolSetting.NIGHT_VISION);
        QolStateSavedData.get(player.server).setValue(
                player.getUUID(), QolSetting.NIGHT_VISION, now ? 1 : 0);
        applyNightVision(player, now);
        return now;
    }

    // ===================== 周期校正 =====================

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        int tick = player.tickCount;
        // 经验飞涨每 tick 都要给，所以它不放在下面那些"每 20 tick 校正一次"的分支里。
        // 它没有需要维持的状态，就是"开着就一直给"，因此也不需要校正。
        applyXpRain(player);
        if (tick % RECONCILE_INTERVAL == 0) {
            applyWaterBreath(player);
            applyVillageHero(player);
            reconcileReach(player);
        }
        if (tick % NIGHT_VISION_INTERVAL == 0) {
            renewNightVision(player);
        }
        // 选区作业的作业由拥有者自己的 tick 驱动（见 ZoneExcavationJob#tick 的说明）。
        boolean wasRunning = ZoneExcavationJob.isRunning(player);
        if (wasRunning) {
            ZoneExcavationJob.tick(player);
            if (!ZoneExcavationJob.isRunning(player)) {
                // 刚好这一 tick 做完了：补一条状态让客户端的进度条归位。
                sendZoneStatus(player);
            } else if (tick % RECONCILE_INTERVAL == 0) {
                // 进度每 20 tick 回一次就够看了，每 tick 发纯属刷包。
                sendZoneStatus(player);
            }
        }
        // 装饰（201–203）每 tick 都要推进：背后三把剑、刺击、光环都是"看起来在动"的东西，
        // 20 tick 一档的话看起来就是一张一张地跳。
        tickCosmetics(player);
    }

    /** 玩家退出时清掉他的选区、作业与撤销记录。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ZONE_PENDING_CONFIRM.remove(player.getUUID());
            ZoneSelection.forget(player.getUUID());
            ZoneExcavationJob.forget(player.getUUID());
            forgetCosmetics(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            applyAll(player);
            sendFullState(player);
            // 装饰是整表快照：新人一进来就重推一次 —— 他要知道别人戴着什么，
            // 别人也要知道他的，而客户端只在收到快照时才会开始画。
            broadcastCosmetics();
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 死亡会清掉药水效果与 transient 属性修饰符，重生后必须整体重挂一次。
            applyAll(player);
            sendFullState(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 换维度同样会丢 transient 修饰符；药水效果会保留，但重挂一次也无害（幂等）。
            applyAll(player);
        }
    }

    // ===================== 各项生效逻辑 =====================

    private static void applyAll(ServerPlayer player) {
        for (QolSetting setting : QolSetting.values()) {
            applyTo(player, setting);
        }
    }

    private static void applyTo(ServerPlayer player, QolSetting setting) {
        switch (setting) {
            case NIGHT_VISION -> applyNightVision(player, isOn(player, setting));
            case WATER_BREATH -> applyWaterBreath(player);
            case VILLAGE_HERO -> applyVillageHero(player);
            case REACH -> reconcileReach(player);
            // 经验飞涨：没有"挂上去"的状态要维持，但开关一打开就立刻给一级 ——
            // 等下一个 tick 再给也不会错，只是玩家点完开关要愣一下才看到数字动。
            case XP_BOOST -> applyXpRain(player);
            // 下面这些没有"挂到玩家身上"的动作：
            //   83 凌空采掘 由 SwordMiningPenaltyHandler 现查现用；
            //   84 烈焰不侵 由 EntityFireImmuneMixin 现查现用；
            //   70 万载不磨 由 ItemStackDurabilityMixin 现查现用；
            //   94/95 只在交易发生的那一刻判定；
            //   77 与 127 的其余项是服务器级，值在 toml 里，消费方各自去读。
            // 保留这些分支而不是删掉，是为了让"新增一项时该不该写生效逻辑"这个问题
            // 在编译期就逼着人想一遍 —— 枚举加了项而这里没加，switch 会报错。
            case FLY_MINING, FIRE_IMMUNE, UNLIMITED_TRADES, VILLAGER_MASTER,
                 ANVIL_UNBREAKABLE, ANVIL_XP_COST, DURABILITY_PROTECTION,
                 MACHINE_LOOT, FOG_VISION, TREASURE_HUNTER_SERVER, TREASURE_HUNTER_RADIUS,
                 TREASURE_HUNTER, ZONE_EXCAVATE_SERVER, ZONE_MAX_SIDE, ZONE_DROP_POLICY,
                 ZONE_TICK_BUDGET, ZONE_BATCH_SIDE, ZONE_UNDO_MAX,
                 // 两行作业入口也没有"挂上去"的动作：它们没有值，生效点就是按下执行键那一刻。
                 ZONE_BREAK, ZONE_PLACE -> {
            }
            // 装饰（201 称号牌 / 202 浮游挂件 / 203 光环拖尾）同样没有"挂到玩家身上"的动作：
            // 它们由 tickCosmetics 每 tick 推进，开关一改下一 tick（50 ms）就出现或消失，
            // 不需要在这里补一次，也不该在这里另写一份 —— 两份逻辑迟早在某一边漏掉一处。
            case COSMETIC_TITLE, COSMETIC_ORBIT, COSMETIC_AURA -> {
            }
            // 屹立不倒（204）同样没有"挂上去"的状态：客户端每 tick 现查现用（StandFirmClient），
            // 服务端的两个事件处理器也是现查现用。开关一改下一 tick 就生效。
            case STAND_FIRM -> {
            }
        }
    }

    /**
     * 经验飞涨（72）：开着的时候每一 tick 直接加一个经验等级。
     * <p>
     * <b>不走"给经验点"那条路。</b> {@code giveExperiencePoints} 要按等级曲线折算，
     * 而"每 tick 一级"是直接改等级计数 —— 用前者的话这个效果的强度会随等级升高而急剧衰减，
     * 表现成"一开始涨得飞快、后来几乎不动"，与"每 tick 一级"这句话对不上。
     * <p>
     * 也不做上限：这是管理员工具，想停就关掉开关。每秒 20 级，开着不管一分钟就是 1200 级。
     */
    private static void applyXpRain(ServerPlayer player) {
        if (!isOn(player, QolSetting.XP_BOOST)) {
            return;
        }
        player.giveExperienceLevels(1);
    }

    /**
     * 星瞳夜视（80）。
     * <p>
     * 刻意只在"没有效果"或"效果不长于我们自己那一档"时才写入：
     * 玩家自己喝的夜视药水通常比 300 tick 长，覆盖它等于把他的药水时长砍短，
     * 属于"抢玩家的药水"。反向也成立 —— 关闭时只摘不长于我们那一档的那条。
     */
    private static void applyNightVision(ServerPlayer player, boolean enabled) {
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        boolean looksLikeOurs = current == null || current.getDuration() <= NIGHT_VISION_DURATION;
        if (enabled) {
            if (looksLikeOurs) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION, 0, false, false, false));
            }
        } else if (looksLikeOurs && current != null) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
    }

    /** 续期：只在快到期时补，长于我们那一档的一律不碰。 */
    private static void renewNightVision(ServerPlayer player) {
        if (!isOn(player, QolSetting.NIGHT_VISION)) {
            return;
        }
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        if (current == null || current.getDuration() < NIGHT_VISION_RENEW_BELOW) {
            applyNightVision(player, true);
        }
    }

    /** 鲛人之息（85）：直接把氧气补满。刻意不用水肺药水 —— 那会在 HUD 上多一个图标，还要续期。 */
    private static void applyWaterBreath(ServerPlayer player) {
        if (!isOn(player, QolSetting.WATER_BREATH)) {
            return;
        }
        if (player.getAirSupply() < player.getMaxAirSupply()) {
            player.setAirSupply(player.getMaxAirSupply());
        }
    }

    /** 万民敬仰（88）：常驻村庄英雄，等级换算成 amplifier。 */
    private static void applyVillageHero(ServerPlayer player) {
        int level = isOn(player, QolSetting.VILLAGE_HERO) ? valueOf(player, QolSetting.VILLAGE_HERO) : 0;
        MobEffectInstance current = player.getEffect(MobEffects.HERO_OF_THE_VILLAGE);

        if (level <= 0) {
            // 只摘我们自己那一档：玩家刚打完袭击拿到的村庄英雄不能顺手清掉。
            if (current != null && current.getDuration() <= HERO_DURATION) {
                player.removeEffect(MobEffects.HERO_OF_THE_VILLAGE);
            }
            return;
        }

        int amplifier = Math.min(255, level * HERO_AMPLIFIER_PER_LEVEL - 1);
        // 玩家有更强的（比如袭击给的），不抢；只在没有、快过期、或我们该给得更高时才写。
        if (current != null && current.getAmplifier() > amplifier) {
            return;
        }
        if (current == null || current.getDuration() < HERO_RENEW_BELOW || current.getAmplifier() < amplifier) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.HERO_OF_THE_VILLAGE, HERO_DURATION, amplifier, false, false, false));
        }
    }

    /** 长臂善舞（89）：两个交互距离各加 N 格。用 transient 修饰符，不落盘，重挂靠生命周期事件。 */
    private static void reconcileReach(ServerPlayer player) {
        int blocks = isOn(player, QolSetting.REACH) ? valueOf(player, QolSetting.REACH) : 0;
        applyReachModifier(player, net.minecraftforge.common.ForgeMod.BLOCK_REACH.get(), REACH_BLOCK_MODIFIER, blocks);
        applyReachModifier(player, net.minecraftforge.common.ForgeMod.ENTITY_REACH.get(), REACH_ENTITY_MODIFIER, blocks);
    }

    private static void applyReachModifier(ServerPlayer player, Attribute attribute,
                                           java.util.UUID modifierId, int amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (amount <= 0) {
            instance.removeModifier(modifierId);
            return;
        }
        AttributeModifier existing = instance.getModifier(modifierId);
        // 已经是目标值就别动：每 20 tick 摘一次再挂一次会让属性面板上的数字反复闪。
        if (existing != null && existing.getAmount() == amount) {
            return;
        }
        instance.removeModifier(modifierId);
        instance.addTransientModifier(new AttributeModifier(
                modifierId, "kunjinkao_reach", amount, AttributeModifier.Operation.ADDITION));
    }

    // ===================== 管理员装饰的实现 =====================

    /**
     * 装饰的总入口：每 tick 走一遍。
     * <p>
     * 这里只做两件事：推进反击（伤害判定），以及在状态变了的时候把整表推给客户端。
     * 位置、动画、粒子都不在这里 —— 那些由每个客户端用自己那份玩家位置算（零延迟）。
     */
    private static void tickCosmetics(ServerPlayer player) {
        boolean striking = driveStrike(player);
        int flags = cosmeticFlags(player);

        Integer last = LAST_FLAGS.get(player.getUUID());
        if (last == null || last != flags) {
            LAST_FLAGS.put(player.getUUID(), flags);
            broadcastCosmetics();
            return;
        }
        // 反击期间每 STRIKE_SYNC_INTERVAL tick 补推一次：客户端在两次之间自己把进度往前推，
        // 时间久了会漂，定期对齐一次。不是每 tick 都推 —— 那是每个在线玩家每秒 20 个包。
        if (striking && player.tickCount % CosmeticStatePayload.STRIKE_SYNC_INTERVAL == 0) {
            broadcastCosmetics();
        }
    }

    /** 这个玩家现在戴着哪几项装饰。没通过管理员密码验证的人一律 0（面板上也只是置灰）。 */
    private static int cosmeticFlags(ServerPlayer player) {
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            return 0;
        }
        int flags = 0;
        if (isOn(player, QolSetting.COSMETIC_TITLE)) {
            flags |= CosmeticStatePayload.FLAG_TITLE;
        }
        if (isOn(player, QolSetting.COSMETIC_ORBIT)) {
            flags |= CosmeticStatePayload.FLAG_ORBIT;
        }
        if (isOn(player, QolSetting.COSMETIC_AURA)) {
            flags |= CosmeticStatePayload.FLAG_AURA;
        }
        return flags;
    }

    /**
     * 把"谁戴着什么、在打谁、打到第几 tick"整表推给所有客户端。
     * <p>
     * 整表而不是增量：一条记录三十来字节、玩家数量又少，整表推就省掉了
     * "增量包字段错位 / 漏包"这一整类问题 —— 客户端拿到的永远是权威快照，
     * 表里没有的人一律视为没有装饰（下线、关开关都不需要额外的清理消息）。
     */
    public static void broadcastCosmetics() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        List<CosmeticStatePayload.Entry> entries = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            int flags = cosmeticFlags(online);
            Strike strike = STRIKES.get(online.getUUID());
            entries.add(strike == null
                    ? CosmeticStatePayload.Entry.idle(online.getUUID(), flags)
                    : CosmeticStatePayload.Entry.striking(online.getUUID(), flags, strike.target, strike.ticks));
        }
        NetworkHandler.sendToAll(new CosmeticStatePayload(List.copyOf(entries)));
    }

    /** 掉线：清掉反击状态与旗帜缓存，并重推一次整表（让别人的客户端别再画他）。 */
    private static void forgetCosmetics(ServerPlayer player) {
        STRIKES.remove(player.getUUID());
        LAST_FLAGS.remove(player.getUUID());
        broadcastCosmetics();
    }

    /**
     * 玩家打了管理员 → 起一次反击。
     * <p>
     * 只记下"追谁"，真正的扣血交给 {@link #driveStrike} 按 tick 走：在事件里把整段反击做完
     * 会把主线程按住 5 秒，那就是一次卡服。
     * <p>
     * 只认"打进来的一方是玩家"：这条装饰的语义是"打管理员会还手"，让僵尸也能触发的话，
     * 管理员挂机一晚就会被自己的剑拖着满地图追怪。一次只追一个人：被几个人围住时，
     * 先把第一个打回去，比三把剑各飞一边清楚得多。
     */
    @SubscribeEvent
    public void onLivingIncomingDamage(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer admin)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker) || attacker == admin) {
            return;
        }
        if ((cosmeticFlags(admin) & CosmeticStatePayload.FLAG_ORBIT) == 0) {
            return;
        }
        if (STRIKES.containsKey(admin.getUUID())) {
            return;   // 已经在追别人了 —— 不叠加
        }
        Strike strike = new Strike();
        strike.target = attacker.getUUID();
        STRIKES.put(admin.getUUID(), strike);
        broadcastCosmetics();
    }

    /**
     * 推进一次反击。
     *
     * @return 这一 tick 是否还在反击中
     */
    private static boolean driveStrike(ServerPlayer admin) {
        Strike strike = STRIKES.get(admin.getUUID());
        if (strike == null) {
            return false;
        }
        ServerPlayer victim = admin.server.getPlayerList().getPlayer(strike.target);
        if (victim == null || !victim.isAlive() || victim.level() != admin.level()) {
            // 目标下线、死了、或者换维度了：这次反击到此为止。
            // 跨维度追击没有意义 —— 挨打的人已经不在管理员眼前了。
            STRIKES.remove(admin.getUUID());
            broadcastCosmetics();
            return false;
        }
        strike.ticks++;
        // 先让客户端的剑飞过去（STRIKE_FLIGHT_TICKS），飞到了才开始扣血：
        // 否则会出现"剑还在半路，人已经开始掉血"的错位。
        if (strike.ticks >= CosmeticStatePayload.STRIKE_FLIGHT_TICKS
                && strike.ticks >= strike.nextDamageTick) {
            strike.nextDamageTick = strike.ticks + CosmeticStatePayload.STRIKE_DAMAGE_INTERVAL;
            strikeDamage(admin, victim);
        }
        if (strike.ticks >= CosmeticStatePayload.STRIKE_DURATION) {
            STRIKES.remove(admin.getUUID());
            broadcastCosmetics();
            return false;
        }
        return true;
    }

    /**
     * 反击的一击。
     * <p>
     * 伤害用 {@code indirectMagic}：魔法伤害不吃护甲，否则一身好装备的玩家会把这套反击
     * 减到看不见数字，那这条装饰就等于没做。附魔保护仍然有效 —— 那属于"他就是很硬"。
     * <p>
     * 低于 {@link #STRIKE_MIN_HEALTH} 就停手：这是惩罚，不是处决。
     * 命中火花与音效由服务端发（所有客户端都收得到），不然只有挨打的人自己知道发生了什么。
     */
    private static void strikeDamage(ServerPlayer admin, ServerPlayer victim) {
        if (victim.getHealth() <= STRIKE_MIN_HEALTH) {
            return;
        }
        ServerLevel level = serverLevelOf(victim);
        if (level == null) {
            return;
        }
        // 直接来源与起因都填 admin：击退方向取自 source.getEntity()，所以效果仍然是
        // "从管理员那边被打退"，死亡信息也仍然署名到管理员头上。这里不再有那把飞出去的剑实体，
        // 也不需要 —— 它现在只活在客户端画面里。
        victim.hurt(admin.damageSources().indirectMagic(admin, admin), STRIKE_DAMAGE);
        level.sendParticles(ParticleTypes.CRIT,
                victim.getX(), victim.getY() + victim.getBbHeight() * 0.85D, victim.getZ(),
                6, 0.25D, 0.25D, 0.25D, 0.02D);
        level.sendParticles(STRIKE_SPARK,
                victim.getX(), victim.getY() + victim.getBbHeight() + 0.45D, victim.getZ(),
                8, 0.18D, 0.12D, 0.18D, 0.0D);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.55F, 1.6F);
    }

    /** 当前维度；命中火花只在服务端世界里发。 */
    private static ServerLevel serverLevelOf(ServerPlayer player) {
        return player.level() instanceof ServerLevel level ? level : null;
    }
}