package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 预览结果 S2C：这次会动到的那些格子。
 * <p>
 * 线格式：{@code writeVarInt(seq) | writeVarInt(count) | count × writeBlockPos}。
 * 与参考实现只有一处差别：<b>带上了长度</b>。参考实现是靠"包读到尾"来判断结束的，
 * 那种写法一旦以后要往同一个包里加字段就没法兼容，加个 varint 更稳。
 * <p>
 * 载荷里只有坐标，和参考实现一样：颜色、能不能放、缺不缺材料都由客户端按核心类型自行决定，
 * 因为"哪些格子进得来"本身就已经是服务端筛过的结果了（放不下的格子根本不在列表里）。
 */
public record WandPreviewResultPayload(int seq, Set<BlockPos> positions) {

    public static void encode(WandPreviewResultPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.seq);
        buf.writeVarInt(payload.positions.size());
        for (BlockPos pos : payload.positions) {
            buf.writeBlockPos(pos);
        }
    }

    public static WandPreviewResultPayload decode(FriendlyByteBuf buf) {
        int seq = buf.readVarInt();
        int count = buf.readVarInt();
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            positions.add(buf.readBlockPos());
        }
        return new WandPreviewResultPayload(seq, positions);
    }

    public static void handle(WandPreviewResultPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}