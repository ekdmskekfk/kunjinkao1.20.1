package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.world.PlacementUndoHistory;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.config.UltimateDeathSavedData;
import dev.modmind.kunjinkao.network.ExcludedPlayerData;
import dev.modmind.kunjinkao.network.ExcludedPlayersPayload;
import dev.modmind.kunjinkao.network.HudEntityAction;
import dev.modmind.kunjinkao.network.HudEntityActionResultPayload;
import dev.modmind.kunjinkao.network.HudEntityData;
import dev.modmind.kunjinkao.network.HudEntityListPayload;
// 4 个 HUD 状态回执包已合并为 HudStatePayload（带 stateId 判别）。
import dev.modmind.kunjinkao.network.HudStatePayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 覆写·断未的冷色数据特效：将攻击轨迹表现为分段矩形波，
 * 并在命中点生成一轮收缩式六边形数据网格。
 */
public final class KunJinKaoColdDataEffectsHandler {

    private static final DustParticleOptions CORE_PARTICLE =
            new DustParticleOptions(new Vector3f(0.0F, 0.90F, 1.0F), 1.10F);
    private static final DustParticleOptions EDGE_PARTICLE =
            new DustParticleOptions(new Vector3f(0.62F, 0.92F, 1.0F), 0.75F);
    private static final DustParticleOptions DEEP_BLUE_PARTICLE =
            new DustParticleOptions(new Vector3f(0.04F, 0.20F, 0.40F), 0.90F);

    @SubscribeEvent
    public void onSwordAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        ItemStack sword = player.getMainHandItem();
        if (!(KunJinKaoSwordItem.isSwordLike(sword))
                || KunJinKaoSwordItem.isDisguised(sword)) {
            return;
        }

