package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.KunJinKaoPalette;
import dev.modmind.kunjinkao.client.render.CosmeticRenderer;
import dev.modmind.kunjinkao.network.CosmeticStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端保存的装饰状态 + 每 tick 的推进。
 * <p>
 * 这里是"服务端只报状态、画面全在客户端"的那一半（另一半是 {@link CosmeticRenderer}）。
 * 每次收到 {@link CosmeticStatePayload} 都是<b>整表快照</b>：表里没有的人直接删掉，
 * 所以下线、关开关、掉线都不需要额外的清理消息。
 * <p>
 * 反击进度（{@code strikeTicks}）在两次快照之间由客户端每 tick 自己 +1：
 * 服务端每 10 tick 才补推一次，靠它逐帧显示刺击节奏会一顿一顿的。
 * 快照到了就以服务端的值为准（每 10 tick 重新对齐一次，误差不会累积）。
 */
@OnlyIn(Dist.CLIENT)
public final class CosmeticClientState {

    // ===== 光环与拖尾（203）=====
    /** 光环每几 tick 撒一圈。 */
    private static final int AURA_INTERVAL = 2;
    /** 光环半径（格）。 */
    private static final double AURA_RADIUS = 0.9D;
    /** 光环离脚底多高（格）。 */
    private static final double AURA_HEIGHT = 0.15D;
    /** 一圈的点数。 */
    private static final int AURA_POINTS = 10;
    /** 每 tick 转过的弧度：一圈约 4 秒。 */
    private static final double AURA_SPIN = 0.05D;
    /** 拖尾：走过这么远才补一段粒子，站着不动就不刷。 */
    private static final double TRAIL_STEP = 0.55D;
    /** 一次最多补几个点：瞬移几千格时用它封顶。 */
    private static final int TRAIL_MAX_POINTS = 8;

    private static final DustParticleOptions AURA_PARTICLE =
            dust(KunJinKaoPalette.COSMETIC_AURA, 1.05F);
    /** 拖尾用同一个淡蓝，只是更小更暗一档：同色系里靠尺寸分层，否则两团粒子会糊在一起。 */
    private static final DustParticleOptions TRAIL_PARTICLE =
            dust(KunJinKaoPalette.COSMETIC_AURA, 0.75F);

    /** 追击速度：每 tick 靠近剩余距离的这个比例。越近越慢，停在头顶时不会抖。 */
    private static final double STRIKE_CHASE = 0.45D;

    private static final Map<UUID, Tracked> TRACKED = new ConcurrentHashMap<>();

    private CosmeticClientState() {
    }

    /** 一个玩家的装饰状态，外加反击那一把剑的动画位置。 */
    public static final class Tracked {
        private final UUID player;
        private int flags;
        @Nullable
        private UUID strikeTarget;
        private int strikeTicks;
        /** 反击那一把剑：prev 是上一 tick 的位置、pos 是这一 tick，渲染时按 partialTick 插值。 */
        @Nullable
        private Vec3 swordPrev;
        @Nullable
        private Vec3 swordPos;
        private boolean trailSeeded;
        @Nullable
        private Vec3 trailFrom;

        private Tracked(UUID player) {
            this.player = player;
        }

        private void apply(CosmeticStatePayload.Entry entry) {
            this.flags = entry.flags();
            UUID target = entry.strikeTarget();
            if (!Objects.equals(this.strikeTarget, target)) {
                // 换目标（或者刚开始、刚结束）：剑的动画状态一起重置，下一 tick 重新从身后起飞。
                this.swordPrev = null;
                this.swordPos = null;
            }
            this.strikeTarget = target;
            this.strikeTicks = entry.strikeTicks();
        }

        public UUID player() {
            return player;
        }

        public int flags() {
            return flags;
        }

        @Nullable
        public UUID strikeTarget() {
            return strikeTarget;
        }

        public int strikeTicks() {
            return strikeTicks;
        }

        @Nullable
        public Vec3 swordPrev() {
            return swordPrev;
        }

        @Nullable
        public Vec3 swordPos() {
            return swordPos;
        }
    }

    /** 收到整表快照：表里没有的人直接删掉。 */
    public static void accept(List<CosmeticStatePayload.Entry> entries) {
        Set<UUID> present = new HashSet<>();
        for (CosmeticStatePayload.Entry entry : entries) {
            TRACKED.computeIfAbsent(entry.player(), Tracked::new).apply(entry);
            present.add(entry.player());
        }
        TRACKED.keySet().removeIf(uuid -> !present.contains(uuid));
    }

    public static void clear() {
        TRACKED.clear();
    }

