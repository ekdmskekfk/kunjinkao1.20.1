package dev.modmind.kunjinkao.client.function;

import javax.annotation.Nullable;

/**
 * 仅管理功能选择状态；需要改变服务器状态的功能将在此处接入 C2S 数据包。
 */
public final class HudFunctionManager {

    @Nullable
    private static HudFunction activeFunction;
    private static boolean nightVisionEnabled;
    private static boolean trueInvisibilityEnabled;
    private static boolean magnetEnabled;

    private HudFunctionManager() {
    }

    public static void activate(HudFunction function) {
        activeFunction = function;
    }

    /**
     * HUD 开启时不预选任何功能：原先默认高亮的 SCAN 是无实现的死功能，已从枚举删除。
     */
    public static void activateDefault() {
        deactivateAll();
    }

    public static void deactivate(HudFunction function) {
        if (activeFunction == function) {
            activeFunction = null;
        }
    }

    public static void deactivateAll() {
        activeFunction = null;
    }

    /**
     * 全部归零（登出/切世界时调用）。
     * <p>
     * 三个开关是纯客户端的镜像值，登录后由服务端回包重新覆盖；但登出后若留着上次的值，
     * HUD 重开时按钮会显示成"已启用"而服务端状态未必如此 —— 所以生命周期结束时一并清掉。
     */
    public static void resetAll() {
        activeFunction = null;
        nightVisionEnabled = false;
        trueInvisibilityEnabled = false;
        magnetEnabled = false;
    }

    public static boolean isActive(HudFunction function) {
        return activeFunction == function
                || (function == HudFunction.NIGHT_VISION && nightVisionEnabled)
                || (function == HudFunction.TRUE_INVISIBILITY && trueInvisibilityEnabled)
                || (function == HudFunction.MAGNET && magnetEnabled);
    }

    public static void setNightVisionEnabled(boolean enabled) {
        nightVisionEnabled = enabled;
    }

    public static void setTrueInvisibilityEnabled(boolean enabled) {
        trueInvisibilityEnabled = enabled;
    }

    public static void setMagnetEnabled(boolean enabled) {
        magnetEnabled = enabled;
    }
}
