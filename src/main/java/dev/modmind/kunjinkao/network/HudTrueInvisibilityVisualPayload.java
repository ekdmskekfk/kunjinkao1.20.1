package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 真实隐身（视觉）开关（S2C）。
 * <p>
 * 线格式：{@code writeUUID(playerUuid) | writeBoolean(enabled)}。
 */
public record HudTrueInvisibilityVisualPayload(UUID playerUuid, boolean enabled) {

    public static void encode(HudTrueInvisibilityVisualPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.playerUuid);
        buf.writeBoolean(payload.enabled);
    }

    public static HudTrueInvisibilityVisualPayload decode(FriendlyByteBuf buf) {
        return new HudTrueInvisibilityVisualPayload(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(HudTrueInvisibilityVisualPayload payload,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}