package dev.modmind.my_mod.client.function;

import javax.annotation.Nullable;

/**
 * 仅管理功能选择状态；需要改变服务器状态的功能将在此处接入 C2S 数据包。
 */
public final class HudFunctionManager {

    @Nullable
    private static HudFunction activeFunction;
    private static boolean nightVisionEnabled;

    private HudFunctionManager() {
    }

    public static void activate(HudFunction function) {
        activeFunction = function;
    }

    public static void activateDefault() {
        activate(HudFunction.SCAN);
    }

    public static void deactivate(HudFunction function) {
        if (activeFunction == function) {
            activeFunction = null;
        }
    }

    public static void deactivateAll() {
        activeFunction = null;
    }

    public static boolean isActive(HudFunction function) {
        return activeFunction == function || (function == HudFunction.NIGHT_VISION && nightVisionEnabled);
    }

    public static void setNightVisionEnabled(boolean enabled) {
        nightVisionEnabled = enabled;
    }
}
