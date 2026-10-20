package dev.modmind.kunjinkao.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.server.ServerLifecycleHooks;

import dev.modmind.kunjinkao.qol.QolSetting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员身份由服务器端配置的密码授予：玩家在游戏内验证通过后，
 * 其 UUID 被记入当前存档的 {@link PasswordAdminSavedData}，之后所有管理员功能都读这份存档数据。
 * <p>
 * 密码存放在 {@code config/kunjinkao-admin.toml}，不再硬编码在代码里。
 * 该文件只存在于实例/服务端本地，不会随模组 jar 分发。
 */
public final class AdminToolConfig {

    /**
     * 默认密码，仅用于让既有存档开箱即用。
     * 服务器管理员应当立即在配置文件中改掉它——默认值是公开的。
     */
    public static final String DEFAULT_ADMIN_PASSWORD = "118329";

    public static final ForgeConfigSpec COMMON_SPEC;
    private static final ForgeConfigSpec.ConfigValue<String> ADMIN_PASSWORD;

    /**
     * 功能面板（QoL）的服务器级参数。
     * <p>
     * 这些是"服务器愿意给到多少"的上限，不是个人开关：个人开关与个人数值存在
     * {@link QolStateSavedData} 里，按 UUID 分。两边分开是为了让服主调小上限时
     * 不用去动玩家存档，把上限调回来玩家的个人设置也还在。
     */
    private static final ForgeConfigSpec.IntValue QOL_VILLAGE_HERO_MAX_LEVEL;
    private static final ForgeConfigSpec.IntValue QOL_REACH_MAX_BLOCKS;
    /**
     * 服务器级设定项在 toml 里的值。
     * <p>
     * 用 Map 而不是给每项写一个字段：这些项的值、范围、默认值都已经在 {@link QolSetting} 里了，
     * 在这里再抄一遍字段就是三处维护同一件事。以后加服务器级功能只要往枚举里加一项。
     */
    private static final Map<QolSetting, ForgeConfigSpec.IntValue> QOL_SERVER = new EnumMap<>(QolSetting.class);

    /**
     * 建筑手杖（对齐 Construction Wand KOTS 的 ConfigServer）。
     * <p>
     * 这些量控制手杖一次能作用多大一片、能伸多远、哪些带方块实体的方块不许碰、
     * 以及 match=SIMILAR 时哪些方块算同一种。默认值与参考实现保持一致，
     * 只有两个例外写在注释里（放置上限与破坏上限按用户要求取 1024）。
     */
    private static final ForgeConfigSpec.IntValue WAND_PLACE_LIMIT;
    private static final ForgeConfigSpec.IntValue WAND_DESTROY_LIMIT;
    private static final ForgeConfigSpec.IntValue WAND_ANGEL_DISTANCE;
    private static final ForgeConfigSpec.BooleanValue WAND_ANGEL_FALLING;
    private static final ForgeConfigSpec.IntValue WAND_CREATIVE_LIMIT;
    private static final ForgeConfigSpec.IntValue WAND_MAX_RANGE;
    private static final ForgeConfigSpec.BooleanValue WAND_TE_WHITELIST;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> WAND_TE_LIST;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> WAND_SIMILAR_BLOCKS;

    // ===== 注入（替代原「管理员剑」的获取方式）=====
    private static final ForgeConfigSpec.BooleanValue INJECTION_ENABLED;
    private static final ForgeConfigSpec.IntValue INJECTION_DURATION_TICKS;
    private static final ForgeConfigSpec.BooleanValue INJECTION_REQUIRE_AUTHORIZATION;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> INJECTION_BLACKLIST;

    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");

    /** 同一玩家两次尝试之间的最小间隔（毫秒），阻断脚本化高速爆破。 */
    private static final long MIN_ATTEMPT_INTERVAL_MILLIS = 2000L;
    /** 滑动窗口内允许的失败次数，超过则本窗口内直接拒绝。 */
    private static final int MAX_FAILURES_PER_WINDOW = 5;
    private static final long FAILURE_WINDOW_MILLIS = 300_000L;

    /** 进程内的尝试记录：重启即清空，够用且不写盘。 */
    private static final Map<UUID, Attempts> ATTEMPTS = new ConcurrentHashMap<>();
    /** 默认密码告警每个服务器会话只打一次。 */
    private static volatile boolean warnedAboutDefaultPassword = false;

