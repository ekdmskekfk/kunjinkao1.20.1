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
 * 加速方块配置消息（客户端→服务端）。
 * 玩家在 GUI 中调节倍率 / 范围后发送，服务端写入对应方块实体。
 */
public class AcceleratorConfigMessage {

    private final BlockPos pos;
    private final int multiplier;
    private final int radius;

    public AcceleratorConfigMessage(BlockPos pos, int multiplier, int radius) {
        this.pos = pos;
        this.multiplier = multiplier;
        this.radius = radius;
    }

    public static void encode(AcceleratorConfigMessage message, FriendlyByteBuf buf) {
        buf.writeBlockPos(message.pos);
        buf.writeInt(message.multiplier);
        buf.writeInt(message.radius);
    }

    public static AcceleratorConfigMessage decode(FriendlyByteBuf buf) {
        return new AcceleratorConfigMessage(buf.readBlockPos(), buf.readInt(), buf.readInt());
    }

    public static void handle(AcceleratorConfigMessage message, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            accelerator.setMultiplier(message.multiplier);
            accelerator.setRadius(message.radius);
            accelerator.setChanged();
            BlockState state = level.getBlockState(message.pos);
            level.sendBlockUpdated(message.pos, state, state, 2);

            // 把最新参数主动同步给客户端，否则客户端方块实体仍是旧值（重开 GUI 会显示被重置）
            player.connection.send(ClientboundBlockEntityDataPacket.create(accelerator));

            int size = accelerator.getRadius() * 2 + 1;
            player.displayClientMessage(Component.literal(
                    "§b[加速方块] §f倍率 " + accelerator.getMultiplier() + "x · 范围 "
                            + size + "x" + size + "x" + size), true);
        });
        ctx.setPacketHandled(true);
    }
}