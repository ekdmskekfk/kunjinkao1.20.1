package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.KunJinKaoTheme;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 设异象主题（C2S）。线格式：{@code writeEnum(hand) | writeInt(theme)}。
 */
public record ToggleThemePayload(InteractionHand hand, int theme) {

    public static void encode(ToggleThemePayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.hand);
        buf.writeInt(payload.theme);
    }

    public static ToggleThemePayload decode(FriendlyByteBuf buf) {
        return new ToggleThemePayload(NetworkHandler.readEnumSafe(buf, InteractionHand.class), buf.readInt());
    }

    public static void handle(ToggleThemePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
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
            if (KunJinKaoSwordItem.isSwordLike(stack)) {
                KunJinKaoSwordItem.setTheme(stack, payload.theme);
                player.displayClientMessage(
                        Component.literal("§d异象主题：" + KunJinKaoTheme.displayName(KunJinKaoSwordItem.getTheme(stack))),
                        true);
            }
        });
        context.setPacketHandled(true);
    }
}