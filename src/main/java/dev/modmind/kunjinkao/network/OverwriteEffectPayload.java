package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 覆写（"改写现实"）特效的进度与结果（S2C）。
 * <p>
 * 线格式：{@code writeInt(entityId) | writeInt(remainingTicks) | writeInt(phase) | writeInt(phaseDetail)
 * | writeBoolean(hasPosition)}，hasPosition 为真时再跟三个 writeInt 坐标。
 * <p>
 * 这里的分支是安全的：hasPosition 只由一个布尔门控，且解码端把它存进局部变量后再用，
 * 不像 0.10.0 的 ZoneStatusPayload 那样在构造实参里连读两次导致流错位。
 */
public record OverwriteEffectPayload(int entityId, int remainingTicks, int phase, int phaseDetail,
                                     boolean hasPosition, int posX, int posY, int posZ) {

    public static final int PHASE_START = 0;
    public static final int PHASE_PROGRESS = 1;
    public static final int PHASE_END = 2;
    public static final int PHASE_CANCEL = 3;
    public static final int PHASE_DECISION = 4;

    public static void encode(OverwriteEffectPayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.entityId);
        buf.writeInt(payload.remainingTicks);
        buf.writeInt(payload.phase);
        buf.writeInt(payload.phaseDetail);
        buf.writeBoolean(payload.hasPosition);
        if (payload.hasPosition) {
            buf.writeInt(payload.posX);
            buf.writeInt(payload.posY);
            buf.writeInt(payload.posZ);
        }
    }

    public static OverwriteEffectPayload decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        int remainingTicks = buf.readInt();
        int phase = buf.readInt();
        int phaseDetail = buf.readInt();
        boolean hasPosition = buf.readBoolean();
        int x = 0;
        int y = 0;
        int z = 0;
        if (hasPosition) {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
        }
        return new OverwriteEffectPayload(entityId, remainingTicks, phase, phaseDetail, hasPosition, x, y, z);
    }

    public BlockPos position() {
        return hasPosition ? new BlockPos(posX, posY, posZ) : null;
    }

    public static OverwriteEffectPayload start(int entityId, int ticks, int theme) {
        return new OverwriteEffectPayload(entityId, ticks, PHASE_START, theme, false, 0, 0, 0);
    }

    public static OverwriteEffectPayload progress(int entityId, int ticks) {
        return new OverwriteEffectPayload(entityId, ticks, PHASE_PROGRESS, 0, false, 0, 0, 0);
    }

    public static OverwriteEffectPayload end(int entityId, BlockPos pos) {
        return new OverwriteEffectPayload(entityId, 0, PHASE_END, 0, pos != null,
                pos != null ? pos.getX() : 0, pos != null ? pos.getY() : 0, pos != null ? pos.getZ() : 0);
    }

    public static OverwriteEffectPayload cancel(int entityId) {
        return new OverwriteEffectPayload(entityId, 0, PHASE_CANCEL, 0, false, 0, 0, 0);
    }

    public static OverwriteEffectPayload decision(int entityId, int ticks) {
        return new OverwriteEffectPayload(entityId, ticks, PHASE_DECISION, 0, false, 0, 0, 0);
    }

    public static void handle(OverwriteEffectPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}