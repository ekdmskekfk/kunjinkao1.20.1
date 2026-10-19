package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络层（1.20.1 Forge 版）。
 * <p>
 * 与 1.21.1 的结构差异：那边是 CustomPacketPayload + StreamCodec + ResourceLocation 判别，
 * 这边是 SimpleChannel + 整数判别 id。线格式保持逐字节一致：每个 payload 的
 * encode/decode 与原来的 StreamCodec 读写顺序相同，所以协议语义没有变。
 * <p>
 * 发送辅助方法刻意把参数声明成 Object：SimpleChannel.send* 接受任意已注册消息，
 * 而这样写让 1.21.1 那边 sendToPlayer(player, payload) 的调用点一行都不用改。
 * <p>
 * 注册按批次增补：每移植一个 payload，就在 register() 里加一条 messageBuilder，
 * 并把 nextMessageId 往后推一位。已发布的 id 永远不能改，只能在末尾追加。
 */
public final class NetworkHandler {

    /**
     * 这是 1.20.1 线自己的握手版本，改注册表就要 +1。
     * <p>
     * 2 → 3：新增 PlacementSyncPayload（放置记录整表同步）。
     * 1 → 2：一次性补进 14 条 S2C 回执包（HUD 状态 / 实体列表 / 装饰 / 选区 / 加速场 / 建筑手杖预览…）。
     * 注册表从 1 条变成 15 条，旧端必须被握手挡下 —— 否则解码时会按错的判别 id 读错的类。
     */
    public static final String PROTOCOL_VERSION = "5";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** 下一个可用的判别 id。只增不改。 */
    private static int nextMessageId = 0;

    private NetworkHandler() {
    }

