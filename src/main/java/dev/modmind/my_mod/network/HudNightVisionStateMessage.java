package dev.modmind.my_mod.network;

import dev.modmind.my_mod.client.TacticalHudClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端向点击夜视按钮的客户端回传实际夜视状态。 */
public final class HudNightVisionStateMessage {

    private final boolean enabled;
    private final boolean authorized;

    public HudNightVisionStateMessage(boolean enabled, boolean authorized) {
        this.enabled = enabled;
        this.authorized = authorized;
    }

    public static void encode(HudNightVisionStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.enabled);
        buffer.writeBoolean(message.authorized);
    }

    public static HudNightVisionStateMessage decode(FriendlyByteBuf buffer) {
        return new HudNightVisionStateMessage(buffer.readBoolean(), buffer.readBoolean());
    }

    public static void handle(HudNightVisionStateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> TacticalHudClientPacketHandler.applyNightVision(message.enabled, message.authorized));
        context.setPacketHandled(true);
    }
}
