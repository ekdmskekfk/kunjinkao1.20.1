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
 * 加速方块：显示/隐藏范围框（C2S）。
 * <p>
 * 线格式：{@code writeBlockPos(pos) | writeBoolean(showRange)}。
 */
public record AcceleratorShowRangePayload(BlockPos pos, boolean showRange) {

    public static void encode(AcceleratorShowRangePayload payload, FriendlyByteBuf buf) {
        buf.writeBlockPos(payload.pos);
        buf.writeBoolean(payload.showRange);
    }

    public static AcceleratorShowRangePayload decode(FriendlyByteBuf buf) {
        return new AcceleratorShowRangePayload(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(AcceleratorShowRangePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
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
            accelerator.setShowRange(payload.showRange);
            accelerator.setChanged();
            BlockState state = level.getBlockState(payload.pos);
            // 只同步给追踪该区块的玩家；原来遍历全服（含其它维度）发方块实体包是过度广播。
            level.sendBlockUpdated(payload.pos, state, state, 2);
            player.displayClientMessage(Component.literal(payload.showRange
                    ? "§b[加速方块] §f已显示加速范围"
                    : "§b[加速方块] §f已隐藏加速范围"), true);
        });
        context.setPacketHandled(true);
    }
}