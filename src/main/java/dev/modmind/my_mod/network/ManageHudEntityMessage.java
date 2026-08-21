package dev.modmind.my_mod.network;

import dev.modmind.my_mod.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/** 管理员请求结束实体或传送至实体位置。实体 UUID 在服务端重新查找，绝不信任客户端坐标。 */
public final class ManageHudEntityMessage {

    private final HudEntityAction action;
    private final UUID entityUuid;

    public ManageHudEntityMessage(HudEntityAction action, UUID entityUuid) {
        this.action = action;
        this.entityUuid = entityUuid;
    }

    public static void encode(ManageHudEntityMessage message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
        buffer.writeUUID(message.entityUuid);
    }

    public static ManageHudEntityMessage decode(FriendlyByteBuf buffer) {
        return new ManageHudEntityMessage(buffer.readEnum(HudEntityAction.class), buffer.readUUID());
    }

    public static void handle(ManageHudEntityMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
            boolean success = false;
            if (authorized) {
                Entity entity = null;
                ServerLevel entityLevel = null;
                for (ServerLevel level : player.serverLevel().getServer().getAllLevels()) {
                    Entity candidate = level.getEntity(message.entityUuid);
                    if (candidate != null && !candidate.isRemoved()) {
                        entity = candidate;
                        entityLevel = level;
                        break;
                    }
                }
                if (entity != null && entityLevel != null) {
                    switch (message.action) {
                        case KILL -> {
                            entity.kill();
                            success = true;
                        }
                        case TELEPORT -> {
                            player.teleportTo(entityLevel, entity.getX(), entity.getY(), entity.getZ(),
                                    player.getYRot(), player.getXRot());
                            success = true;
                        }
                    }
                }
            }
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new HudEntityActionResultMessage(message.action, message.entityUuid, success, authorized));
        });
        context.setPacketHandled(true);
    }
}
