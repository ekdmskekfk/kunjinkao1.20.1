package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 管理员密码校验结果（S2C）。线格式：{@code writeBoolean(authorized)}。 */
public record AdminPasswordResultPayload(boolean authorized) {

    public static void encode(AdminPasswordResultPayload payload, FriendlyByteBuf buf) {
        buf.writeBoolean(payload.authorized);
    }

    public static AdminPasswordResultPayload decode(FriendlyByteBuf buf) {
        return new AdminPasswordResultPayload(buf.readBoolean());
    }

    public static void handle(AdminPasswordResultPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}