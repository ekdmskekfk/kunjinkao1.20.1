package dev.modmind.my_mod.network;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ToggleDisguiseMessage {
    private final InteractionHand hand;

    public ToggleDisguiseMessage(InteractionHand hand) {
        this.hand = hand;
    }

    public static ToggleDisguiseMessage decode(FriendlyByteBuf buf) {
        return new ToggleDisguiseMessage(buf.readEnum(InteractionHand.class));
    }

    public static void encode(ToggleDisguiseMessage message, FriendlyByteBuf buf) {
        buf.writeEnum(message.hand);
    }

    public static void handle(ToggleDisguiseMessage message, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                ItemStack stack = player.getItemInHand(message.hand);
                if (stack.getItem() instanceof KunJinKaoSwordItem) {
                    KunJinKaoSwordItem.toggleDisguise(stack);
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
