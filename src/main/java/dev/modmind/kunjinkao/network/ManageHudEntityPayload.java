package dev.modmind.kunjinkao.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 管理员对 HUD 列表里某个实体的操作请求（C2S）。
 * <p>
 * 线格式：{@code writeEnum(action) | writeUUID(entityUuid)}。
 */
public record ManageHudEntityPayload(HudEntityAction action, UUID entityUuid) {

    public static void encode(ManageHudEntityPayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.action);
        buf.writeUUID(payload.entityUuid);
    }

    public static ManageHudEntityPayload decode(FriendlyByteBuf buf) {
        return new ManageHudEntityPayload(NetworkHandler.readEnumSafe(buf, HudEntityAction.class), buf.readUUID());
    }

    public static void handle(ManageHudEntityPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> dev.modmind.kunjinkao.event.KunJinKaoColdDataEffectsHandler.handleManageEntity(
                ServerLifecycleHooks.getCurrentServer(), sender, payload.action, payload.entityUuid));
        context.setPacketHandled(true);
    }
}