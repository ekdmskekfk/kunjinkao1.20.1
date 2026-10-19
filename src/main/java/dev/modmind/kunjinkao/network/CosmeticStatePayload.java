package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 管理员装饰状态（S2C）："谁身上戴着哪几项装饰、正在打谁、打到第几 tick"。
 * <p>
 * <b>为什么装饰要同步状态、而不是直接生成服务端实体：</b>
 * 服务端实体的位置必然绕一圈网络（服务端拿到玩家坐标 → 算装饰位置 → 再发回客户端），
 * 表现就是玩家一动、装饰拖在后面。这个延迟在服务端消不掉，只能把画面搬到客户端：
 * 服务端只保留"状态"，每个客户端用<b>自己那份插值过的玩家位置</b>把装饰画出来 —— 零延迟。
 * <p>
 * 代价是没装模组的客户端看不见。但本模组注册了物品，专用服务器本来就要求双端都装，
 * 所以这个代价是空的。
 * <p>
 * <b>整表推送</b>而不是增量：一条记录三十来字节、玩家数量又少，每次变化直接推全表，
 * 可以省掉"增量包字段错位 / 漏包"这一整类问题；客户端拿到就是权威快照
 * （没出现在表里的人一律视为没有装饰）。反击期间每 {@link #STRIKE_SYNC_INTERVAL} tick 补推一次，
 * 中间的进度由客户端自己每 tick 往前推。
 */
public record CosmeticStatePayload(List<Entry> entries) {

    /** 201 头顶乱码称号牌。 */
    public static final int FLAG_TITLE = 1;
    /** 202 身后三把剑（含反击）。 */
    public static final int FLAG_ORBIT = 2;
    /** 203 光环与拖尾。 */
    public static final int FLAG_AURA = 4;

    /*
     * 反击的时间参数放在这里、而不是服务端客户端各写一份：服务端按它们扣血、客户端按它们做刺击动画，
     * 两边必须完全一致 —— 差一 tick 就是"剑刺下去的时候人没掉血"。
     */
    /** 一次反击持续多少 tick（100 = 5 秒）。 */
    public static final int STRIKE_DURATION = 100;
    /** 剑飞过去要多少 tick，飞到了才开始扣血；客户端用同一个值决定什么时候开始刺。 */
    public static final int STRIKE_FLIGHT_TICKS = 6;
    /** 两下之间的间隔（tick）。取 10 是因为玩家的无敌帧是 20 tick、hurt 的判定是 invulnerableTime > 10。 */
    public static final int STRIKE_DAMAGE_INTERVAL = 10;
    /** 反击期间多久补推一次状态。 */
    public static final int STRIKE_SYNC_INTERVAL = 10;

    /** 一条玩家装饰记录。{@code strikeTarget} 为 null 表示没有在反击。 */
    public record Entry(UUID player, int flags, @Nullable UUID strikeTarget, int strikeTicks) {

        public static Entry idle(UUID player, int flags) {
            return new Entry(player, flags, null, 0);
        }

        public static Entry striking(UUID player, int flags, UUID target, int strikeTicks) {
            return new Entry(player, flags, target, strikeTicks);
        }
    }

    public static void encode(CosmeticStatePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buf.writeUUID(entry.player());
            buf.writeVarInt(entry.flags());
            boolean striking = entry.strikeTarget() != null;
            buf.writeBoolean(striking);
            if (striking) {
                buf.writeUUID(entry.strikeTarget());
            }
            buf.writeVarInt(entry.strikeTicks());
        }
    }

    public static CosmeticStatePayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID player = buf.readUUID();
            int flags = buf.readVarInt();
            UUID target = buf.readBoolean() ? buf.readUUID() : null;
            entries.add(new Entry(player, flags, target, buf.readVarInt()));
        }
        return new CosmeticStatePayload(List.copyOf(entries));
    }

    public static void handle(CosmeticStatePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 与其它 S2C 包同一入口：先过 common 侧网关，保证服务端不会加载 client 包下的类。
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}