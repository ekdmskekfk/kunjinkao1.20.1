package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.event.InjectionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 开始注入（C2S）。线格式（与 1.21.1 逐字节一致）：{@code writeEnum(hand)}。
 *
 * <p>客户端只报"哪只手"，不报物品内容 —— 服务端一律以自己看到的那只手的栈为准。</p>
 */
public record InjectionStartPayload(InteractionHand hand) {

    public static void encode(InjectionStartPayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.hand);
    }

    public static InjectionStartPayload decode(FriendlyByteBuf buf) {
        return new InjectionStartPayload(
                NetworkHandler.readEnumSafe(buf, InteractionHand.class));
    }

    public static void handle(InjectionStartPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player != null) {
                InjectionHandler.start(player, payload.hand());
            }
        });
        context.setPacketHandled(true);
    }
}
