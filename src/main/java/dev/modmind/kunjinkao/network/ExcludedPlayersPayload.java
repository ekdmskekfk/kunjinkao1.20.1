package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 排除玩家名单（S2C）。
 * <p>
 * 线格式：{@code writeBoolean(authorized) | writeVarInt(count) | count × (writeUUID | writeUtf(name))}。
 * 编解码两侧共用 {@link #MAX_ENTRIES} / {@link #MAX_STRING_LENGTH}，避免上限不对称。
 */
public record ExcludedPlayersPayload(boolean authorized, List<ExcludedPlayerData> players) {

    /** 编解码共用的条数上限：编码端先截断，解码端复用同一常量校验，避免两侧上限不对称。 */
    public static final int MAX_ENTRIES = 4096;

    /** 玩家名的字符数上限（与 buf.writeUtf / readUtf 的限制保持一致）。 */
    private static final int MAX_STRING_LENGTH = 64;

    public static void encode(ExcludedPlayersPayload payload, FriendlyByteBuf buf) {
        buf.writeBoolean(payload.authorized);
        // 编码前先截到 MAX_ENTRIES：名单过长时解码端会抛异常，客户端连排除列表都刷新不出来。
        int count = Math.min(payload.players.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ExcludedPlayerData entry = payload.players.get(i);
            buf.writeUUID(entry.uuid());
            // 名字超过上限时 writeUtf 会抛 EncoderException，先截断再写。
            buf.writeUtf(truncate(entry.name(), MAX_STRING_LENGTH), MAX_STRING_LENGTH);
        }
    }

    public static ExcludedPlayersPayload decode(FriendlyByteBuf buf) {
        boolean authorized = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid excluded player list size: " + count);
        }
        List<ExcludedPlayerData> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = buf.readUUID();
            list.add(new ExcludedPlayerData(uuid, buf.readUtf(MAX_STRING_LENGTH)));
        }
        return new ExcludedPlayersPayload(authorized, list);
    }

    /** 超长字符串先截断到上限：writeUtf 超限会抛 EncoderException，整个包都发不出去。 */
    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    public static void handle(ExcludedPlayersPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}