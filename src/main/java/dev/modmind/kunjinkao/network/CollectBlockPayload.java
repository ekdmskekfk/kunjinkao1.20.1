package dev.modmind.kunjinkao.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：请求把"玩家正看着的那个方块"直接收进物品栏。
 * <p>
 * 刻意不带坐标 —— 服务端用自己的射线检测（{@code player.pick}）确定目标，
 * 客户端只负责说"我按了 R"。这样客户端无法指定任意坐标去挖区块外的方块。
 * <p>
 * 线格式：空的（{@code StreamCodec.unit} 等价于"什么都不写"）。
 */
public record CollectBlockPayload() {

    public static final CollectBlockPayload INSTANCE = new CollectBlockPayload();

    public static void encode(CollectBlockPayload payload, FriendlyByteBuf buf) {
        // 空载荷。
    }

    public static CollectBlockPayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }

    public static void handle(CollectBlockPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> dev.modmind.kunjinkao.event.SwordAutoPickupHandler.collectTargetedBlock(sender));
        context.setPacketHandled(true);
    }
}