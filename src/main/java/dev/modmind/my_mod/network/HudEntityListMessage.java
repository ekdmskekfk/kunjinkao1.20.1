package dev.modmind.my_mod.network;

import dev.modmind.my_mod.client.TacticalHudClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 服务端返回实体管理界面所需的全部已加载维度实体列表。 */
public final class HudEntityListMessage {

    private final boolean authorized;
    private final List<HudEntityData> entities;

    public HudEntityListMessage(boolean authorized, List<HudEntityData> entities) {
        this.authorized = authorized;
        this.entities = List.copyOf(entities);
    }

    public static void encode(HudEntityListMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.authorized);
        buffer.writeVarInt(message.entities.size());
        for (HudEntityData entity : message.entities) {
            buffer.writeUUID(entity.uuid());
            buffer.writeVarInt(entity.entityId());
            buffer.writeUtf(entity.dimensionId(), 128);
            buffer.writeUtf(entity.typeTranslationKey(), 128);
            buffer.writeUtf(entity.displayName(), 128);
            buffer.writeDouble(entity.x());
            buffer.writeDouble(entity.y());
            buffer.writeDouble(entity.z());
        }
    }

    public static HudEntityListMessage decode(FriendlyByteBuf buffer) {
        boolean authorized = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > 16384) {
            throw new IllegalArgumentException("Invalid HUD entity list size: " + count);
        }
        List<HudEntityData> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = buffer.readUUID();
            int entityId = buffer.readVarInt();
            String dimensionId = buffer.readUtf(128);
            String type = buffer.readUtf(128);
            String name = buffer.readUtf(128);
            entities.add(new HudEntityData(uuid, entityId, dimensionId, type, name,
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        }
        return new HudEntityListMessage(authorized, entities);
    }

    public static void handle(HudEntityListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> TacticalHudClientPacketHandler.openEntityList(message.entities, message.authorized));
        context.setPacketHandled(true);
    }
}
