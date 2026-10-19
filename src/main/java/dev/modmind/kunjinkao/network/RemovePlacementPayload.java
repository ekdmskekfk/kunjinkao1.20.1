package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.world.SwordAreaFields;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.function.Supplier;

/**
 * C2S：从放置记录里收回一条。
 * <p>
 * 线格式：{@code writeVarInt(kind) | writeResourceLocation(dimension) | writeBlockPos(pos)}。
 * <p>
 * 授权照旧落在服务端：这里不信任客户端传来的任何东西，
 * 收回是否真的发生由 {@link SwordAreaFields#revoke} 说了算。
 */
public record RemovePlacementPayload(int kind, ResourceLocation dimension, BlockPos pos) {

    public static void encode(RemovePlacementPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.kind);
        buf.writeResourceLocation(payload.dimension);
        buf.writeBlockPos(payload.pos);
    }

    public static RemovePlacementPayload decode(FriendlyByteBuf buf) {
        return new RemovePlacementPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(RemovePlacementPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            ResourceLocation dimensionId = payload.dimension();
            server.getAllLevels().forEach(level -> {
                if (level.dimension().location().equals(dimensionId)) {
                    SwordAreaFields.revoke(server, payload.kind(), level.dimension(), payload.pos());
                }
            });
            // 收回之后立刻把最新表推回客户端，避免列表还显示已经收掉的条目。
            if (player != null) {
                PlacementSyncPayload.sendTo(player);
            }
        });
        context.setPacketHandled(true);
    }
}