package dev.modmind.kunjinkao.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 君君：一只贴地蠕动的手（原名"锟斤拷之手"）。
 * <p>
 * 定位改过一次，而且改得很彻底：它<b>不是武器，是受害者</b>。
 * <ul>
 *   <li><b>不攻击任何人</b>：没有近战 goal、没有目标 goal，连 {@code doHurtTarget} 都不重写 ——
 *       它对这个世界唯一的互动是"看一眼"和"跑"；</li>
 *   <li><b>被打就跑</b>：{@link PanicGoal} 挂在最高优先级，受伤的那一 tick 就开始逃，
 *       别的 goal 不许插队；</li>
 *   <li><b>几乎所有会打架的生物都恨它</b>：这条不在实体里做，而是给<b>别人</b>注入目标
 *       （见 {@code KunJinKaoHandHateHandler}）—— 因为"谁恨谁"是对方的目标选择器说了算。</li>
 * </ul>
 * <p>
 * 父类因此从 {@code Monster} 换成了 {@link PathfinderMob}：Monster 会把"怪物掉落、
 * 怪物类 despawn、怪物音效"那套行为一起带进来，而它现在是一只被全世界追着打的可怜小东西。
 * 造型没变：手背朝上、四指并拢朝前、指甲又长又白、腕部断口。
 * <p>
 * 蠕动依旧是<b>动画而不是物理</b>（见 {@code KunJinKaoHandModel}）：四根手指带相位差地做波浪，
 * 实体这层只用最普通的步行寻路。它跑得比僵尸快一点（0.32）—— 跑不掉的"逃跑"没有观赏性。
 */
public class KunJinKaoHandEntity extends PathfinderMob {

    public KunJinKaoHandEntity(EntityType<? extends KunJinKaoHandEntity> type, Level level) {
        super(type, level);
        this.xpReward = 3;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return net.minecraft.world.entity.Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.32D)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2D);
                // 1.20.1 没有 STEP_HEIGHT 属性（1.20.5 才引入），步高用默认值 0.6 —— 与设计意图一致。
    }

    @Override
    protected void registerGoals() {
        // 逃跑：最高优先级。被打了什么都不管，先跑。
        this.goalSelector.addGoal(0, new PanicGoal(this, 1.7D));
        // 平时在地上慢慢挪。
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        // 喷尿：站在中距离朝目标滋一泡。
        this.goalSelector.addGoal(1, new SprayUrineGoal(this));
        // 现在会主动找人（MobCategory 已改成 MONSTER）。
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
        // 没有 targetSelector 的 goal：它谁也不恨，谁也不打。
    }

    /**
     * 贴地的东西不该吃摔落伤害：它能从两格高的地方掉下去，但"啪"一下摔掉血很出戏。
     */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        // 指骨。用原版的骨头而不是新加一个物品：这东西的价值在"长得吓人"，
        // 不在掉落物上；真要给它做专属掉落，应该连配方一起设计，而不是先扔一个用不上的东西。
        this.spawnAtLocation(new ItemStack(Items.BONE, 1 + this.random.nextInt(2)));
        if (this.random.nextFloat() < 0.25F) {
            this.spawnAtLocation(new ItemStack(Items.LEATHER));
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        // 借蜘蛛的音效：细碎的爬行系声音，比造一套新音效省事且不出戏。
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        // 被打时的声音很重要 —— 它没有攻击，惨叫就是它全部的表达。
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SPIDER_DEATH;
    }

    /**
     * 宠物语义：放出来就一直在，远处不自动清除。
     * 用户是管理员，杀掉它的成本为零；自己消失的成本是"他又得去拿刷怪蛋"。
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /** 让模型知道"现在该蠕动得多用力"：移动时用走路的摆幅，站着时留一点轻微蠕动。 */
    // ===== 喷尿攻击 =====

    /** 朝目标滋一泡尿：沿视线撒土黄色粒子，命中则施加「骚臭」。 */
    public void sprayAt(LivingEntity target) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 from = this.position().add(0.0D, 0.35D, 0.0D);
        Vec3 to = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 delta = to.subtract(from);
        int steps = (int) Math.max(4.0D, delta.length() * 4.0D);
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.add(delta.scale(i / (double) steps));
            serverLevel.sendParticles(ParticleTypes.FALLING_HONEY,
                    point.x, point.y, point.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
        serverLevel.playSound(null, this.blockPosition(), SoundEvents.SLIME_SQUISH,
                SoundSource.HOSTILE, 1.0F, 1.2F);
        if (this.hasLineOfSight(target)) {
            target.addEffect(new MobEffectInstance(KunJinKaoEffects.STENCH.get(),
                    StenchEffect.DURATION_TICKS), this);
        }
    }

    /** 喷尿的 goal：每 3 秒滋一次。 */
    static final class SprayUrineGoal extends Goal {

        private static final int COOLDOWN_TICKS = 60;
        private static final double RANGE_SQR = 10.0D * 10.0D;

        private final KunJinKaoHandEntity hand;
        private int cooldown;

        SprayUrineGoal(KunJinKaoHandEntity hand) {
            this.hand = hand;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.hand.getTarget();
            return target != null && target.isAlive()
                    && this.hand.distanceToSqr(target) <= RANGE_SQR;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            this.cooldown = 20;
        }

        @Override
        public void tick() {
            LivingEntity target = this.hand.getTarget();
            if (target == null) {
                return;
            }
            this.hand.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (this.cooldown > 0) {
                this.cooldown--;
                return;
            }
            this.cooldown = COOLDOWN_TICKS;
            this.hand.sprayAt(target);
        }
    }
    public float squirmAmplitude(float limbSwingAmount) {
        return 0.25F + 0.75F * Math.min(1.0F, limbSwingAmount);
    }
}