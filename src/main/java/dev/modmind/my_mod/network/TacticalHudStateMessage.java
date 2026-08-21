package dev.modmind.my_mod.network;

import dev.modmind.my_mod.client.TacticalHudClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端将白名单验证后的战术 HUD 状态回传给请求者。 */
public final class TacticalHudStateMessage {

    private final boolean enabled;
    private final boolean authorized;

    public TacticalHudStateMessage(boolean enabled, boolean authorized) {
        this.enabled = enabled;
        this.authorized = authorized;
    }

    public static void encode(TacticalHudStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.enabled);
        buffer.writeBoolean(message.authorized);
    }

    public static TacticalHudStateMessage decode(FriendlyByteBuf buffer) {
        return new TacticalHudStateMessage(buffer.readBoolean(), buffer.readBoolean());
    }

    public static void handle(TacticalHudStateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> TacticalHudClientPacketHandler.apply(message.enabled, message.authorized));
        context.setPacketHandled(true);
    }
}
