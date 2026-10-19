package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 拔剑动画触发（S2C）。
 * <p>
 * 线格式：{@code writeUUID(playerUuid) | writeEnum(hand)}。枚举走 {@link NetworkHandler#readEnumSafe}，
 * 改造过的客户端发越界序号时回落到第一个枚举常量，而不是在 netty 解码线程把连接打断。
 */
public record SwordDrawAnimationPayload(UUID playerUuid, InteractionHand hand) {

    public static void encode(SwordDrawAnimationPayload payload, FriendlyByteBuf buf) {
        buf.writeUUID(payload.playerUuid);
        buf.writeEnum(payload.hand);
    }

    public static SwordDrawAnimationPayload decode(FriendlyByteBuf buf) {
        return new SwordDrawAnimationPayload(buf.readUUID(),
                NetworkHandler.readEnumSafe(buf, InteractionHand.class));
    }

    public static void handle(SwordDrawAnimationPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}