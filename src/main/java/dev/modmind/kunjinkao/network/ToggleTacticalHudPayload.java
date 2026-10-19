package dev.modmind.kunjinkao.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.function.Supplier;

/**
 * 战术 HUD 开关请求（C2S）。
 * <p>
 * 线格式：{@code writeBoolean(requestedEnabled)}。
 */
public record ToggleTacticalHudPayload(boolean requestedEnabled) {

    public static void encode(ToggleTacticalHudPayload payload, FriendlyByteBuf buf) {
        buf.writeBoolean(payload.requestedEnabled);
    }

    public static ToggleTacticalHudPayload decode(FriendlyByteBuf buf) {
        return new ToggleTacticalHudPayload(buf.readBoolean());
    }

    public static void handle(ToggleTacticalHudPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> dev.modmind.kunjinkao.event.KunJinKaoColdDataEffectsHandler.handleToggleTacticalHudRequest(
                ServerLifecycleHooks.getCurrentServer(), sender, payload.requestedEnabled));
        context.setPacketHandled(true);
    }
}