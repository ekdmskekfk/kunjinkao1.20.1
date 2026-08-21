package dev.modmind.my_mod.entity;

import dev.modmind.my_mod.SwordRegistry;
import dev.modmind.my_mod.config.AdminToolConfig;
import dev.modmind.my_mod.network.NetworkHandler;
import dev.modmind.my_mod.network.SlashWaveImpactMessage;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/** A single-hit, server-authoritative slash wave released by Kun Jin Kao. */
public final class SlashWaveEntity extends Entity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER_UUID =
            SynchedEntityData.defineId(SlashWaveEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private int lifeTicks;

    public SlashWaveEntity(EntityType<? extends SlashWaveEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public SlashWaveEntity(Level level, LivingEntity owner) {
        this(SwordRegistry.SLASH_WAVE.get(), level);
        setOwner(owner);
    }

    public void setOwner(LivingEntity owner) {
        entityData.set(OWNER_UUID, Optional.of(owner.getUUID()));
    }

    @Nullable
    public LivingEntity getOwnerEntity() {
        Optional<UUID> ownerUuid = entityData.get(OWNER_UUID);
        if (ownerUuid.isEmpty()) {
            return null;
        }
        Entity entity = level().getPlayerByUUID(ownerUuid.get());
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity;
        }
        for (Entity candidate : level().getEntities(this, getBoundingBox().inflate(128.0D))) {
            if (ownerUuid.get().equals(candidate.getUUID()) && candidate instanceof LivingEntity livingEntity) {
                return livingEntity;
            }
        }
        return null;
    }

    public void setDirection(float yRot, float xRot) {
        setYRot(yRot);
        setXRot(xRot);
        float yaw = yRot * ((float) Math.PI / 180F);
        float pitch = xRot * ((float) Math.PI / 180F);
        Vec3 direction = new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
        setDeltaMovement(direction.normalize().scale(AdminToolConfig.SLASH_SPEED.get()));
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER_UUID, Optional.empty());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        lifeTicks = tag.getInt("LifeTicks");
        if (tag.hasUUID("Owner")) {
            entityData.set(OWNER_UUID, Optional.of(tag.getUUID("Owner")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("LifeTicks", lifeTicks);
        entityData.get(OWNER_UUID).ifPresent(ownerUuid -> tag.putUUID("Owner", ownerUuid));
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        if (++lifeTicks > AdminToolConfig.SLASH_LIFETIME.get()) {
            discard();
            return;
        }

        move(MoverType.SELF, getDeltaMovement());
        if (level() instanceof ServerLevel serverLevel) {
            spawnTrailParticles(serverLevel);
            hitLivingEntity(serverLevel);
        }
    }

    private void hitLivingEntity(ServerLevel serverLevel) {
        LivingEntity owner = getOwnerEntity();
        AABB hitBox = getBoundingBox().inflate(0.35D);
        for (Entity candidate : serverLevel.getEntities(this, hitBox, entity -> entity instanceof LivingEntity)) {
            if (!(candidate instanceof LivingEntity target) || target == owner || !target.isAlive()) {
                continue;
            }
            DamageSource source = owner instanceof Player player
                    ? serverLevel.damageSources().playerAttack(player)
                    : owner != null ? serverLevel.damageSources().mobAttack(owner) : serverLevel.damageSources().magic();
            target.invulnerableTime = 0;
            target.hurt(source, Float.MAX_VALUE);
            if (target.isAlive()) {
                target.kill();
            }
            applyOverwriteEffect(serverLevel, target);
            discard();
            return;
        }
    }

    private void spawnTrailParticles(ServerLevel level) {
        double x = getX();
        double y = getY() + 1.75D;
        double z = getZ();
        level.sendParticles(SwordRegistry.INK_TRAIL.get(), x, y, z, 12, 1.4D, 1.6D, 0.18D, 0.01D);
        level.sendParticles(SwordRegistry.PIXEL_SHATTER.get(), x, y, z, 9, 1.7D, 1.7D, 0.3D, 0.06D);
    }

    private void applyOverwriteEffect(ServerLevel level, LivingEntity target) {
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.5D;
        double z = target.getZ();
        level.sendParticles(SwordRegistry.GLITCH_CHUNK.get(), x, y, z, 50, 1.1D, 1.2D, 1.1D, 0.16D);
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 18, 0.8D, 1.0D, 0.8D, 0.03D);
        if (target instanceof ServerPlayer player) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new SlashWaveImpactMessage(target.blockPosition(), 40));
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }
}
