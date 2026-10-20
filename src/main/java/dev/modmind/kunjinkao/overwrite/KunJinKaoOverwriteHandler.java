package dev.modmind.kunjinkao.overwrite;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.event.KunJinKaoDeathEventHandler;
import dev.modmind.kunjinkao.event.KunJinKaoProtectionHandler;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.OverwriteEffectPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.TickEvent;

import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;

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

    /**
     * 覆写期间写在目标实体 persistent data 里的两个键。
     * <p>
     * 主手原件以前只备份在内存字段里，而"损坏的泥土"是直接写进 HandItems 并随区块存盘的：
     * 覆写中途区块卸载/退档，内存备份和状态一起没了，泥土却留在存档里，生物就此永久拿着泥土。
     * 所以备份必须写进实体自己的 persistent data（NeoForge 会随实体 NBT 存盘），
     * 并额外打一个"覆写进行中"的标记，供下次实体进入世界时判断是否属于上一局的遗留状态。
     */
    private static final String OVERWRITE_IN_PROGRESS_KEY = "KunJinKaoOverwriteInProgress";
    private static final String MAIN_HAND_BACKUP_KEY = "KunJinKaoOverwriteMainHandBackup";

    private static final DustParticleOptions COLD_CORE =
            new DustParticleOptions(new Vector3f(0.00F, 0.90F, 1.00F), 0.95F);
    private static final DustParticleOptions COLD_EDGE =
            new DustParticleOptions(new Vector3f(0.72F, 0.96F, 1.00F), 0.68F);
    private static final DustParticleOptions COLD_PIXEL =
            new DustParticleOptions(new Vector3f(0.18F, 0.58F, 0.84F), 0.55F);

    /* 1.20.1 的 AttributeModifier 用 UUID 标识，1.21 才改成 ResourceLocation。用固定字节派生，保证同一会话内稳定。 */
    private static final java.util.UUID SLOW_ID = java.util.UUID.nameUUIDFromBytes("kunjinkao:overwrite_slow".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        private static final java.util.UUID JUMP_ID = java.util.UUID.nameUUIDFromBytes("kunjinkao:overwrite_jump".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        private static final java.util.UUID ARMOR_ID = java.util.UUID.nameUUIDFromBytes("kunjinkao:overwrite_armor".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static final Map<UUID, OverwriteState> STATES = new HashMap<>();
    private static final List<Zone> ZONES = new ArrayList<>();

    private static class OverwriteState {
        final LivingEntity target;
        final ServerPlayer attacker;
        final ServerBossEvent bossEvent;
        final ItemStack backupMainHand;
        /** 剑上的抢夺设置值 1..1000（1 = 无抢夺）；覆写期间先存下来，处决时写回目标身上。 */
        final int lootingLevelSetting;
        final double armorValue;
        final int theme;
        final boolean freezeAi;
        final boolean wasNoAi;
        final boolean wasInvisible;
        boolean freezeApplied;
        int ticksLeft;
        int flickerCounter;
        boolean dirtApplied;
        boolean decisionSent;

        OverwriteState(LivingEntity target, ServerPlayer attacker, ServerBossEvent bossEvent,
                       ItemStack backupMainHand, int lootingLevelSetting, double armorValue, int theme) {
            this.target = target;
            this.attacker = attacker;
            this.bossEvent = bossEvent;
            this.backupMainHand = backupMainHand;
            this.lootingLevelSetting = lootingLevelSetting;
            this.armorValue = armorValue;
            this.theme = theme;
            // 主题 1 / 4 在覆写期间额外冻结 AI：这是有意设计（这两个主题表现"完全停机"），不要当成 bug 去掉
            this.freezeAi = theme == 1 || theme == 4;
            this.wasNoAi = target instanceof Mob mob && mob.isNoAi();
            this.wasInvisible = target.isInvisible();
            this.ticksLeft = OVERWRITE_TICKS;
        }
    }

    private static class Zone {
        final ServerLevel level;
        final BlockPos base;
        final long expireGameTime;
        final Map<BlockPos, BlockState> originals;
        /** 落盘记录的句柄：到期还原之后要把它从存档里删掉，否则下次启动会再还原一次。 */
        UndefinedZoneSavedData.ZoneRecord record;

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
                KunJinKaoSwordItem.getLootingLevelSetting(sword),
                target.getArmorValue(),
                KunJinKaoSwordItem.getTheme(sword)
        );
        STATES.put(id, state);
        applyDebuffs(state);
        spawnOverwriteParticles(state);
    }

    private static void applyDebuffs(OverwriteState state) {
        LivingEntity target = state.target;
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(SLOW_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(SLOW_ID, "kunjinkao_overwrite_slow", -0.6D, AttributeModifier.Operation.MULTIPLY_BASE));
        }
        AttributeInstance jump = target.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(JUMP_ID) == null) {
            jump.addTransientModifier(new AttributeModifier(JUMP_ID, "kunjinkao_overwrite_jump", -0.8D, AttributeModifier.Operation.MULTIPLY_BASE));
        }
        if (state.freezeAi && !state.freezeApplied) {
            state.freezeApplied = true;
            if (target instanceof Mob mob) {
                mob.setNoAi(true);
            }
            target.setDeltaMovement(0.0D, 0.0D, 0.0D);
        }
        AttributeInstance armor = target.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifier(ARMOR_ID) == null) {
            armor.addTransientModifier(new AttributeModifier(ARMOR_ID, "kunjinkao_overwrite_armor", -state.armorValue, AttributeModifier.Operation.ADDITION));
        }
        List<MobEffectInstance> beneficial = new ArrayList<>();
        for (MobEffectInstance effect : target.getActiveEffects()) {
            if (effect.getEffect().isBeneficial()) {
                beneficial.add(effect);
            }
        }
        for (MobEffectInstance effect : beneficial) {
            target.removeEffect(effect.getEffect());
        }
        if (!state.dirtApplied) {
            // 关键顺序：先把主手原件写进实体 persistent data（随实体存盘），再换成泥土。
            // 反过来一旦在这两步之间崩溃/退档，存档里就只剩泥土，原件永久丢失。
            writeMainHandBackup(target);
            target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
            state.dirtApplied = true;
        }
    }

    private static void cleanupDebuffs(OverwriteState state) {
        LivingEntity target = state.target;
        removeModifier(target, Attributes.MOVEMENT_SPEED, SLOW_ID);
        removeModifier(target, Attributes.JUMP_STRENGTH, JUMP_ID);
        removeModifier(target, Attributes.ARMOR, ARMOR_ID);
        if (state.freezeApplied && target instanceof Mob mob) {
            mob.setNoAi(state.wasNoAi);
        }
        target.setInvisible(state.wasInvisible);
        if (state.dirtApplied && target.getMainHandItem().is(Items.DIRT)) {
            // 优先用实体 persistent data 里的备份（内存备份在崩溃/退档后就没了），内存备份只作兜底
            target.setItemInHand(InteractionHand.MAIN_HAND, readMainHandBackup(target, state.backupMainHand));
            // 只有真的恢复过才清备份与标记
            clearMainHandBackup(target);
        }
        // 手上不是泥土时**不要**清：那说明覆写期间主手被别的来源（拾取/其它 mod）换过，
        // 原件还留在 persistent data 里，清掉就等于永久丢失。这种实体下次进入世界时
        // 由 EntityJoinLevelEvent 按备份恢复（审计指出的隐患）。
    }

    /**
     * 备份主手原件到实体 persistent data，并打上"覆写进行中"标记。
     * 空手也照样打标记：恢复时才知道"原件本来就是空的"，不能把泥土留在手上。
     */
    private static void writeMainHandBackup(LivingEntity target) {
        CompoundTag data = target.getPersistentData();
        ItemStack mainHand = target.getMainHandItem();
        if (!mainHand.isEmpty()) {
            data.put(MAIN_HAND_BACKUP_KEY, mainHand.copy().save(new CompoundTag()));
        }
        data.putBoolean(OVERWRITE_IN_PROGRESS_KEY, true);
    }

    /** 从 persistent data 读取主手备份；没有备份键（原件为空手）时退回内存备份。 */
    private static ItemStack readMainHandBackup(LivingEntity target, ItemStack memoryBackup) {
        CompoundTag data = target.getPersistentData();
        if (!data.contains(MAIN_HAND_BACKUP_KEY, Tag.TAG_COMPOUND)) {
            return memoryBackup;
        }
        return ItemStack.of(data.getCompound(MAIN_HAND_BACKUP_KEY));
    }

    private static void clearMainHandBackup(LivingEntity target) {
        CompoundTag data = target.getPersistentData();
        data.remove(MAIN_HAND_BACKUP_KEY);
        data.remove(OVERWRITE_IN_PROGRESS_KEY);
    }

    private static void removeModifier(LivingEntity target, Attribute attribute, java.util.UUID id) {
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance != null && instance.getModifier(id) != null) {
            instance.removeModifier(id);
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
        // 不再切换实体隐身状态。旧的可见性闪烁会造成“抖动”观感，
        // 现在由冷色六边形网格和由上至下的扫描删除线表现覆写过程。

        // 距断未裁决还有 4 tick：通知客户端进入阶段三（目标头顶红色裁决文字）
        if (!state.decisionSent && state.ticksLeft <= 4) {
            state.decisionSent = true;
            sendToAttacker(state.attacker, target.getId(), state.ticksLeft, OverwriteEffectPayload.PHASE_DECISION);
        }

        // 每 4 tick：粒子与 BossBar 进度、进度包
        if (state.flickerCounter % 2 == 0) {
            spawnOverwriteParticles(state);
        }
        if (state.flickerCounter % 4 == 0) {
            if (state.bossEvent != null) {
                state.bossEvent.setProgress(state.ticksLeft / (float) OVERWRITE_TICKS);
                sendToAttacker(state.attacker, target.getId(), state.ticksLeft, OverwriteEffectPayload.PHASE_PROGRESS);
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
        target.getPersistentData().putInt(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY, state.lootingLevelSetting);
        target.getPersistentData().putBoolean(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY, true);

        if (state.bossEvent != null) {
            state.bossEvent.removePlayer(state.attacker);
            sendToAttacker(state.attacker, target.getId(), target.blockPosition(), OverwriteEffectPayload.PHASE_END);
        }

        if (target.level() instanceof ServerLevel serverLevel) {
            spawnFinalResidue(serverLevel, target);
            spawnUndefinedZone(serverLevel, target.blockPosition());
        }

        // 先移除状态再 kill，避免死亡事件再次进入清理路径
        STATES.remove(id);
        LOGGER.debug("[OVERWRITE-FINISH] killing target={}", target.getType());
        KunJinKaoSwordItem.killIgnoringImmunity(target, KunJinKaoSwordItem.swordKillSource(state.attacker));
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
        Zone zone = new Zone(level, center, level.getGameTime() + ZONE_TICKS, originals);
        // 屏障是普通方块，会随区块一起写进存档，而内存里的还原表（ZONES）活不过一次崩溃/退档。
        // 所以在放下屏障的同时把"原方块 + 剩余时间"也写进存档，供下次启动时兜底还原。
        MinecraftServer server = level.getServer();
        if (server != null && !originals.isEmpty()) {
            zone.record = UndefinedZoneSavedData.get(server)
                    .add(level.dimension().location(), ZONE_TICKS, originals);
        }
        ZONES.add(zone);
        level.sendParticles(COLD_PIXEL,
                center.getX() + 0.5D, center.getY() + 1.0D, center.getZ() + 0.5D,
                72, 1.15D, 0.85D, 1.15D, 0.06D);
        level.sendParticles(ParticleTypes.END_ROD,
                center.getX() + 0.5D, center.getY() + 1.0D, center.getZ() + 0.5D,
                20, 0.7D, 0.7D, 0.7D, 0.025D);
    }

    /**
     * 还原未定义区块。
     * <p>
     * 只还原"当前仍然是屏障"的位置：这 30 秒内玩家放进区块里的方块必须保留（否则等于把玩家建筑一起删了），
     * 其它来源（指令/其它模组）后来放下的方块同理不动。
     */
    private static void restoreZone(ServerLevel level, Map<BlockPos, BlockState> originals) {
        for (Map.Entry<BlockPos, BlockState> entry : originals.entrySet()) {
            if (!level.getBlockState(entry.getKey()).is(Blocks.BARRIER)) {
                continue;
            }
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
    }

    private static void spawnOverwriteParticles(OverwriteState state) {
        LivingEntity target = state.target;
        if (!(target.level() instanceof ServerLevel server)) {
            return;
        }
        double progress = 1.0D - state.ticksLeft / (double) OVERWRITE_TICKS;
        double centerX = target.getX();
        double centerY = target.getY() + target.getBbHeight() * 0.5D;
        double centerZ = target.getZ();
        double baseRadius = Math.max(0.42D, Math.max(target.getBbWidth(), target.getBbHeight() * 0.45D));
        double radius = baseRadius * (1.0D - progress * 0.62D);

        // 两层六边形网格逐步向中心收缩。
        for (int ring = 0; ring < 2; ring++) {
            double ringRadius = radius + ring * 0.13D;
            for (int point = 0; point < 6; point++) {
                double angle = Math.toRadians(point * 60.0D + ring * 30.0D);
                double yOffset = ((point & 1) == 0 ? 0.11D : -0.11D) * (1.0D - progress * 0.5D);
                sendCold(server, ring == 0 ? COLD_CORE : COLD_EDGE,
                        centerX + Math.cos(angle) * ringRadius,
                        centerY + yOffset,
                        centerZ + Math.sin(angle) * ringRadius);
            }
        }

        // 文本被退格般的逐行删除：扫描线始终由上向下移动。
        double scanY = target.getY() + target.getBbHeight() * (1.0D - progress);
        double halfWidth = Math.max(0.28D, target.getBbWidth() * 0.58D);
        for (int column = -3; column <= 3; column++) {
            double offset = column * halfWidth / 3.0D;
            sendCold(server, column == 0 ? COLD_CORE : COLD_EDGE, centerX + offset, scanY, centerZ);
            if ((column & 1) == 0) {
                sendCold(server, COLD_PIXEL, centerX + offset, scanY - 0.045D, centerZ + 0.035D);
            }
        }
    }

    private static void spawnFinalResidue(ServerLevel level, LivingEntity target) {
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.45D;
        double z = target.getZ();
        level.sendParticles(COLD_PIXEL, x, y, z, 42,
                Math.max(0.35D, target.getBbWidth() * 0.7D), target.getBbHeight() * 0.45D,
                Math.max(0.35D, target.getBbWidth() * 0.7D), 0.055D);
        level.sendParticles(COLD_EDGE, x, y, z, 18,
                0.32D, 0.42D, 0.32D, 0.025D);
    }

    private static void sendCold(ServerLevel level, DustParticleOptions particle, double x, double y, double z) {
        level.sendParticles(particle, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    /**
     * 从攻击者主手/副手查找覆写·断未剑；未找到返回 EMPTY。
     */
    private static ItemStack findSword(LivingEntity holder) {
        // 这里只排除"未验证的合成成品"（pending），伪装的剑仍要交给 handleSwordAttack 走原版分支，
        // 否则伪装玩法会失效。
        ItemStack main = holder.getMainHandItem();
        if (isOverwriteSword(main)) {
            return main;
        }
        ItemStack off = holder.getOffhandItem();
        return isOverwriteSword(off) ? off : ItemStack.EMPTY;
    }

    private static boolean isOverwriteSword(ItemStack stack) {
        return !stack.isEmpty()
                && KunJinKaoSwordItem.isSwordLike(stack)
                && !KunJinKaoSwordItem.isPendingCraft(stack);
    }

    /**
     * 执行覆写或瞬杀。成功处理返回 true（调用方应取消原伤害事件，避免免疫机制干扰）。
     */
    private static boolean handleSwordAttack(LivingEntity attacker, LivingEntity target, ItemStack sword, Level level) {
        if (level.isClientSide() || target == null || attacker == null || sword.isEmpty()) {
            return false;
        }
        if (KunJinKaoSwordItem.isDisguised(sword)) {
            LOGGER.debug("[SWORD-ATTACK] sword disguised -> vanilla");
            return false;
        }
        // 伤害上限被人为调低时不要接管这次攻击，交给原版按普通伤害结算。
        // 这个判断必须放在这里：AttackEntityEvent 的处理会先取消原版攻击，
        // 而 KunJinKaoProtectionHandler 里的上限判断走的是 LivingIncomingDamageEvent，
        // 那条路径根本到不了 —— 审计指出的"上限对生物失效"就是这里漏判。
        if (KunJinKaoSwordItem.getAttackDamageLimit(sword) < KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (KunJinKaoSwordItem.isOverwriteEnabled(sword)) {
            LOGGER.debug("[SWORD-ATTACK] overwriteEnabled=true -> startOverwrite target={}", target.getType());
            startOverwrite(attacker, target, sword, serverLevel);
        } else {
            LOGGER.debug("[SWORD-ATTACK] overwriteEnabled=false -> INSTANT KILL target={}", target.getType());
            KunJinKaoSwordItem.applyKunJinKaoMark(target, sword);
            KunJinKaoSwordItem.killIgnoringImmunity(target, KunJinKaoSwordItem.swordKillSource(attacker));
        }
        return true;
    }

    private static void sendStart(ServerPlayer player, int entityId, int theme) {
        if (player != null) {
            NetworkHandler.sendToPlayer(player, OverwriteEffectPayload.start(entityId, OVERWRITE_TICKS, theme));
        }
    }

    private static void sendToAttacker(ServerPlayer player, int entityId, int remainingTicks, int phase) {
        if (player != null) {
            OverwriteEffectPayload payload = switch (phase) {
                case OverwriteEffectPayload.PHASE_PROGRESS -> OverwriteEffectPayload.progress(entityId, remainingTicks);
                case OverwriteEffectPayload.PHASE_CANCEL -> OverwriteEffectPayload.cancel(entityId);
                case OverwriteEffectPayload.PHASE_DECISION -> OverwriteEffectPayload.decision(entityId, remainingTicks);
                default -> new OverwriteEffectPayload(entityId, remainingTicks, phase, 0, false, 0, 0, 0);
            };
            NetworkHandler.sendToPlayer(player, payload);
        }
    }

    private static void sendToAttacker(ServerPlayer player, int entityId, BlockPos pos, int phase) {
        if (player != null) {
            // 这个重载以前把 phase 参数丢掉了、无条件发 PHASE_END，
            // 于是中断/取消会被客户端当成一次正常的"断未"演出；现在按传入的 phase 分发
            NetworkHandler.sendToPlayer(player, phase == OverwriteEffectPayload.PHASE_CANCEL
                    ? OverwriteEffectPayload.cancel(entityId)
                    : OverwriteEffectPayload.end(entityId, pos));
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
     * 把"被砍中的那个东西"还原成真正该挨打的本体。
     * <p>
     * 多部件 BOSS 的外壳是 {@link PartEntity}：准星打到的是它，原版会把伤害转发给本体，
     * 但剑的一切接管逻辑都挂在"目标是 LivingEntity"这个前提上 —— 部件不是活体，
     * 于是这条链路整段落空。末影龙就是最典型的例子：不解析本体的话，
     * 用剑砍它跟用普通剑砍没区别。
     *
     * @return 本体；既不是活体也解析不出部件父实体时返回 null
     */
    private static LivingEntity resolveOwner(Entity hit) {
        if (hit instanceof LivingEntity living) {
            return living;
        }
        if (hit instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity parent) {
            return parent;
        }
        return null;
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
        if (!(event.getTarget() instanceof LivingEntity) && !(event.getTarget() instanceof PartEntity<?>)) {
            LOGGER.debug("[ATTACK-EVENT] skipped target={} (非活体也非部件)", event.getTarget());
            return;
        }
        // 多部件 BOSS（末影龙那种）打中的是 PartEntity，不是本体：原版会把伤害转发给本体，
        // 但 Item#hurtEnemy 只对 LivingEntity 目标调用 —— 于是剑的秒杀在这类 BOSS 身上
        // 根本不会触发，表现就是"砍上去有伤害数字，怎么砍都不死"。这里先把部件解析回本体。
        LivingEntity target = resolveOwner(event.getTarget());
        if (target == null || target instanceof Player) {
            LOGGER.debug("[ATTACK-EVENT] skipped target={} (解析不出本体或是玩家)", event.getTarget());
            return;
        }
        ItemStack sword = findSword(player);
        if (sword.isEmpty()) {
            LOGGER.debug("[ATTACK-EVENT] no sword held -> vanilla");
            return;
        }
        LOGGER.debug("[ATTACK-EVENT] target={} overwriteEnabled={}",
                target.getType(), KunJinKaoSwordItem.isOverwriteEnabled(sword));
        // 无论接下来走覆写还是秒杀，先把击杀者记在受害者身上 ——
        // 那两条路都是"取消攻击 + 直接 kill()"，走的是无来源伤害，
        // 事后再也查不出是谁动的手（斩首与刷怪蛋掉落就卡在这里）。
        KunJinKaoSwordItem.recordKiller(target, player);
        if (handleSwordAttack(player, target, sword, player.level())) {
            event.setCanceled(true);
        }
    }

    /**
     * 非玩家攻击者兜底：当任意活体实体手持覆写·断未发起伤害时，同样接管攻击。
     * 注意 LivingIncomingDamageEvent 在 isInvulnerableTo 判定之后触发，纯免疫目标不经过此处；
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
        // 1.20.1 没有 EntityTickEvent.Post：活体 tick 走 LivingEvent.LivingTickEvent（已经只有活体）。
        LivingEntity entity = event.getEntity();
        if (entity == null) {
            return;
        }
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
            sendToAttacker(state.attacker, entity.getId(), 0, OverwriteEffectPayload.PHASE_CANCEL);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (ZONES.isEmpty()) {
            return;
        }
        // 只有存在存活区块时才需要维护落盘记录，没有区块时不必每 tick 去碰存档数据
        UndefinedZoneSavedData data = UndefinedZoneSavedData.get(event.getServer());
        Iterator<Zone> iterator = ZONES.iterator();
        while (iterator.hasNext()) {
            Zone zone = iterator.next();
            long remaining = zone.expireGameTime - zone.level.getGameTime();
            if (remaining <= 0) {
                restoreZone(zone.level, zone.originals);
                // 屏障已经还原：落盘记录必须同步删除，否则下次启动还会再还原一次
                if (zone.record != null) {
                    data.remove(zone.record);
                }
                iterator.remove();
            } else if (zone.record != null && zone.level.getGameTime() % 20L == 0L) {
                // 剩余时间只用于崩溃后的日志，每 20 tick（1 秒）刷新一次即可：
                // updateRemaining 会 setDirty()，而每次自动保存都会把整个 zones 列表全量序列化一遍。
                data.updateRemaining(zone.record, remaining);
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
            player.displayClientMessage(Component.translatable("message.kunjinkao.zone_break_denied"), true);
        }
    }

    /**
     * 服务器启动时兜底还原上一局遗留的未定义区块。
     * <p>
     * 上一局在 600 tick 内被强杀/崩溃，或者单机退档重进时，屏障方块已经随区块写进了存档，
     * 而内存里的 ZONES 早就没了 —— 没有这一步，存档里就会永久留下 3×3×2 不可破坏屏障。
     * <p>
     * 这里直接还原而不是接着倒计时：跨重启续算"剩下的 25 秒"没有意义，
     * 玩家下次进服时不该再看见上一局的屏障。
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // 单机"退档重进"不重启 JVM，进程级 static 会带着上一局的 ServerLevel / ServerPlayer 强引用跨存档泄漏。
        // 正常情况下 ServerStoppedEvent 已经清过；这里再清一次，防止上一局异常退出（没触发 StoppedEvent）留下脏引用。
        // 主手恢复不在这里做：实体侧一律交给 EntityJoinLevelEvent 按 persistent data 兜底。
        STATES.clear();
        ZONES.clear();

        UndefinedZoneSavedData data = UndefinedZoneSavedData.get(server);
        for (UndefinedZoneSavedData.ZoneRecord record : data.snapshot()) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, record.dimension());
            ServerLevel level = server.getLevel(key);
            if (level == null) {
                // 维度可能已经被数据包移除：宁可不还原，也不能把方块还原到别的地方，保留记录并记日志
                LOGGER.warn("[OVERWRITE-ZONE] saved undefined zone dimension not found: {}", record.dimension());
                continue;
            }
            // 这里使用会按需加载区块的 getBlockState：启动期同步加载最多 2~4 个区块，
            // 换来的是"崩溃遗留的屏障一定会被还原"。相比偶发的启动卡顿，
            // 存档里永久留下普通玩家清不掉的屏障是更糟的结果，故有意保持同步。
            restoreZone(level, record.originals());
            data.remove(record);
            LOGGER.info("[OVERWRITE-ZONE] restored leftover undefined zone in {} ({} blocks, {} ticks left)",
                    record.dimension(), record.originals().size(), record.remainingTicks());
        }
    }

    /**
     * 服务器停止：把还在覆写中的目标恢复干净，并清空全部进程级静态状态。
     * <p>
     * 注意实体 NBT 在 ServerStoppedEvent 之前就已经落盘（stopServer 先 saveAllChunks 再发本事件），
     * 所以这里的主手恢复只保证内存对象一致；真正修复存档的是下次进服时的 EntityJoinLevelEvent。
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        for (OverwriteState state : new ArrayList<>(STATES.values())) {
            cleanupDebuffs(state);
            if (state.bossEvent != null) {
                state.bossEvent.removeAllPlayers();
            }
        }
        STATES.clear();
        ZONES.clear();
    }

    /**
     * 攻击者掉线时把该玩家从所有 BossBar 里摘掉。
     * BossBar 是挂在玩家连接上的显示对象，不清掉就会一直持有这个 ServerPlayer 引用（悬挂 + 内存泄漏）。
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (OverwriteState state : STATES.values()) {
            if (state.bossEvent != null) {
                state.bossEvent.removePlayer(player);
            }
        }
    }

    /**
     * 实体进入世界时的兜底恢复。
     * <p>
     * 上一局崩溃/退档之后，内存里的覆写状态与主手备份都没了，只剩下实体 NBT 里的
     * "覆写进行中"标记和主手备份（覆写中途区块卸载时，泥土已经写进 HandItems 存了盘）。
     * 只要此刻没有指向该实体的内存状态，就说明这是遗留脏数据，必须把主手换回来并清掉标记。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        if (!entity.getPersistentData().getBoolean(OVERWRITE_IN_PROGRESS_KEY)) {
            return;
        }
        OverwriteState state = STATES.get(entity.getUUID());
        if (state != null && state.target == entity) {
            // 同一局内的正常覆写流程：状态还在内存里，交给 tickOverwrite 管理，这里不要抢着恢复
            return;
        }
        if (state != null) {
            // 状态里持有的实体实例已被替换（例如覆写中途跨维度）：先按中断路径给旧状态收尾
            STATES.remove(entity.getUUID());
            cleanupDebuffs(state);
            if (state.bossEvent != null) {
                state.bossEvent.removePlayer(state.attacker);
                sendToAttacker(state.attacker, entity.getId(), 0, OverwriteEffectPayload.PHASE_CANCEL);
            }
        }
        // 从 persistent data 恢复主手：这是"区块卸载吃过主手原件"那条后果的唯一补救点。
        // 两道防护缺一不可：
        // 1) 只在手上仍是泥土时才替换 —— cleanupDebuffs 可能刚刚已经恢复过主手，
        //    无条件覆盖会把恢复好的原件（或实体本来的手持物）冲成备份值；
        // 2) 备份键缺失时回退到内存备份而不是 EMPTY —— 传入 EMPTY 意味着
        //    "一旦宁时序意外导致键已消失，就把主手清空"，那正是复核指出的丢物品路径。
        if (entity.getMainHandItem().is(Items.DIRT)) {
            ItemStack memoryBackup = state == null ? ItemStack.EMPTY : state.backupMainHand;
            entity.setItemInHand(InteractionHand.MAIN_HAND, readMainHandBackup(entity, memoryBackup));
        }
        clearMainHandBackup(entity);
        LOGGER.info("[OVERWRITE-RECOVER] restored overwritten main hand for {}", entity.getType());
    }
}
