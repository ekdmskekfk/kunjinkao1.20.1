package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 加速场状态 S2C 包：告诉客户端"现在有哪些东西正在被加速、倍率多少、还剩多久"，
 * 由客户端在它们上方画悬浮提示。
 * <p>
 * 用一个 kinds 字段区分三种加速对象，共用同一条线格式：
 * <ul>
 *   <li>{@link #KIND_BLOCK}：某一格机器 —— 用 pos；</li>
 *   <li>{@link #KIND_ENTITY}：被加速的生物 —— 用 entityId（客户端自己解析成实体）；</li>
 *   <li>{@link #KIND_TIME}：整体时间加速 —— 没有具体位置，提示画在太阳方向上。</li>
 * </ul>
 * 剩余时间以毫秒下发，客户端用自己的时钟往前推，避免两边计时器慢慢跑偏。
 */
public record TimeAccelStatusPayload(List<Entry> entries) {

    public static final int KIND_BLOCK = 0;
    public static final int KIND_ENTITY = 1;
    public static final int KIND_TIME = 2;

    /** 一条加速记录。未用到的字段填默认值（方块填 BlockPos.ZERO，生物填 0）。 */
    public record Entry(int kind, BlockPos pos, int entityId, int multiplier, long remainingMillis) {

        public static Entry block(BlockPos pos, int multiplier, long remainingMillis) {
            return new Entry(KIND_BLOCK, pos, 0, multiplier, remainingMillis);
        }

        public static Entry entity(int entityId, int multiplier, long remainingMillis) {
            return new Entry(KIND_ENTITY, BlockPos.ZERO, entityId, multiplier, remainingMillis);
        }

        public static Entry time(int multiplier, long remainingMillis) {
            return new Entry(KIND_TIME, BlockPos.ZERO, 0, multiplier, remainingMillis);
        }
    }

    public static void encode(TimeAccelStatusPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buf.writeVarInt(entry.kind());
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.entityId());
            buf.writeVarInt(entry.multiplier());
            buf.writeVarLong(entry.remainingMillis());
        }
    }

    public static TimeAccelStatusPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readBlockPos(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarLong()));
        }
        return new TimeAccelStatusPayload(List.copyOf(entries));
    }

    public static void handle(TimeAccelStatusPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 与其它 S2C 包同一入口：先过 common 侧网关，保证服务端不会加载 client 包下的类。
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}