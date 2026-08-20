package dev.modmind.my_mod.network;

import dev.modmind.my_mod.block.entity.AcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 加速方块"显示加速范围"开关消息（客户端→服务端）。
 */
public class AcceleratorShowRangeMessage {

    private final BlockPos pos;
    private final boolean showRange;

    public AcceleratorShowRangeMessage(BlockPos pos, boolean showRange) {
        this.pos = pos;
        this.showRange = showRange;
    }

    public static void encode(AcceleratorShowRangeMessage message, FriendlyByteBuf buf) {
        buf.writeBlockPos(message.pos);
        buf.writeBoolean(message.showRange);
    }

    public static AcceleratorShowRangeMessage decode(FriendlyByteBuf buf) {
        return new AcceleratorShowRangeMessage(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(AcceleratorShowRangeMessage message, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Level level = player.level();
            if (level == null || !level.hasChunkAt(message.pos)) {
                return;
            }
            BlockEntity blockEntity = level.getBlockEntity(message.pos);
            if (!(blockEntity instanceof AcceleratorBlockEntity accelerator)) {
                return;
            }
            accelerator.setShowRange(message.showRange);
            accelerator.setChanged();
            BlockState state = level.getBlockState(message.pos);
            level.sendBlockUpdated(message.pos, state, state, 2);

            // 广播方块实体数据给所有玩家，保证多人联机时每个人都能看到/隐藏范围框
            if (level.getServer() != null) {
                for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
                    other.connection.send(ClientboundBlockEntityDataPacket.create(accelerator));
                }
            }

            player.displayClientMessage(Component.literal(message.showRange
                    ? "§b[加速方块] §f已显示加速范围"
                    : "§b[加速方块] §f已隐藏加速范围"), true);
        });
        ctx.setPacketHandled(true);
    }
}