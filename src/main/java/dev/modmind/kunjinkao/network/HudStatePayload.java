package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 合并后的 HUD 状态 S2C 包：把原来 4 个形状逐字节相同的 (enabled, authorized) 回执包
 * 收敛到这一条线格式，由 {@link #stateId()} 判别回执属于哪个 HUD 功能。
 * <p>
 * 线格式：{@code writeVarInt(stateId) | writeBoolean(enabled) | writeBoolean(authorized)}。
 * 两个 boolean 的语义与原来完全一致，客户端 applyXxx 的逻辑也不改，
 * 只在 client/ClientPayloadHandlers 的分发处按 stateId 选择对应方法。
 */
public record HudStatePayload(int stateId, boolean enabled, boolean authorized) {

    // ===== stateId 常量表 =====
    // 这些数值是线格式的一部分（会被写进包里），只能往后追加，绝对不能改动已有取值，
    // 否则客户端会把某个功能的状态回执套到另一个功能上。
    public static final int TACTICAL_HUD = 0;
    public static final int NIGHT_VISION = 1;
    public static final int TRUE_INVISIBILITY = 2;
    public static final int MAGNET = 3;

    public static void encode(HudStatePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.stateId);
        buf.writeBoolean(payload.enabled);
        buf.writeBoolean(payload.authorized);
    }

    public static HudStatePayload decode(FriendlyByteBuf buf) {
        return new HudStatePayload(buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(HudStatePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 与原来 4 个包完全相同的入口：先过 common 侧的客户端网关照发，
        // 保证服务端不会因为加载本类而碰到 net.minecraft.client 下的类。
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}