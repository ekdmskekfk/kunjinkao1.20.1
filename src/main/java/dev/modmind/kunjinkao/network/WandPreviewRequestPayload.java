package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.wand.WandPreviewServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 预览请求 C2S：客户端把"我正看着哪一格、看的哪一面"发上来。
 * <p>
 * 线格式：{@code writeVarInt(seq) | writeBlockHitResult(hit)}。
 * <p>
 * 两个刻意的设计（都跟参考实现不同，理由写在 {@link WandPreviewServer} 上）：
 * <ul>
 *   <li>带 {@code seq}：结果可能因为网络乱序而晚到，客户端靠它丢掉过期的结果；</li>
 *   <li><b>不</b>随包发物品栈：服务端用自己那份主手物品算，客户端无从伪造。</li>
 * </ul>
 */
public record WandPreviewRequestPayload(int seq, BlockHitResult hit) {

    public static void encode(WandPreviewRequestPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.seq);
        buf.writeBlockHitResult(payload.hit);
    }

    public static WandPreviewRequestPayload decode(FriendlyByteBuf buf) {
        return new WandPreviewRequestPayload(buf.readVarInt(), buf.readBlockHitResult());
    }

    public static void handle(WandPreviewRequestPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null || !WandPreviewServer.allow(player)) {
                return;
            }
            WandPreviewServer.serve(player, payload.seq(), payload.hit());
        });
        context.setPacketHandled(true);
    }
}