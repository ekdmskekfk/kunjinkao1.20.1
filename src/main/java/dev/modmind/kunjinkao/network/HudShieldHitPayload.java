package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * HUD 盾牌受击反馈（S2C）。
 * <p>
 * 线格式（与 1.21.1 逐字节一致）：{@code writeUUID(playerUuid) | writeFloat(relativeImpactYaw)
 * | writeFloat(impactHeight)}。
 * <p>
 * 这是本工程第一个移植完成的 payload，作为其余 32 个的样板：
 * 1.21.1 的 {@code StreamCodec.of(写 lambda, 读 lambda)} 拆成静态
 * {@link #encode} / {@link #decode}；{@code handle} 从 {@code IPayloadContext}
 * 换成 {@code Supplier<NetworkEvent.Context>}，用 {@code enqueueWork} 回主线程。
 */
public record HudShieldHitPayload(UUID playerUuid, float relativeImpactYaw, float impactHeight) {

    public static void encode(HudShieldHitPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.playerUuid);
        buf.writeFloat(payload.relativeImpactYaw);
        buf.writeFloat(payload.impactHeight);
    }

    public static HudShieldHitPayload decode(FriendlyByteBuf buf) {
        return new HudShieldHitPayload(buf.readUUID(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(HudShieldHitPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}