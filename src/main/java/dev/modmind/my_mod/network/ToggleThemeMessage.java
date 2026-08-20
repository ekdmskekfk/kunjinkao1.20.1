package dev.modmind.my_mod.network;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import dev.modmind.my_mod.KunJinKaoTheme;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 异象主题切换消息（客户端→服务端）。
 * 客户端按 P 键后携带新主题号发送，服务端将手中覆写剑写入该主题号。
 */
public class ToggleThemeMessage {
    private final InteractionHand hand;
    private final int theme;

    public ToggleThemeMessage(InteractionHand hand, int theme) {
        this.hand = hand;
        this.theme = theme;
    }

    public static void encode(ToggleThemeMessage message, FriendlyByteBuf buf) {
        buf.writeEnum(message.hand);
        buf.writeInt(message.theme);
    }

    public static ToggleThemeMessage decode(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        int theme = buf.readInt();
        return new ToggleThemeMessage(hand, theme);
    }

    public static void handle(ToggleThemeMessage message, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = player.getItemInHand(message.hand);
            if (!(stack.getItem() instanceof KunJinKaoSwordItem)) {
                return;
            }
            KunJinKaoSwordItem.setTheme(stack, message.theme);
            player.displayClientMessage(
                    Component.literal("§d异象主题：" + KunJinKaoTheme.displayName(KunJinKaoSwordItem.getTheme(stack))),
                    true
            );
        });
        ctx.setPacketHandled(true);
    }
}