        Entity target = event.getTarget();
        Vec3 impact = target.getBoundingBox().getCenter();
        renderStructuredWave(level, player.getEyePosition(), impact);
        renderImpactGrid(level, impact);
    }

    /**
     * 两条交替错位的粒子带组成方形、分段的“数据波”，避免传统弧形刀光。
     */
    private static void renderStructuredWave(ServerLevel level, Vec3 origin, Vec3 impact) {
        Vec3 line = impact.subtract(origin);
        double distance = Math.min(4.5D, line.length());
        if (distance < 0.1D) {
            return;
        }

        Vec3 forward = line.normalize();
        Vec3 side = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-4D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            side = side.normalize();
        }
        Vec3 up = side.cross(forward).normalize();

        // 以整齐的矩形格点代替弧形刀光。中心为淡蓝，四周为更亮的青色。
        for (int segment = 0; segment < 10; segment++) {
            double progress = (segment + 1.0D) / 11.0D;
            Vec3 center = origin.add(forward.scale(distance * progress));
            double width = 0.14D + segment * 0.012D;
            for (int column = -2; column <= 2; column++) {
                Vec3 point = center.add(side.scale(column * width * 0.42D));
                send(level, column == 0 ? CORE_PARTICLE : EDGE_PARTICLE, point);
                if (Math.abs(column) == 2) {
                    send(level, EDGE_PARTICLE, point.add(up.scale(0.075D)));
                    send(level, EDGE_PARTICLE, point.subtract(up.scale(0.075D)));
                }
            }
            // 深蓝底层只保留为冷色数据阴影，不再混入任何红色故障色。
            if ((segment & 1) == 0) {
                send(level, DEEP_BLUE_PARTICLE, center.subtract(up.scale(0.09D)));
            }
        }
    }

    /** 命中点周围先呈现规整六边形网格，作为“数据删除”前的锁定提示。 */
    private static void renderImpactGrid(ServerLevel level, Vec3 impact) {
        for (int ring = 0; ring < 2; ring++) {
            double radius = 0.18D + ring * 0.13D;
            for (int point = 0; point < 6; point++) {
                double angle = Math.toRadians(point * 60.0D + ring * 30.0D);
                Vec3 position = impact.add(Math.cos(angle) * radius,
                        0.06D + Math.sin(angle) * radius, Math.sin(angle) * radius);
                send(level, ring == 0 ? CORE_PARTICLE : EDGE_PARTICLE, position);
            }
        }
        level.sendParticles(ParticleTypes.END_ROD, impact.x, impact.y, impact.z,
                4, 0.08D, 0.08D, 0.08D, 0.01D);
    }

    private static void send(ServerLevel level, DustParticleOptions particle, Vec3 position) {
        level.sendParticles(particle, position.x, position.y, position.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    // ===== 服务端网络动作入口：客户端只请求，所有权限和物品状态均在此验证 =====

    public static void handleToggleTacticalHudRequest(MinecraftServer server, Player source, boolean requestedEnabled) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        // 线格式与语义未变，只是换成合并包 + stateId：两个 boolean 的含义仍是 (enabled, authorized)。
        NetworkHandler.sendToPlayer(player,
                new HudStatePayload(HudStatePayload.TACTICAL_HUD, authorized && requestedEnabled, authorized));
    }

    public static void handleToggleHudNightVision(MinecraftServer server, Player source) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        boolean enabled = false;
        if (authorized) {
            // 真值改从 QolStateSavedData 取（功能面板里 80 那一行看的是同一份）。
            // 原来这里用"身上有没有夜视效果"当状态，判断不出效果是不是我们放的：
            // 玩家自己先喝了一瓶夜视，第一次点这个按钮会被当成"关闭"，顺手把他的药水也摘了。
            enabled = KunJinKaoQolHandler.toggleNightVision(player);
        }
        NetworkHandler.sendToPlayer(player, new HudStatePayload(HudStatePayload.NIGHT_VISION, enabled, authorized));
        // 面板那一行也要跟着刷新：两个入口操作同一份状态，回执就得两个都发。
        KunJinKaoQolHandler.sendState(player, QolSetting.NIGHT_VISION, authorized);
    }

    public static void handleRequestEntityList(MinecraftServer server, Player source) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        List<HudEntityData> entities = new ArrayList<>();
        if (authorized && server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity.isRemoved()) continue;
                    entities.add(new HudEntityData(entity.getUUID(), entity.getId(),
                            level.dimension().location().toString(), entity.getType().getDescriptionId(),
                            entity.getName().getString(), entity.getX(), entity.getY(), entity.getZ()));
                }
            }
        }
        NetworkHandler.sendToPlayer(player, new HudEntityListPayload(authorized, entities));
    }

    public static void handleManageEntity(MinecraftServer server, Player source, HudEntityAction action, UUID entityUuid) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        boolean success = false;
        if (authorized && server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(entityUuid);
                if (entity == null || entity == player) continue;
                if (action == HudEntityAction.KILL) {
                    entity.kill();
                    success = true;
                } else if (action == HudEntityAction.TELEPORT) {
                    player.teleportTo(level, entity.getX(), entity.getY(), entity.getZ(), player.getYRot(), player.getXRot());
                    success = true;
                }
                if (success) break;
            }
        }
        NetworkHandler.sendToPlayer(player, new HudEntityActionResultPayload(action, entityUuid, success, authorized));
    }

    public static void handleToggleHudTrueInvisibility(MinecraftServer server, Player source) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        boolean enabled = authorized && TacticalHudTrueInvisibilityHandler.toggle(player);
        NetworkHandler.sendToPlayer(player,
                new HudStatePayload(HudStatePayload.TRUE_INVISIBILITY, enabled, authorized));
    }

    public static void handleToggleHudMagnet(MinecraftServer server, Player source) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        boolean enabled = authorized && TacticalHudMagnetHandler.toggle(player);
        NetworkHandler.sendToPlayer(player, new HudStatePayload(HudStatePayload.MAGNET, enabled, authorized));
    }

    public static void handleToggleBlueScreenAttack(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setBlueScreenAttackEnabled(stack, enabled));
    }

    public static void handleSetSwordMiningSpeed(Player player, InteractionHand hand, int speed) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setMiningSpeed(stack, speed));
    }

    public static void handleToggleUnbreakableBlockBreaking(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setBreakUnbreakableBlocks(stack, enabled));
    }

    public static void handleSetAreaClearTargetMode(Player player, InteractionHand hand, int modeId) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAreaClearTargetMode(stack, KunJinKaoSwordItem.AreaClearTargetMode.byId(modeId)));
    }

    public static void handleSetSwordAttackDamageLimit(Player player, InteractionHand hand, int limit) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAttackDamageLimit(stack, limit));
    }

    public static void handleSetSwordOreDropMultiplier(Player player, InteractionHand hand, int multiplier) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setOreDropMultiplier(stack, multiplier));
    }

    /** 抢夺设置值 1..1000（1 = 无抢夺）。服务端同样夹一次范围，不信任客户端来的数值。 */
    public static void handleSetSwordLootingMode(Player player, InteractionHand hand, int settingValue) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setLootingLevel(stack, settingValue));
    }

    public static void handleToggleUltimateDeath(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setUltimateDeathEnabled(stack, enabled));
    }

    public static void handleToggleQuitStrike(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setQuitStrikeEnabled(stack, enabled));
    }

    /** 建筑手杖：沿所视方块面延伸放置一排方块。 */
    public static void handleToggleConstructionWand(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setConstructionWandEnabled(stack, enabled));
    }

    /** 天使核心：把方块放到所视方块的背面，或对空在半空放置。 */
    public static void handleToggleAngelCore(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAngelCoreEnabled(stack, enabled));
    }

    /** 破坏核心：挖掉一格时连带清除面向那一侧的整排方块。 */
    public static void handleToggleDestructionCore(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setDestructionCoreEnabled(stack, enabled));
    }

    /** 撤销开关：开启后按 K 键可撤销最近 10 步内的放置/破坏。 */
    public static void handleTogglePlacementUndo(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setPlacementUndoEnabled(stack, enabled));
    }

    /** 刷子：长按右键可刷的方块即可刷取。 */
    public static void handleToggleBrush(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setBrushEnabled(stack, enabled));
    }

    /** 工具模式：0 关 / 1 锄头 / 2 铲子。 */
    public static void handleSetToolMode(Player player, InteractionHand hand, int mode) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setToolMode(stack, mode));
    }

    /** 避雷针：右键避雷针召唤闪电。 */
    /**
     * 避雷针的循环状态：0 = 关闭，1..3 = 开启且闪电形态为 state - 1
     * （0 自然 / 1 普通 / 2 仅视觉）。四态放在一个按钮上循环，
     * 这样不必在已经排满的工具分区里再挤一格。
     */
    public static void handleSetLightningRodState(Player player, InteractionHand hand, int state) {
        withSword(player, hand, stack -> {
            boolean enabled = state > 0;
            KunJinKaoSwordItem.setLightningRodEnabled(stack, enabled);
            if (enabled) {
                KunJinKaoSwordItem.setLightningMode(stack, state - 1);
            }
        });
    }

    public static void handleToggleLightningRod(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setLightningRodEnabled(stack, enabled));
    }

    /** 时间加速模式：0 关 / 1 限时（30 秒）/ 2 无限。 */
    public static void handleSetTimeAccelMode(Player player, InteractionHand hand, int mode) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setTimeAccelMode(stack, mode));
    }

    /** 斩首：击杀生物额外掉落对应头颅。 */
    public static void handleToggleBeheading(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setBeheadingEnabled(stack, enabled));
    }

    /** 刷怪蛋掉落：击杀生物额外掉落它的刷怪蛋。 */
    public static void handleToggleSpawnEggDrop(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setSpawnEggDropEnabled(stack, enabled));
    }

    /** 精准采集：把原版精准采集附魔真的挂到剑上或摘掉。 */
    public static void handleToggleSilkTouch(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setSilkTouchEnabled(stack, enabled, player.level()));
    }

    /** 挖掘熔炼：挖到的掉落自动过一遍熔炉配方。替换逻辑在 KunJinKaoAutoSmeltHandler。 */
    public static void handleToggleAutoSmelt(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAutoSmeltEnabled(stack, enabled));
    }

    /** 扳手标记：给剑带上/取下扳手 NBT。 */
    public static void handleToggleWrench(Player player, InteractionHand hand, boolean enabled) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setWrenchEnabled(stack, enabled));
    }

    public static void handleAreaMode(Player player, InteractionHand hand, int mode) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAreaMode(stack, mode));
    }

    public static void handlePauseSize(Player player, InteractionHand hand, int radius) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setPauseSize(stack, radius));
    }

    public static void handleAreaAccelSize(Player player, InteractionHand hand, int radius) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAreaAccelSize(stack, radius));
    }

    public static void handleAreaAccelMultiplier(Player player, InteractionHand hand, int multiplier) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setAreaAccelMultiplier(stack, multiplier));
    }

    /** 时间加速倍率：shift+滚轮 调节，服务端同样夹到合法档位。 */
    public static void handleSetTimeAccelMultiplier(Player player, InteractionHand hand, int multiplier) {
        withSword(player, hand, stack -> KunJinKaoSwordItem.setTimeAccelMultiplier(stack, multiplier));
    }

    /**
     * 撤销最近一步放置/破坏。
     * <p>
     * 只有剑上开着"撤销"才执行：这个键是全局按键，不加这道闸门的话，
     * 玩家在别的场景误按 K 也会去翻撤销栈。
     */
    public static void handleUndoPlacement(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 先试选区挖掘：它是"一次性改变大片地形"的操作，撤销的优先级明显高于一步放置。
        // 两边共用一个撤销键（K），玩家不需要记住"刚才那次该用哪个键撤"。
        if (dev.modmind.kunjinkao.world.ZoneExcavationJob.undoLast(serverPlayer) > 0) {
            return;
        }
        if (!KunJinKaoSwordItem.isPlacementUndoEnabledInEitherHand(serverPlayer)) {
            return;
        }
        int restored = PlacementUndoHistory.undoLast(serverPlayer);
        serverPlayer.displayClientMessage(restored > 0
                ? Component.translatable("message.kunjinkao.undo_restored", restored)
                : Component.translatable("message.kunjinkao.undo_empty"), true);
    }

    /** HUD 排除列表查询：只有管理员能拿到名单。 */
    public static void handleRequestExcludedPlayers(MinecraftServer server, Player source) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        NetworkHandler.sendToPlayer(player, new ExcludedPlayersPayload(authorized, collectExcluded(server, authorized)));
    }

    /** 从排除名单中移除一名玩家，使其可以重新进入服务器。 */
    public static void handlePardonExcludedPlayer(MinecraftServer server, Player source, UUID targetUuid) {
        if (!(source instanceof ServerPlayer player)) return;
        boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
        if (!authorized || server == null) {
            NetworkHandler.sendToPlayer(player, new ExcludedPlayersPayload(false, List.of()));
            return;
        }
        String removedName = null;
        for (Map.Entry<UUID, String> entry : UltimateDeathSavedData.get(server).entries()) {
            if (entry.getKey().equals(targetUuid)) {
                removedName = entry.getValue();
                break;
            }
        }
        boolean success = UltimateDeathSavedData.get(server).pardon(targetUuid);
        if (success) {
            Component label = removedName == null || removedName.isBlank()
                    ? Component.literal(targetUuid.toString()) : Component.literal(removedName);
            player.displayClientMessage(Component.translatable("message.kunjinkao.exclusion_removed", label), true);
        } else {
            player.displayClientMessage(Component.translatable("message.kunjinkao.exclusion_remove_failed"), true);
        }
        // 无论成功与否都回传服务端的权威名单，客户端据此刷新。
        NetworkHandler.sendToPlayer(player, new ExcludedPlayersPayload(true, collectExcluded(server, true)));
    }

    private static List<ExcludedPlayerData> collectExcluded(MinecraftServer server, boolean authorized) {
        List<ExcludedPlayerData> entries = new ArrayList<>();
        if (authorized && server != null) {
            for (Map.Entry<UUID, String> entry : UltimateDeathSavedData.get(server).entries()) {
                entries.add(new ExcludedPlayerData(entry.getKey(), entry.getValue()));
            }
        }
        return entries;
    }

    /**
     * 剑设置类数据包的统一入口。
     * <p>
     * 授权闸门放在这里而不是各个 handle 里：9 个剑设置项合并成 SwordSettingPayload 之后，
     * 它们的每一个 settingId 分支仍然全部调用本方法，一处校验即可保证
     * "改剑的设置"与 HUD / 排除名单一样只对通过密码验证的玩家开放（合并没有绕过它）。
     * <p>
     * 注意：剑本身的战斗能力仍按既定设计只看"手上有没有剑"，这里只拦设置写入。
     */
    private static void withSword(Player player, InteractionHand hand, java.util.function.Consumer<ItemStack> action) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!AdminToolConfig.isAuthorized(serverPlayer.getUUID())) {
            serverPlayer.displayClientMessage(Component.translatable("message.kunjinkao.admin_required"), true);
            return;
        }
        ItemStack stack = serverPlayer.getItemInHand(hand);
        if (KunJinKaoSwordItem.isSwordLike(stack)) action.accept(stack);
    }
}
