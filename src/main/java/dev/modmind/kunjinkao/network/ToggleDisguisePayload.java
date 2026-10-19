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
 * 切伪装（C2S）。线格式：{@code writeEnum(hand)}。
 */
public record ToggleDisguisePayload(InteractionHand hand) {

    public static void encode(ToggleDisguisePayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.hand);
    }

    public static ToggleDisguisePayload decode(FriendlyByteBuf buf) {
        return new ToggleDisguisePayload(NetworkHandler.readEnumSafe(buf, InteractionHand.class));
    }

    public static void handle(ToggleDisguisePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null) {
                return;
            }
            // 与 withSword 一致的授权闸门：改剑的设置（覆写开关 / 主题 / 伪装）只对通过密码验证的玩家开放。
            if (!AdminToolConfig.isAuthorized(player.getUUID())) {
                player.displayClientMessage(Component.translatable("message.kunjinkao.admin_required"), true);
                return;
            }
            ItemStack stack = player.getItemInHand(payload.hand);
            if (stack.getItem() instanceof KunJinKaoSwordItem) {
                KunJinKaoSwordItem.toggleDisguise(stack);
            }
        });
        context.setPacketHandled(true);
    }
}