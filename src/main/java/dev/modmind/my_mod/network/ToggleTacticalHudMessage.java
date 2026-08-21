package dev.modmind.my_mod.network;

import dev.modmind.my_mod.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** 客户端请求切换战术 HUD；白名单判断只在服务端执行。 */
public final class ToggleTacticalHudMessage {

    private final boolean requestedEnabled;

    public ToggleTacticalHudMessage(boolean requestedEnabled) {
        this.requestedEnabled = requestedEnabled;
    }

    public static void encode(ToggleTacticalHudMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.requestedEnabled);
    }

    public static ToggleTacticalHudMessage decode(FriendlyByteBuf buffer) {
        return new ToggleTacticalHudMessage(buffer.readBoolean());
    }

    public static void handle(ToggleTacticalHudMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
            boolean enabled = authorized && message.requestedEnabled;
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new TacticalHudStateMessage(enabled, authorized));
        });
        context.setPacketHandled(true);
    }
}
