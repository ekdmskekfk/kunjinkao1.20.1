package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 加速方块的过滤配置（整份状态一次性同步）。
 * <p>
 * 为什么整份发而不是"改哪一格发哪一格"：过滤状态很小（9 个槽 + 2 个开关），
 * 整份发送天然幂等、不会出现"客户端与服务端某一格不一致"的中间态，也省掉一套增量协议。
 * <p>
 * 线格式：{@code writeBlockPos | bool whitelist | bool matchNbt | 9 × writeItem(ItemStack)}。
 * 槽位数固定为 {@link AcceleratorBlockEntity#FILTER_SLOTS}，所以不需要长度前缀。
 * <p>
 * <b>1.20.1 差异</b>：1.21 用的是 {@code ItemStack.OPTIONAL_STREAM_CODEC} +
 * {@code RegistryFriendlyByteBuf}（需要注册表上下文）；1.20.1 的物品编解码就是
 * {@code FriendlyByteBuf#writeItem / #readItem}，空槽写成"数量 0"，读回来就是
 * {@link ItemStack#EMPTY}，语义与 OPTIONAL 编解码一致。
 * <p>
 * 服务端不信任客户端内容：{@link AcceleratorBlockEntity#applyFilter} 只接受方块物品、数量归 1。
 */
public record AcceleratorFilterPayload(BlockPos pos, List<ItemStack> filter, boolean whitelist, boolean matchNbt) {

    public static void encode(AcceleratorFilterPayload payload, FriendlyByteBuf buf) {
        buf.writeBlockPos(payload.pos);
        buf.writeBoolean(payload.whitelist);
        buf.writeBoolean(payload.matchNbt);
        for (int slot = 0; slot < AcceleratorBlockEntity.FILTER_SLOTS; slot++) {
            ItemStack entry = slot < payload.filter.size() ? payload.filter.get(slot) : ItemStack.EMPTY;
            buf.writeItem(entry);
        }
    }

    public static AcceleratorFilterPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean whitelist = buf.readBoolean();
        boolean matchNbt = buf.readBoolean();
        List<ItemStack> filter = new ArrayList<>(AcceleratorBlockEntity.FILTER_SLOTS);
        for (int slot = 0; slot < AcceleratorBlockEntity.FILTER_SLOTS; slot++) {
            filter.add(buf.readItem());
        }
        return new AcceleratorFilterPayload(pos, filter, whitelist, matchNbt);
    }

    public static void handle(AcceleratorFilterPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null) {
                return;
            }
            // 与倍率/范围同一个闸门：授权 + 交互距离
            if (!AcceleratorBlockEntity.mayEdit(player, payload.pos)) {
                return;
            }
            Level level = player.level();
            if (level == null || !level.hasChunkAt(payload.pos)) {
                return;
            }
            if (!(level.getBlockEntity(payload.pos) instanceof AcceleratorBlockEntity accelerator)) {
                return;
            }
            if (!accelerator.applyFilter(payload.filter, payload.whitelist, payload.matchNbt)) {
                return;
            }
            accelerator.setChanged();
            BlockState state = level.getBlockState(payload.pos);
            // 复用方块更新把方块实体数据（含过滤列表）同步回客户端，GUI 据此刷新
            level.sendBlockUpdated(payload.pos, state, state, 2);
        });
        context.setPacketHandled(true);
    }
}