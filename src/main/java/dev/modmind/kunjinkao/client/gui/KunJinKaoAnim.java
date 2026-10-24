package dev.modmind.kunjinkao.client.gui;

/**
 * 界面动效的公共数学层：缓动、进度、循环、颜色插值，以及"平滑悬停"。
 *
 * <p><b>为什么单独一个类、且不含任何 Minecraft 类型</b>：Minecraft 的 GUI 是立即模式，
 * 没有补间系统，每一帧都得自己算"现在该画成什么样"。把这些计算集中在纯数学类里，
 * 四个界面就共用同一套手感（不会一个界面快、一个界面慢），做交互动效时也不用各写一遍。</p>
 *
 * <p><b>为什么用 wall clock 而不是 tick</b>：界面动画跟游戏时间无关 —— 暂停、卡顿、
 * 掉 TPS 都不该让动画变形。{@code System.currentTimeMillis()} 在客户端每帧都可用。</p>
 *
 * <p><b>为什么没有"动画对象"</b>：一个界面同一时刻最多十几个插值量，做成对象池反而
 * 要管生命周期（关闭要清、切换要重置）。这里全是不含状态的方法，状态只留在调用方的
 * 一个 {@code long openAt} 里。</p>
 */
public final class KunJinKaoAnim {

    private KunJinKaoAnim() {
    }

    // ===== 缓动 =====

    public static float clamp01(float t) {
        return t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
    }

    /** 起步快、收尾慢。入场、展开、回弹都用它。 */
    public static float easeOutCubic(float t) {
        float u = 1.0F - clamp01(t);
        return 1.0F - u * u * u;
    }

    /** 两头慢、中间快。适合"从 A 移动到 B"这类位移。 */
    public static float easeInOutQuad(float t) {
        float c = clamp01(t);
        return c < 0.5F ? 2.0F * c * c : 1.0F - (float) Math.pow(-2.0F * c + 2.0F, 2.0D) / 2.0F;
    }

    /** 冲过头再回弹一点，用于"弹出"这类有存在感的动作。 */
    public static float easeOutBack(float t) {
        float c = clamp01(t) - 1.0F;
        float s = 1.70158F;
        return 1.0F + (s + 1.0F) * c * c * c + s * c * c;
    }

    // ===== 时间 =====

    /**
     * 从 {@code startMillis} 起算的进度，0→1，超过时长后恒为 1。
     *
     * <p>界面只要在 {@code init()} 里记一个 {@code System.currentTimeMillis()}，
     * 之后每帧拿它换进度即可，不需要维护任何"动画是否在播"的标记。</p>
     */
    public static float progress(long startMillis, int durationMs) {
        if (durationMs <= 0) {
            return 1.0F;
        }
        return clamp01((System.currentTimeMillis() - startMillis) / (float) durationMs);
    }

    /** 0→1 线性循环。{@code progress} 管"进场一次"，它管"持续循环"。 */
    public static float cycle(int periodMs) {
        if (periodMs <= 0) {
            return 0.0F;
        }
        return (System.currentTimeMillis() % periodMs) / (float) periodMs;
    }

    /** 0→1→0 的正弦呼吸，用于状态灯、光边。 */
    public static float pulse(int periodMs) {
        return (float) ((Math.sin(cycle(periodMs) * Math.PI * 2.0D - Math.PI / 2.0D) + 1.0D) / 2.0D);
    }

    // ===== 插值 =====

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * clamp01(t);
    }

    public static int lerp(int from, int to, float t) {
        return Math.round(lerp((float) from, (float) to, t));
    }

    /** 按 ARGB 逐通道插值（含 alpha），用于渐变色与悬停淡入。 */
    public static int lerpColor(int from, int to, float t) {
        float c = clamp01(t);
        int a = lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, c);
        int r = lerp((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, c);
        int g = lerp((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, c);
        int b = lerp(from & 0xFF, to & 0xFF, c);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 只改 alpha，保留 RGB。 */
    public static int fade(int argb, float opacity) {
        int a = Math.round(((argb >>> 24) & 0xFF) * clamp01(opacity));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ===== 平滑悬停 =====

    /** 悬停插值状态：键是控件的矩形（位置变了就等于换了一个控件）。 */
    private static final java.util.Map<Long, Float> HOVER = new java.util.HashMap<>();
    private static long lastFrameMillis;
    private static float lastDelta;

    /**
     * 平滑悬停强度 0..1。
     *
     * <p><b>为什么用静态表而不是让每个界面自己管</b>：四个界面加起来有近百个可悬停行，
     * 每行都挂一个字段既啰嗦又容易在重建控件时漏初始化。以矩形打包成 long 作键，
     * 同一位置的行天然复用同一份插值状态；界面关掉后那些条目会在下一帧因为
     * {@code target == 0} 被移除，剩下的也由上限兜底，不会无限增长。</p>
     *
     * <p>{@code speed} 是"每秒收敛速度"，12~16 大致相当于 120ms 淡入。</p>
     */
    public static float hover(int x, int y, int w, int h, boolean hovered, float speed) {
        stepClock();
        long key = pack(x, y, w, h);
        Float stored = HOVER.get(key);
        float value = stored == null ? 0.0F : stored;
        float target = hovered ? 1.0F : 0.0F;
        value += (target - value) * Math.min(1.0F, lastDelta * speed);
        if (target == 0.0F && value < 0.01F) {
            HOVER.remove(key);
            return 0.0F;
        }
        if (HOVER.size() > 1024) {
            HOVER.clear();
        }
        HOVER.put(key, value);
        return value;
    }

    /** 关界面时清掉悬停状态（不清也只是多几条记录，纯保险）。 */
    public static void clearHover() {
        HOVER.clear();
    }

    // ===== 数值滚动 =====

    /** 每个数值控件的当前显示值。弱键：控件被回收后条目自动消失。 */
    private static final java.util.Map<Object, float[]> ROLL = new java.util.WeakHashMap<>();

    /**
     * 数值滚动：返回"此刻应该显示的整数"，向 {@code target} 平滑逼近。
     *
     * <p>滑条直接把 {@code updateMessage()} 里的真实值换成这个返回值，并在每帧重画一次，
     * 数字就会从旧值滚到新值（约 200ms），而不是瞬间跳变。{@code speed} 越大越快，16 约等于 200ms。</p>
     *
     * <p>用弱键挂 {@code owner}（一般是滑条本身）：滑条随界面销毁时条目一起回收，
     * 不需要任何清理代码，也不会因为界面反复开关而堆积。</p>
     */
    public static int roll(Object owner, int target, float speed) {
        float[] state = ROLL.get(owner);
        if (state == null) {
            state = new float[]{target};
            ROLL.put(owner, state);
        }
        float current = state[0];
        if (Math.abs(target - current) < 0.5F) {
            state[0] = target;
            return target;
        }
        stepClock();
        current += (target - current) * Math.min(1.0F, lastDelta * speed);
        state[0] = current;
        return Math.round(current);
    }

    private static void stepClock() {
        long now = System.currentTimeMillis();
        if (lastFrameMillis == 0L) {
            lastDelta = 0.016F;
        } else {
            lastDelta = Math.min(0.1F, (now - lastFrameMillis) / 1000.0F);
        }
        lastFrameMillis = now;
    }

    private static long pack(int x, int y, int w, int h) {
        return ((long) (x & 0xFFFF) << 48)
                | ((long) (y & 0xFFFF) << 32)
                | ((long) (w & 0xFFFF) << 16)
                | (h & 0xFFFF);
    }
}
