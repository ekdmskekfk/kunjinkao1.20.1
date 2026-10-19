package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.client.function.HudFunctionManager;
import dev.modmind.kunjinkao.client.hud.HudScreen;
import dev.modmind.kunjinkao.client.hud.HudEntityScreen;
import dev.modmind.kunjinkao.client.hud.HudExclusionScreen;
import dev.modmind.kunjinkao.network.ExcludedPlayerData;
import dev.modmind.kunjinkao.network.HudEntityAction;
import dev.modmind.kunjinkao.network.HudEntityData;

import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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
                        Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            }
            return;
        }

        if (enabled) {
            ClientHudState.enable();
            HudFunctionManager.activateDefault();
            Minecraft minecraft = Minecraft.getInstance();
            // 不无条件顶掉当前界面：玩家可能正开着背包/箱子/加速器界面。
            if (canReplaceWithHudScreen(minecraft.screen)) {
                minecraft.setScreen(new HudScreen());
            }
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
                    Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            return;
        }
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                enabled ? "message.kunjinkao.hud_night_vision_enabled" : "message.kunjinkao.hud_night_vision_disabled"), true);
    }

    public static void applyTrueInvisibility(boolean enabled, boolean authorized) {
        HudFunctionManager.setTrueInvisibilityEnabled(enabled && authorized);
        if (Minecraft.getInstance().player == null) {
            return;
        }
        TacticalHudInvisibilityVisualState.setTrueInvisible(Minecraft.getInstance().player.getUUID(), enabled && authorized);
        if (!authorized) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            return;
        }
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                enabled ? "message.kunjinkao.hud_true_invisibility_enabled"
                        : "message.kunjinkao.hud_true_invisibility_disabled"), true);
    }

    public static void applyMagnet(boolean enabled, boolean authorized) {
        HudFunctionManager.setMagnetEnabled(enabled && authorized);
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (!authorized) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            return;
        }
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                enabled ? "message.kunjinkao.hud_magnet_enabled" : "message.kunjinkao.hud_magnet_disabled"), true);
    }

    public static void openEntityList(List<HudEntityData> entities, boolean authorized) {
        if (!authorized) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof HudEntityScreen screen) {
            // 服务端每次刷新列表都会发这个包；已经在实体列表里时就地替换数据，
            // 保留玩家当前的选中项、搜索词与滚动位置，而不是重建界面。
            screen.replaceEntities(entities);
            return;
        }
        if (canReplaceWithHudScreen(minecraft.screen)) {
            minecraft.setScreen(new HudEntityScreen(entities));
        }
    }

    public static void applyEntityAction(HudEntityAction action, UUID entityUuid, boolean success, boolean authorized) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!authorized) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            }
            return;
        }
        if (minecraft.screen instanceof HudEntityScreen screen) {
            screen.applyActionResult(action, entityUuid, success);
        }
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(success
                    ? (action == HudEntityAction.KILL ? "message.kunjinkao.entity_killed" : "message.kunjinkao.entity_teleported")
                    : "message.kunjinkao.entity_action_failed"), true);
        }
    }

    /**
     * 排除列表：既用于首次打开，也用于解除之后的名单刷新。
     * 列表内容始终以服务端回传为准。
     */
    public static void applyExcludedList(List<ExcludedPlayerData> players, boolean authorized) {
        if (!authorized) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.kunjinkao.hud_not_authorized"), true);
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof HudExclusionScreen screen) {
            screen.replacePlayers(players);
        } else if (canReplaceWithHudScreen(minecraft.screen)) {
            minecraft.setScreen(new HudExclusionScreen(players));
        }
    }

    /**
     * 是否允许切换到本模组自己的 HUD 界面：只有当前没有界面，或当前界面本来就是本模组
     * HUD 系列时才允许。否则（背包、箱子、加速器设置等）宁可不打开，也不要把玩家的界面顶掉。
     */
    private static boolean canReplaceWithHudScreen(Screen screen) {
        return screen == null || screen instanceof HudScreen || screen instanceof HudEntityScreen
                || screen instanceof HudExclusionScreen;
    }

    private static void closeHudScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof HudScreen || minecraft.screen instanceof HudEntityScreen
                || minecraft.screen instanceof HudExclusionScreen) {
            minecraft.setScreen(null);
        }
    }
}
