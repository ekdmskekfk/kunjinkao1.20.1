package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 切覆写开关（C2S）。线格式：{@code writeEnum(hand)}。
 */
public record ToggleOverwritePayload(InteractionHand hand) {

    public static void encode(ToggleOverwritePayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.hand);
    }

    public static ToggleOverwritePayload decode(FriendlyByteBuf buf) {
        return new ToggleOverwritePayload(NetworkHandler.readEnumSafe(buf, InteractionHand.class));
    }

    public static void handle(ToggleOverwritePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null) {
                return;
            }
            if (!AdminToolConfig.isAuthorized(player.getUUID())) {
                player.displayClientMessage(Component.translatable("message.kunjinkao.admin_required"), true);
                return;
            }
            ItemStack stack = player.getItemInHand(payload.hand);
            if (stack.getItem() instanceof KunJinKaoSwordItem) {
                KunJinKaoSwordItem.toggleOverwrite(stack);
            }
        });
        context.setPacketHandled(true);
    }
}