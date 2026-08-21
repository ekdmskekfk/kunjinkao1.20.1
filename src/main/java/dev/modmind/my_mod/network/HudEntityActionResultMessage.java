package dev.modmind.my_mod.network;

import dev.modmind.my_mod.client.TacticalHudClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 服务端返回实体结束或传送请求的实际执行结果。 */
public final class HudEntityActionResultMessage {

    private final HudEntityAction action;
    private final UUID entityUuid;
    private final boolean success;
    private final boolean authorized;

    public HudEntityActionResultMessage(HudEntityAction action, UUID entityUuid, boolean success, boolean authorized) {
        this.action = action;
        this.entityUuid = entityUuid;
        this.success = success;
        this.authorized = authorized;
    }

    public static void encode(HudEntityActionResultMessage message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
        buffer.writeUUID(message.entityUuid);
        buffer.writeBoolean(message.success);
        buffer.writeBoolean(message.authorized);
    }

    public static HudEntityActionResultMessage decode(FriendlyByteBuf buffer) {
        return new HudEntityActionResultMessage(buffer.readEnum(HudEntityAction.class), buffer.readUUID(),
                buffer.readBoolean(), buffer.readBoolean());
    }

    public static void handle(HudEntityActionResultMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> TacticalHudClientPacketHandler.applyEntityAction(
                message.action, message.entityUuid, message.success, message.authorized));
        context.setPacketHandled(true);
    }
}
