package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 管理员透视状态（S2C）。
 * <p>
 * 线格式（与 1.21.1 逐字节一致）：{@code writeUUID(playerUuid) | writeBoolean(enabled)}。
 * <p>
 * 1.20.1 移植：{@code CustomPacketPayload} / {@code Type} / {@code StreamCodec} 全部去掉，
 * 判别 id 改由 {@link NetworkHandler} 的 {@code nextMessageId} 在注册时分配。
 */
public record AdminEyeStatePayload(UUID playerUuid, boolean enabled) {

    public static void encode(AdminEyeStatePayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.playerUuid);
        buf.writeBoolean(payload.enabled);
    }

    public static AdminEyeStatePayload decode(FriendlyByteBuf buf) {
        return new AdminEyeStatePayload(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(AdminEyeStatePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}