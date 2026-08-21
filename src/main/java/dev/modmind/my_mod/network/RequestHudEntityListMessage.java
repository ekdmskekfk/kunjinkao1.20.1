package dev.modmind.my_mod.network;

import dev.modmind.my_mod.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 请求当前存档所有已加载维度中的实体摘要列表。 */
public final class RequestHudEntityListMessage {

    public static void encode(RequestHudEntityListMessage message, FriendlyByteBuf buffer) {
        // 无载荷请求。
    }

    public static RequestHudEntityListMessage decode(FriendlyByteBuf buffer) {
        return new RequestHudEntityListMessage();
    }

    public static void handle(RequestHudEntityListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
            List<HudEntityData> entities = new ArrayList<>();
            if (authorized) {
                for (ServerLevel level : player.serverLevel().getServer().getAllLevels()) {
                    for (Entity entity : level.getAllEntities()) {
                        if (!entity.isRemoved()) {
                            entities.add(new HudEntityData(entity.getUUID(), entity.getId(),
                                    level.dimension().location().toString(), entity.getType().getDescriptionId(),
                                    entity.getDisplayName().getString(), entity.getX(), entity.getY(), entity.getZ()));
                        }
                    }
                }
            }
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new HudEntityListMessage(authorized, entities));
        });
        context.setPacketHandled(true);
    }
}
