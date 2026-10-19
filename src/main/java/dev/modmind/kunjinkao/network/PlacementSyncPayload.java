package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import dev.modmind.kunjinkao.world.SwordPlacementRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C：把整张「放置记录」表推给客户端，供菜单第二页显示。
 * <p>
 * 整表推送而不是增量：这张表天然很小（玩家手动立起来的场地，几十条量级），
 * 增量同步要额外维护版本号与重连补发，不划算。
 * <p>
 * 线格式（与 1.21.1 逐字节一致）：{@code writeVarInt(count)}，随后每条
 * {@code writeVarInt(kind) | writeResourceLocation(dimension) | writeBlockPos(pos)
 * | writeVarInt(radius) | writeVarInt(multiplier) | writeUUID(owner)}。
 * <p>
 * 1.20.1 移植：{@code StreamCodec} 拆成 encode/decode，{@code IPayloadContext} 换成
 * {@code Supplier<NetworkEvent.Context>}；{@code PacketDistributor.sendToPlayer/sendToAllPlayers}
 * 换成 {@link NetworkHandler} 的发送辅助。客户端的界面刷新走 {@link ClientHooks} 网关 ——
 * 这个类的 common 侧不能引用 {@code net.minecraft.client}，否则专用服务器一加载就炸。
 */
public record PlacementSyncPayload(List<Entry> entries) {

    /** 客户端展示用的一条记录。owner 也带上，界面可以标出"这是你自己的场"。 */
    public record Entry(int kind, ResourceLocation dimension, BlockPos pos, int radius, int multiplier, UUID owner) {
    }

    /** 客户端收到的最后一份表。菜单第二页直接读它，不再自己发请求。 */
    private static volatile List<Entry> clientCache = List.of();

    public static List<Entry> clientEntries() {
        return clientCache;
    }

    /** 客户端侧：收到新表时先留一份旧的，交给世界效果层比对出"这次新立起来的那几个场"。 */
    public static List<Entry> applyClientCache(List<Entry> incoming) {
        List<Entry> before = clientCache;
        clientCache = List.copyOf(incoming);
        return before;
    }

    public static void encode(PlacementSyncPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buf.writeVarInt(entry.kind());
            buf.writeResourceLocation(entry.dimension());
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.radius());
            buf.writeVarInt(entry.multiplier());
            buf.writeUUID(entry.owner());
        }
    }

    public static PlacementSyncPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Entry(buf.readVarInt(), buf.readResourceLocation(),
                    buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readUUID()));
        }
        return new PlacementSyncPayload(list);
    }

    /** 服务端：把当前表推给一个人。 */
    public static void sendTo(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        NetworkHandler.sendToPlayer(serverPlayer, of(server));
    }

    /** 服务端：把当前表推给所有人（有人立起/收回场地时调）。 */
    public static void broadcast(MinecraftServer server) {
        NetworkHandler.sendToAll(of(server));
    }

    private static PlacementSyncPayload of(MinecraftServer server) {
        List<Entry> list = new ArrayList<>();
        for (SwordPlacementRegistry.Entry entry : SwordPlacementRegistry.get(server).entries()) {
            list.add(new Entry(entry.kind(), entry.dimension().location(), entry.pos(),
                    entry.radius(), entry.multiplier(), entry.owner()));
        }
        return new PlacementSyncPayload(list);
    }

    public static void handle(PlacementSyncPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}