package dev.modmind.kunjinkao.client.qol;

import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 寻宝大师（功能 96）：查出玩家周围所有"值得高亮"的方块实体，缓存成位置表。
 * <p>
 * <b>怎么找：不逐格遍历，只翻方块实体表。</b> 64 格半径覆盖 129x129x129 约 210 万个方块，
 * 无论怎么优化，按格扫都是必卡的死路。但方块实体在区块里是稀疏的 ——
 * 一个区块通常只有几十个 —— 所以正确的做法是：先由半径算出覆盖哪些区块，
 * 再对每个区块调 {@link LevelChunk#getBlockEntities()} 只翻那张表。
 * 工作量从"百万格"降到"千余个方块实体"，这是本项性能的根本来源。
 * <p>
 * <b>怎么不卡帧：分帧摊开。</b> 半径拉满时有几百个区块，一次全扫完仍然会在一帧里
 * 砸下几千次判定。所以每次真正扫描时最多只处理 {@link #MAX_CHUNKS_PER_SCAN} 个区块，
 * 游标记在 {@link #cursor}，下次接着往下走。任一帧的最大工作量因此被钉死成常量，
 * 与半径无关 —— 半径只影响"扫完一圈要几秒"，不影响任何一帧的长度。
 * <p>
 * <b>结果怎么更新：整表重建，而不是增量修改。</b> 每次扫描前清空累加表，
 * 从头往下填。这样"同一个容器被这次和下次扫到两次"不会在表里出现两份，
 * 天然去重，也省掉了哈希集合的开销。代价是新一圈的前几帧表会偏小，
 * 但那时候画面本来就在刷新，肉眼看不出来。
 * <p>
 * <b>门槛：三个开关同时满足才扫。</b> 服务端总闸、个人开关的授权、个人开关本身。
 * 少判任何一个都等于绕开服主的总闸，那正是这一项最容易出问题的地方。
 * <p>
 * <b>线程约定：</b> 所有状态都在客户端主线程上读写（tick 与渲染都在主线程），
 * 所以不设锁。{@link #tick(Minecraft)} 必须每客户端 tick 调一次。
 */
public final class QolTreasureScanner {

    /**
     * 每次真扫最多处理多少个区块。
     * <p>
     * <b>取 128 是为了让一拍就能扫完一整圈。</b> 最大半径 64 格 = 4 个区块，
     * 网格边长 9，一共 81 个区块 —— 128 覆盖得住，于是间隔到了就是一次完整刷新，
     * 而不是"分四拍慢慢凑齐"。
     * <p>
     * 早先这里是 24，配 20 tick 的间隔，半径拉满时刷完一圈要四秒：玩家走两步，
     * 框还留在两秒前的位置上，看起来就是"不实时"。24 是为了压单帧尖峰，
     * 但 81 个区块的方块实体表本来就只有几千项判定，属于微秒量级 ——
     * 为省这点开销牺牲整整四秒的新鲜度，是本末倒置。
     */
    private static final int MAX_CHUNKS_PER_SCAN = 128;

    /**
     * 每多少客户端 tick 真扫一次。
     * <p>
     * 5 tick = 每秒四次。原来写 20（一秒一次），叠上分批就是四秒一圈。
     * 现在一拍扫完整圈，四次/秒已经足够跟手：再快也不会更准，
     * 因为容器的出现与消失本来就是以秒计的。
     */
    private static final int SCAN_INTERVAL_TICKS = 5;

    /**
     * 高亮框相对方块外扩多少格。
     * <p>
     * 外扩而不是紧贴方块本身：贴面画出来的线会和方块边缘的贴图混在一起，远处几乎看不见。
     * 外扩一点点能让线框"浮"在方块外，这也是原版选中框的做法。
     */
    private static final double BOX_INFLATE = 0.02D;

    /** 服务端下发的半径兜底：万一某个服务器的配置异常，这里还是要落在面板允许的 8 到 64 之内。 */
    private static final int MIN_RADIUS = 8;
    private static final int MAX_RADIUS = 64;

    private QolTreasureScanner() {
    }

    /** 本圈已扫到的方块位置（用 long 编码）。整表重建，所以这里不需要去重结构。 */
    private static final List<Long> ENCODED = new ArrayList<>();

    /**
     * 渲染线程读的高亮框。
     * <p>
     * 缓存成 AABB 而不是让渲染每帧自己算：每帧几十次乘加虽然不多，
     * 但把它挪到一秒一次的扫描里就是零成本，渲染只做取用。
     */
    private static List<AABB> highlights = List.of();

    /** 距离上次真扫过去了多少 tick。 */
    private static int tickCounter;

    /** 本圈已经扫到第几个区块（在 {@link #span} x {@link #span} 的网格里从左上往右下数）。 */
    private static int cursor;

    /** 本圈覆盖的区块边长（区块数）。0 表示当前没有进行中的圈。 */
    private static int span;

    /** 本圈是以哪个区块为中心的。 */
    private static int centerChunkX;
    private static int centerChunkZ;

    /** 本圈是按哪个维度建的。换维度时必须作废重来，否则会把上一张地图的坐标画到这一张上。 */
    private static Level currentLevel;

    /**
     * 每客户端 tick 调一次。内部自己按 tick 计数决定要不要真扫。
     * <p>
     * 之所以把"计数"放在内部而不是让调用方每 20 tick 调一次：调用方只需要无脑转发，
     * 节奏只在这一个文件里定义，以后要调间隔不用去另一个类里找。
     */
    public static void tick(Minecraft minecraft) {
        if (!isEnabled()) {
            // 关掉开关必须立刻清空：不清的话关掉之后那一屏线框还挂着，
            // 玩家会以为开关没生效，而且会一路跟着走到天涯海角。
            clear();
            return;
        }
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            clear();
            return;
        }
        // 暂停（单机 Esc 菜单）时世界不动，扫描没有任何新信息，白烧 CPU。
        if (minecraft.isPaused()) {
            return;
        }

        if (level != currentLevel) {
            // 换维度等于换了一套坐标：先把累加到一半的结果丢掉，
            // 并且让下一帧从新地图的第一个区块重新起圈。
            clear();
            currentLevel = level;
        }

        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        scan(level, player);
    }

    /**
     * 当前要高亮的方块外框。
     * <p>
     * 渲染线程直接读这个列表，不做任何加工，也不要去改它 ——
     * 它归扫描器所有，下一次扫描会整表换掉。返回的是内部引用而不是拷贝：
     * 渲染每帧调一次，拷贝一份几十项的列表纯属无谓分配。
     */
    public static List<AABB> getHighlights() {
        return highlights;
    }

    /** 三个开关同时满足才算"允许"。见类注释里为什么一个都不能少。 */
    private static boolean isEnabled() {
        return QolClientState.isOn(QolSetting.TREASURE_HUNTER_SERVER)
                && QolClientState.authorized(QolSetting.TREASURE_HUNTER)
                && QolClientState.isOn(QolSetting.TREASURE_HUNTER);
    }

    private static void clear() {
        if (!ENCODED.isEmpty()) {
            ENCODED.clear();
        }
        if (!highlights.isEmpty()) {
            highlights = List.of();
        }
        span = 0;
        cursor = 0;
        tickCounter = 0;
        // currentLevel 故意不置空：留着它，回到同一维度时就不必白清一次。
    }

    /**
     * 真扫一拍：只处理本圈的下一批区块，不跨圈。
     */
    private static void scan(Level level, LocalPlayer player) {
        if (span == 0) {
            startSweep(level, player);
        }
        if (span == 0) {
            // 半径配置离谱到算不出网格（理论上不会发生，因为下面夹过），那就别扫。
            return;
        }

        Vec3 eye = player.getEyePosition();
        int radius = radius();
        double radiusSqr = (double) radius * radius;

        // 注意这里不建区块坐标列表：需要哪一块就现算哪一块。
        // 建表意味着每次真扫都要分配一个几百项的 List，而我们一秒才用得上其中 24 项。
        int end = Math.min(cursor + MAX_CHUNKS_PER_SCAN, span * span);
        for (; cursor < end; cursor++) {
            int localX = cursor % span;
            int localZ = cursor / span;
            int chunkX = centerChunkX + localX;
            int chunkZ = centerChunkZ + localZ;

            ChunkSource source = level.getChunkSource();
            // 最后一个 false 是关键：只拿已经加载好的区块。
            // 传 true 会为了扫描而把远处区块同步加载出来，那是把客户端卡死的最快方式，
            // 而且等于替服务器决定要加载什么。
            LevelChunk chunk = source.getChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                continue;
            }
            collectFrom(chunk, eye, radiusSqr);
        }

        if (cursor >= span * span) {
            // 本圈到头了：游标归零，下一拍从新的中心重新起圈（玩家这一秒里走到哪就以哪为中心）。
            cursor = 0;
            span = 0;
        }

        rebuildHighlights();
    }

    /**
     * 起一个新圈：以玩家当前所在区块为中心，把半径换算成"要扫哪些区块"。
     */
    private static void startSweep(Level level, LocalPlayer player) {
        int radius = radius();
        ChunkPos center = new ChunkPos(player.blockPosition());
        centerChunkX = center.x;
        centerChunkZ = center.z;
        // 半径 64 格 = 4 个区块，所以网格边长 9（-4..+4 含中心）。
        // 用向下取整而不是四舍五入：宁可多扫一圈边缘，也不能漏掉半径内的区块。
        // 多扫的那些由下面的"方块中心到眼睛的距离"二次过滤掉，不会画出半径外的东西。
        int halfChunks = radius >> 4;

        // 中心一旦落定就不再变：一圈扫到一半时玩家走动会让覆盖范围漂移，
        // 游标与网格就对不上了。漂移的代价是边缘区块可能晚一圈才扫到，
        // 一秒后自然补上，远比"网格和游标错位、扫过的地方永远扫不到"要好。
        span = halfChunks * 2 + 1;
        cursor = 0;
    }

    /**
     * 翻一个区块的方块实体表，把半径内值得高亮的记下来。
     * <p>
     * 这里是全流程唯一的热点：单次扫描 1 ms 的指标基本就是它决定的。
     * 让它保持"只读字段、只做判定、不开新集合"是刻意的 ——
     * 一旦在这里加一次 HashSet 或一次 stream，指标立刻掉一个数量级。
     */
    private static void collectFrom(LevelChunk chunk, Vec3 eye, double radiusSqr) {
        Map<BlockPos, BlockEntity> entities = chunk.getBlockEntities();
        if (entities.isEmpty()) {
            return;
        }
        double eyeX = eye.x;
        double eyeY = eye.y;
        double eyeZ = eye.z;
        for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet()) {
            BlockEntity entity = entry.getValue();
            if (entity == null || !isTreasure(entity)) {
                continue;
            }
            BlockPos pos = entry.getKey();
            // 比"方块中心到眼睛"而不是"方块角到眼睛"：角上的方块按角算会被判出界，
            // 表现为贴着半径边缘的箱子时有时无地闪。
            double dx = pos.getX() + 0.5D - eyeX;
            double dy = pos.getY() + 0.5D - eyeY;
            double dz = pos.getZ() + 0.5D - eyeZ;
            if (dx * dx + dy * dy + dz * dz > radiusSqr) {
                continue;
            }
            ENCODED.add(pos.asLong());
        }
    }

    /**
     * 方块实体判定，集中在这一处。
     * <p>
     * 以后要加"值得找"的类型只改这里，渲染与扫描都不用动。
     * <p>
     * 容器用接口 {@link Container} 判定而不是枚举原版箱子类：模组的容器
     * （抽屉、储罐、各种机器）也都实现这个接口，按接口判才不会漏掉一整个整合包的东西。
     * <p>
     * 已知的"多画"：刷空之后的可疑沙子仍然是 {@link BrushableBlockEntity}，
     * 这是刻意接受的 —— 想精确判断就得去读它们的内部状态，而不同版本字段名会变，
     * 为一个"多画一个空框"的观感问题去绑死内部字段不划算。
     */
    private static boolean isTreasure(BlockEntity entity) {
        if (entity instanceof Container) {
            return true;
        }
        if (entity instanceof BrushableBlockEntity) {
            return true;
        }
        if (entity instanceof SpawnerBlockEntity) {
            return true;
        }
        // 1.21 的试炼刷怪笼 / 宝库（TrialSpawnerBlockEntity / VaultBlockEntity）在 1.20.1 不存在，
        // 这两个分支随试炼厅内容一起推迟。
        return false;
    }

    /**
     * 把本圈累计的位置换算成渲染要用的外框。
     * <p>
     * 整表重建（而不是往旧表上追加）：一圈里前面扫过的区块可能在下一圈被扫到第二次，
     * 沿用旧表就会重复。重建是去重里最省事也最省 CPU 的办法 ——
     * 不需要哈希集合，不需要逐项比对，代价只是新一圈开头几帧线框会少一些。
     */
    private static void rebuildHighlights() {
        if (ENCODED.isEmpty()) {
            if (!highlights.isEmpty()) {
                highlights = List.of();
            }
            return;
        }
        List<AABB> built = new ArrayList<>(ENCODED.size());
        for (int i = 0; i < ENCODED.size(); i++) {
            BlockPos pos = BlockPos.of(ENCODED.get(i));
            built.add(new AABB(pos).inflate(BOX_INFLATE));
        }
        highlights = built;
    }

    /**
     * 当前生效的扫描半径。
     * <p>
     * 兜底的夹取不是多余的：半径来自服务端下发的数值，配置写坏时可能是 0 或者极大，
     * 0 会让这一项静悄悄地什么都不显示（玩家只会以为功能坏了），
     * 极大则会让网格大得离谱。夹到面板允许的区间内，至少行为是可预期的。
     */
    private static int radius() {
        int value = QolClientState.value(QolSetting.TREASURE_HUNTER_RADIUS);
        if (value < MIN_RADIUS) {
            return MIN_RADIUS;
        }
        return Math.min(value, MAX_RADIUS);
    }
}