    public static Collection<Tracked> tracked() {
        return TRACKED.values();
    }

    /** 按 UUID 在当前客户端世界里找玩家实体；不在（别的维度、已下线）返回 null。 */
    @Nullable
    public static AbstractClientPlayer findPlayer(ClientLevel level, UUID player) {
        for (AbstractClientPlayer candidate : level.players()) {
            if (candidate.getUUID().equals(player)) {
                return candidate;
            }
        }
        return null;
    }

    /** 每客户端 tick 调一次（{@code KunJinKaoClientEvents#onClientTick}）。 */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        for (Tracked tracked : TRACKED.values()) {
            AbstractClientPlayer player = findPlayer(level, tracked.player);
            if (player == null) {
                continue;
            }
            tickStrike(level, tracked, player);
            tickAura(level, tracked, player);
        }
    }

    /**
     * 反击：推进进度，并把"飞出去的那一把剑"往目标头顶挪。
     * <p>
     * 位置在这里按 tick 推进、由渲染层按 partialTick 插值，所以剑是平滑飞过去的；
     * 目标位置取的是<b>客户端本地</b>的敌人坐标 —— 这正是零延迟的来源。
     */
    private static void tickStrike(ClientLevel level, Tracked tracked, AbstractClientPlayer player) {
        UUID target = tracked.strikeTarget;
        if (target == null) {
            tracked.strikeTicks = 0;
            tracked.swordPrev = null;
            tracked.swordPos = null;
            return;
        }
        if (tracked.strikeTicks < CosmeticStatePayload.STRIKE_DURATION) {
            tracked.strikeTicks++;
        }
        if (tracked.swordPos == null) {
            // 第一次：把上一 tick 的位置放在它原本待的槽位上，看起来才是"从队伍里飞出去"。
            tracked.swordPrev = CosmeticRenderer.idleSwordPosition(player, 0);
        }
        Vec3 from = tracked.swordPos != null ? tracked.swordPos : tracked.swordPrev;
        AbstractClientPlayer victim = findPlayer(level, target);
        Vec3 head = victim == null ? null
                : new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() + CosmeticRenderer.STRIKE_HOVER,
                        victim.getZ());
        tracked.swordPrev = from;
        tracked.swordPos = head == null ? from : from.add(head.subtract(from).scale(STRIKE_CHASE));
    }

    /** 光环与拖尾：粒子在这里本地生成，所以跟着谁看都不延迟。 */
    private static void tickAura(ClientLevel level, Tracked tracked, AbstractClientPlayer player) {
        if ((tracked.flags & CosmeticStatePayload.FLAG_AURA) == 0) {
            tracked.trailSeeded = false;
            return;
        }
        if (player.tickCount % AURA_INTERVAL == 0) {
            double phase = player.tickCount * AURA_SPIN;
            for (int i = 0; i < AURA_POINTS; i++) {
                double angle = phase + i * (Math.PI * 2.0D / AURA_POINTS);
                level.addParticle(AURA_PARTICLE,
                        player.getX() + Math.cos(angle) * AURA_RADIUS,
                        player.getY() + AURA_HEIGHT,
                        player.getZ() + Math.sin(angle) * AURA_RADIUS,
                        0.0D, 0.01D, 0.0D);
            }
        }

        // 拖尾按"走过的距离"补点，不按 tick：站着不动不该在原地堆一团粒子，
        // 而跑起来时两点之间要补满，不然速度快了尾迹会断成一节一节。
        Vec3 now = player.position();
        Vec3 from = tracked.trailFrom;
        tracked.trailFrom = now;
        if (!tracked.trailSeeded) {
            tracked.trailSeeded = true;
            return;
        }
        if (from == null) {
            return;
        }
        double distance = now.distanceTo(from);
        if (distance < TRAIL_STEP) {
            return;
        }
        // 瞬移（回城、传送门、/tp 几千格）时按上限截断，否则一 tick 就在整条线上撒满粒子。
        int points = (int) Math.min(TRAIL_MAX_POINTS, Math.ceil(distance / TRAIL_STEP));
        for (int i = 1; i <= points; i++) {
            double t = i / (double) points;
            level.addParticle(TRAIL_PARTICLE,
                    from.x + (now.x - from.x) * t,
                    from.y + (now.y - from.y) * t + 0.25D,
                    from.z + (now.z - from.z) * t,
                    0.0D, 0.0D, 0.0D);
        }
    }

    private static DustParticleOptions dust(int argb, float scale) {
        return new DustParticleOptions(new Vector3f(
                KunJinKaoPalette.red(argb), KunJinKaoPalette.green(argb), KunJinKaoPalette.blue(argb)), scale);
    }
}