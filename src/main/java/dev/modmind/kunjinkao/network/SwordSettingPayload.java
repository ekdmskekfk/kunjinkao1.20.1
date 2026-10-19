package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.event.KunJinKaoColdDataEffectsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 合并后的剑设置 C2S 包：把原来 9 个"形状相同"的包（5 个 (hand, int) + 4 个 (hand, boolean)）
 * 收敛到这一条线格式，由 {@link #settingId()} 判别具体设置项。
 * <p>
 * 线格式：{@code writeEnum(hand) | writeVarInt(settingId) | writeVarInt(value)}。
 * 用 VarInt 而不是定长 int：id 与 value 都是小整数；开关类设置统一编码为 0/1
 * （{@link #value()} != 0），使所有设置项共用同一条解码路径。
 * <p>
 * 授权闸门刻意没有搬进这里：仍然调用 {@link KunJinKaoColdDataEffectsHandler} 原来的 handleXxx，
 * 由它们内部的 withSword(isAuthorized) 统一校验，保证"改剑设置"只对通过密码验证的玩家开放。
 * <p>
 * 1.20.1 移植：{@code IPayloadContext#player()} → {@code NetworkEvent.Context#getSender()}。
 */
public record SwordSettingPayload(InteractionHand hand, int settingId, int value) {

    // ===== settingId 常量表 =====
    // 这些数值是线格式的一部分（会被写进包里），只能往后追加，绝对不能改动已有取值：
    // 一旦改动，新旧客户端/服务端会把同一个 id 解释成不同的设置项，改到别的设置上。
    public static final int MINING_SPEED = 0;
    public static final int ATTACK_DAMAGE_LIMIT = 1;
    public static final int ORE_DROP_MULTIPLIER = 2;
    public static final int LOOTING_MODE = 3;
    public static final int AREA_CLEAR_TARGET_MODE = 4;
    public static final int BLUE_SCREEN_ATTACK = 5;
    public static final int UNBREAKABLE_BLOCK_BREAKING = 6;
    public static final int ULTIMATE_DEATH = 7;
    public static final int QUIT_STRIKE = 8;
    public static final int CONSTRUCTION_WAND = 9;
    public static final int ANGEL_CORE = 10;
    public static final int DESTRUCTION_CORE = 11;
    public static final int PLACEMENT_UNDO = 12;
    public static final int BRUSH = 13;
    public static final int TOOL_MODE = 14;
    public static final int LIGHTNING_ROD = 15;
    public static final int TIME_ACCEL_MODE = 16;
    public static final int TIME_ACCEL_MULTIPLIER = 17;
    public static final int WRENCH = 18;
    public static final int SILK_TOUCH = 21;
    public static final int BEHEADING = 19;
    public static final int SPAWN_EGG_DROP = 20;
    public static final int AREA_MODE = 22;
    public static final int PAUSE_SIZE = 23;
    public static final int AREA_ACCEL_SIZE = 24;
    public static final int AREA_ACCEL_MULTIPLIER = 25;
    public static final int AUTO_SMELT = 26;

    public static void encode(SwordSettingPayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.hand);
        buf.writeVarInt(payload.settingId);
        buf.writeVarInt(payload.value);
    }

    public static SwordSettingPayload decode(FriendlyByteBuf buf) {
        return new SwordSettingPayload(NetworkHandler.readEnumSafe(buf, InteractionHand.class),
                buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SwordSettingPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            InteractionHand hand = payload.hand();
            int value = payload.value();
            switch (payload.settingId()) {
                case MINING_SPEED -> KunJinKaoColdDataEffectsHandler.handleSetSwordMiningSpeed(sender, hand, value);
                case ATTACK_DAMAGE_LIMIT -> KunJinKaoColdDataEffectsHandler.handleSetSwordAttackDamageLimit(sender, hand, value);
                case ORE_DROP_MULTIPLIER -> KunJinKaoColdDataEffectsHandler.handleSetSwordOreDropMultiplier(sender, hand, value);
                case LOOTING_MODE -> KunJinKaoColdDataEffectsHandler.handleSetSwordLootingMode(sender, hand, value);
                case AREA_CLEAR_TARGET_MODE -> KunJinKaoColdDataEffectsHandler.handleSetAreaClearTargetMode(sender, hand, value);
                case BLUE_SCREEN_ATTACK -> KunJinKaoColdDataEffectsHandler.handleToggleBlueScreenAttack(sender, hand, value != 0);
                case UNBREAKABLE_BLOCK_BREAKING -> KunJinKaoColdDataEffectsHandler.handleToggleUnbreakableBlockBreaking(sender, hand, value != 0);
                case ULTIMATE_DEATH -> KunJinKaoColdDataEffectsHandler.handleToggleUltimateDeath(sender, hand, value != 0);
                case QUIT_STRIKE -> KunJinKaoColdDataEffectsHandler.handleToggleQuitStrike(sender, hand, value != 0);
                case CONSTRUCTION_WAND -> KunJinKaoColdDataEffectsHandler.handleToggleConstructionWand(sender, hand, value != 0);
                case ANGEL_CORE -> KunJinKaoColdDataEffectsHandler.handleToggleAngelCore(sender, hand, value != 0);
                case DESTRUCTION_CORE -> KunJinKaoColdDataEffectsHandler.handleToggleDestructionCore(sender, hand, value != 0);
                case PLACEMENT_UNDO -> KunJinKaoColdDataEffectsHandler.handleTogglePlacementUndo(sender, hand, value != 0);
                case BRUSH -> KunJinKaoColdDataEffectsHandler.handleToggleBrush(sender, hand, value != 0);
                case TOOL_MODE -> KunJinKaoColdDataEffectsHandler.handleSetToolMode(sender, hand, value);
                case LIGHTNING_ROD -> KunJinKaoColdDataEffectsHandler.handleSetLightningRodState(sender, hand, value);
                case TIME_ACCEL_MODE -> KunJinKaoColdDataEffectsHandler.handleSetTimeAccelMode(sender, hand, value);
                case TIME_ACCEL_MULTIPLIER -> KunJinKaoColdDataEffectsHandler.handleSetTimeAccelMultiplier(sender, hand, value);
                case WRENCH -> KunJinKaoColdDataEffectsHandler.handleToggleWrench(sender, hand, value != 0);
                case AREA_MODE -> KunJinKaoColdDataEffectsHandler.handleAreaMode(sender, hand, value);
                case PAUSE_SIZE -> KunJinKaoColdDataEffectsHandler.handlePauseSize(sender, hand, value);
                case AREA_ACCEL_SIZE -> KunJinKaoColdDataEffectsHandler.handleAreaAccelSize(sender, hand, value);
                case AREA_ACCEL_MULTIPLIER -> KunJinKaoColdDataEffectsHandler.handleAreaAccelMultiplier(sender, hand, value);
                case SILK_TOUCH -> KunJinKaoColdDataEffectsHandler.handleToggleSilkTouch(sender, hand, value != 0);
                case AUTO_SMELT -> KunJinKaoColdDataEffectsHandler.handleToggleAutoSmelt(sender, hand, value != 0);
                case BEHEADING -> KunJinKaoColdDataEffectsHandler.handleToggleBeheading(sender, hand, value != 0);
                case SPAWN_EGG_DROP -> KunJinKaoColdDataEffectsHandler.handleToggleSpawnEggDrop(sender, hand, value != 0);
                default -> {
                    // 未知 settingId：与原来"收到自己认不出的包"一样，什么都不做。
                }
            }
        });
        context.setPacketHandled(true);
    }
}