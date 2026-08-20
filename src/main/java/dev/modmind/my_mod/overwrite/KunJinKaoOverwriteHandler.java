package dev.modmind.my_mod.overwrite;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import dev.modmind.my_mod.KunJinKaoTheme;
import dev.modmind.my_mod.event.KunJinKaoDeathEventHandler;
import dev.modmind.my_mod.event.KunJinKaoProtectionHandler;
import dev.modmind.my_mod.network.NetworkHandler;
import dev.modmind.my_mod.network.OverwriteEffectMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 锟斤拷之剑「覆写」流程处理器（服务端）：
 * 目标持有泥土时进入 2 秒（40 tick）覆写：护甲清零、移除正面效果、主手替换为损坏泥土、减速 60%、降跳 80%、
 * 每 4 tick 粒子，BossBar 进度仅对攻击者可见；倒计时结束时触发「断未」，
 * 写入掉落标记后 kill()，并在目标位置生成 3×3×2 屏障区块持续 30 秒，区块内玩家减速且无法破坏方块。
 */
public class KunJinKaoOverwriteHandler {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    public static final int OVERWRITE_TICKS = 40;
    private static final int ZONE_TICKS = 600;

    private static final UUID SLOW_UUID = UUID.fromString("5f2b1c40-1111-4a5a-9c1d-000000000001");
    private static final UUID JUMP_UUID = UUID.fromString("5f2b1c40-1111-4a5a-9c1d-000000000002");
    private static final UUID ARMOR_UUID = UUID.fromString("5f2b1c40-1111-4a5a-9c1d-000000000003");

    private static final Map<UUID, OverwriteState> STATES = new HashMap<>();
    private static final List<Zone> ZONES = new ArrayList<>();

    private static class OverwriteState {
        final LivingEntity target;
        final ServerPlayer attacker;
        final ServerBossEvent bossEvent;
        final ItemStack backupMainHand;
        final int lootingMode;
        final double armorValue;
        final int theme;
        final boolean freezeAi;
        final boolean wasNoAi;
        final boolean wasInvisible;
        boolean freezeApplied;
        int ticksLeft;
        int flickerCounter;
        boolean invisibleFlicker;
        boolean dirtApplied;
        boolean decisionSent;

        OverwriteState(LivingEntity target, ServerPlayer attacker, ServerBossEvent bossEvent,
                       ItemStack backupMainHand, int lootingMode, double armorValue, int theme) {
            this.target = target;
            this.attacker = attacker;
            this.bossEvent = bossEvent;
            this.backupMainHand = backupMainHand;
            this.lootingMode = lootingMode;
            this.armorValue = armorValue;
            this.theme = theme;
            this.freezeAi = theme == 1 || theme == 4;
            this.wasNoAi = target instanceof Mob mob && mob.isNoAi();
            this.wasInvisible = target.isInvisible();
            this.invisibleFlicker = target.isInvisible();
            this.ticksLeft = OVERWRITE_TICKS;
        }
    }

    private static class Zone {
        final ServerLevel level;
        final BlockPos base;
        final long expireGameTime;
        final Map<BlockPos, BlockState> originals;

        Zone(ServerLevel level, BlockPos base, long expireGameTime, Map<BlockPos, BlockState> originals) {
            this.level = level;
            this.base = base;
            this.expireGameTime = expireGameTime;
            this.originals = originals;
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= base.getX() - 1 && pos.getX() <= base.getX() + 1
                    && pos.getZ() >= base.getZ() - 1 && pos.getZ() <= base.getZ() + 1
                    && pos.getY() >= base.getY() && pos.getY() <= base.getY() + 1;
        }
    }

