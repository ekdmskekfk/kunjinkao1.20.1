package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.event.KunJinKaoColdDataEffectsHandler;
import dev.modmind.kunjinkao.event.KunJinKaoQolHandler;
import dev.modmind.kunjinkao.world.ZoneExcavationJob;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.function.Supplier;

/**
 * 合并后的空载荷 C2S 动作包：把原来 5 个"没有参数"的包（2 个列表请求 + 3 个 HUD 开关）
 * 收敛到这一条线格式，由 {@link #actionId()} 判别动作。
 * <p>
 * 线格式：{@code writeVarInt(actionId)}。原来每个动作都要一个空载荷 + 一条独立注册，
 * 合并后只剩一个类型、一条注册，包体也只有一个 VarInt。
 * <p>
 * 服务端对象的取法照抄原来各包：{@code ServerLifecycleHooks.getCurrentServer()}；
 * 授权仍然发生在 {@link KunJinKaoColdDataEffectsHandler} 对应的 handleXxx 里
 * （AdminToolConfig.isAuthorized），这里只做分发。
 */
public record SimpleActionPayload(int actionId) {

    // ===== actionId 常量表 =====
    // 这些数值是线格式的一部分（会被写进包里），只能往后追加，绝对不能改动已有取值，
    // 否则新旧客户端/服务端会把同一个 id 解释成不同的动作。
    public static final int REQUEST_HUD_ENTITY_LIST = 0;
    public static final int REQUEST_EXCLUDED_PLAYERS = 1;
    public static final int TOGGLE_HUD_NIGHT_VISION = 2;
    public static final int TOGGLE_HUD_TRUE_INVISIBILITY = 3;
    public static final int TOGGLE_HUD_MAGNET = 4;
    /** 撤销最近一步放置/破坏。 */
    public static final int UNDO_PLACEMENT = 5;
    /** 功能面板：请求一次全量状态（打开面板时发）。 */
    public static final int REQUEST_QOL_STATE = 6;
    /** 选区作业（127）：对当前选区执行「破坏」。 */
    public static final int ZONE_EXECUTE_BREAK = 7;
    /** 选区作业（127）：取消正在跑的作业，或清空当前选区。 */
    public static final int ZONE_CANCEL = 8;
    /** 选区作业（127）：对当前选区执行「放置」。接在 8 之后，不回头改已有取值。 */
    public static final int ZONE_EXECUTE_PLACE = 9;
    /** 10：切换「木棍右键框选」开关（管理员/玩家自己都可切）。 */
    public static final int TOGGLE_ZONE_SELECTION = 10;

    public static void encode(SimpleActionPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.actionId);
    }

    public static SimpleActionPayload decode(FriendlyByteBuf buf) {
        return new SimpleActionPayload(buf.readVarInt());
    }

    public static void handle(SimpleActionPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            switch (payload.actionId()) {
                case REQUEST_HUD_ENTITY_LIST -> KunJinKaoColdDataEffectsHandler.handleRequestEntityList(server, sender);
                case REQUEST_EXCLUDED_PLAYERS -> KunJinKaoColdDataEffectsHandler.handleRequestExcludedPlayers(server, sender);
                case TOGGLE_HUD_NIGHT_VISION -> KunJinKaoColdDataEffectsHandler.handleToggleHudNightVision(server, sender);
                case TOGGLE_HUD_TRUE_INVISIBILITY -> KunJinKaoColdDataEffectsHandler.handleToggleHudTrueInvisibility(server, sender);
                case TOGGLE_HUD_MAGNET -> KunJinKaoColdDataEffectsHandler.handleToggleHudMagnet(server, sender);
                case UNDO_PLACEMENT -> KunJinKaoColdDataEffectsHandler.handleUndoPlacement(sender);
                case REQUEST_QOL_STATE -> KunJinKaoQolHandler.handleRequestFullState(sender);
                case ZONE_EXECUTE_BREAK -> KunJinKaoQolHandler.handleZoneExecute(sender, ZoneExcavationJob.MODE_BREAK);
                case ZONE_EXECUTE_PLACE -> KunJinKaoQolHandler.handleZoneExecute(sender, ZoneExcavationJob.MODE_PLACE);
                case ZONE_CANCEL -> KunJinKaoQolHandler.handleZoneCancel(sender);
            case TOGGLE_ZONE_SELECTION -> KunJinKaoQolHandler.toggleZoneSelection(sender);
                default -> {
                    // 未知 actionId：忽略，等价于原来收到一个不认识的空载荷包。
                }
            }
        });
        context.setPacketHandled(true);
    }
}