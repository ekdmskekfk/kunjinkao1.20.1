package dev.modmind.kunjinkao.qol;

import java.util.Locale;

/**
 * 功能面板的设定项总表：一项一行，服务端与客户端共用同一份定义。
 * <p>
 * 放在这里而不是各写一份，是因为"这一项叫什么、取值范围多少、谁能改"必须两边一致 ——
 * 客户端拿它画数值条，服务端拿它校验客户端发来的值。
 * <p>
 * <b>本类不得触碰任何 {@code net.minecraft.client} 类型</b>：服务端也要加载它。
 */
public enum QolSetting {

    /** 80 星瞳夜视：常驻夜视，短时长高频续期。与战术 HUD 的夜视开关是同一份状态。 */
    NIGHT_VISION(0, 80, Category.SURVIVAL, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 83 凌空采掘：空中（可选水下）挖掘不再吃原版的处境惩罚。 */
    FLY_MINING(1, 83, Category.CONVENIENCE, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 85 鲛人之息：水下氧气永远满。 */
    WATER_BREATH(2, 85, Category.SURVIVAL, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 88 万民敬仰：常驻村庄英雄，等级可调，上限由服务器配置压。 */
    VILLAGE_HERO(3, 88, Category.INCOME, Scope.PERSONAL, Kind.NUMBER, 0, 255, 0, ""),
    /** 89 长臂善舞：方块交互与实体交互距离各 +N 格，上限由服务器配置压。 */
    REACH(4, 89, Category.CONVENIENCE, Scope.PERSONAL, Kind.NUMBER, 0, 64, 0, ""),

    /**
     * 72 经验飞涨：开着的时候，<b>每一 tick 直接加一个经验等级</b>（每秒 20 级）。
     * <p>
     * 它原来是"击杀经验乘一个服务器级倍率"，改成逐 tick 给等级之后，性质变了：
     * 不再是"改变掉落规则"，而是"挂在玩家身上的被动效果"，所以<b>作用域也从服务器级改成个人级</b>。
     * 单机玩时两者表现一样，但多人服上原来的写法会让全服一起刷级 ——
     * 那不是"经验飞涨"，那是全服事件。
     * <p>
     * 没有倍率参数可调了：这个效果的强度就是"每 tick 一级"，给个倍率只是把同一件事换个说法。
     */
    XP_BOOST(5, 72, Category.INCOME, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 77 不朽铭文：铁砧里两个相同物品合出【无法破坏】。 */
    ANVIL_UNBREAKABLE(6, 77, Category.INCOME, Scope.SERVER, Kind.TOGGLE, 0, 1, 0, "anvilUnbreakableEnabled"),
    /** 77 的成本：上面那个开关生效时，铁砧收多少级经验。 */
    ANVIL_XP_COST(7, 77, Category.INCOME, Scope.SERVER, Kind.NUMBER, 1, 100, 10, "anvilXpCost"),

    /** 84 烈焰不侵：免疫火焰与岩浆伤害、不着火。 */
    FIRE_IMMUNE(8, 84, Category.SURVIVAL, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 94 无限交易：交易次数不减少、永不售罄。 */
    UNLIMITED_TRADES(9, 94, Category.INCOME, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 95 村民大师：交易一次该村民直接满级并刷新交易表。 */
    VILLAGER_MASTER(10, 95, Category.INCOME, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /**
     * 70 万载不磨：物品不再掉耐久。
     * <p>
     * 开发计划原本把这一项的开关放在<b>剑的设置界面</b>。这里放进了面板：
     * 计划的横切目标就是"一个入口看全 15 项"，而 70 要的效果是"<b>玩家</b>的物品不掉耐久"，
     * 不是"手上那把剑的能力" —— 按持剑判定会让玩家一换主手就时灵时不灵。
     * 83 凌空采掘在 M1 已经按同样的理由放进了面板，两项保持一致。
     */
    DURABILITY_PROTECTION(11, 70, Category.CONVENIENCE, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /**
     * 119 战利品大爆发·机器共鸣：假玩家（模拟玩家机器）击杀时也吃掉落倍率。
     * <p>
     * <b>只有服务器级，没有个人开关。</b> 开发计划 5.9 写了"服务器倍率（OP）+ 个人开关"，
     * 但假玩家身上没有可靠的"属于哪个玩家"的凭据 —— 各模组机器的实现方式差异很大，
     * 有的用固定 GameProfile、有的每台机器一个。做一个绑不上人的个人开关，
     * 只会变成"点了没反应"的按钮。等真的遇到需要按玩家区分的场景再说。
     * <p>
     * <b>弱兼容</b>：只认 NeoForge 的 {@code FakePlayer}，自己实现假玩家类的机器不受影响。
     */
    MACHINE_LOOT(12, 119, Category.ENGINEERING, Scope.SERVER, Kind.NUMBER, 1, 100, 1, "machineLootMultiplier"),
    /**
     * 87 碧波清眸：水下与岩浆里把雾推开。
     * <p>
     * 纯客户端表现，服务端只负责存开关。默认关闭：它与光影（Iris/Oculus）大概率冲突，
     * 而画面类的问题最容易被误当成"模组坏了"，所以让玩家自己决定要不要开。
     */
    FOG_VISION(13, 87, Category.SURVIVAL, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /**
     * 96 寻宝大师·服务器总闸。
     * <p>
     * 这一项存在的意义是<b>让服主能一票否决</b>：透视类功能在服务器上很容易被当成作弊，
     * 没有总闸的话，只要玩家自己开了个人开关就能用，服主只能去删模组。
     * 总闸关闭时客户端不会扫描，个人开关点了也不会有任何反应。
     * <p>
     * 默认<b>开启</b>：本工程的用法就是管理员自己开，不想让它在服务器上出现的话设 0 即可。
     */
    TREASURE_HUNTER_SERVER(14, 96, Category.INCOME, Scope.SERVER, Kind.TOGGLE, 0, 1, 1, "treasureHunterEnabled"),
    /** 96 的扫描半径（格）。服务器级：半径直接决定性能开销与"能看到多远"，不该由玩家自己定。 */
    TREASURE_HUNTER_RADIUS(15, 96, Category.INCOME, Scope.SERVER, Kind.NUMBER, 8, 64, 64, "treasureHunterRadius"),
    /** 96 的个人开关：只有它还要求服务端总闸开着才生效。 */
    TREASURE_HUNTER(16, 96, Category.INCOME, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /**
     * 127 机关拆解·选区挖掘的服务器总闸。
     * <p>
     * 这一项会<b>不可逆地改变地形</b>，所以只有通过管理员密码验证的人才能用；
     * 总闸本身默认<b>开启</b>（服主按需要关掉即可），所有参数都是服务器级 ——
     * 由服主决定这台服务器上允许挖多大、挖出来的东西去哪，而不是让玩家自己放开上限。
     */
    ZONE_EXCAVATE_SERVER(17, 127, Category.ENGINEERING, Scope.SERVER, Kind.TOGGLE, 0, 1, 1, "zoneExcavateEnabled"),
    /**
     * 127 的单边上限（格）。
     * <p>
     * <b>512 是立方级的量。</b> 64 → 512 是单边 8 倍、方块数 512 倍：
     * 512×512 的一层就是 26 万方块，若两个角点还跨了整个高度（384 格），
     * 总量是 1 亿格。作业本身不会因此掉 TPS —— 它按 {@code zoneTickBudgetMs} 分批，
     * 无论多大都只吃那么多时间；代价是<b>总时长</b>：1 亿格在 45 ms/tick 的预算下要跑很久。
     * 执行前的确认提示会把总格数报出来，框完先看一眼那个数字再按第二下。
     */
    ZONE_MAX_SIDE(18, 127, Category.ENGINEERING, Scope.SERVER, Kind.NUMBER, 1, 512, 512, "zoneMaxSide"),
    /**
     * 127 的掉落策略：0 = 进玩家背包、1 = 生成掉落物、2 = 直接销毁、3 = 优先进 AE 网络。
     * <p>
     * 默认进背包：本工程没有「绑定容器」那套系统，掉一地的东西在 64×64 的作业量下
     * 既卡服又难捡，直接进背包最省事。想留下「挖矿感」的服主可以改成 1。
     * <p>
     * 3 是 0 的加强版而不是第四种互斥去向：先往玩家附近的 ME 网络里塞，
     * 塞不下的那部分再按 0 的方式进背包。于是没装 AE2 的服务器上这一项与 0 表现完全一致，
     * 服主不必为「有没有 AE2」准备两套配置。
     */
    ZONE_DROP_POLICY(19, 127, Category.ENGINEERING, Scope.SERVER, Kind.CHOICE, 0, 3, 0, "zoneDropPolicy"),
    /**
     * 127 每 tick 的时间预算（毫秒）。作业按它分批，保证 MSPT 不被一次挖穿地形拉爆。
     * <p>
     * <b>这是"用 TPS 换速度"的那个旋钮。</b> 预算 50 ms 就是"每 tick 花 50 毫秒挖"，
     * 服务器在这一段时间里只有 10 TPS —— 换算过来就是"每秒只跑 10 帧"。
     * 上限给到 200（约 5 TPS）是刻意留出来的：单机自己挖地时，
     * "卡 3 分钟挖完" 比 "流畅地挖 15 分钟" 更合算。
     * <p>
     * 先靠降单格成本（去掉邻居更新、跳过不需要的掉落计算）拿速度，
     * 那个不花 TPS；实在还嫌慢再来调这里。
     */
    ZONE_TICK_BUDGET(20, 127, Category.ENGINEERING, Scope.SERVER, Kind.NUMBER, 10, 200, 45, "zoneTickBudgetMs"),
    /**
     * 127 单批处理的目标方块数。
     * <p>
     * <b>这个值应当只是"防跑飞"的副保险，真正的限速器是 {@link #ZONE_TICK_BUDGET}。</b>
     * 作业循环是「处理满这么多格 <b>或</b> 用完时间预算，谁先到算谁」，
     * 所以批边长一旦偏小，它就会变成实际瓶颈、把时间预算架空。
     * <p>
     * 早先的上限是 512、默认 256，配 45 ms 的预算：512 格只要约 1 ms，
     * 预算永远轮不到说话 —— 速度被钉死在 512 格/tick。
     * 单边上限提到 512 之后这会变得很刺眼（跨全高的一亿格要跑几小时），
     * 所以上限放大到 8192、默认 2048，让预算成为真正的约束。
     * <p>
     * 调大是安全的：循环每 64 格查一次时间，超预算立刻退出，
     * 所以"一批太大"最多让单 tick 多跑几十格，不会把 MSPT 顶穿。
     */
    ZONE_BATCH_SIDE(21, 127, Category.ENGINEERING, Scope.SERVER, Kind.NUMBER, 32, 8192, 2048, "zoneBatchSide"),
    /**
     * 127 允许记录的撤销方块数上限；0 = 完全禁用撤销。
     * <p>
     * 超过这个数就不记快照，并在开始时明确告诉玩家"这次挖完撤不回来"。
     * 与其记一半导致撤销出一个残缺地形，不如一开始就说清。
     */
    ZONE_UNDO_MAX(22, 127, Category.ENGINEERING, Scope.SERVER, Kind.NUMBER, 0, 100000, 20000, "zoneUndoMaxBlocks"),
    /**
     * 127 选区作业·破坏：按执行键把选区挖空。
     * <p>
     * <b>破坏与放置拆成两行、各绑一个键</b>，而不是一行里切换模式再共用一个执行键。
     * 理由是这一步没有回头路：共用一个键时"现在到底是挖还是填"取决于上次滚轮停在哪，
     * 而滚轮很容易被误碰 —— 想填一面墙结果把墙挖了，代价太大。
     * 两个键各管一件事，按哪个就是哪个，不存在"当前模式"这个中间状态。
     * <p>
     * 破坏<b>不给主手物品扣耐久</b>，见 {@code ZoneExcavationJob} 里的说明：
     * 一次作业可能几十万方块，按格扣耐久等于必然毁掉工具。
     */
    ZONE_BREAK(23, 127, Category.ENGINEERING, Scope.PERSONAL, Kind.ACTION, 0, 1, 0, ""),
    /** 127 选区作业·放置：按执行键把选区里的空位用副手方块填上。 */
    ZONE_PLACE(24, 127, Category.ENGINEERING, Scope.PERSONAL, Kind.ACTION, 0, 1, 0, ""),

    // ===== 管理员装饰（G 面板的「装饰」页）=====
    // 三项都是个人级开关，但服务端在生效前还会再查一次"这个人是不是通过验证的管理员"
    // （见 KunJinKaoQolHandler 里的装饰判定）：装饰是管理员身份的展示，
    // 不该让任何玩家点开开关就给自己戴上。
    //
    // 序号沿用"给人看的功能号"。装饰这三项不在原开发计划里，所以从 201 起另起一段，
    // 免得和 70-127 那些真正对应计划条目的号码混在一起。
    /** 201 乱码称号：头顶名牌上方另浮一块乱码称号牌，所有玩家都看得见。 */
    COSMETIC_TITLE(25, 201, Category.COSMETIC, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 202 浮游挂件：身后三把剑（所有人看得见），被玩家攻击时其中一把飞去他头顶向下刺。 */
    COSMETIC_ORBIT(26, 202, Category.COSMETIC, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /** 203 光环与拖尾：脚下一圈淡蓝光环，移动时拖出一条渐隐的淡蓝尾迹。 */
    COSMETIC_AURA(27, 203, Category.COSMETIC, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, ""),
    /**
     * 204 屹立不倒：不会被推动、击退、弹飞（自己按方向键移动不受影响），也不会被传送到其他维度。
     * <p>
     * 生效分两半：客户端拒绝"不是自己产生的位移"（见 {@code StandFirmClient}），
     * 服务端取消击退与换维度（见 {@code KunJinKaoStandFirmHandler}）。
     */
    STAND_FIRM(28, 204, Category.SURVIVAL, Scope.PERSONAL, Kind.TOGGLE, 0, 1, 0, "");

    /**
     * 面板顶部的分类标签。
     * <p>
     * 顺序就是标签条上的显示顺序（照开发计划 5.2 节）。{@link #SERVER} 比较特殊：
     * 它不是"另一批项"，而是把各分类里 {@code scope == SERVER} 的行<b>再集中列一遍</b>，
     * 方便管理员一屏定位到所有会写进 toml 的开关（见 {@link #inCategory}）。
     */
    public enum Category {
        SURVIVAL("survival"),
        INCOME("income"),
        CONVENIENCE("convenience"),
        ENGINEERING("engineering"),
        /** 管理员装饰：称号、浮游挂件、光环拖尾。 */
        COSMETIC("cosmetic"),
        SERVER("server");

        private final String key;

        Category(String key) {
            this.key = key;
        }

        public String translationKey() {
            return "screen.kunjinkao.qol.category." + key;
        }
    }

    /** 作用域：个人级改动写进玩家存档，服务器级改动写回 toml 且只有 OP 能动。 */
    public enum Scope {
        PERSONAL,
        SERVER
    }

    /**
     * 交互形态。
     * <ul>
     *   <li>{@link #TOGGLE}：0/1 开关，点一下切换。</li>
     *   <li>{@link #NUMBER}：有取值区间的数值项，滚轮调。</li>
     *   <li>{@link #CHOICE}：少数几个有名字的取值。当前没有项在用，保留给以后。</li>
     *   <li>{@link #ACTION}：没有"值"，只有一个执行动作，靠行尾绑定的执行键触发。</li>
     * </ul>
     * CHOICE 的<b>取值范围、校验、上限</b>与 NUMBER 完全共用同一套（见 {@link QolSettings#maxOf}）——
     * 区别只在面板怎么呈现。
     */
    public enum Kind {
        TOGGLE,
        NUMBER,
        CHOICE,
        ACTION;

        /**
         * 是不是"有取值范围"的项。
         * <p>
         * 抽成一个判断而不是各处写 {@code kind == NUMBER || kind == CHOICE}：
         * 将来再加一种带范围的形态时，只改这里一处，不会漏掉某个面板分支。
         * <p>
         * <b>ACTION 不算有范围</b>：它没有值可调，给它一条数值条只会让玩家以为能滚。
         */
        public boolean hasRange() {
            return this == NUMBER || this == CHOICE;
        }
    }

    /** 设定项总数，用于遍历与数组定长。 */
    public static final int COUNT = values().length;

    private final int id;
    private final int code;
    private final Category category;
    private final Scope scope;
    private final Kind kind;
    private final int min;
    private final int hardMax;
    private final int defaultValue;
    private final String configKey;

    QolSetting(int id, int code, Category category, Scope scope, Kind kind,
               int min, int hardMax, int defaultValue, String configKey) {
        this.id = id;
        this.code = code;
        this.category = category;
        this.scope = scope;
        this.kind = kind;
        this.min = min;
        this.hardMax = hardMax;
        this.defaultValue = defaultValue;
        this.configKey = configKey;
    }

    /** 线格式里的判别 id。只能往后追加，不能改已有取值。 */
    public int id() {
        return id;
    }

    /**
     * 原始开发计划里的功能序号（72 / 77 / 80 / 84 …）。
     * <p>
     * 只用于面板上的序号徽章与搜索。与线格式用的 {@link #id()} 故意分开：
     * 序号是给人看的，动了不影响兼容；线格式 id 动了会让新旧端对不上。
     * <b>两项允许共用同一个序号</b>：77 是"开关 + 成本"两行，它们本来就是同一个功能。
     */
    public int code() {
        return code;
    }

    /** 主要归属分类。服务器级项同时还会出现在 {@link Category#SERVER} 那个汇总标签下。 */
    public Category category() {
        return category;
    }

    public Scope scope() {
        return scope;
    }

    public Kind kind() {
        return kind;
    }

    public int min() {
        return min;
    }

    /**
     * 本项自身的硬上限。真正生效的上限还要再被服务器配置压一次
     * （见 {@link QolSettings#maxOf}）—— 这里只是"再怎么配也不能超"的那条线。
     */
    public int hardMax() {
        return hardMax;
    }

    public int defaultValue() {
        return defaultValue;
    }

    /**
     * 服务器级项在 {@code kunjinkao-admin.toml} 的 {@code [qol]} 里的键名；个人级项为空串。
     * <p>
     * 写死在这里而不是按枚举名推导：toml 键是服主会看到、会写进启动脚本的东西，
     * 改个枚举名就悄悄换掉配置键，服主的旧配置文件会一声不响地失效。
     */
    public String configKey() {
        return configKey;
    }

    /** 名称的翻译键。按枚举名生成，加一项就自动多一个键，不用回来改这里。 */
    public String translationKey() {
        return "screen.kunjinkao.qol." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * 这一项在面板上是否还带一个「执行键」格（按一下对当前选区开工）。
     * <p>
     * 写成一个方法、而不是在面板与快捷键里各写一次 {@code setting.kind() == ACTION}：
     * 将来再出现"一次性动作"类的项时只改这一处，也不会出现
     * "面板画了格子、按键却不响应"这种两边各判一次才会有的不一致。
     */
    public boolean hasExecuteAction() {
        return kind == Kind.ACTION;
    }

    /**
     * 这一项要不要出现在 G 键面板里。
     * <p>
     * 默认规则是<b>服务器级项不上面板</b>：它们只活在 {@code config/kunjinkao-admin.toml} 的
     * {@code [qol]} 分节里。理由是两种东西混在一张列表里看着像同一种 ——
     * 面板上的行点一下就改，而那些行改了是写进服务器配置、影响所有人的，
     * 放在一起玩家会以为"我关了寻宝大师"只是关自己的。
     * <p>
     * <b>但有三项例外，见 {@link #SERVER_ROWS_ON_PANEL}。</b> 它们都是"服主自己会边玩边调"的
     * 旋钮，每次改都要退出去编辑文件、再回来重载，代价明显大于"混在一张表里"的那点混淆风险。
     * 例外是白名单、不是"服务器级都行"：新加服务器级项时默认仍然不上面板，
     * 要想清楚它是不是真的需要现场调。
     * <p>
     * 枚举里保留全部服务器级项，因为它们同时承担"配置键的定义"这个职责
     * （见 {@code AdminToolConfig} 里的 {@code [qol]} 分节是照它们生成的）。
     */
    public boolean panelVisible() {
        if (scope == Scope.PERSONAL) {
            return true;
        }
        for (QolSetting allowed : SERVER_ROWS_ON_PANEL) {
            if (allowed == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * 允许出现在面板上的服务器级项（白名单）。
     * <p>
     * 选这两项的标准是"改了要立刻看到效果"：
     * <ul>
     *   <li>{@link #ANVIL_UNBREAKABLE} 不朽铭文开关 —— 现场开一下、拿两个物品试一次；</li>
     *   <li>{@link #ZONE_DROP_POLICY} 选区掉落策略 —— 挖之前先确认东西往哪去，
     *       挖完才发现进了销毁就晚了。</li>
     * </ul>
     * 其余服务器级项（总闸、上限、预算这些）是"设定一次就不动"的，留在配置文件里更合适。
     * <p>
     * 经验飞涨原来也在这份名单里，它改成个人级之后就不需要了 —— 个人级本来就上面板。
     */
    private static final QolSetting[] SERVER_ROWS_ON_PANEL = {
            ANVIL_UNBREAKABLE, ZONE_DROP_POLICY
    };

    /**
     * 选项类项的取值名翻译键，形如 {@code <名称键>.value0}。
     * <p>
     * 当前没有项在用（原本是选区作业的"模式"，后来破坏与放置拆成两行，就不再需要选项类了），
     * 保留它是给以后真有"三选一"这类项时直接用。
     * <p>
     * 拼在名称键后面而不是另起一套前缀：选项名与这一项强绑定，
     * 换个前缀就要在两处地方同时改，而语言文件里的漏项在编译期是发现不了的。
     */
    public String choiceKey(int value) {
        return translationKey() + ".value" + value;
    }

    /**
     * 本项是否属于某个分类标签。
     * <p>
     * <b>不在面板上的项（服务器级）一律不属于任何分类</b>，包括 {@link Category#SERVER} 本身 ——
     * 那个标签是在面板里给服务器级项做汇总用的，既然它们不上面板，这个标签也就没有内容、
     * 面板不会画它。留着这个枚举值是为了不改动已写进代码的引用。
     */
    public static boolean inCategory(QolSetting setting, Category category) {
        if (!setting.panelVisible()) {
            return false;
        }
        if (category == Category.SERVER) {
            return false;
        }
        return setting.category == category;
    }

    /** 本类别下有没有项 —— 面板用它决定要不要画这个标签。 */
    public static boolean hasAnyIn(Category category) {
        for (QolSetting setting : values()) {
            if (inCategory(setting, category)) {
                return true;
            }
        }
        return false;
    }

    /** 按线格式 id 取项；未知 id 返回 null（调用方必须当成"什么都不做"处理）。 */
    public static QolSetting byId(int id) {
        for (QolSetting setting : values()) {
            if (setting.id == id) {
                return setting;
            }
        }
        return null;
    }
}