    /** 单个玩家的密码尝试状态。 */
    private static final class Attempts {
        private long lastAttempt;
        private long windowStart;
        private int failures;
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("admin");
        ADMIN_PASSWORD = builder
                .comment(" 管理员密码。玩家在游戏内通过该密码验证后即可使用战术 HUD 与管理员剑。",
                        " 默认值是公开的，请务必改成你自己的密码。",
                        " 留空表示关闭密码验证，任何人都无法通过密码取得管理员权限。")
                .define("password", DEFAULT_ADMIN_PASSWORD);
        builder.pop();
        builder.push("qol");
        QOL_VILLAGE_HERO_MAX_LEVEL = builder
                .comment(" 万民敬仰（88）个人等级的服务器上限。",
                        " 面板里玩家最多能把自己调到多少级；施加的 amplifier = 等级 × 4 - 1。",
                        " 设为 0 等于全服禁用这一项。")
                .defineInRange("villageHeroMaxLevel", 5, 0, 255);
        QOL_REACH_MAX_BLOCKS = builder
                .comment(" 长臂善舞（89）交互距离加成的服务器上限（单位：格）。",
                        " 同时作用于方块交互距离与实体交互距离。",
                        " 默认给到 64（本项上限）：这是管理员工具，不是平衡性调整，",
                        " 卡在 8 格只会让人每次都得回来改一次。",
                        " 设为 0 等于全服禁用这一项。")
                .defineInRange("reachMaxBlocks", 64, 0, 64);
        // 服务器级设定项：本项自己就是"值"，同时只有 OP 能在面板里改。
        // 键名与取值范围都从 QolSetting 取，免得这里和枚举各写一份范围、改一处漏一处。
        for (QolSetting setting : QolSetting.values()) {
            if (setting.scope() != QolSetting.Scope.SERVER) {
                continue;
            }
            QOL_SERVER.put(setting, builder
                    .comment(serverSettingComment(setting))
                    .defineInRange(setting.configKey(), setting.defaultValue(),
                            setting.min(), setting.hardMax()));
        }
        builder.pop();
        builder.push("wand");
        WAND_PLACE_LIMIT = builder
                .comment(" 建筑手杖一次最多放置多少格。",
                        " 参考实现的无限手杖是 1024，这里默认沿用同一口径。")
                .defineInRange("placeLimit", 1024, 1, 4096);
        WAND_DESTROY_LIMIT = builder
                .comment(" 破坏核心一次最多破坏多少格。",
                        " 设为 0 等于关闭破坏核心（对齐参考实现的 destruction=0）。")
                .defineInRange("destroyLimit", 1024, 0, 4096);
        WAND_ANGEL_DISTANCE = builder
                .comment(" 天使核心最多能往方块背面穿透几格去找落脚点。",
                        " 对齐参考实现每根手杖各自的 angel 值（0 = 关闭天使核心）。")
                .defineInRange("angelDistance", 16, 0, 128);
        WAND_ANGEL_FALLING = builder
                .comment(" true = 下落超过 10 格时也允许天使核心往脚下放方块。",
                        " 参考实现默认关闭（false），打开等于允许拿它从虚空里自救。")
                .define("angelFalling", false);
        WAND_CREATIVE_LIMIT = builder
                .comment(" 创造模式下建筑手杖的放置上限。",
                        " 对齐参考实现的 InfinityWandCreative（默认 2048）。")
                .defineInRange("creativePlaceLimit", 2048, 1, 8192);
        WAND_MAX_RANGE = builder
                .comment(" 放置与破坏的最大距离（格）。0 = 不限。",
                        " 参考实现里这一项是防卡顿用的，不是平衡性调整。")
                .defineInRange("maxRange", 100, 0, 512);
        WAND_TE_LIST = builder
                .comment(" 带方块实体的方块名单：可以写方块 id（minecraft:chest），也可以写模组 id（minecraft）。",
                        " 与 teWhitelist 配合，决定这些方块能不能被手杖放置或破坏。")
                .defineList("teList", List.of("chiselsandbits"), value -> value instanceof String);
        WAND_TE_WHITELIST = builder
                .comment(" true = 上面的名单是白名单（只有名单里的允许），false = 黑名单（名单里的禁止）。")
                .define("teWhitelist", false);
        WAND_SIMILAR_BLOCKS = builder
                .comment(" match=SIMILAR 时视为同种的方块组。一组写一行，组内用分号隔开，例如：",
                        " minecraft:dirt;minecraft:grass_block;minecraft:coarse_dirt")
                .defineList("similarBlocks", List.of(
                                "minecraft:dirt;minecraft:grass_block;minecraft:coarse_dirt;minecraft:podzol;"
                                        + "minecraft:mycelium;minecraft:farmland;minecraft:dirt_path;minecraft:rooted_dirt"),
                        value -> value instanceof String);
        builder.pop();
        builder.push("injection");
        INJECTION_ENABLED = builder
                .comment(" 是否允许「注入」：拿着任意物品按注入键，进度条走完后该物品获得管理员能力。",
                        " false = 完全关闭这个机制（键位仍在，按下无反应）。")
                .define("enabled", true);
        INJECTION_DURATION_TICKS = builder
                .comment(" 注入需要多少 tick（20 tick = 1 秒）。",
                        " 默认 60 = 3 秒。移动 / 受伤 / 换手 / 物品变化都会中断并重来。")
                .defineInRange("durationTicks", 60, 1, 200);
        INJECTION_REQUIRE_AUTHORIZATION = builder
                .comment(" true = 只有通过管理员密码验证的玩家能注入（默认，与原管理员剑一致）。",
                        " false = 任何玩家都能注入 —— 等于把管理员能力完全放开，谨慎修改。")
                .define("requireAuthorization", true);
        INJECTION_BLACKLIST = builder
                .comment(" 不允许被注入的物品。",
                        " 可以写完整物品 id（minecraft:bedrock），也可以写模组 id（createdeco）一次封掉整个模组。")
                .defineList("blacklist", List.<String>of(), value -> value instanceof String);
        builder.pop();
        COMMON_SPEC = builder.build();
    }