    /**
     * 注册全部消息。必须双侧都调用（在 mod 构造里调一次即可），
     * 否则对端会因为没有对应 id 而在解码时失败。
     */
    public static void register() {
        // 已发布的判别 id 永远不能改，只能在末尾追加。
        CHANNEL.messageBuilder(HudShieldHitPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HudShieldHitPayload::encode)
                .decoder(HudShieldHitPayload::decode)
                .consumerMainThread(HudShieldHitPayload::handle)
                .add();

        // ===== 以下 14 条全部是 S2C：handle 只转发 ClientHooks.handleClientPayload =====
        // 顺序照抄 1.21.1 的注册表顺序，方便两侧对照；但判别 id 是本线自己的（1..15），
        // 与 1.21.1 的 ResourceLocation 判别无关。

        // 覆写特效的进度 / 结束 / 取消
        CHANNEL.messageBuilder(OverwriteEffectPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OverwriteEffectPayload::encode)
                .decoder(OverwriteEffectPayload::decode)
                .consumerMainThread(OverwriteEffectPayload::handle)
                .add();
        // 原 TacticalHudState / HudNightVisionState / HudTrueInvisibilityState / HudMagnetState
        // 四条注册合并为这一条，由 stateId 判别。
        CHANNEL.messageBuilder(HudStatePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HudStatePayload::encode)
                .decoder(HudStatePayload::decode)
                .consumerMainThread(HudStatePayload::handle)
                .add();
        CHANNEL.messageBuilder(HudEntityListPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HudEntityListPayload::encode)
                .decoder(HudEntityListPayload::decode)
                .consumerMainThread(HudEntityListPayload::handle)
                .add();
        CHANNEL.messageBuilder(HudEntityActionResultPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HudEntityActionResultPayload::encode)
                .decoder(HudEntityActionResultPayload::decode)
                .consumerMainThread(HudEntityActionResultPayload::handle)
                .add();
        CHANNEL.messageBuilder(HudTrueInvisibilityVisualPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HudTrueInvisibilityVisualPayload::encode)
                .decoder(HudTrueInvisibilityVisualPayload::decode)
                .consumerMainThread(HudTrueInvisibilityVisualPayload::handle)
                .add();
        CHANNEL.messageBuilder(AdminEyeStatePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminEyeStatePayload::encode)
                .decoder(AdminEyeStatePayload::decode)
                .consumerMainThread(AdminEyeStatePayload::handle)
                .add();
        CHANNEL.messageBuilder(SwordDrawAnimationPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SwordDrawAnimationPayload::encode)
                .decoder(SwordDrawAnimationPayload::decode)
                .consumerMainThread(SwordDrawAnimationPayload::handle)
                .add();
        CHANNEL.messageBuilder(AdminPasswordResultPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminPasswordResultPayload::encode)
                .decoder(AdminPasswordResultPayload::decode)
                .consumerMainThread(AdminPasswordResultPayload::handle)
                .add();
        CHANNEL.messageBuilder(ExcludedPlayersPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ExcludedPlayersPayload::encode)
                .decoder(ExcludedPlayersPayload::decode)
                .consumerMainThread(ExcludedPlayersPayload::handle)
                .add();
        CHANNEL.messageBuilder(TimeAccelStatusPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TimeAccelStatusPayload::encode)
                .decoder(TimeAccelStatusPayload::decode)
                .consumerMainThread(TimeAccelStatusPayload::handle)
                .add();
        // 功能面板（QoL）：逐行状态回执。
        CHANNEL.messageBuilder(QolStatePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(QolStatePayload::encode)
                .decoder(QolStatePayload::decode)
                .consumerMainThread(QolStatePayload::handle)
                .add();
        // 选区作业（127）：选区与进度回执。设角点不走网络（木棍右键在服务端直接处理）。
        CHANNEL.messageBuilder(ZoneStatusPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ZoneStatusPayload::encode)
                .decoder(ZoneStatusPayload::decode)
                .consumerMainThread(ZoneStatusPayload::handle)
                .add();
        // 建筑手杖预览：服务端把算好的格子发回来。
        CHANNEL.messageBuilder(WandPreviewResultPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(WandPreviewResultPayload::encode)
                .decoder(WandPreviewResultPayload::decode)
                .consumerMainThread(WandPreviewResultPayload::handle)
                .add();
        // 装饰（201–203）：服务端只推"谁戴着什么、在打谁"，画面全部由客户端自己画。
        CHANNEL.messageBuilder(CosmeticStatePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CosmeticStatePayload::encode)
                .decoder(CosmeticStatePayload::decode)
                .consumerMainThread(CosmeticStatePayload::handle)
                .add();

        // 放置记录整表同步（S2C）：菜单第二页显示"你立了哪些场"。
        CHANNEL.messageBuilder(PlacementSyncPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlacementSyncPayload::encode)
                .decoder(PlacementSyncPayload::decode)
                .consumerMainThread(PlacementSyncPayload::handle)
                .add();

        // ===== C2S：动作请求（handle 调 event 包里的处理器）=====
        CHANNEL.messageBuilder(ToggleTacticalHudPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ToggleTacticalHudPayload::encode)
                .decoder(ToggleTacticalHudPayload::decode)
                .consumerMainThread(ToggleTacticalHudPayload::handle)
                .add();
        CHANNEL.messageBuilder(CollectBlockPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CollectBlockPayload::encode)
                .decoder(CollectBlockPayload::decode)
                .consumerMainThread(CollectBlockPayload::handle)
                .add();
        CHANNEL.messageBuilder(ManageExcludedPlayerPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ManageExcludedPlayerPayload::encode)
                .decoder(ManageExcludedPlayerPayload::decode)
                .consumerMainThread(ManageExcludedPlayerPayload::handle)
                .add();
        CHANNEL.messageBuilder(SubmitAdminPasswordPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SubmitAdminPasswordPayload::encode)
                .decoder(SubmitAdminPasswordPayload::decode)
                .consumerMainThread(SubmitAdminPasswordPayload::handle)
                .add();

        // ===== 其余 C2S：剑设置 / 快捷动作 / 手杖预览 / 加速器 / 选区 =====
        CHANNEL.messageBuilder(ToggleDisguisePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ToggleDisguisePayload::encode).decoder(ToggleDisguisePayload::decode)
                .consumerMainThread(ToggleDisguisePayload::handle).add();
        CHANNEL.messageBuilder(ToggleOverwritePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ToggleOverwritePayload::encode).decoder(ToggleOverwritePayload::decode)
                .consumerMainThread(ToggleOverwritePayload::handle).add();
        CHANNEL.messageBuilder(ToggleThemePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ToggleThemePayload::encode).decoder(ToggleThemePayload::decode)
                .consumerMainThread(ToggleThemePayload::handle).add();
        CHANNEL.messageBuilder(AcceleratorConfigPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AcceleratorConfigPayload::encode).decoder(AcceleratorConfigPayload::decode)
                .consumerMainThread(AcceleratorConfigPayload::handle).add();
        CHANNEL.messageBuilder(AcceleratorShowRangePayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AcceleratorShowRangePayload::encode).decoder(AcceleratorShowRangePayload::decode)
                .consumerMainThread(AcceleratorShowRangePayload::handle).add();
        CHANNEL.messageBuilder(AcceleratorFilterPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AcceleratorFilterPayload::encode).decoder(AcceleratorFilterPayload::decode)
                .consumerMainThread(AcceleratorFilterPayload::handle).add();
        CHANNEL.messageBuilder(SimpleActionPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SimpleActionPayload::encode).decoder(SimpleActionPayload::decode)
                .consumerMainThread(SimpleActionPayload::handle).add();
        CHANNEL.messageBuilder(RemovePlacementPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RemovePlacementPayload::encode).decoder(RemovePlacementPayload::decode)
                .consumerMainThread(RemovePlacementPayload::handle).add();
        CHANNEL.messageBuilder(ManageHudEntityPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ManageHudEntityPayload::encode).decoder(ManageHudEntityPayload::decode)
                .consumerMainThread(ManageHudEntityPayload::handle).add();
        CHANNEL.messageBuilder(SwordSettingPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SwordSettingPayload::encode).decoder(SwordSettingPayload::decode)
                .consumerMainThread(SwordSettingPayload::handle).add();
        CHANNEL.messageBuilder(QolSettingPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(QolSettingPayload::encode).decoder(QolSettingPayload::decode)
                .consumerMainThread(QolSettingPayload::handle).add();
        CHANNEL.messageBuilder(ZoneAdjustPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ZoneAdjustPayload::encode).decoder(ZoneAdjustPayload::decode)
                .consumerMainThread(ZoneAdjustPayload::handle).add();
        CHANNEL.messageBuilder(WandPreviewRequestPayload.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(WandPreviewRequestPayload::encode).decoder(WandPreviewRequestPayload::decode)
                .consumerMainThread(WandPreviewRequestPayload::handle).add();
    }

    /** 统计已注册的消息数，供自检/日志用。 */
    public static int registeredMessageCount() {
        return nextMessageId;
    }

    // ===== 发送辅助（签名与 1.21.1 保持一致，调用点无需改动） =====

    public static void sendToServer(Object payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToAll(Object payload) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), payload);
    }

    public static void sendToAllTracking(ServerPlayer entity, Object payload) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), payload);
    }

    /** 按维度广播：加速场提示只需要发给同维度的客户端。 */
    public static void sendToPlayersInDimension(ServerLevel level, Object payload) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), payload);
    }

    /**
     * 原生 FriendlyByteBuf#readEnum 的安全版本：线格式完全一致（VarInt 序号），
     * 只在序号越界时回落到 values()[0]。合法输入的解析结果与原生完全一致。
     */
    public static <E extends Enum<E>> E readEnumSafe(FriendlyByteBuf buf, Class<E> type) {
        int ordinal = buf.readVarInt();
        E[] values = type.getEnumConstants();
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Enum type has no constants: " + type.getName());
        }
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : values[0];
    }
}
