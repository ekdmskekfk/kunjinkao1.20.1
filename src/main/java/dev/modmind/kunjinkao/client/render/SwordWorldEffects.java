package dev.modmind.kunjinkao.client.render;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.network.PlacementSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 剑在世界里留下的痕迹。
 * <p>
 * 与 {@link SwordAmbientParticles} 的分工：那边画"拿在手里时的存在感"，
 * 这边画"用出去之后留在世界上的东西"。两件：
 * <ul>
 *   <li><b>立起/收回区域场</b>：在那一格的地面扩散一圈扫描环；</li>
 *   <li><b>掉在地上的剑</b>：向上打一束细光柱，让人在远处也能一眼看见它在哪。</li>
 * </ul>
 * 都用粒子实现 —— 不碰渲染管线，不会与别的模组抢注入口。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwordWorldEffects {

    private static final DustParticleOptions RING_INNER =
            new DustParticleOptions(new Vector3f(0.69F, 0.95F, 1.00F), 0.7F);
    private static final DustParticleOptions RING_OUTER =
            new DustParticleOptions(new Vector3f(0.34F, 0.81F, 1.00F), 0.55F);
    private static final DustParticleOptions PILLAR =
            new DustParticleOptions(new Vector3f(0.42F, 0.88F, 1.00F), 0.45F);

    /** 扫描环持续多少 tick 扩完。 */
    private static final int RING_TICKS = 12;
    /** 扫描环最大半径（格）。 */
    private static final float RING_RADIUS = 2.6F;
    /** 掉在地上的剑，多久看一眼。 */
    private static final int PILLAR_INTERVAL = 2;
    /** 光柱的搜索半径与最多同时处理的数量 —— 必须设上限，否则大包里会卡。 */
    private static final double PILLAR_RANGE = 16.0D;
    private static final int PILLAR_MAX_ITEMS = 4;
    /** 光柱高度（格）。 */
    private static final double PILLAR_HEIGHT = 2.2D;

    /** 正在扩散的扫描环。 */
    private record Ring(BlockPos pos, ResourceKey<Level> dimension, int age) {
    }

    private static final List<Ring> RINGS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static int pillarTicker;

    private SwordWorldEffects() {
    }

    /**
     * 收到最新的放置记录表时调（由 {@code PlacementSyncPayload} 转发）。
     * <p>
     * 只对【新出现】的记录放环：收回场地不该也炸一下，
     * 否则分不清这次是立起来还是撤掉了。
     */
    public static void onPlacementSync(List<PlacementSyncPayload.Entry> before,
                                       List<PlacementSyncPayload.Entry> after) {
        if (after.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        for (PlacementSyncPayload.Entry entry : before) {
            known.add(key(entry));
        }
        for (PlacementSyncPayload.Entry entry : after) {
            if (known.add(key(entry))) {
                RINGS.add(new Ring(entry.pos(), dimensionOf(entry), 0));
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 1.20.1 的客户端 tick 事件没有 .Post 子类，靠 Phase 区分。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null || minecraft.isPaused()) {
            return;
        }

        tickRings(level);
        if (++pillarTicker >= PILLAR_INTERVAL) {
            pillarTicker = 0;
            emitDroppedSwordPillars(player, level);
        }
    }

    /** 把每个环的当前位置画出来，并推进年龄；跑完就丢掉。 */
    private static void tickRings(Level level) {
        if (RINGS.isEmpty()) {
            return;
        }
        RINGS.removeIf(ring -> ring.age() >= RING_TICKS
                || !ring.dimension().equals(level.dimension()));
        for (int i = 0; i < RINGS.size(); i++) {
            Ring ring = RINGS.get(i);
            float progress = (ring.age() + 1) / (float) RING_TICKS;
            float radius = RING_RADIUS * progress;
            int points = 18;
            for (int p = 0; p < points; p++) {
                double angle = (Math.PI * 2 / points) * p + progress * 0.6;
                double x = ring.pos().getX() + 0.5 + Math.cos(angle) * radius;
                double z = ring.pos().getZ() + 0.5 + Math.sin(angle) * radius;
                double y = ring.pos().getY() + 0.15;
                level.addParticle(p % 3 == 0 ? RING_INNER : RING_OUTER, x, y, z, 0.0, 0.004, 0.0);
            }
            RINGS.set(i, new Ring(ring.pos(), ring.dimension(), ring.age() + 1));
        }
    }

    /**
     * 附近掉在地上的剑，各打一束向上的光柱。
     * <p>
     * 搜索范围与数量都设了上限：一个整合包里可能的掉落物很多，
     * 无上限地每 2 tick 全图扫描会把客户端拖垮。
     */
    private static void emitDroppedSwordPillars(LocalPlayer player, Level level) {
        AABB area = player.getBoundingBox().inflate(PILLAR_RANGE);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area);
        int handled = 0;
        for (ItemEntity item : items) {
            if (handled >= PILLAR_MAX_ITEMS) {
                break;
            }
            ItemStack stack = item.getItem();
            if (!(stack.getItem() instanceof KunJinKaoSwordItem)) {
                continue;
            }
            handled++;
            double baseX = item.getX();
            double baseY = item.getY();
            double baseZ = item.getZ();
            // 主柱：细而直，带一点点上浮
            int column = 2 + RANDOM.nextInt(2);
            for (int i = 0; i < column; i++) {
                double t = i / (double) column;
                level.addParticle(PILLAR,
                        baseX + (RANDOM.nextDouble() - 0.5) * 0.16,
                        baseY + 0.25 + t * PILLAR_HEIGHT,
                        baseZ + (RANDOM.nextDouble() - 0.5) * 0.16,
                        0.0, 0.012, 0.0);
            }
            // 底部一圈光晕，让光柱"接地"
            if (RANDOM.nextInt(2) == 0) {
                double angle = RANDOM.nextDouble() * Math.PI * 2;
                level.addParticle(RING_INNER,
                        baseX + Math.cos(angle) * 0.28,
                        baseY + 0.12,
                        baseZ + Math.sin(angle) * 0.28,
                        0.0, 0.0, 0.0);
            }
            // 偶尔一颗发光棒粒子，拉高射程感
            if (RANDOM.nextInt(6) == 0) {
                level.addParticle(ParticleTypes.END_ROD, baseX, baseY + 0.4 + RANDOM.nextDouble(),
                        baseZ, 0.0, 0.02, 0.0);
            }
        }
    }

    private static String key(PlacementSyncPayload.Entry entry) {
        return entry.kind() + "@" + entry.dimension() + "@" + entry.pos().asLong();
    }

    private static ResourceKey<Level> dimensionOf(PlacementSyncPayload.Entry entry) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, entry.dimension());
    }
}