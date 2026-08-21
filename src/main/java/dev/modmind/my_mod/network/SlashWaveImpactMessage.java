package dev.modmind.my_mod.network;

import dev.modmind.my_mod.client.SlashWaveClientEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-to-client hit feedback for a player struck by a slash wave. */
public record SlashWaveImpactMessage(BlockPos position, int duration) {
    public static void encode(SlashWaveImpactMessage message, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(message.position);
        buffer.writeVarInt(message.duration);
    }

    public static SlashWaveImpactMessage decode(FriendlyByteBuf buffer) {
        return new SlashWaveImpactMessage(buffer.readBlockPos(), buffer.readVarInt());
    }

    public static void handle(SlashWaveImpactMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SlashWaveClientEffects.start(message.position, message.duration)));
        context.setPacketHandled(true);
    }
}
