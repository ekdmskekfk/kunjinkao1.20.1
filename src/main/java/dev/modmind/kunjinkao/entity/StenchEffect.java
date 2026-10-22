package dev.modmind.kunjinkao.entity;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 骚臭：被君君的尿液喷中后获得，持续 5 分钟（1.20.1 Forge 版）。
 *
 * <p>与 1.21.1 的差别：那边逻辑写在 {@code MobEffect#applyEffectTick} 里；
 * 1.20.1 的这个方法签名不同（返回 void、另有一个 {@code isDurationEffectTick} 门控），
 * 为了两边行为一致、少踩版本差异，这里<b>效果只做数据</b>，真正的每 tick 逻辑
 * 放在 {@code StenchTickHandler}（{@code LivingTickEvent}）里。</p>
 *
 * <p>三个效果：所有生物远离、60 秒后臭死、黄色粒子。</p>
 */
public class StenchEffect extends MobEffect {

    /** 效果总时长：5 分钟。 */
    public static final int DURATION_TICKS = 20 * 60 * 5;
    /** 从"被喷到"到"臭死"的时长：60 秒。 */
    public static final int DEATH_AFTER_TICKS = 20 * 60;

    /** 自定义伤害类型（data/kunjinkao/damage_type/stench.json）。 */
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath("kunjinkao", "stench"));

    public StenchEffect() {
        super(MobEffectCategory.HARMFUL, 0xE8C63A);
    }

    /** 构造「臭死」伤害来源；数据包缺失时回落到普通伤害，不至于崩。 */
    public static DamageSource stenchSource(LivingEntity entity) {
        var registry = entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> holder = registry.getHolder(DAMAGE_TYPE)
                .map(h -> (Holder<DamageType>) h)
                .orElseGet(() -> registry.getHolderOrThrow(net.minecraft.world.damagesource.DamageTypes.GENERIC));
        return new DamageSource(holder, null, null);
    }

    /** 让生物远离臭源：推一次寻路 + 清掉以它为目的的仇恨。 */
    public static void scareNearbyMobs(LivingEntity stinky) {
        AABB area = stinky.getBoundingBox().inflate(16.0D);
        for (Mob mob : stinky.level().getEntitiesOfClass(Mob.class, area, m -> m != stinky && m.isAlive())) {
            if (mob.hasEffect(KunJinKaoEffects.STENCH.get())) {
                continue;
            }
            if (mob.getTarget() == stinky) {
                mob.setTarget(null);
            }
            Vec3 away = mob.position().subtract(stinky.position());
            if (away.lengthSqr() < 1.0E-4D) {
                away = new Vec3(1.0D, 0.0D, 0.0D);
            }
            away = away.normalize().scale(8.0D);
            mob.getNavigation().moveTo(mob.getX() + away.x, mob.getY(), mob.getZ() + away.z, 1.4D);
        }
    }

    public static void spawnStenchParticles(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.FALLING_HONEY,
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.6D, entity.getZ(),
                3, 0.35D, 0.25D, 0.35D, 0.0D);
    }

    /** 剩余时长反推已经过的 tick 数（被刷新时 duration 回到满值，计时自动重来）。 */
    public static int elapsedTicks(LivingEntity entity) {
        MobEffectInstance instance = entity.getEffect(KunJinKaoEffects.STENCH.get());
        return instance == null ? -1 : DURATION_TICKS - instance.getDuration();
    }
}
