package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 加速方块：倍率与范围（C2S）。
 * <p>
 * 线格式：{@code writeBlockPos(pos) | writeInt(multiplier) | writeInt(radius)}。
 */
public record AcceleratorConfigPayload(BlockPos pos, int multiplier, int radius) {

    public static void encode(AcceleratorConfigPayload payload, FriendlyByteBuf buf) {
        buf.writeBlockPos(payload.pos);
        buf.writeInt(payload.multiplier);
        buf.writeInt(payload.radius);
    }

    public static AcceleratorConfigPayload decode(FriendlyByteBuf buf) {
        return new AcceleratorConfigPayload(buf.readBlockPos(), buf.readInt(), buf.readInt());
    }

    public static void handle(AcceleratorConfigPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null) {
                return;
            }
            if (!AcceleratorBlockEntity.mayEdit(player, payload.pos)) {
                return;
            }
            Level level = player.level();
            if (level == null || !level.hasChunkAt(payload.pos)) {
                return;
            }
            BlockEntity be = level.getBlockEntity(payload.pos);
            if (!(be instanceof AcceleratorBlockEntity accelerator)) {
                return;
            }
            accelerator.setMultiplier(payload.multiplier);
            accelerator.setRadius(payload.radius);
            accelerator.setChanged();
            BlockState state = level.getBlockState(payload.pos);
            // sendBlockUpdated 已把方块实体数据同步给追踪该区块的玩家，无需再手动重发一次。
            level.sendBlockUpdated(payload.pos, state, state, 2);
            int size = accelerator.getRadius() * 2 + 1;
            player.displayClientMessage(Component.literal(
                    "§b[加速方块] §f倍率 " + accelerator.getMultiplier() + "x · 范围 " + size + "x" + size + "x" + size), true);
        });
        context.setPacketHandled(true);
    }
}