    /** 服务器级项在 toml 里的说明。写中文是因为服主看到的就是这个文件。 */
    private static String[] serverSettingComment(QolSetting setting) {
        return switch (setting) {
            // 经验飞涨（72）原来在这里有一项倍率配置，改成「每 tick 加一级」之后它变成
            // 个人级开关（存在玩家存档里），不再是服务器级项，所以这里没有它的分支。
            case ANVIL_UNBREAKABLE -> new String[]{
                    " 不朽铭文（77）：铁砧里放两个相同物品，合出【无法破坏】的成品。",
                    " 1 = 开启，0 = 关闭。"};
            case ANVIL_XP_COST -> new String[]{
                    " 不朽铭文（77）的经验成本：上面那个开关生效时，铁砧收多少级经验。",
                    " 设得再低也不会是 0 —— 白送的经验成本会让创造模式以外也用不出代价。"};
            case MACHINE_LOOT -> new String[]{
                    " 机器共鸣（119）：假玩家（模拟玩家机器）击杀生物时的掉落倍率。1 = 原版。",
                    " 弱兼容：只认 NeoForge 的 FakePlayer，自己实现假玩家类的机器不受影响。"};
            case TREASURE_HUNTER_SERVER -> new String[]{
                    " 寻宝大师（96）服务器总闸：0 = 全服禁用（个人开关也会失效），1 = 允许。",
                    " 它会给玩家画出周围容器的高亮框，很容易被当成透视；",
                    " 默认开启（本工程的用法就是管理员自己用），不想让它出现就设 0。"};
            case TREASURE_HUNTER_RADIUS -> new String[]{
                    " 寻宝大师（96）的扫描半径（格，8~64）。",
                    " 半径直接决定客户端每 20 tick 的扫描开销，别设得比实际视野大太多。"};
            case ZONE_EXCAVATE_SERVER -> new String[]{
                    " 选区挖掘（127）服务器总闸：0 = 全服禁用，1 = 允许。",
                    " 这一项会让授权玩家框选一块区域一次性挖空，地形不可逆；",
                    " 默认开启（本工程的用法就是管理员自己用），不想让它在服务器上出现就设 0。"};
            case ZONE_MAX_SIDE -> new String[]{
                    " 选区作业（127）的单边上限（格，1~512）。",
                    " 512 是立方级的量：512×512 的一层就是 26 万方块，跨整个高度则是 1 亿格。",
                    " 作业按 zoneTickBudgetMs 分批，不会因此掉 TPS，但总时长会非常久 ——",
                    " 调大之前想清楚「要挖多久」，而不是「能不能挖」。"};
            case ZONE_DROP_POLICY -> new String[]{
                    " 选区挖掘（127）的掉落策略：0 = 进玩家背包、1 = 生成掉落物、2 = 直接销毁、",
                    " 3 = 优先进附近的 AE2 ME 网络（塞不下的部分再进背包，没装 AE2 时与 0 相同）。",
                    " 默认 0：本工程没有容器绑定系统，几十万方块掉一地既卡服又难捡。"};
            case ZONE_TICK_BUDGET -> new String[]{
                    " 选区作业（127）每 tick 的时间预算（毫秒，10~200）。",
                    " 这是「用 TPS 换速度」的旋钮：预算 50 就是每 tick 挖 50 毫秒，",
                    " 服务器在这段时间里只有 10 TPS（每秒 10 帧）。",
                    " 上限 200（约 5 TPS）是刻意留的：单机自己挖地时，卡三分钟挖完比流畅挖十五分钟合算。"};
            case ZONE_BATCH_SIDE -> new String[]{
                    " 选区作业（127）单批处理的目标方块数（32~8192）。",
                    " 这个值只是防跑飞的副保险，真正的限速器是 zoneTickBudgetMs：",
                    " 循环是「处理满这么多格 或 用完时间预算，谁先到算谁」，批边长偏小就会把预算架空。"};
            case ZONE_UNDO_MAX -> new String[]{
                    " 选区挖掘（127）最多记录多少方块的撤销快照；0 = 禁用撤销。",
                    " 超过上限时不会记一半，而是明确提示「这次挖完撤不回来」—— 半个撤销比没有更危险。"};
            default -> new String[]{" 功能面板的服务器级设定项：" + setting.name()};
        };
    }

