package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * HUD 实体操作回执（S2C）。
 * <p>
 * 线格式：{@code writeEnum(action) | writeUUID(entityUuid) | writeBoolean(success) | writeBoolean(authorized)}。
 */
public record HudEntityActionResultPayload(HudEntityAction action, UUID entityUuid, boolean success,
                                           boolean authorized) {

    public static void encode(HudEntityActionResultPayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.action);
        buf.writeUUID(payload.entityUuid);
        buf.writeBoolean(payload.success);
        buf.writeBoolean(payload.authorized);
    }

    public static HudEntityActionResultPayload decode(FriendlyByteBuf buf) {
        return new HudEntityActionResultPayload(
                NetworkHandler.readEnumSafe(buf, HudEntityAction.class),
                buf.readUUID(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(HudEntityActionResultPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}