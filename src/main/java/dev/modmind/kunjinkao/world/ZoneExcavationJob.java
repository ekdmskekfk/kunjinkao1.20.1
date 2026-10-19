package dev.modmind.kunjinkao.world;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.compat.Ae2ZoneSupply;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 选区作业（127）：把框选的一块区域<b>按模式</b>分批处理掉。
 * <p>
 * 两种模式共用同一套批处理、权限、上限与撤销：
 * <ul>
 *   <li><b>破坏</b>（{@link #MODE_BREAK}）：把区域挖空，掉落用主手物品当工具算。</li>
 *   <li><b>放置</b>（{@link #MODE_PLACE}）：把区域里可替换的位置填上物品，
 *       货源依次是副手、背包、附近的 AE2 ME 网络（见 {@code Ae2ZoneSupply}）。</li>
 * </ul>
 * 模式由调用方在 {@link #start} 时传入（来自玩家按的是破坏键还是放置键）。作业一开工就把它固定下来：
 * 中途不会有"同一场作业一半在挖、一半在填"这种事 —— 没有"当前模式"这个可变状态可以中途变。
 * <p>
 * 这是全模组唯一一处<b>不可逆</b>改变地形的功能，所以四道保险一道都不能省：
 * 服务器总闸（默认关）、只有通过密码授权的人能用、单边上限、以及执行前的二次确认
 * （确认在 {@code KunJinKaoQolHandler} 里做，这里只管拿到"已经确认过"的请求）。
 * <p>
 * <b>分批是硬要求，不是优化。</b> 64×64×全高最多二十六万方块，一口气挖完就是一次
 * 几十秒的卡死。作业按 {@code zoneTickBudgetMs} 的时间预算切分，每 tick 只干预算内的活，
 * 于是无论选区多大，MSPT 的增量都是有上限的。
 * <p>
 * <b>破坏模式刻意不给主手物品扣耐久。</b> 一次作业动辄几万到几十万方块，按格扣一点耐久的结果
 * 就是"开工几十秒后工具没了"，而那点消耗换不来任何平衡 —— 这个功能本来就是给已授权玩家的
 * 批量工具，不是原版挖掘的省力替代。放置模式同理：只按格消耗填进去的那个方块本身。
 */
public final class ZoneExcavationJob {

    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");

    /** 破坏模式：挖空选区。 */
    public static final int MODE_BREAK = 0;
    /** 放置模式：用副手（以及背包、AE 库存）的方块填满可替换的位置。 */
    public static final int MODE_PLACE = 1;

    /**
     * 不挖的方块，做成方块标签而不是写死一串 id：服主想加自己的保护方块时
     * 只要往数据包的同名标签里追加，不用改代码也不用等模组更新。
     */
    public static final TagKey<Block> BLACKLIST = TagKey.create(Registries.BLOCK,
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "zone_excavation_blacklist"));

    /** 掉落策略：进玩家背包。 */
    public static final int POLICY_INVENTORY = 0;
    /** 掉落策略：生成掉落物。 */
    public static final int POLICY_DROP = 1;
    /** 掉落策略：直接销毁。 */
    public static final int POLICY_DESTROY = 2;
    /**
     * 掉落策略：优先进 AE 网络，塞不下的部分再进背包。
     * <p>
     * <b>它是策略 0 的加强版，不是第四种互斥去向。</b> 塞不进 AE 时剩下的那部分走的正是
     * {@link #POLICY_INVENTORY} 那条路（背包满了就掉在脚下），于是「AE 不在」的服务器上
     * 这一项与策略 0 的表现完全一致，服主不需要为「没装 AE2」准备第二套配置。
     */
    public static final int POLICY_AE_FIRST = 3;

    /** 每处理这么多方块检查一次时间预算。每块都看的话，System.nanoTime 自己就成了开销。 */
    private static final int BUDGET_CHECK_INTERVAL = 64;

    private static final Map<UUID, Job> JOBS = new HashMap<>();
    /** 每人最近一次完成的作业，供撤销用。只留一步 —— 这和放置撤销的十步不是一个量级。 */
    private static final Map<UUID, Completed> LAST_COMPLETED = new HashMap<>();

    private ZoneExcavationJob() {
    }

    private static final class Job {
        final UUID owner;
        final ServerLevel level;
        final ResourceLocation dimension;
        final BlockPos min;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final int total;
        /** 开工时定下的模式，见 {@link #MODE_BREAK} / {@link #MODE_PLACE}。 */
        final int mode;
        /**
         * 放置模式的"填什么"：开工时副手那一堆的副本，数量固定为 1。
         * <p>
         * 整场作业都按它判"是不是同一种东西"，所以它必须是一份<b>快照</b>而不是
         * 每次现读副手 —— 副手会被消耗掉，消耗完的那一刻就没有"参照物"了。
         */
        final ItemStack template;
        /** 放置模式要写进世界的方块状态，由 {@link #template} 对应的方块决定，开工时算一次。 */
        final BlockState placedState;
        int cursor;
        /** 放置模式真正放下去了多少格；破坏模式不用（看 cursor 即可）。 */
        int placedCount;
        /** 撤销快照；为 null 表示这次不记（超出上限或服主关了）。 */
        final List<PlacementUndoHistory.Change> undo;
        /** 策略 0 下真正进了背包的东西，撤销时要按这份清单收回来。 */
        final List<ItemStack> granted = new ArrayList<>();
        /**
         * 策略 3 下真正塞进 AE 的东西，撤销时要按这份清单从 ME 网络里抽回来。
         * <p>
         * <b>与 {@link #granted} 分开记，不能合成一份。</b> 撤销时一个要从背包收、一个要从
         * 网络里抽，混在一起就不知道该去哪找；而「不回收 AE 那一份」的后果不是少收点东西，
         * 是撤销直接变成刷物品 —— 地形回来了、东西还在 AE 里，按几次撤销就凭空多几份。
         */
        final List<ItemStack> grantedToAe = new ArrayList<>();
        /** 同一 BlockState 的掉落只掷一次骰子。见 processOne 里的说明。 */
        final Map<BlockState, List<ItemStack>> dropCache = new HashMap<>();
        /**
         * 附近的 ME 网络存储，第一次需要 AE 货源时才去扫。
         * <p>
         * 懒加载是必须的：绝大多数作业（尤其破坏模式）根本用不到 AE，
         * 一开工就扫区块等于为一件大概率不发生的事付费。
         * {@code aeScanned} 与"列表为空"是两件事 —— 空列表也要记下来，否则每一次缺料都要重扫一遍。
         */
        List<Object> aeStorages;
        boolean aeScanned;

        Job(UUID owner, ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ,
            int total, int mode, ItemStack template, BlockState placedState,
            List<PlacementUndoHistory.Change> undo) {
            this.owner = owner;
            this.level = level;
            this.dimension = level.dimension().location();
            this.min = min;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.total = total;
            this.mode = mode;
            this.template = template;
            this.placedState = placedState;
            this.undo = undo;
        }
    }

    /** 一次已完成作业的撤销数据。 */
    private record Completed(ServerLevel level, ResourceLocation dimension,
                             List<PlacementUndoHistory.Change> changes, List<ItemStack> granted,
                             List<ItemStack> grantedToAe) {
    }

    public static boolean isRunning(ServerPlayer player) {
        return JOBS.containsKey(player.getUUID());
    }

    /** 作业进度：[已处理, 总数]；没在跑返回 null。 */
    public static int[] progress(ServerPlayer player) {
        Job job = JOBS.get(player.getUUID());
        return job == null ? null : new int[]{job.cursor, job.total};
    }

    /**
     * 开始一次作业。调用方必须先确认过权限与二次确认。
     *
     * @param mode 这次要做什么，见 {@link #MODE_BREAK} / {@link #MODE_PLACE}。
     *             由调用方按玩家按的是哪个执行键传进来 —— 作业里不再读"当前模式"设置，
     *             那个设置已经不存在了。
     * @return 是否真的开始（没开始时会自己给玩家发原因）
     */
    public static boolean start(ServerPlayer player, BlockPos a, BlockPos b, int mode) {
        if (JOBS.containsKey(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_busy"), true);
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        int maxSide = AdminToolConfig.qolServerValue(QolSetting.ZONE_MAX_SIDE);
        int sizeX = Math.abs(a.getX() - b.getX()) + 1;
        int sizeY = Math.abs(a.getY() - b.getY()) + 1;
        int sizeZ = Math.abs(a.getZ() - b.getZ()) + 1;
        if (sizeX > maxSide || sizeY > maxSide || sizeZ > maxSide) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_too_big", maxSide), true);
            return false;
        }
        long totalLong = (long) sizeX * sizeY * sizeZ;
        if (totalLong > Integer.MAX_VALUE) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_too_big", maxSide), true);
            return false;
        }
        int total = (int) totalLong;

        // 模式由调用方给：它来自玩家按的是哪个执行键，而不是某个"当前模式"设置。
        // 这样不存在"设置里是放置、手却按成了破坏"这种需要回头确认的状态。
        ItemStack template = ItemStack.EMPTY;
        BlockState placedState = null;
        if (mode == MODE_PLACE) {
            // 放置模式先在这里把"填什么"定死。定不下来的话这一场作业一格也放不了，
            // 与其开一个马上就得停的作业、不如在起点就说清楚。
            ItemStack offhand = player.getOffhandItem();
            if (offhand.isEmpty()) {
                // 副手空着 = 连要填什么都不知道。用"材料用完"那条文案并把已填数报成 0：
                // 它说的正是"副手、背包与 AE 库存里都拿不到这种方块"，与事实一致。
                player.displayClientMessage(
                        Component.translatable("message.kunjinkao.zone_fill_out_of_items", 0), true);
                return false;
            }
            Block block = Block.byItem(offhand.getItem());
            if (block == Blocks.AIR) {
                // 不是方块物品（比如一把剑）：放不下去，也没有"默认方块状态"可谈。
                player.displayClientMessage(Component.translatable("message.kunjinkao.zone_fill_bad_item"), true);
                return false;
            }
            template = offhand.copyWithCount(1);
            placedState = block.defaultBlockState();
        }

        int undoCap = AdminToolConfig.qolServerValue(QolSetting.ZONE_UNDO_MAX);
        List<PlacementUndoHistory.Change> undo =
                undoCap > 0 && total <= undoCap ? new ArrayList<>(total) : null;
        if (undo == null) {
            // 明确告知，而不是默默不记 —— 玩家按了撤销却发现没反应才是最糟的。
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_no_undo"), true);
        }

        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        JOBS.put(player.getUUID(), new Job(player.getUUID(), level, min, sizeX, sizeY, sizeZ, total,
                mode, template, placedState, undo));
        player.displayClientMessage(Component.translatable("message.kunjinkao.zone_started", total), true);
        return true;
    }

    /**
     * 推进作业。由拥有者自己的 {@code PlayerTickEvent.Post} 驱动。
     * <p>
     * 用拥有者的 tick 而不是全局 ServerTickEvent：作业本来就只在他在线时有意义，
     * 挂在他身上，退出登录自然就停了，不需要额外写一套"玩家离线要清理什么"的逻辑。
     */
    public static void tick(ServerPlayer player) {
        Job job = JOBS.get(player.getUUID());
        if (job == null) {
            return;
        }
        int budgetMs = AdminToolConfig.qolServerValue(QolSetting.ZONE_TICK_BUDGET);
        int batch = AdminToolConfig.qolServerValue(QolSetting.ZONE_BATCH_SIDE);
        // 掉落策略只在破坏模式下有意义：放置模式是往里放东西，没有掉落可谈。
        // 那个设置项本身保持原样不动 —— 换回破坏模式时它还是原来那个值。
        int policy = AdminToolConfig.qolServerValue(QolSetting.ZONE_DROP_POLICY);
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        int processed = 0;
        boolean outOfItems = false;
        while (job.cursor < job.total && processed < batch) {
            if (job.mode == MODE_PLACE) {
                if (!placeOne(job, player)) {
                    // 材料没了：立刻收工，绝不跳过这一格继续填后面。
                    // 跳过的结果是选区区里凭空空一块，而且玩家看到进度条走完了会以为填满了。
                    outOfItems = true;
                    break;
                }
            } else {
                processOne(job, player, policy);
            }
            job.cursor++;
            processed++;
            if (processed % BUDGET_CHECK_INTERVAL == 0 && System.nanoTime() >= deadline) {
                break;
            }
        }
        if (outOfItems) {
            finishOutOfItems(job, player);
            return;
        }
        if (job.cursor >= job.total) {
            finish(job, player);
        }
    }

    /** 破坏模式的一格：挖掉、按策略处理掉落。 */
    private static void processOne(Job job, ServerPlayer player, int policy) {
        BlockPos pos = positionOf(job, job.cursor);

        BlockState state = job.level.getBlockState(pos);
        if (state.isAir() || state.is(BLACKLIST)) {
            return;
        }

        // 掉落必须在方块消失之前算（方块一没，方块实体与战利品表的判定上下文就跟着没了），
        // 但"直接销毁"策略下根本不需要掉落 —— 先算一遍再丢掉是纯浪费，
        // 而 Block.getDrops 会跑一次战利品表，是单格里最贵的一步。
        List<ItemStack> drops = List.of();
        if (policy != POLICY_DESTROY) {
            // 只有"可能带方块实体"的方块才去查：getBlockEntity 每次都要过一遍区块的方块实体表，
            // 而石头、泥土这些绝大多数方块根本没有方块实体，这一问是白问。
            BlockEntity blockEntity = state.hasBlockEntity() ? job.level.getBlockEntity(pos) : null;
            // 掉落骰子按 BlockState 缓存，只在"没有方块实体"时用。
            // 带方块实体的方块（箱子、桶）掉落取决于里面的东西，按状态缓存会把第一个箱子的
            // 内容复制给后面所有同状态的箱子 —— 那是刷物品，不是优化。
            //
            // 工具一律传主手物品：破坏模式就是"用主手的物品挖"，附魔与时运都按它算。
            // 有意不扣它的耐久，理由见类注释。
            if (blockEntity == null) {
                drops = job.dropCache.computeIfAbsent(state,
                        cached -> Block.getDrops(cached, job.level, pos, null, player, player.getMainHandItem()));
            } else {
                drops = Block.getDrops(state, job.level, pos, blockEntity, player, player.getMainHandItem());
            }
        }

        if (job.undo != null) {
            // returned 一律为空：挖掘不"退还"任何东西，撤销时该做的是把已经拿到手的收回来，
            // 那件事由下面的 granted 清单负责，不走 PlacementUndoHistory 的退还通道。
            job.undo.add(new PlacementUndoHistory.Change(pos.immutable(), state, ItemStack.EMPTY));
        }

        // 只发客户端更新，不做邻居更新 —— 原版那套 3 是"邻居更新 + 客户端更新"。
        // 邻居更新会为每一格触发 6 次 neighborChanged，红石、观察者、沙砾坠落都跟着连锁；
        // 挖空一整片时这些连锁毫无意义，却是单格成本里最贵的一块。
        // 光照不受影响：它在 LevelChunk#setBlockState 里更新（updateSectionStatus 无条件调、
        // checkBlock 在光照属性变化时调），与 flag 无关 —— 所以不会挖出一个黑窟窿。
        // 代价是这一片里"依赖邻居"的东西不连锁（悬空沙砾不掉、拉杆不弹），
        // 而"一次性挖空"要的正是这个：挖完再被沙砾埋回来才是灾难。
        job.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        if (policy == POLICY_DESTROY) {
            return;
        }
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack copy = drop.copy();
            if (policy == POLICY_AE_FIRST) {
                // 策略 3 只是「先进 AE」这一道前置：塞不进去的部分原样留下来，
                // 走下面策略 0 那条路进背包 —— 于是 AE 满了不会把东西吞掉。
                copy = routeToAeFirst(job, player, copy);
                if (copy.isEmpty()) {
                    continue;
                }
            }
            if (policy == POLICY_INVENTORY || policy == POLICY_AE_FIRST) {
                int before = copy.getCount();
                if (!player.getInventory().add(copy)) {
                    player.drop(copy, false);
                }
                int added = before - copy.getCount();
                if (added > 0) {
                    job.granted.add(drop.copyWithCount(added));
                }
            } else {
                Block.popResource(job.level, pos, copy);
            }
        }
    }

    /**
     * 策略 3 的第一步：先把掉落物塞进玩家附近的 ME 网络。
     * <p>
     * <b>网络列表懒加载、而且与放置模式的取货共用同一份缓存。</b> 扫区块要遍历 32 格半径内
     * 所有已加载区块的方块实体，按格做一遍就是每格扫一次；一场作业动辄几万格，这一步不能省。
     * 作业只有一种模式，取货与存货不会同时发生，共用缓存不会互相污染。
     * <p>
     * <b>「附近没有网络」只提示一次。</b> 提示放在首次扫描那一次里，是因为这里每格都会走到，
     * 每格说一句的话，几万条聊天消息会把玩家的屏幕彻底刷没 —— 而那正是他最需要看进度的时候。
     *
     * @return 没塞进 AE、还需要另找去处的部分；全塞进去了返回空堆
     */
    private static ItemStack routeToAeFirst(Job job, ServerPlayer player, ItemStack stack) {
        if (!job.aeScanned) {
            job.aeScanned = true;
            job.aeStorages = Ae2ZoneSupply.findStorages(job.level, player.position());
            if (job.aeStorages == null || job.aeStorages.isEmpty()) {
                // 说清楚「为什么东西进了背包」：玩家选的是策略 3，如果 AE 不可用却不吭声，
                // 他只会以为这一项没生效。
                player.displayClientMessage(
                        Component.translatable("message.kunjinkao.zone_ae_unavailable"), false);
            }
        }
        if (job.aeStorages == null || job.aeStorages.isEmpty()) {
            return stack;
        }
        int leftover = Ae2ZoneSupply.insert(job.aeStorages, stack, player);
        int inserted = stack.getCount() - leftover;
        if (inserted > 0) {
            job.grantedToAe.add(stack.copyWithCount(inserted));
        }
        return leftover <= 0 ? ItemStack.EMPTY : stack.copyWithCount(leftover);
    }

    /**
     * 放置模式的一格。
     * <p>
     * 返回值就是"还能不能继续"：只有<b>材料取不到</b>才返回 false。
     * 位置上已经有方块（不能替换）属于正常情况，跳过它继续走 —— 选区框的是一块空间，
     * 里面本来就可能有不想动的东西，为此停掉整场作业毫无道理。
     *
     * @return 材料还够不够继续往下填
     */
    private static boolean placeOne(Job job, ServerPlayer player) {
        BlockPos pos = positionOf(job, job.cursor);
        BlockState previous = job.level.getBlockState(pos);
        if (!previous.isAir() && !previous.canBeReplaced()) {
            return true;
        }
        if (!consumeOne(job, player)) {
            return false;
        }
        if (job.undo != null) {
            // returned 记下这一格消耗掉的那一个方块：放置把玩家的东西变成了地形，
            // 撤销时必须把它还回去，否则撤销就成了"吞掉玩家的材料"。
            job.undo.add(new PlacementUndoHistory.Change(
                    pos.immutable(), previous, job.template.copyWithCount(1)));
        }
        job.level.setBlock(pos, job.placedState, Block.UPDATE_CLIENTS);
        job.placedCount++;
        return true;
    }

    /**
     * 按「副手 → 背包 → AE 库存」的顺序取一个方块。
     * <p>
     * 副手用完不会自动从背包里补货：副手只是一个优先来源，玩家看着自己的方块减少就够了；
     * 自动把背包里的东西搬进副手，会让人以为模组在动他的物品栏。
     * <p>
     * 判定"同一种"统一用 {@link ItemStack#isSameItemSameComponents}，
     * 与开工时的 {@link Job#template} 比：比较物品与数据组件（附魔、名字等），不比较数量。
     *
     * @return 是否真的取到了一个
     */
    private static boolean consumeOne(Job job, ServerPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        if (isSame(offhand, job.template)) {
            offhand.shrink(1);
            player.getInventory().setChanged();
            return true;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack inSlot = player.getInventory().getItem(slot);
            if (isSame(inSlot, job.template)) {
                inSlot.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return consumeFromAe(job, player);
    }

    private static boolean isSame(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && ItemStack.isSameItemSameTags(stack, template);
    }

    /**
     * 最后一级货源：玩家附近的 ME 网络。
     * <p>
     * 找到的网络列表缓存在作业里（{@link Job#aeStorages}）：这一步要扫区块，
     * 而缺料之后每一格都会走到这里，不缓存就是每格扫一次区块。
     * <p>
     * AE2 不在、附近没有网络、网络里没有这种方块，三种情况在这里都只是"取不到"，
     * 作业据此停下并把原因如实告诉玩家（见 {@link #finishOutOfItems}）。
     */
    private static boolean consumeFromAe(Job job, ServerPlayer player) {
        if (!job.aeScanned) {
            job.aeScanned = true;
            job.aeStorages = Ae2ZoneSupply.findStorages(job.level, player.position());
        }
        if (job.aeStorages == null || job.aeStorages.isEmpty()) {
            return false;
        }
        return Ae2ZoneSupply.extractOne(job.aeStorages, job.template, player);
    }

    /** 把作业格子下标换算成坐标。x 变得最快、y 变得最慢，与"从上往下、一层一层"的直觉一致。 */
    private static BlockPos positionOf(Job job, int index) {
        int x = index % job.sizeX;
        int z = (index / job.sizeX) % job.sizeZ;
        int y = index / (job.sizeX * job.sizeZ);
        return job.min.offset(x, y, z);
    }

    private static void finish(Job job, ServerPlayer player) {
        JOBS.remove(job.owner);
        if (job.undo != null && !job.undo.isEmpty()) {
            LAST_COMPLETED.put(job.owner,
                    new Completed(job.level, job.dimension, job.undo, job.granted, job.grantedToAe));
        }
        // 只在真往 AE 里存了东西时说一句。这一句是策略 3 唯一的「确实生效了」的证据：
        // 东西直接进了网络，背包里不会多出来，不说的话玩家分不清是存进去了还是被吞了。
        int aeInserted = countOf(job.grantedToAe);
        if (aeInserted > 0) {
            player.displayClientMessage(
                    Component.translatable("message.kunjinkao.zone_ae_inserted", aeInserted), false);
        }
        player.displayClientMessage(Component.translatable("message.kunjinkao.zone_done", job.total), true);
        LOGGER.info("[Kunjinkao] 选区作业完成：模式 {}，{} 格，玩家 {}",
                job.mode == MODE_PLACE ? "放置" : "破坏", job.total, player.getName().getString());
    }

    /** 数一数清单里一共有多少件（不是多少堆）：给玩家看的数字应当是件数。 */
    private static int countOf(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * 放置模式中途没材料了：收工并说明白。
     * <p>
     * <b>不静默跳过继续填后面的格子。</b> 跳过的结果是区里凭空空出一块，
     * 而玩家看着进度走完只会以为填满了；说清楚"填了多少、停在哪"至少还能让他补货后重来。
     * 停止坐标单独发一行裸坐标：它不含任何文字，因此不需要为它新增语言键，
     * 也不会出现某一种语言下这句话看不懂的情况。
     */
    private static void finishOutOfItems(Job job, ServerPlayer player) {
        JOBS.remove(job.owner);
        if (job.undo != null && !job.undo.isEmpty()) {
            // 填进去的部分照样记撤销：半场作业同样需要能还原。
            LAST_COMPLETED.put(job.owner,
                    new Completed(job.level, job.dimension, job.undo, job.granted, job.grantedToAe));
        }
        player.displayClientMessage(
                Component.translatable("message.kunjinkao.zone_fill_out_of_items", job.placedCount), false);
        BlockPos stopped = positionOf(job, job.cursor);
        player.displayClientMessage(Component.literal("(" + stopped.getX() + ", "
                + stopped.getY() + ", " + stopped.getZ() + ")"), false);
        LOGGER.info("[Kunjinkao] 选区作业缺料停止：已填 {} 格，停在 {}，玩家 {}",
                job.placedCount, stopped.toShortString(), player.getName().getString());
    }

    /** 取消正在跑的作业。已经改掉的部分不会还原（要还原请用撤销）。 */
    public static boolean cancel(ServerPlayer player) {
        Job job = JOBS.remove(player.getUUID());
        if (job == null) {
            return false;
        }
        // 抱哪个数字给玩家看取决于模式：破坏时说"处理了多少格"，放置时说"填了多少格"。
        // 统一用 cursor 会让放置模式报出"处理了多少格"这种把跳过也算进去的数。
        int done = job.mode == MODE_PLACE ? job.placedCount : job.cursor;
        player.displayClientMessage(Component.translatable("message.kunjinkao.zone_cancelled", done), true);
        return true;
    }

    /**
     * 撤销最近一次完成的选区作业。
     * <p>
     * 两条通道各管一半，缺一不可：
     * <ul>
     *   <li>破坏模式：把当时塞进背包的东西按清单收回来（{@link #revokeGranted}）。
     *       不收的话，撤销就成了刷物品 —— 地形回来了，东西还在手里。</li>
     *   <li>放置模式：把每一格消耗掉的方块按撤销记录<b>退还</b>给玩家。
     *       不退的话，撤销就成了吞材料：地形没了，玩家搭进去的方块也没了。</li>
     * </ul>
     * 收不回来时（玩家已经把东西用掉/丢掉）只还原地形并如实提示，
     * 不做"反正是管理员"这种假设。
     *
     * @return 还原的方块数；没有可撤销的作业返回 0
     */
    public static int undoLast(ServerPlayer player) {
        Completed completed = LAST_COMPLETED.remove(player.getUUID());
        if (completed == null) {
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)
                || !level.dimension().location().equals(completed.dimension())) {
            // 换了维度：坐标对不上，直接作废而不是在错误的世界里乱挖。
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_undo_wrong_dim"), true);
            return 0;
        }
        int restored = 0;
        List<PlacementUndoHistory.Change> changes = completed.changes();
        // 倒序还原：同一格在一次作业里被反复改动时，只有倒序才能得到正确结果。
        for (int i = changes.size() - 1; i >= 0; i--) {
            PlacementUndoHistory.Change change = changes.get(i);
            if (!change.returned().isEmpty()) {
                // 放置模式消耗掉的那个方块，原样还给玩家（背包满就掉在脚下，不吞掉）。
                ItemStack refund = change.returned().copy();
                if (!player.getInventory().add(refund)) {
                    player.drop(refund, false);
                }
            }
            level.setBlock(change.pos(), change.previousState(), Block.UPDATE_CLIENTS);
            restored++;
        }
        int missing = revokeGranted(player, completed.granted());
        // 策略 3 的那一份不在背包里，得回 ME 网络去抽。抽不回来的同样只算「缺失」并如实提示，
        // 不能因为「反正是管理员」就当作已经收回了 —— 那正是刷物品。
        missing += revokeAeGranted(player, completed.grantedToAe());
        if (missing > 0) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_undo_partial", missing), true);
        }
        player.displayClientMessage(Component.translatable("message.kunjinkao.zone_undo_done", restored), true);
        return restored;
    }

    /** 按清单从背包里收回物品；返回没能收回的件数。 */
    private static int revokeGranted(ServerPlayer player, List<ItemStack> granted) {
        int missing = 0;
        for (ItemStack want : granted) {
            int remaining = want.getCount();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack inSlot = player.getInventory().getItem(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameTags(inSlot, want)) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                inSlot.shrink(take);
                remaining -= take;
            }
            missing += remaining;
        }
        player.getInventory().setChanged();
        return missing;
    }

    /**
     * 按清单从 ME 网络里收回物品；返回没能收回的件数。
     * <p>
     * <b>抽不回来只算缺失，不做任何补救。</b> 东西可能已经被玩家从网络里取走、用掉，
     * 或者那一张网络已经拆了 —— 这时正确的做法是让地形照常还原、并如实报出少了几件，
     * 而不是「既然抽不回来就算了」。后者等于把撤销变成刷物品。
     * <p>
     * 网络列表只扫一次：清单里可能有几百种掉落物，每种扫一遍区块是几百次全量扫描，
     * 而撤销本来就是一按到底的操作，不该在它上面再叠几百次扫描。
     */
    private static int revokeAeGranted(ServerPlayer player, List<ItemStack> grantedToAe) {
        if (grantedToAe == null || grantedToAe.isEmpty()) {
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return countOf(grantedToAe);
        }
        List<Object> storages = Ae2ZoneSupply.findStorages(level, player.position());
        int missing = 0;
        for (ItemStack want : grantedToAe) {
            int taken = Ae2ZoneSupply.extractUpTo(storages, want, want.getCount(), player);
            missing += want.getCount() - taken;
        }
        return missing;
    }

    /** 玩家退出时清掉作业与撤销记录。 */
    public static void forget(UUID uuid) {
        JOBS.remove(uuid);
        LAST_COMPLETED.remove(uuid);
    }
}