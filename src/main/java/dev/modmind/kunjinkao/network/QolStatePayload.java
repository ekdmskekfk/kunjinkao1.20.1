package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 功能面板一行的状态回执（S2C）。
 * <p>
 * 线格式：{@code writeVarInt(settingId) | writeBoolean(enabled) | writeVarInt(value)
 * | writeVarInt(min) | writeVarInt(max) | writeBoolean(serverScope) | writeBoolean(authorized)}。
 * <p>
 * 一条包只描述<b>一行</b>：打开面板时服务端按行连发若干条，等同于"全量同步"；
 * 之后每次改动只回一条。没有做"全量包 + 增量包"两种类型，是因为行数只有十几，
 * 连发的开销可以忽略，而少一种包就少一处能对不上的地方。
 * <p>
 * 特意把 {@code enabled} 与 {@code value} 分成两个字段：开关类两者等价（enabled == value != 0），
 * 但后面要加的"总闸 + 参数"型功能（选区挖掘、寻宝大师）里它们是两件事。
 * <p>
 * {@code min}/{@code max} 由服务端下发而不是客户端自己写死：上限里含服务器配置，
 * 客户端算不出来。{@code authorized} 为 false 时客户端把整行置灰，
 * 但仍然会显示当前值 —— 让玩家看得见"这项存在、但你没权限"。
 */
public record QolStatePayload(int settingId, boolean enabled, int value, int min, int max,
                              boolean serverScope, boolean authorized) {

    public static void encode(QolStatePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.settingId);
        buf.writeBoolean(payload.enabled);
        buf.writeVarInt(payload.value);
        buf.writeVarInt(payload.min);
        buf.writeVarInt(payload.max);
        buf.writeBoolean(payload.serverScope);
        buf.writeBoolean(payload.authorized);
    }

    public static QolStatePayload decode(FriendlyByteBuf buf) {
        return new QolStatePayload(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
    }

    /** 组装一条回执；范围与作用域都取自服务端自己的表，不接受调用方随手给。 */
    public static QolStatePayload of(QolSetting setting, boolean enabled, int value,
                                     int min, int max, boolean authorized) {
        return new QolStatePayload(setting.id(), enabled, value, min, max,
                setting.scope() == QolSetting.Scope.SERVER, authorized);
    }

    public static void handle(QolStatePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 与 HudStatePayload 同一个入口：先过 common 侧网关照发，
        // 保证服务端不会因为加载本类而碰到 net.minecraft.client。
        context.enqueueWork(() -> ClientHooks.handleClientPayload(payload));
        context.setPacketHandled(true);
    }
}