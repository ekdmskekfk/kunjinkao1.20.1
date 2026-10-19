package dev.modmind.kunjinkao.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 管理员在 HUD 排除列表里解除某个玩家的排除（C2S）。
 * <p>
 * 线格式：{@code writeUUID(playerUuid)}。
 */
public record ManageExcludedPlayerPayload(UUID playerUuid) {

    public static void encode(ManageExcludedPlayerPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.playerUuid);
    }

    public static ManageExcludedPlayerPayload decode(FriendlyByteBuf buf) {
        return new ManageExcludedPlayerPayload(buf.readUUID());
    }

    public static void handle(ManageExcludedPlayerPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> dev.modmind.kunjinkao.event.KunJinKaoColdDataEffectsHandler.handlePardonExcludedPlayer(
                ServerLifecycleHooks.getCurrentServer(), sender, payload.playerUuid));
        context.setPacketHandled(true);
    }
}