    /**
     * 入口：由 KunJinKaoSwordItem.hurtEnemy 在有泥土分支调用。
     */
    public static void startOverwrite(LivingEntity attacker, LivingEntity target, ItemStack sword, ServerLevel level) {
        if (level.isClientSide()) {
            return;
        }
        UUID id = target.getUUID();
        OverwriteState existing = STATES.get(id);
        if (existing != null) {
            // 目标已在覆写中：重置倒计时，其余削弱继续生效
            existing.ticksLeft = OVERWRITE_TICKS;
            existing.flickerCounter = 0;
            if (existing.bossEvent != null) {
                existing.bossEvent.setProgress(1.0F);
            }
            sendStart(existing.attacker, target.getId(), existing.theme);
            return;
        }

        ServerPlayer playerAttacker = attacker instanceof ServerPlayer sp ? sp : null;
        ServerBossEvent bossEvent = null;
        if (playerAttacker != null) {
            bossEvent = new ServerBossEvent(
                    Component.literal("§f§l覆写中 · 断未倒计时"),
                    BossEvent.BossBarColor.WHITE,
                    BossEvent.BossBarOverlay.PROGRESS
            );
            bossEvent.setVisible(true);
            bossEvent.addPlayer(playerAttacker);
            sendStart(playerAttacker, target.getId(), KunJinKaoSwordItem.getTheme(sword));
        }

        OverwriteState state = new OverwriteState(
                target,
                playerAttacker,
                bossEvent,
                target.getMainHandItem().copy(),
                KunJinKaoSwordItem.getLootingMode(sword),
                target.getArmorValue(),
                KunJinKaoSwordItem.getTheme(sword)
        );
        STATES.put(id, state);
        applyDebuffs(state);
    }