    /** 万民敬仰的服务器上限等级。配置未加载时回落到 0（等于禁用），不放开权限。 */
    public static int qolVillageHeroMaxLevel() {
        return intOrDefault(QOL_VILLAGE_HERO_MAX_LEVEL, 0);
    }

    /** 长臂善舞的服务器上限格数。配置未加载时回落到 0（等于禁用），不放开权限。 */
    public static int qolReachMaxBlocks() {
        return intOrDefault(QOL_REACH_MAX_BLOCKS, 0);
    }

    /**
     * 读一个服务器级设定项的当前值。
     * <p>
     * 非服务器级项、或配置还没加载时，回落到枚举里的默认值 —— 那是这一项最保守的取值，
     * 不会因为"读不到配置"就意外开启某个功能。
     */
    public static int qolServerValue(QolSetting setting) {
        ForgeConfigSpec.IntValue value = QOL_SERVER.get(setting);
        return value == null ? setting.defaultValue() : intOrDefault(value, setting.defaultValue());
    }

    /**
     * 写一个服务器级设定项并落盘。
     * <p>
     * 面板改这一项走的就是这里，所以 toml 与面板永远一致：服主手改文件、面板读到的就是手改后的值；
     * 面板改完，服主打开文件看到的也是新值。写盘失败只记日志不抛异常 ——
     * 内存里的值已经生效，没必要因为磁盘问题把玩家的操作整个打断。
     */
    public static void setQolServerValue(QolSetting setting, int newValue) {
        ForgeConfigSpec.IntValue value = QOL_SERVER.get(setting);
        if (value == null) {
            return;
        }
        value.set(newValue);
        try {
            COMMON_SPEC.save();
        } catch (Exception saveFailed) {
            LOGGER.warn("[Kunjinkao] 功能面板的服务器级设置写盘失败（内存中已生效）：{}", saveFailed.toString());
        }
    }

