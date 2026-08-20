package dev.modmind.my_mod.entity;

import dev.modmind.my_mod.SwordRegistry;
import dev.modmind.my_mod.event.KunJinKaoDeathEventHandler;
import dev.modmind.my_mod.event.KunJinKaoProtectionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.UUID;

public class DiamondProjectile extends ThrowableItemProjectile {

    private int lootingMode;
    private UUID ownerId;

    public DiamondProjectile(EntityType<? extends DiamondProjectile> type, Level level) {
        super(type, level);
        this.setItem(new ItemStack(Items.DIAMOND));
    }

    public DiamondProjectile(Level level, LivingEntity owner) {
        super(SwordRegistry.DIAMOND_PROJECTILE.get(), owner, level);
    }

    public void setLootingMode(int mode) {
        this.lootingMode = mode;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.DIAMOND;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (this.level().isClientSide()) {
            this.discard();
            return;
        }
        if (result.getEntity() instanceof LivingEntity target && target.isAlive()) {
            if (this.level() instanceof ServerLevel serverLevel) {
                // 生成纯视觉落雷（不造成伤害）
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(serverLevel);
                if (bolt != null) {
                    bolt.moveTo(target.getX(), target.getY(), target.getZ());
                    bolt.setVisualOnly(true);
                    serverLevel.addFreshEntity(bolt);
                }
                // 以目标为中心半径 4 格内随机放置 3~6 处火焰
                placeRandomFire(serverLevel, target.blockPosition());
            }

            UUID killer = this.ownerId != null
                    ? this.ownerId
                    : (this.getOwner() instanceof Player player ? player.getUUID() : null);

            // 写入掉落增强标记（供 LivingDropsEvent 应用抢夺加成）后立即处决
            CompoundTag data = target.getPersistentData();
            data.putBoolean(KunJinKaoDeathEventHandler.MARK_KEY, true);
            data.putInt(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY, this.lootingMode);
            // 断未抛射物同样可穿透背包持剑的玩家保护
            data.putBoolean(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY, true);
            if (killer != null) {
                data.putUUID(KunJinKaoDeathEventHandler.KILLER_UUID_KEY, killer);
            }
            target.kill();
        }
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.END_ROD,
                    this.getX(), this.getY(), this.getZ(),
                    10, 0.15D, 0.15D, 0.15D, 0.03D);
            serverLevel.playSound(null, this.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.6F, 1.4F);

            // 落点生成视觉落雷
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(serverLevel);
            if (bolt != null) {
                bolt.moveTo(result.getLocation().x, result.getLocation().y, result.getLocation().z);
                bolt.setVisualOnly(true);
                serverLevel.addFreshEntity(bolt);
            }
            // 落点为中心半径 4 格内随机放置 3~6 处火焰
            placeRandomFire(serverLevel, result.getBlockPos());
        }
        this.discard();
    }

    /**
     * 以 center 为中心，半径 4 格内（含垂直方向 ±1）随机挑选空气位置放置火焰。
     * 每个位置要求下方不是空气，避免火焰悬空；最多尝试 64 次。
     */
    private void placeRandomFire(ServerLevel serverLevel, BlockPos center) {
        int wanted = 3 + serverLevel.random.nextInt(4); // 3~6
        int placed = 0;
        int attempts = 0;
        while (placed < wanted && attempts < 64) {
            attempts++;
            BlockPos pos = center.offset(
                    serverLevel.random.nextInt(9) - 4,
                    serverLevel.random.nextInt(3) - 1,
                    serverLevel.random.nextInt(9) - 4);
            if (serverLevel.getBlockState(pos).isAir()
                    && !serverLevel.getBlockState(pos.below()).isAir()) {
                serverLevel.setBlockAndUpdate(pos, Blocks.FIRE.defaultBlockState());
                placed++;
            }
        }
    }
}
