package dev.modmind.kunjinkao.block.entity;

import dev.modmind.kunjinkao.AcceleratorRegistry;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.modmind.kunjinkao.compat.Ae2GridAcceleration;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 时间加速器方块实体。
 * <p>
 * 每个游戏刻按设定倍率对周围范围内的方块进行"加速"：
 * <ul>
 *   <li>有方块实体（熔炉、漏斗、信标等机器）的方块，额外调用其原版 ticker 若干次；</li>
 *   <li>没有方块实体但会随机刻（作物、树苗、草方块等）的方块，额外调用其 randomTick 若干次。</li>
 * </ul>
 * 倍率与范围通过右键打开的 GUI 调节，并持久化到 NBT。
 * <p>
 * 1.20.1 移植要点（都是机械改动，逻辑一字未动）：
 * <ul>
 *   <li>{@code saveAdditional(tag, registries)} / {@code loadAdditional(tag, registries)}
 *       → {@code saveAdditional(tag)} / {@code load(tag)}；</li>
 *   <li>{@code ItemStack#save(registries)} → {@code save(new CompoundTag())}，
 *       {@code ItemStack.parseOptional(registries, tag)} → {@code ItemStack.of(tag)}；</li>
 *   <li>{@code DataComponents.BLOCK_ENTITY_DATA} 组件 → {@link BlockItem#getBlockEntityData(ItemStack)}；</li>
 *   <li>{@code saveWithoutMetadata(registryAccess())} → {@code saveWithoutMetadata()}。</li>
 * </ul>
 */
public class AcceleratorBlockEntity extends BlockEntity {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    /** 可选加速倍率：4 / 8 / 16 / 32 / 64 / 128 / 256 / 512 / 1024 倍。 */
    public static final int[] MULTIPLIERS = {4, 8, 16, 32, 64, 128, 256, 512, 1024};

    /** 可选范围（半径的一半）：1 → 3x3x3，2 → 5x5x5，3 → 7x7x7，4 → 9x9x9。 */
    public static final int[] RADII = {1, 2, 3, 4};

    /**
     * 每个方块实体每刻允许执行的额外 tick 次数上限。
     * <p>
     * 1024 倍 × 9×9×9 理论上每刻要做约 74.5 万次 tick，足以拖垮 TPS。这里给出硬预算：
     * 预算耗尽后本刻不再继续加速（下一 tick 继续），把最坏情况限制在可控范围内。
     */
    public static final int MAX_EXTRA_TICKS_PER_TICK = 4096;

    /** 允许修改配置的最大距离（格）的平方：约 8 格。 */
    public static final double MAX_EDIT_DISTANCE_SQR = 64.0D;

    /** 过滤列表槽位数（GUI 里排成一行）。 */
    public static final int FILTER_SLOTS = 9;

    private static final String TAG_MULTIPLIER = "Multiplier";
    private static final String TAG_RADIUS = "Radius";
    private static final String TAG_SHOW_RANGE = "ShowRange";
    private static final String TAG_FILTER = "Filter";
    private static final String TAG_WHITELIST = "FilterWhitelist";
    private static final String TAG_MATCH_NBT = "FilterMatchNbt";

    private int multiplier = MULTIPLIERS[0];
    private int radius = RADII[0];
    private boolean showRange = false;

    /** 过滤列表：只放方块物品；空槽为 EMPTY。列表里一个都没有时视为"不过滤"。 */
    private final NonNullList<ItemStack> filter = NonNullList.withSize(FILTER_SLOTS, ItemStack.EMPTY);
    /** true = 白名单（只加速命中的），false = 黑名单（命中的不加速）。 */
    private boolean whitelist = true;
    /** 是否要求命中项的 NBT 与过滤项记录的基准一致。 */
    private boolean matchNbt = false;

    public AcceleratorBlockEntity(BlockPos pos, BlockState state) {
        super(AcceleratorRegistry.ACCELERATOR_BE.get(), pos, state);
    }

    /** 由方块 {@code getTicker} 注册的服务端 ticker。 */
    public static void serverTick(Level level, BlockPos pos, BlockState state, AcceleratorBlockEntity self) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        self.accelerateSurroundings(serverLevel, pos);
    }

    private void accelerateSurroundings(ServerLevel serverLevel, BlockPos pos) {
        accelerateArea(serverLevel, pos, this.radius, this.multiplier, this);
        // 方块之外，生物同样在这个立方体里被加速 —— 否则把机器调到 1024 倍，
        // 走进去的怪还是原速，看起来像"只加速了一半"。
        accelerateEntitiesInArea(serverLevel, pos, this.radius, this.multiplier);
    }

    /**
     * 对一个立方区域做时间加速：区域内每个方块被额外 tick（倍率 - 1）次。
     * <p>
     * 独立成公开静态方法，是为了让剑的"时间加速"复用<b>同一套</b>逻辑 ——
     * 否则两份实现早晚会在每刻预算与过滤器判定上跑偏。
     *
     * @param filterOwner 持过滤器的一方；传 {@code null} 表示不过滤（剑的加速没有过滤列表）
     */
    public static void accelerateArea(ServerLevel serverLevel, BlockPos pos, int radius, int multiplier,
                                      @Nullable AcceleratorBlockEntity filterOwner) {
        int half = radius;
        int extra = multiplier - 1;
        if (extra <= 0) {
            return;
        }
        RandomSource random = serverLevel.random;
        int budget = MAX_EXTRA_TICKS_PER_TICK;

        for (int dx = -half; dx <= half; dx++) {
            for (int dy = -half; dy <= half; dy++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (budget <= 0) {
                        return;
                    }
                    BlockPos target = pos.offset(dx, dy, dz);
                    // 刻意不跳过中心格：加速方块自己由 tickBlockEntityExtra 里的
                    // AcceleratorBlockEntity 判断排除即可，而剑的"单台加速"中心格
                    // 正是要加速的那台机器 —— 在这里一刀切跳过会把它漏掉。
                    BlockState targetState = serverLevel.getBlockState(target);
                    if (targetState.isAir()) {
                        continue;
                    }
                    BlockEntity targetEntity = serverLevel.getBlockEntity(target);
                    // 过滤：列表为空直接放行；否则按黑白名单决定是否加速
                    if (filterOwner != null && !filterOwner.matchesFilter(serverLevel, targetState, targetEntity)) {
                        continue;
                    }
                    if (targetEntity != null) {
                        budget -= tickBlockEntityExtra(serverLevel, target, targetState, targetEntity, extra, budget);
                    } else if (targetState.isRandomlyTicking()) {
                        int calls = Math.min(extra, budget);
                        for (int i = 0; i < calls; i++) {
                                // 复刻原版随机刻的概率（每刻 1/1365）。无条件调用 randomTick
                                // 等于把随机刻频率放大一千多倍 —— 那不是加速，是把世界烧了。
                                if (random.nextInt(1365) == 0) {
                                    targetState.randomTick(serverLevel, target, random);
                                }
                        }
                        budget -= calls;
                    }
                }
            }
        }
    }

    /**
     * 每刻允许执行的额外<b>生物</b> tick 次数上限（一次调用内、范围内所有生物合计）。
     * <p>
     * 与方块的 {@link #MAX_EXTRA_TICKS_PER_TICK} 分开：实体 tick 比方块实体 tick 贵
     * 一个数量级（AI、寻路、碰撞、药水与伤害计时全在里头），4096 次实体 tick 足够把
     * TPS 打到个位数。这里单独收窄，并按范围内生物数量<b>均分</b>：走进场里的每一只
     * 都真的被加速，而不是迭代顺序靠前的吃满、后面的饿死。
     */
    public static final int MAX_EXTRA_ENTITY_TICKS_PER_TICK = 512;

    /**
     * 对一个立方区域里的生物做时间加速。
     * <p>
     * 语义与 {@link #accelerateArea} 的方块加速对应：区域内每只生物额外 tick
     * （倍率 - 1）次，随后把<b>位移、朝向、速度原样还原</b>。留下的是每刻自减的计时器
     * （成长、繁殖冷却、受伤无敌帧、药水时长、攻击冷却……），而不是"鸡满场乱飞"——
     * {@code tick()} 同时会跑 AI 与物理，副作用不能留在外面。
     * <p>
     * 只加速 {@link LivingEntity}：掉落物、经验球、箭矢不算"生物"，把它们的消失计时
     * 一起加速，表现就是"刚掉在地上的东西转眼没了"。玩家同样排除：把玩家 tick 多次
     * 会让移动与攻击判定在同一刻重复触发。
     *
     * @param radius 立方区域半径，与 {@link #accelerateArea} 的 radius 同义
     */
    public static void accelerateEntitiesInArea(ServerLevel serverLevel, BlockPos pos, int radius, int multiplier) {
        int extra = multiplier - 1;
        if (extra <= 0 || radius < 0) {
            return;
        }
        AABB box = new AABB(pos).inflate(radius);
        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, box,
                candidate -> !(candidate instanceof Player) && candidate.isAlive());
        if (targets.isEmpty()) {
            return;
        }
        int calls = Math.max(1, Math.min(extra, MAX_EXTRA_ENTITY_TICKS_PER_TICK / targets.size()));
        for (LivingEntity entity : targets) {
            if (entity.isRemoved()) {
                continue;
            }
            double x = entity.getX();
            double y = entity.getY();
            double z = entity.getZ();
            float yaw = entity.getYRot();
            float pitch = entity.getXRot();
            float headYaw = entity.getYHeadRot();
            Vec3 motion = entity.getDeltaMovement();
            for (int i = 0; i < calls && !entity.isRemoved(); i++) {
                entity.tick();
            }
            entity.setPos(x, y, z);
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            entity.setYHeadRot(headYaw);
            entity.setDeltaMovement(motion);
            // 同步"上一帧位置"，否则客户端会朝新位置插值，看起来像瞬移了一下再弹回来。
            entity.setOldPosAndRot();
        }
    }

    /**
     * 这个位置"能不能被加速"：有会 tick 的方块实体，或者方块本身会随机 tick。
     * <p>
     * 判定标准与 {@link #accelerateArea} 实际会处理的对象完全一致 ——
     * 所以"可以加速的东西"在剑和加速器两边含义相同，不会出现
     * "右键说命中了、实际却什么都没加速"的情况。
     */
    public static boolean isAcceleratable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            return state.isRandomlyTicking();
        }
        // 加速器自己不再被加速：tickBlockEntityExtra 里同样把它排除掉了。
        if (entity instanceof AcceleratorBlockEntity) {
            return false;
        }
        // 用 BlockState.getTicker 而不是 (EntityBlock) 强转 + getTicker：
        // 前者是模组界的通行写法（无用之物的 tickTarget 就是这么拿的），
        // 而且不要求方块实现 EntityBlock 接口，能多覆盖一类机器。
        if (state.getTicker(level, entity.getType()) != null) {
            return true;
        }
        // AE2 那类 ME 网络机器：tick 由网络调度，走 IGridTickable，上面两条都拿不到。
        if (Ae2GridAcceleration.hasTickable(level, pos)) {
            return true;
        }
        // AE2 这类模组不走原版 ticker：方块实体自己实现 serverTick()，由模组自己的调度器驱动
        // （AE2 是 appeng/hooks/ticking/TickHandler）。这种机器同样能被额外 tick 加速，
        // 只是入口不是 getTicker —— 认它，否则"可加速"会把这些机器整类拒掉。
        return findServerTick(entity) != null;
    }

    /**
     * 额外调用目标方块实体的原版 ticker（使用原始类型规避泛型捕获问题）。
     *
     * @return 实际执行的 tick 次数，供调用方扣减每刻预算
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int tickBlockEntityExtra(Level level, BlockPos pos, BlockState state, BlockEntity entity, int extra, int budget) {
        if (entity instanceof AcceleratorBlockEntity) {
            return 0;
        }
        BlockEntityTicker ticker = state.getTicker(level, entity.getType());
        if (ticker == null) {
            int viaGrid = Ae2GridAcceleration.tick(level, pos, extra, budget);
            if (viaGrid > 0) {
                return viaGrid;
            }
            return tickServerTickMethod(level, entity, extra, budget);
        }
        int calls = Math.min(extra, budget);
        for (int i = 0; i < calls; i++) {
            ticker.tick(level, pos, state, entity);
        }
        return calls;
    }

    /** serverTick() 的查找结果缓存：反射只在每个类上做一次。 */
    private static final java.util.Map<Class<?>, java.util.Optional<java.lang.reflect.Method>>
            SERVER_TICK_CACHE = new java.util.HashMap<>();

    /**
     * 找出方块实体自己实现的 {@code serverTick()}（无参）。
     * <p>
     * 这类机器（AE2 是典型）把 tick 入口做成了方块实体的方法，由模组自己的调度器驱动，
     * 因此不注册原版 BlockEntityTicker。它仍然是"这台机器每一刻要做的事"，
     * 额外调用它就是加速这台机器本身 —— 和其他方块走 getTicker 是同一件事，只是入口不同。
     *
     * @return 可调用的无参 serverTick；没有则返回 null
     */
    private static java.lang.reflect.Method findServerTick(BlockEntity entity) {
        if (entity instanceof AcceleratorBlockEntity) {
            return null;
        }
        return SERVER_TICK_CACHE.computeIfAbsent(entity.getClass(), type -> {
            try {
                java.lang.reflect.Method method = type.getMethod("serverTick");
                return method.getParameterCount() == 0
                        ? java.util.Optional.of(method)
                        : java.util.Optional.empty();
            } catch (NoSuchMethodException | SecurityException e) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    /** 额外调用机器自己的 serverTick()。 */
    private static int tickServerTickMethod(Level level, BlockEntity entity, int extra, int budget) {
        java.lang.reflect.Method method = findServerTick(entity);
        if (method == null) {
            return 0;
        }
        int calls = Math.min(extra, budget);
        for (int i = 0; i < calls; i++) {
            try {
                method.invoke(entity);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 调用失败就放弃这类方块，避免每刻刷异常、也避免把服务器拖住。
                LOGGER.debug("[ACCEL] serverTick() 调用失败，跳过 {}: {}", entity.getClass().getName(), e.toString());
                return i;
            }
        }
        return calls;
    }

    /**
     * 加速方块配置包的共用闸门：授权 + 交互距离。
     * <p>
     * 加速倍率直接决定方块 tick 次数，是"能拖垮服务器"的配置，因此只允许通过管理员验证的玩家修改；
     * 距离校验用于阻止改造客户端远程改写任意已加载区块里的加速器。
     *
     * @return true 表示允许继续修改
     */
    public static boolean mayEdit(ServerPlayer player, BlockPos pos) {
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.admin_required"), true);
            return false;
        }
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_EDIT_DISTANCE_SQR;
    }


    public ItemStack getFilterEntry(int slot) {
        return slot >= 0 && slot < FILTER_SLOTS ? this.filter.get(slot) : ItemStack.EMPTY;
    }

    /**
     * 默认包围盒只有方块本体 1x1x1，而范围框最大到 9x9x9；
     * 方块本体离开视锥时整块范围框会一并消失，因此按加速半径扩大包围盒。
     * <p>
     * 1.20.1 的钩子在<b>方块实体</b>上（{@code IForgeBlockEntity#getRenderBoundingBox()}）；
     * 1.21 挪到了方块实体渲染器（{@code BlockEntityRenderer#getRenderBoundingBox(T)}），
     * 所以这个方法从渲染器搬到了这里。
     */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(this.worldPosition).inflate(this.radius + 1.0D);
    }
    /** 过滤列表里是否至少有一项。 */
    public boolean hasFilterEntries() {
        for (int i = 0; i < FILTER_SLOTS; i++) {
            if (!this.filter.get(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public boolean isWhitelist() {
        return this.whitelist;
    }

    public boolean isMatchNbt() {
        return this.matchNbt;
    }

    /**
     * 服务端校验后写入整份过滤状态（客户端发来的列表不可信）。
     * <p>
     * 只接受方块物品、数量归 1、只取前 {@link #FILTER_SLOTS} 项；返回是否真的有改动。
     */
    public boolean applyFilter(java.util.List<ItemStack> incoming, boolean whitelist, boolean matchNbt) {
        boolean changed = false;
        for (int i = 0; i < FILTER_SLOTS; i++) {
            ItemStack wanted = i < incoming.size() ? incoming.get(i) : ItemStack.EMPTY;
            ItemStack sanitized = ItemStack.EMPTY;
            if (!wanted.isEmpty() && wanted.getItem() instanceof BlockItem) {
                sanitized = wanted.copy();
                sanitized.setCount(1);
            }
            if (!ItemStack.matches(this.filter.get(i), sanitized)) {
                this.filter.set(i, sanitized);
                changed = true;
            }
        }
        if (this.whitelist != whitelist) {
            this.whitelist = whitelist;
            changed = true;
        }
        if (this.matchNbt != matchNbt) {
            this.matchNbt = matchNbt;
            changed = true;
        }
        return changed;
    }

    /** 过滤项记录的 NBT 基准（方块物品的 BlockEntityTag）；没有记录则返回 null。 */
    private static CompoundTag filterNbtBasis(ItemStack entry) {
        return BlockItem.getBlockEntityData(entry);
    }

    /**
     * 目标方块是否通过过滤。
     * <p>
     * 语义（与 GUI 上的两个开关对应）：
     * <ul>
     *   <li>列表为空 → 一律通过（不过滤）；</li>
     *   <li>白名单 → 只有命中列表的才加速；黑名单 → 命中列表的反而不加速；</li>
     *   <li>开启"匹配 NBT"时，命中项还要求目标方块实体的 NBT 与过滤项记录的基准**完全相同**；
     *       过滤项本身没有记录 NBT 时只按方块类型判定（该项不受开关影响）。</li>
     * </ul>
     */
    public boolean matchesFilter(Level level, BlockState state, BlockEntity targetEntity) {
        if (!hasFilterEntries()) {
            return true;
        }
        boolean matched = false;
        for (int i = 0; i < FILTER_SLOTS; i++) {
            ItemStack entry = this.filter.get(i);
            if (entry.isEmpty() || !(entry.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (blockItem.getBlock() != state.getBlock()) {
                continue;
            }
            if (this.matchNbt) {
                CompoundTag basis = filterNbtBasis(entry);
                if (basis != null) {
                    if (targetEntity == null) {
                        continue;
                    }
                    CompoundTag actual = targetEntity.saveWithoutMetadata();
                    if (!basis.equals(actual)) {
                        continue;
                    }
                }
            }
            matched = true;
            break;
        }
        return this.whitelist == matched;
    }

    public int getMultiplier() {
        return this.multiplier;
    }

    public void setMultiplier(int multiplier) {
        this.multiplier = clampMultiplier(multiplier);
    }

    /** 返回范围半径的一半（1..4），对应 3x3x3 ~ 9x9x9。 */
    public int getRadius() {
        return this.radius;
    }

    public void setRadius(int radius) {
        this.radius = clampRadius(radius);
    }

    /** 是否显示蓝色半透明加速范围框。 */
    public boolean shouldShowRange() {
        return this.showRange;
    }

    public void setShowRange(boolean showRange) {
        this.showRange = showRange;
    }

    public static int clampMultiplier(int value) {
        for (int candidate : MULTIPLIERS) {
            if (candidate == value) {
                return value;
            }
        }
        return MULTIPLIERS[0];
    }

    public static int clampRadius(int value) {
        for (int candidate : RADII) {
            if (candidate == value) {
                return value;
            }
        }
        return RADII[0];
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(TAG_MULTIPLIER, this.multiplier);
        tag.putInt(TAG_RADIUS, this.radius);
        tag.putBoolean(TAG_SHOW_RANGE, this.showRange);
        ListTag filterTag = new ListTag();
        for (int i = 0; i < FILTER_SLOTS; i++) {
            ItemStack entry = this.filter.get(i);
            if (entry.isEmpty()) {
                continue;
            }
            CompoundTag slotTag = new CompoundTag();
            slotTag.putByte("Slot", (byte) i);
            slotTag.put("Item", entry.save(new CompoundTag()));
            filterTag.add(slotTag);
        }
        tag.put(TAG_FILTER, filterTag);
        tag.putBoolean(TAG_WHITELIST, this.whitelist);
        tag.putBoolean(TAG_MATCH_NBT, this.matchNbt);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.multiplier = clampMultiplier(tag.getInt(TAG_MULTIPLIER));
        this.radius = clampRadius(tag.getInt(TAG_RADIUS));
        this.showRange = tag.getBoolean(TAG_SHOW_RANGE);
        for (int i = 0; i < FILTER_SLOTS; i++) {
            this.filter.set(i, ItemStack.EMPTY);
        }
        ListTag filterTag = tag.getList(TAG_FILTER, CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < filterTag.size(); index++) {
            CompoundTag slotTag = filterTag.getCompound(index);
            int slot = slotTag.getByte("Slot") & 0xFF;
            if (slot >= FILTER_SLOTS) {
                continue;
            }
            this.filter.set(slot, ItemStack.of(slotTag.getCompound("Item")));
        }
        this.whitelist = !tag.contains(TAG_WHITELIST) || tag.getBoolean(TAG_WHITELIST);
        this.matchNbt = tag.getBoolean(TAG_MATCH_NBT);
    }
}