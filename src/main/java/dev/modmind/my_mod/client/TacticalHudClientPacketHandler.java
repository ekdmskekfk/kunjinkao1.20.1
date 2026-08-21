package dev.modmind.my_mod.client;

import dev.modmind.my_mod.client.function.HudFunctionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** S2C 战术 HUD 状态处理器。该类只会在客户端收到回包时调用。 */
public final class TacticalHudClientPacketHandler {

    private TacticalHudClientPacketHandler() {
    }

    public static void apply(boolean enabled, boolean authorized) {
        if (!authorized) {
            ClientHudState.reset();
            HudFunctionManager.deactivateAll();
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.my_mod.hud_not_authorized"), true);
            }
            return;
        }

        if (enabled) {
            ClientHudState.enable();
            HudFunctionManager.activateDefault();
        } else {
            ClientHudState.disable();
            HudFunctionManager.deactivateAll();
        }
    }
}
