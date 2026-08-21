package dev.modmind.my_mod.network;

import dev.modmind.my_mod.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** 管理员 HUD 的夜视开关。药水效果只能由服务端添加或移除。 */
public final class ToggleHudNightVisionMessage {

    private static final String NIGHT_VISION_KEY = "KunJinKaoHudNightVision";
    private static final int DURATION_TICKS = 20 * 60 * 30;

    public static void encode(ToggleHudNightVisionMessage message, FriendlyByteBuf buffer) {
        // 无载荷消息；保留编码方法以符合 SimpleChannel 注册签名。
    }

    public static ToggleHudNightVisionMessage decode(FriendlyByteBuf buffer) {
        return new ToggleHudNightVisionMessage();
    }

    public static void handle(ToggleHudNightVisionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            boolean authorized = AdminToolConfig.isAuthorized(player.getUUID());
            boolean currentlyEnabled = player.getPersistentData().getBoolean(NIGHT_VISION_KEY)
                    && player.hasEffect(MobEffects.NIGHT_VISION);
            boolean enabled = authorized && !currentlyEnabled;
            if (enabled) {
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, DURATION_TICKS,
                        0, false, false, true));
            } else if (currentlyEnabled) {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
            player.getPersistentData().putBoolean(NIGHT_VISION_KEY, enabled);
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new HudNightVisionStateMessage(enabled, authorized));
        });
        context.setPacketHandled(true);
    }
}
