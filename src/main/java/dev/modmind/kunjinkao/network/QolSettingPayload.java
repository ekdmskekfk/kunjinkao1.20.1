package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.event.KunJinKaoQolHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 功能面板的改动请求（C2S）。
 * <p>
 * 线格式：{@code writeVarInt(settingId) | writeVarInt(value)}。
 * 开关类的 value 统一是 0/1，数值类是真的数值 —— 与 {@code SwordSettingPayload} 同一套做法，
 * 解码路径只有一条。
 * <p>
 * <b>这里不做任何判定。</b> 客户端说自己是谁、值是多少都不可信，
 * 授权、范围、前置依赖全部在 {@link KunJinKaoQolHandler#handleSettingChange} 里重新算一遍。
 * 判别 id 的含义见 {@code qol/QolSetting}，只能往后追加，不能改动已有取值。
 */
public record QolSettingPayload(int settingId, int value) {

    public static void encode(QolSettingPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.settingId);
        buf.writeVarInt(payload.value);
    }

    public static QolSettingPayload decode(FriendlyByteBuf buf) {
        return new QolSettingPayload(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(QolSettingPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> KunJinKaoQolHandler.handleSettingChange(player, payload.settingId(), payload.value()));
        context.setPacketHandled(true);
    }
}