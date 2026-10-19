package dev.modmind.kunjinkao.client.render;

import dev.modmind.kunjinkao.KunJinKaoPalette;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.Random;

/**
 * 命中那一刻在目标身上炸开的冲击波。
 * <p>
 * 原来打中一个生物，只有一段 HUD 文字和一个残影，没有任何<b>爆点</b>，
 * 而爽感恰恰全在这一瞬：打出去要有回应。
 * <p>
 * 做法是给粒子一个<b>向外的初速度</b>，让环自己扩出去 ——
 * 不需要像区域场那样维护一张"正在扩散的环"的表逐帧重画，省一份状态，观感一样。
 * <p>
 * 颜色取自 {@link KunJinKaoPalette}：品红 = 损坏，青 = 常态。
 * <p>
 * 【这里曾经还有一层全屏"屏幕故障"（水平撕裂带假装 RGB 分离）。
 * 用户看过之后要求去掉，已删除。】如果以后要做，注意它当时的挂法是
 * 注册成 {@code RegisterGuiLayersEvent} 的 registerAboveAll 层，
 * 并且挂在 registerAttackData 之后 —— 记在这里免得重新踩一遍。
 */
public final class SwordHitFeedback {

    private static final DustParticleOptions CORRUPT_DUST =
            new DustParticleOptions(new Vector3f(
                    KunJinKaoPalette.red(KunJinKaoPalette.CORRUPT),
                    KunJinKaoPalette.green(KunJinKaoPalette.CORRUPT),
                    KunJinKaoPalette.blue(KunJinKaoPalette.CORRUPT)), 0.85F);
    private static final DustParticleOptions NORMAL_DUST =
            new DustParticleOptions(new Vector3f(
                    KunJinKaoPalette.red(KunJinKaoPalette.NORMAL),
                    KunJinKaoPalette.green(KunJinKaoPalette.NORMAL),
                    KunJinKaoPalette.blue(KunJinKaoPalette.NORMAL)), 0.7F);

    private static final Random RANDOM = new Random();

    private SwordHitFeedback() {
    }

    /** 由 {@code KunJinKaoClientSwordVisuals.onLocalAttack} 调用。 */
    public static void trigger(Player player, Entity target) {
        if (player != null && target != null) {
            spawnShockwave(player.level(), target);
        }
    }

    private static void spawnShockwave(Level level, Entity target) {
        double cx = target.getX();
        double cy = target.getY() + target.getBbHeight() * 0.5D;
        double cz = target.getZ();

        int points = 20;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D / points * i;
            double radius = 0.5D + RANDOM.nextDouble() * 0.18D;
            level.addParticle(CORRUPT_DUST,
                    cx + Math.cos(angle) * radius,
                    cy + (RANDOM.nextDouble() - 0.5D) * 0.25D,
                    cz + Math.sin(angle) * radius,
                    Math.cos(angle) * 0.16D, 0.02D, Math.sin(angle) * 0.16D);
        }
        // 中心迸溅：几颗青色向上窜，给环一个"芯"
        for (int i = 0; i < 8; i++) {
            level.addParticle(NORMAL_DUST, cx, cy, cz,
                    (RANDOM.nextDouble() - 0.5D) * 0.3D,
                    0.12D + RANDOM.nextDouble() * 0.12D,
                    (RANDOM.nextDouble() - 0.5D) * 0.3D);
        }
    }
}