package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 注入进度状态（S2C）。线格式（与 1.21.1 逐字节一致）：{@code writeInt(state) |
 * writeInt(durationTicks) | writeInt(hand)}。
 *
 * <p>定长三整数 —— 这个模组被"变长解码端字段错位"坑过一次，
 * 新包一律不用可选/VarInt 列表。</p>
 */
public record InjectionStatePayload(int state, int durationTicks, int hand) {

    /** 开始计时：客户端按 durationTicks 自行插值画进度条。 */
    public static final int START = 0;
    /** 中断：移动 / 受伤 / 换手 / 物品变了。进度条立刻消失。 */
    public static final int ABORT = 1;
    /** 完成：物品已被服务端写入注入标记。 */
    public static final int DONE = 2;

    public static void encode(InjectionStatePayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.state);
        buf.writeInt(payload.durationTicks);
        buf.writeInt(payload.hand);
    }

    public static InjectionStatePayload decode(FriendlyByteBuf buf) {
        return new InjectionStatePayload(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(InjectionStatePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 1.20.1 的客户端分发只有单参重载（见 ClientHooks）。
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}