    private static void applyDebuffs(OverwriteState state) {
        LivingEntity target = state.target;
        // 移动速度降低 60%
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(SLOW_UUID) == null) {
            speed.addTransientModifier(new AttributeModifier(
                    SLOW_UUID, "kun_jin_kao_overwrite_slow", -0.6D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        // 跳跃能力降低 80%
        AttributeInstance jump = target.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(JUMP_UUID) == null) {
            jump.addTransientModifier(new AttributeModifier(
                    JUMP_UUID, "kun_jin_kao_overwrite_jump", -0.8D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        // 主题 1/4：覆写期间冻结目标 AI（无法移动/攻击）
        if (state.freezeAi && !state.freezeApplied) {
            state.freezeApplied = true;
            if (target instanceof Mob mob) {
                mob.setNoAi(true);
            }
            target.setDeltaMovement(0.0D, 0.0D, 0.0D);
        }
        // 护甲值清零
        AttributeInstance armor = target.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifier(ARMOR_UUID) == null) {
            armor.addTransientModifier(new AttributeModifier(
                    ARMOR_UUID, "kun_jin_kao_overwrite_armor", -state.armorValue, AttributeModifier.Operation.ADDITION));
        }
        // 移除所有正面药水效果
        List<MobEffectInstance> beneficial = new ArrayList<>();
        for (MobEffectInstance effect : target.getActiveEffects()) {
            if (effect.getEffect().isBeneficial()) {
                beneficial.add(effect);
            }
        }
        for (MobEffectInstance effect : beneficial) {
            target.removeEffect(effect.getEffect());
        }
        // 主手武器替换为损坏的泥土
        if (!state.dirtApplied) {
            ItemStack dirt = new ItemStack(Items.DIRT);
            dirt.getOrCreateTag().putBoolean("KunJinKaoBrokenDirt", true);
            target.setItemInHand(InteractionHand.MAIN_HAND, dirt);
            state.dirtApplied = true;
        }
    }

    private static void cleanupDebuffs(OverwriteState state) {
        LivingEntity target = state.target;
        removeModifier(target, Attributes.MOVEMENT_SPEED, SLOW_UUID);
        removeModifier(target, Attributes.JUMP_STRENGTH, JUMP_UUID);
        removeModifier(target, Attributes.ARMOR, ARMOR_UUID);
        if (state.freezeApplied && target instanceof Mob mob) {
            mob.setNoAi(state.wasNoAi);
        }
        // 恢复初始隐身状态，结束隐身闪烁
        target.setInvisible(state.wasInvisible);
        if (state.dirtApplied && target.getMainHandItem().is(Items.DIRT)) {
            target.setItemInHand(InteractionHand.MAIN_HAND, state.backupMainHand);
        }
    }

    private static void removeModifier(LivingEntity target, Attribute attribute, UUID uuid) {
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance != null && instance.getModifier(uuid) != null) {
            instance.removeModifier(uuid);
        }
    }

    private static void tickOverwrite(OverwriteState state) {
        LivingEntity target = state.target;
        if (!target.isAlive() || target.isRemoved()) {
            STATES.remove(target.getUUID());
            return;
        }
        state.ticksLeft--;
        state.flickerCounter++;
        if (state.ticksLeft <= 0) {
            finishOverwrite(state);
            return;
        }
        applyDebuffs(state);

        // 覆写期间目标隐身闪烁：每 8 tick 切换一次可见性，强化「执行中」反馈
        if (state.flickerCounter % 8 == 0) {
            state.invisibleFlicker = !state.invisibleFlicker;
            target.setInvisible(state.invisibleFlicker);
        }

        // 距断未裁决还有 4 tick：通知客户端进入阶段三（目标头顶红色裁决文字）
        if (!state.decisionSent && state.ticksLeft <= 4) {
            state.decisionSent = true;
            sendToAttacker(state.attacker, target.getId(), state.ticksLeft, OverwriteEffectMessage.PHASE_DECISION);
        }

        // 每 4 tick：粒子与 BossBar 进度、进度包
        if (state.flickerCounter % 4 == 0) {
            spawnOverwriteParticles(target, state.theme);
            if (state.bossEvent != null) {
                state.bossEvent.setProgress(state.ticksLeft / (float) OVERWRITE_TICKS);
                sendToAttacker(state.attacker, target.getId(), state.ticksLeft, OverwriteEffectMessage.PHASE_PROGRESS);
            }
        }
    }

    private static void finishOverwrite(OverwriteState state) {
        LivingEntity target = state.target;
        UUID id = target.getUUID();
        if (!target.isAlive() || target.isRemoved()) {
            STATES.remove(id);
            return;
        }
        cleanupDebuffs(state);

        // 写入掉落/经验增强标记与断未赐死标记（绕过持剑保护）
        target.getPersistentData().putBoolean(KunJinKaoDeathEventHandler.MARK_KEY, true);
        target.getPersistentData().putInt(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY, state.lootingMode);
        target.getPersistentData().putBoolean(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY, true);

        if (state.bossEvent != null) {
            state.bossEvent.removePlayer(state.attacker);
            sendToAttacker(state.attacker, target.getId(), target.blockPosition(), OverwriteEffectMessage.PHASE_END);
        }

        if (target.level() instanceof ServerLevel serverLevel) {
            spawnUndefinedZone(serverLevel, target.blockPosition());
        }

        // 先移除状态再 kill，避免死亡事件再次进入清理路径
        STATES.remove(id);
        LOGGER.info("[OVERWRITE-FINISH] killing target={}", target.getType());
        target.kill();
    }

    private static void spawnUndefinedZone(ServerLevel level, BlockPos center) {
        Map<BlockPos, BlockState> originals = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState original = level.getBlockState(pos);
                    if (original.isAir() || original.canBeReplaced() || original.getBlock() == Blocks.BARRIER) {
                        originals.put(pos, original);
                        level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 3);
                    }
                }
            }
        }
        ZONES.add(new Zone(level, center, level.getGameTime() + ZONE_TICKS, originals));
        level.sendParticles(ParticleTypes.CLOUD,
                center.getX() + 0.5D, center.getY() + 1.0D, center.getZ() + 0.5D,
                80, 1.5D, 1.0D, 1.5D, 0.05D);
        level.sendParticles(ParticleTypes.ENCHANT,
                center.getX() + 0.5D, center.getY() + 1.0D, center.getZ() + 0.5D,
                120, 1.8D, 1.5D, 1.8D, 0.4D);
    }

    private static void spawnOverwriteParticles(LivingEntity target, int theme) {
        if (!(target.level() instanceof ServerLevel server)) {
            return;
        }
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.5D;
        double z = target.getZ();
        server.sendParticles(KunJinKaoTheme.particle(theme), x, y, z, 18, 0.4D, 0.6D, 0.4D, 0.05D);
        server.sendParticles(ParticleTypes.ENCHANT, x, y, z, 14, 0.4D, 0.6D, 0.4D, 0.25D);
        server.sendParticles(ParticleTypes.ASH, x, y, z, 8, 0.3D, 0.3D, 0.3D, 0.1D);
    }

    /**
     * 从攻击者主手/副手查找覆写·断未剑；未找到返回 EMPTY。
     */
    private static ItemStack findSword(LivingEntity holder) {
        ItemStack main = holder.getMainHandItem();
        if (!main.isEmpty() && main.getItem() instanceof KunJinKaoSwordItem) {
            return main;
        }
        ItemStack off = holder.getOffhandItem();
        if (!off.isEmpty() && off.getItem() instanceof KunJinKaoSwordItem) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 执行覆写或瞬杀。成功处理返回 true（调用方应取消原伤害事件，避免免疫机制干扰）。
     */
    private static boolean handleSwordAttack(LivingEntity attacker, LivingEntity target, ItemStack sword, Level level) {
        if (level.isClientSide() || target == null || attacker == null || sword.isEmpty()) {
            return false;
        }
        if (KunJinKaoSwordItem.isDisguised(sword)) {
            LOGGER.info("[SWORD-ATTACK] sword disguised -> vanilla");
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (KunJinKaoSwordItem.isOverwriteEnabled(sword)) {
            LOGGER.info("[SWORD-ATTACK] overwriteEnabled=true -> startOverwrite target={}", target.getType());
            startOverwrite(attacker, target, sword, serverLevel);
        } else {
            LOGGER.info("[SWORD-ATTACK] overwriteEnabled=false -> INSTANT KILL target={}", target.getType());
            KunJinKaoSwordItem.applyKunJinKaoMark(target, sword);
            target.kill();
        }
        return true;
    }

    private static void sendStart(ServerPlayer player, int entityId, int theme) {
        if (player != null) {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OverwriteEffectMessage(entityId, OVERWRITE_TICKS, OverwriteEffectMessage.PHASE_START, theme, false, 0, 0, 0)
            );
        }
    }

    private static void sendToAttacker(ServerPlayer player, int entityId, int remainingTicks, int phase) {
        if (player != null) {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OverwriteEffectMessage(entityId, remainingTicks, phase)
            );
        }
    }

    private static void sendToAttacker(ServerPlayer player, int entityId, BlockPos pos, int phase) {
        if (player != null) {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OverwriteEffectMessage(entityId, 0, phase, 0, true, pos.getX(), pos.getY(), pos.getZ())
            );
        }
    }

    private static boolean isInsideAnyZone(Level level, BlockPos pos) {
        for (Zone zone : ZONES) {
            if (zone.level == level && zone.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 玩家攻击兜底：在 Player.attack 流程最开头触发，早于伤害结算与 isInvulnerableTo 免疫判定，
     * 因此即使目标对剑类伤害免疫（例如 BOSS 在 hurt/isInvulnerableTo 中拦截），
     * 只要攻击者手持未伪装的覆写·断未，就会强制接管本次攻击。
     */
    @SubscribeEvent
    public void onPlayerAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target) || target instanceof Player) {
            LOGGER.info("[ATTACK-EVENT] skipped target={} (non-living or player)", event.getTarget());
            return;
        }
        ItemStack sword = findSword(player);
        if (sword.isEmpty()) {
            LOGGER.info("[ATTACK-EVENT] no sword held -> vanilla");
            return;
        }
        LOGGER.info("[ATTACK-EVENT] target={} overwriteEnabled={}",
                target.getType(), KunJinKaoSwordItem.isOverwriteEnabled(sword));
        if (handleSwordAttack(player, target, sword, player.level())) {
            event.setCanceled(true);
        }
    }

    /**
     * 非玩家攻击者兜底：当任意活体实体手持覆写·断未发起伤害时，同样接管攻击。
     * 注意 LivingAttackEvent 在 isInvulnerableTo 判定之后触发，纯免疫目标不经过此处；
     * 玩家攻击场景已由 onPlayerAttack 完整覆盖。
     */
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || target instanceof Player) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker instanceof Player) {
            return;
        }
        ItemStack sword = findSword(attacker);
        if (sword.isEmpty()) {
            return;
        }
        if (handleSwordAttack(attacker, target, sword, target.level())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        OverwriteState state = STATES.get(entity.getUUID());
        if (state != null) {
            tickOverwrite(state);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        // 目标在覆写期间因其他原因死亡：清理削弱状态并恢复主手，避免原物品被泥土覆盖丢失
        LivingEntity entity = event.getEntity();
        OverwriteState state = STATES.remove(entity.getUUID());
        if (state == null) {
            return;
        }
        cleanupDebuffs(state);
        if (state.bossEvent != null) {
            state.bossEvent.removePlayer(state.attacker);
            sendToAttacker(state.attacker, entity.getId(), 0, OverwriteEffectMessage.PHASE_CANCEL);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Iterator<Zone> iterator = ZONES.iterator();
        while (iterator.hasNext()) {
            Zone zone = iterator.next();
            if (zone.level.getGameTime() >= zone.expireGameTime) {
                for (Map.Entry<BlockPos, BlockState> entry : zone.originals.entrySet()) {
                    zone.level.setBlock(entry.getKey(), entry.getValue(), 3);
                }
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide()) {
            return;
        }
        if (isInsideAnyZone(player.level(), player.blockPosition())) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1, false, false, false));
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && isInsideAnyZone(level, event.getPos())) {
            event.setCanceled(true);
            Player player = event.getPlayer();
            player.displayClientMessage(Component.literal("§7§o未定义区块阻断了你的破坏……"), true);
        }
    }
}