    /**
     * 读一个 IntValue，未加载时回落到保守值。
     * <p>
     * 与 {@link #configuredPassword()} 同一个理由：配置还没加载时不能猜一个"宽松"的默认值，
     * 否则启动早期的一瞬间面板能给出比服主配置更大的上限。
     */
    private static int intOrDefault(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    // ===================== 建筑手杖 =====================

    /** 建筑手杖单次放置上限。 */
    public static int wandPlaceLimit() {
        return intOrDefault(WAND_PLACE_LIMIT, 1024);
    }

    /** 破坏核心单次破坏上限。0 表示关闭破坏核心。 */
    public static int wandDestroyLimit() {
        return intOrDefault(WAND_DESTROY_LIMIT, 1024);
    }

    /** 天使核心的穿透距离。0 表示关闭天使核心。 */
    public static int wandAngelDistance() {
        return intOrDefault(WAND_ANGEL_DISTANCE, 16);
    }

    /** 下落中是否允许天使核心往脚下放方块。 */
    public static boolean wandAngelFalling() {
        try {
            return WAND_ANGEL_FALLING.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** 创造模式下的放置上限。 */
    public static int wandCreativeLimit() {
        return intOrDefault(WAND_CREATIVE_LIMIT, 2048);
    }

    /** 手杖的最大作用距离，0 表示不限。 */
    public static int wandMaxRange() {
        return intOrDefault(WAND_MAX_RANGE, 100);
    }

    /** 方块实体名单是不是白名单。 */
    public static boolean wandTeWhitelist() {
        try {
            return WAND_TE_WHITELIST.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** 方块实体名单（方块 id 或模组 id）。 */
    public static List<? extends String> wandTeList() {
        try {
            return WAND_TE_LIST.get();
        } catch (IllegalStateException notLoadedYet) {
            return List.of();
        }
    }

    /** 同类方块组：一组一行，组内用分号隔开。 */
    public static List<? extends String> wandSimilarBlocks() {
        try {
            return WAND_SIMILAR_BLOCKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return List.of();
        }
    }

    /** 注入机制是否开启。 */
    public static boolean injectionEnabled() {
        try {
            return INJECTION_ENABLED.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** 一次注入要多少 tick。 */
    public static int injectionDurationTicks() {
        try {
            return INJECTION_DURATION_TICKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return 60;
        }
    }

    /** 注入是否要求管理员授权。 */
    public static boolean injectionRequiresAuthorization() {
        try {
            return INJECTION_REQUIRE_AUTHORIZATION.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** 禁止注入的物品名单（物品 id 或模组 id）。 */
    public static List<? extends String> injectionBlacklist() {
        try {
            return INJECTION_BLACKLIST.get();
        } catch (IllegalStateException notLoadedYet) {
            return List.of();
        }
    }

    private AdminToolConfig() {
    }

    public static boolean isAuthorized(UUID playerUuid) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && PasswordAdminSavedData.get(server).isAuthorized(playerUuid);
    }

    /** 只应由服务端网络包处理器调用；绝不信任客户端的“已验证”状态。 */
    public static boolean authorize(ServerPlayer player, String submittedPassword) {
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        Attempts attempts = ATTEMPTS.computeIfAbsent(uuid, key -> new Attempts());
        synchronized (attempts) {
            // 频率限制：默认密码只有 6 位数字，不设限的话单个连接就能穷举整个空间。
            if (now - attempts.lastAttempt < MIN_ATTEMPT_INTERVAL_MILLIS) {
                return false;
            }
            attempts.lastAttempt = now;
            if (now - attempts.windowStart > FAILURE_WINDOW_MILLIS) {
                attempts.windowStart = now;
                attempts.failures = 0;
            }
            if (attempts.failures >= MAX_FAILURES_PER_WINDOW) {
                LOGGER.warn("[Kunjinkao] 玩家 {} 的密码尝试过于频繁，本窗口内已拒绝", player.getName().getString());
                return false;
            }
            if (!verify(submittedPassword)) {
                attempts.failures++;
                LOGGER.warn("[Kunjinkao] 玩家 {} 的管理员密码验证失败（本窗口第 {} 次）",
                        player.getName().getString(), attempts.failures);
                return false;
            }
            attempts.failures = 0;
            PasswordAdminSavedData.get(player.server).authorize(uuid);
            LOGGER.info("[Kunjinkao] 玩家 {} 通过密码验证，已获得管理员权限", player.getName().getString());
            return true;
        }
    }

    /** 纯密码比对，不改变任何状态（限流通过后才调用）。 */
    private static boolean verify(String submittedPassword) {
        if (submittedPassword == null || submittedPassword.length() > 64) {
            return false;
        }
        String configured = configuredPassword();
        // 未配置密码（配置缺失或留空）时一律拒绝，避免空密码被用来提权。
        if (configured.isEmpty()) {
            return false;
        }
        warnIfDefaultPassword(configured);
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] submitted = submittedPassword.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, submitted);
    }

    /**
     * 默认密码是公开写在源码里的：服务器没改它，等于对外开放管理员权限，至少在日志里吼一声。
     * 每个服务器会话只提醒一次，且不打印密码本身。
     */
    private static void warnIfDefaultPassword(String configured) {
        if (warnedAboutDefaultPassword || !DEFAULT_ADMIN_PASSWORD.equals(configured)) {
            return;
        }
        warnedAboutDefaultPassword = true;
        LOGGER.warn("[Kunjinkao] 管理员密码仍是源码中的默认值（已公开），任何玩家都能凭它取得管理员权限；"
                + "请修改 config/kunjinkao-admin.toml 的 admin.password");
    }

    /** 配置未加载时返回空串，使授权失败而不是回退到默认密码。 */
    private static String configuredPassword() {
        try {
            String value = ADMIN_PASSWORD.get();
            return value == null ? "" : value;
        } catch (IllegalStateException notLoadedYet) {
            return "";
        }
    }
}
