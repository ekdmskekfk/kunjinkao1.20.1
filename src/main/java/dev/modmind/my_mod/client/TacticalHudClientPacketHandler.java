package dev.modmind.my_mod.client;

import dev.modmind.my_mod.client.function.HudFunctionManager;
import dev.modmind.my_mod.client.hud.HudScreen;
import dev.modmind.my_mod.client.hud.HudEntityScreen;
import dev.modmind.my_mod.network.HudEntityAction;
import dev.modmind.my_mod.network.HudEntityData;

import java.util.List;
import java.util.UUID;
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
            closeHudScreen();
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.my_mod.hud_not_authorized"), true);
            }
            return;
        }

        if (enabled) {
            ClientHudState.enable();
            HudFunctionManager.activateDefault();
            Minecraft.getInstance().setScreen(new HudScreen());
        } else {
            ClientHudState.disable();
            HudFunctionManager.deactivateAll();
            closeHudScreen();
        }
    }

    public static void applyNightVision(boolean enabled, boolean authorized) {
        HudFunctionManager.setNightVisionEnabled(enabled && authorized);
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (!authorized) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("message.my_mod.hud_not_authorized"), true);
            return;
        }
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                enabled ? "message.my_mod.hud_night_vision_enabled" : "message.my_mod.hud_night_vision_disabled"), true);
    }

    public static void openEntityList(List<HudEntityData> entities, boolean authorized) {
        if (!authorized) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.my_mod.hud_not_authorized"), true);
            }
            return;
        }
        Minecraft.getInstance().setScreen(new HudEntityScreen(entities));
    }

    public static void applyEntityAction(HudEntityAction action, UUID entityUuid, boolean success, boolean authorized) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!authorized) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("message.my_mod.hud_not_authorized"), true);
            }
            return;
        }
        if (minecraft.screen instanceof HudEntityScreen screen) {
            screen.applyActionResult(action, entityUuid, success);
        }
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(success
                    ? (action == HudEntityAction.KILL ? "message.my_mod.entity_killed" : "message.my_mod.entity_teleported")
                    : "message.my_mod.entity_action_failed"), true);
        }
    }

    private static void closeHudScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof HudScreen || minecraft.screen instanceof HudEntityScreen) {
            minecraft.setScreen(null);
        }
    }
}
