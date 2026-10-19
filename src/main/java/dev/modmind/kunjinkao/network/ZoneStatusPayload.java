package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 选区挖掘（127）的状态回执（S2C）：两个角点 + 作业进度。
 * <p>
 * 一条包同时管"选区"和"进度"，是因为它们在客户端只服务于同一件事 ——
 * 画出那个框、以及在框旁边显示还剩多少。分成两条包只会让两边的状态有机会对不上。
 * <p>
 * 维度要一起发：选区是绑在某个维度上的，玩家换维度之后旧的框不该跟着飘过来。
 * 客户端拿它和自己的维度比一下就知道该不该画。
 */
public record ZoneStatusPayload(boolean hasCornerA, BlockPos cornerA,
                                boolean hasCornerB, BlockPos cornerB,
                                boolean running, int done, int total,
                                ResourceLocation dimension) {

    /**
     * 线格式：<b>定长</b>，每个字段无条件写、无条件读，没有"下一个字段写不写"的分支。
     * <p>
     * <b>为什么坚持定长。</b> 0.10.0 那版的格式是"先写一个 hasCornerA，为真才写坐标"，
     * 解码端为了紧凑写成
     * {@code new ZoneStatusPayload(buf.readBoolean(), buf.readBoolean() ? buf.readBlockPos() : ...)}。
     * Java 的构造实参从左到右求值，于是第一个 {@code readBoolean()} 读到的值<b>根本没有任何地方用它</b>，
     * 第二个参数里的 {@code readBoolean()} 读到的已经是流里的下一个布尔 —— 从这一步起整条流错位，
     * 表现是进游戏后第一次设角点就被踢出存档（{@code Not enough bytes in buffer}）。
     * 编码端没错，只有解码端串了，而这类错误编译期看不出来、只有真跑起来才炸。
     * <p>
     * 定长之后，"读一个字段"和"写一个字段"是一一对应的，没有岔路，
     * 也就不存在"两边对不齐"这种可能。代价是最多十几字节的空位。
     */
    public static void encode(ZoneStatusPayload payload, FriendlyByteBuf buf) {
        buf.writeBoolean(payload.hasCornerA);
        // 没有角点时也照样写一个坐标：用 ZERO 占位，由 hasCornerA 决定它算不算数。
        buf.writeBlockPos(payload.cornerA);
        buf.writeBoolean(payload.hasCornerB);
        buf.writeBlockPos(payload.cornerB);
        buf.writeBoolean(payload.running);
        buf.writeVarInt(payload.done);
        buf.writeVarInt(payload.total);
        // 维度用空串表示"没有"，而不是再加一个布尔来门控 —— 门控就是上面那个坑。
        buf.writeUtf(payload.dimension == null ? "" : payload.dimension.toString());
    }

    public static ZoneStatusPayload decode(FriendlyByteBuf buf) {
        boolean hasCornerA = buf.readBoolean();
        BlockPos cornerA = buf.readBlockPos();
        boolean hasCornerB = buf.readBoolean();
        BlockPos cornerB = buf.readBlockPos();
        boolean running = buf.readBoolean();
        int done = buf.readVarInt();
        int total = buf.readVarInt();
        String dimensionId = buf.readUtf();
        // 用 tryParse 而不是 parse：这是网络解码路径，一个畸形字符串
        // 不该把整条连接打断。解析不出来就当"没有维度"，客户端那边本来也要判空。
        return new ZoneStatusPayload(hasCornerA, cornerA, hasCornerB, cornerB,
                running, done, total,
                dimensionId.isEmpty() ? null : ResourceLocation.tryParse(dimensionId));
    }

    public static void handle(ZoneStatusPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}