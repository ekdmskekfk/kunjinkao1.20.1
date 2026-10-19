package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 提交管理员密码（C2S）。
 * <p>
 * 线格式：{@code writeUtf(password, 64)}。
 */
public record SubmitAdminPasswordPayload(String password) {

    public static void encode(SubmitAdminPasswordPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.password, 64);
    }

    public static SubmitAdminPasswordPayload decode(FriendlyByteBuf buf) {
        return new SubmitAdminPasswordPayload(buf.readUtf(64));
    }

    public static void handle(SubmitAdminPasswordPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender != null) {
                boolean authorized = AdminToolConfig.authorize(sender, payload.password);
                NetworkHandler.sendToPlayer(sender, new AdminPasswordResultPayload(authorized));
            }
        });
        context.setPacketHandled(true);
    }
}