package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.event.KunJinKaoQolHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 选区作业（127）的缩放请求（C2S）：木棍 + Shift + 滚轮。
 * <p>
 * <b>包里只有一个滚轮格数，没有轴、也没有方向。</b> 轴向与正负由服务端拿
 * {@code player.getLookAngle()} 现算（见 {@link KunJinKaoQolHandler#handleZoneAdjust}）：
 * 客户端只负责说「我往上滚了几格」，格数在两端都会被夹回 ±{@link #MAX_STEPS}。
 * <p>
 * 线格式：<b>定长，一个字段</b> —— {@code writeVarInt(delta)}。
 * 正数 = 扩大，负数 = 缩小，绝对值是这一次要动的格数。
 */
public record ZoneAdjustPayload(int delta) {

    /**
     * 一次请求最多调整多少格。
     * <p>
     * 正常滚轮一格就是 1（触控板的小数滚动由客户端取整后至少算一格），
     * 这个上限存在的意义只是兜住改过的客户端 —— 一次滚轮就让选区涨 1000 格没有任何对应操作。
     */
    public static final int MAX_STEPS = 16;

    /**
     * 夹取写在构造器里，于是编码与解码两条路都会经过它：客户端保证发出去的值合法，
     * 服务端保证收进来的值也合法。不去指望对端守规矩，是这条包唯一需要防的东西。
     */
    public ZoneAdjustPayload {
        delta = Math.max(-MAX_STEPS, Math.min(MAX_STEPS, delta));
    }

    public static void encode(ZoneAdjustPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.delta);
    }

    public static ZoneAdjustPayload decode(FriendlyByteBuf buf) {
        return new ZoneAdjustPayload(buf.readVarInt());
    }

    public static void handle(ZoneAdjustPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> KunJinKaoQolHandler.handleZoneAdjust(player, payload.delta()));
        context.setPacketHandled(true);
    }
}