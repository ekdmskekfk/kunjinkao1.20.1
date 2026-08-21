package dev.modmind.my_mod.client;

/**
 * 客户端战术 HUD 的唯一状态入口。动画使用 tick 推进，渲染侧只读取状态。
 */
public final class ClientHudState {

    private static final float ANIMATION_STEP = 1.0F / 8.0F;
    private static boolean hudEnabled;
    private static float animationProgress;

    private ClientHudState() {
    }

    public static void enable() {
        hudEnabled = true;
    }

    public static void disable() {
        hudEnabled = false;
    }

    public static void toggle() {
        hudEnabled = !hudEnabled;
    }

    public static boolean isEnabled() {
        return hudEnabled;
    }

    public static boolean isVisible() {
        return animationProgress > 0.0F;
    }

    public static float getAnimationProgress() {
        return animationProgress;
    }

    public static void tick() {
        float target = hudEnabled ? 1.0F : 0.0F;
        if (animationProgress < target) {
            animationProgress = Math.min(target, animationProgress + ANIMATION_STEP);
        } else if (animationProgress > target) {
            animationProgress = Math.max(target, animationProgress - ANIMATION_STEP);
        }
    }

    public static void reset() {
        hudEnabled = false;
        animationProgress = 0.0F;
    }
}
