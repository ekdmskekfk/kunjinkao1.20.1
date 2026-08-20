package dev.modmind.my_mod.network;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 覆写流程开关切换消息（客户端→服务端）。
 * 服务端收到后切换持握物品的 OverwriteEnabled 状态，
 * 使服务端实际攻击逻辑与客户端显示状态保持一致。
 */
public class ToggleOverwriteMessage {
    private final InteractionHand hand;

    public ToggleOverwriteMessage(InteractionHand hand) {
        this.hand = hand;
    }

    public static ToggleOverwriteMessage decode(FriendlyByteBuf buf) {
        return new ToggleOverwriteMessage(buf.readEnum(InteractionHand.class));
    }

    public static void encode(ToggleOverwriteMessage message, FriendlyByteBuf buf) {
        buf.writeEnum(message.hand);
    }

    public static void handle(ToggleOverwriteMessage message, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                ItemStack stack = player.getItemInHand(message.hand);
                if (stack.getItem() instanceof KunJinKaoSwordItem) {
                    KunJinKaoSwordItem.toggleOverwrite(stack);
                    LOGGER.info("[TOGGLE-OVERWRITE] hand={} -> overwriteEnabled={}",
                            message.hand, KunJinKaoSwordItem.isOverwriteEnabled(stack));
                } else {
                    LOGGER.warn("[TOGGLE-OVERWRITE] hand={} but held item is not the sword", message.hand);
                }
            }
        });
        ctx.setPacketHandled(true);
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");
}
