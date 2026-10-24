package dev.modmind.kunjinkao.client.gui;

import dev.modmind.kunjinkao.KunJinKaoPalette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 全模组界面共用的一套绘制原语与视觉常量。
 *
 * <p><b>为什么把所有颜色/尺寸集中在这里</b>：之前四个界面的配色是散在各处的字面量，
 * 想统一调一下"面板深一点、描边细一点"就得改四处，还容易只改歪一边。现在所有
 * 观感相关的数字都在本类顶部，改一个值全界面生效。</p>
 *
 * <p><b>为什么全部用 {@code fill()} 画、不用贴图</b>：九宫格贴图在非整数 GUI 缩放下
 * 会出现接缝，而且要么平铺噪点（一个面板几百次 blit）、要么把噪点拉糊。
 * 纯代码画在任意缩放下都是 1px 锐利，也没有资源包冲突。</p>
 *
 * <p>视觉语言：玻璃质感深色底 + 青色双层描边 + 沿周长流动的光边 + 四角括号 + 扫描线。
 * 乱码符号 {@link KunJinKaoPalette#MOJIBAKE} 是本模组自带的记号，直接当标题装饰用。</p>
 *
 * <p><b>动效全部由 {@link KunJinKaoAnim} 的计算驱动</b>，本类不保存任何状态：
 * 每帧按墙上时钟算出该有的亮度/位置再画一遍。所以四个界面不用改一行代码，
 * 只要调用这些原语就自动带上动效。</p>
 */
public final class KunJinKaoUi {

    // ===== 尺寸（8px 网格）=====

    /** 布局网格。所有间距尽量取它的整数倍，界面才会整齐。 */
    public static final int GRID = 8;
    /** 面板内边距。 */
    public static final int PAD = 12;
    /** 标题栏高度。 */
    public static final int TITLE_BAR = 18;
    /** 四角括号臂长。 */
    public static final int CORNER_LEN = 10;

    // ===== 颜色 =====

    /** 面板底色（略透明，能看见后面的世界）。 */
    public static final int PANEL_BG = 0xE80E1620;
    /** 标题栏底色。 */
    public static final int TITLE_BG = 0xF00A1018;
    /** 外描边（暗）。 */
    public static final int BORDER_OUTER = 0xFF05090D;
    /** 内描边（亮）。 */
    public static final int BORDER_INNER = 0xFF57CFFF;
    /** 高光描边：流动光边与悬停外框用它，比内描边更亮一档。 */
    public static final int BORDER_GLOW = 0xFFA8EDFF;
    /** 角标颜色。 */
    public static final int CORNER = 0xFF8FE6FF;
    /** 扫描线。 */
    public static final int SCANLINE = 0x38000000;
    /** 行悬停高亮。 */
    public static final int ROW_HOVER = 0x3357CFFF;
    /** 分隔线。 */
    public static final int DIVIDER = 0x4057CFFF;

    /** 玻璃层顶部（12% 白）。 */
    public static final int GLASS_TOP = 0x1EFFFFFF;
    /** 玻璃层底部（几乎透明），配合顶部形成体积感。 */
    public static final int GLASS_BOTTOM = 0x05FFFFFF;
    /** 标题栏渐变（青 → 透明）。 */
    public static final int TITLE_GRADIENT = 0x2E57CFFF;

    /** 标题文字。 */
    public static final int TEXT_TITLE = 0xFFE9FBFF;
    /** 标签文字（青）。 */
    public static final int TEXT_LABEL = 0xFF8FEAFF;
    /** 次要文字（灰青）。 */
    public static final int TEXT_MUTED = 0xFF6C8A99;
    /** 强调/警告文字（琥珀）。 */
    public static final int TEXT_WARN = 0xFFFFD166;
    /** 错误文字（红）。 */
    public static final int TEXT_ERROR = 0xFFFF6B60;

    // ===== 动效节奏（毫秒）=====

    /** 光边绕面板一周的时间。 */
    private static final int FLOW_PERIOD = 3400;
    /** 标题栏扫光周期。 */
    private static final int SWEEP_PERIOD = 3200;
    /** 状态灯呼吸周期。 */
    private static final int BREATH_PERIOD = 1600;

    private KunJinKaoUi() {
    }

    // ===== 面板 =====

    /**
     * 画一个完整面板：深色底、玻璃层、双层描边、流动光边、扫描线、四角括号。
     *
     * <p>光边与玻璃层都是按当前时间算出来的，所以同一个面板每帧都不一样 ——
     * 这是 A/B 两个方案的落点，调用方不需要传任何时间参数。</p>
     */
    public static void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        // 玻璃层：上亮下暗。分层画而不是用 fillGradient，是为了在 1.20.1 / 1.21.1
        // 两个版本上行为完全一致（两个版本的 fillGradient 重载并不一样）。
        verticalGradient(graphics, x + 1, y + 1, w - 2, h - 2, GLASS_TOP, GLASS_BOTTOM);
        // 暗描边画在面板【外面】一圈：面板内部可用区域与改造前完全一致。
        // 之前把亮色内框画在 x+1/y+1，正好压住了贴边摆放的控件 ——
        // 剑设置的翻页按钮就在 panelY+1，看起来像"挤到框框上"。
        frame(graphics, x - 1, y - 1, w + 2, h + 2, BORDER_OUTER);
        // 亮色描边贴面板边缘（1px）
        frame(graphics, x, y, w, h, BORDER_INNER);
        // 内侧一条极淡高光：让 1px 描边看起来有厚度，而不是一根线
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, alpha(GLASS_TOP, 1.6F));
        // 沿周长流动的光边（B 方案的核心）
        flowingBorder(graphics, x - 1, y - 1, w + 2, h + 2);
        scanlines(graphics, x + 1, y + 1, w - 2, h - 2);
        // 四角括号也画在外框上，不再侵占内容区
        corners(graphics, x - 1, y - 1, w + 2, h + 2);
    }

    /** 只画一圈 1px 边框。 */
    public static void frame(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 每隔 3px 一条极淡横线，做出"CRT 扫描线"的质感。 */
    public static void scanlines(GuiGraphics graphics, int x, int y, int w, int h) {
        for (int row = y; row < y + h; row += 3) {
            graphics.fill(x, row, x + w, row + 1, SCANLINE);
        }
    }

    /** 四角括号，替代"整圈描边"作为识别记号。 */
    public static void corners(GuiGraphics graphics, int x, int y, int w, int h) {
        int len = CORNER_LEN;
        // 左上
        graphics.fill(x, y, x + len, y + 1, CORNER);
        graphics.fill(x, y, x + 1, y + len, CORNER);
        // 右上
        graphics.fill(x + w - len, y, x + w, y + 1, CORNER);
        graphics.fill(x + w - 1, y, x + w, y + len, CORNER);
        // 左下
        graphics.fill(x, y + h - 1, x + len, y + h, CORNER);
        graphics.fill(x, y + h - len, x + 1, y + h, CORNER);
        // 右下
        graphics.fill(x + w - len, y + h - 1, x + w, y + h, CORNER);
        graphics.fill(x + w - 1, y + h - len, x + w, y + h, CORNER);
    }

    /**
     * 沿矩形周长跑的一段渐隐光带。
     *
     * <p>做法是"按周长的像素位置逐点画"：先由时间算出光带头部在周长上的位置，
     * 再从头部往回画 {@code len} 个点，越靠后越透明 —— 于是看起来像一束光在跑，
     * 而不是一段硬边。</p>
     */
    public static void flowingBorder(GuiGraphics graphics, int x, int y, int w, int h) {
        int perimeter = 2 * (w + h);
        if (perimeter <= 0) {
            return;
        }
        int len = Math.max(24, perimeter / 6);
        int head = (int) (KunJinKaoAnim.cycle(FLOW_PERIOD) * perimeter);
        for (int i = 0; i < len; i++) {
            int d = (head - i + perimeter) % perimeter;
            float fade = 1.0F - i / (float) len;
            int color = alpha(BORDER_GLOW, 0.55F * fade * fade);
            drawPerimeterDot(graphics, x, y, w, h, d, color);
        }
    }

    private static void drawPerimeterDot(GuiGraphics graphics, int x, int y, int w, int h,
                                         int distance, int color) {
        int d = distance;
        if (d < w) {
            graphics.fill(x + d, y, x + d + 1, y + 1, color);
            return;
        }
        d -= w;
        if (d < h) {
            graphics.fill(x + w - 1, y + d, x + w, y + d + 1, color);
            return;
        }
        d -= h;
        if (d < w) {
            graphics.fill(x + w - 1 - d, y + h - 1, x + w - d, y + h, color);
            return;
        }
        d -= w;
        graphics.fill(x, y + h - 1 - d, x + 1, y + h - d, color);
    }

    // ===== 标题与分区 =====

    /**
     * 标题栏：左侧乱码记号 + 标题文字，底部一条渐隐横线。
     *
     * @return 标题栏底部的 y（内容从这里往下排）
     */
    public static int titleBar(GuiGraphics graphics, Font font, Component title,
                               int x, int y, int w) {
        graphics.fill(x + 2, y + 2, x + w - 2, y + TITLE_BAR, TITLE_BG);
        // 渐变底：左边主色、右边透明，比纯色条更有"发光面板"的感觉
        verticalGradient(graphics, x + 2, y + 2, w - 4, TITLE_BAR - 2, TITLE_GRADIENT, 0x0057CFFF);
        // 扫光带：一条柔和亮带循环掠过标题栏
        sweepBand(graphics, x + 2, y + 2, w - 4, TITLE_BAR - 2);
        // 左侧竖条：状态灯，带呼吸
        float beat = 0.55F + 0.45F * KunJinKaoAnim.pulse(BREATH_PERIOD);
        graphics.fill(x + 2, y + 4, x + 4, y + TITLE_BAR - 3, alpha(BORDER_INNER, beat));
        String glyph = KunJinKaoPalette.MOJIBAKE;
        graphics.drawString(font, glyph, x + PAD, y + 5, CORNER, false);
        int glyphWidth = font.width(glyph) + 4;
        graphics.drawString(font, title, x + PAD + glyphWidth, y + 5, TEXT_TITLE, false);
        // 底部渐隐线：左亮右暗
        int lineY = y + TITLE_BAR;
        fadeLine(graphics, x + 2, lineY, w - 4, BORDER_INNER);
        return lineY + 1;
    }

    /** 标题栏里循环掠过的亮带。 */
    private static void sweepBand(GuiGraphics graphics, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int band = Math.max(28, w / 5);
        int span = w + band * 2;
        int head = x - band + (int) (KunJinKaoAnim.cycle(SWEEP_PERIOD) * span);
        for (int i = 0; i < band; i++) {
            int px = head - i;
            if (px < x || px >= x + w) {
                continue;
            }
            float fade = 1.0F - Math.abs(i - band / 2.0F) / (band / 2.0F);
            graphics.fill(px, y, px + 1, y + h, alpha(BORDER_GLOW, 0.10F * fade));
        }
    }

    /** 一条从亮到透明的横线（三段近似渐变）。 */
    public static void fadeLine(GuiGraphics graphics, int x, int y, int w, int color) {
        if (w <= 0) {
            return;
        }
        int third = Math.max(1, w / 3);
        graphics.fill(x, y, x + third, y + 1, alpha(color, 1.0F));
        graphics.fill(x + third, y, x + Math.min(w, third * 2), y + 1, alpha(color, 0.6F));
        graphics.fill(x + Math.min(w, third * 2), y, x + w, y + 1, alpha(color, 0.25F));
    }

    /**
     * 分区标题：文字 + 右侧一条渐隐横线。
     *
     * @return 下一行内容的 y
     */
    public static int sectionHeader(GuiGraphics graphics, Font font, Component text,
                                    int x, int y, int w) {
        return sectionHeader(graphics, font, text, x, y, w, 1.0F);
    }

    /**
     * 带"生长"的分区标题：{@code growth} 从 0 到 1，竖条从无到有长出来、横线跟着延伸。
     * 界面按下标错开各自的 growth，就是 C 方案的级联入场。
     *
     * @return 下一行内容的 y
     */
    public static int sectionHeader(GuiGraphics graphics, Font font, Component text,
                                    int x, int y, int w, float growth) {
        float g = KunJinKaoAnim.clamp01(growth);
        // 左侧竖条：从上往下长出来
        int barHeight = Math.round(8.0F * g);
        if (barHeight > 0) {
            graphics.fill(x - 6, y + 1, x - 4, y + 1 + barHeight, alpha(CORNER, 0.9F));
        }
        graphics.drawString(font, text, x, y, alpha(TEXT_LABEL, 0.35F + 0.65F * g), false);
        int textWidth = font.width(text);
        int lineStart = x + textWidth + 8;
        int lineEnd = x + w;
        int visible = lineStart + Math.round(Math.max(0, (lineEnd - lineStart)) * g);
        if (visible > lineStart) {
            fadeLine(graphics, lineStart, y + 4, visible - lineStart, DIVIDER);
        }
        return y + font.lineHeight + 6;
    }

    // ===== 行 / 列表 =====

    /**
     * 行背景：悬停时淡入高亮、左侧竖条滑入、外圈起一层很淡的描边。
     *
     * <p>悬停强度不是布尔量，而是由 {@link KunJinKaoAnim#hover} 平滑插值到 0..1 的，
     * 所以鼠标扫过时是"渐渐亮起来"而不是"啪一下变色"。</p>
     */
    public static void rowBackground(GuiGraphics graphics, int x, int y, int w, int h, boolean hovered) {
        float hover = KunJinKaoAnim.hover(x, y, w, h, hovered, 14.0F);
        if (hover <= 0.01F) {
            return;
        }
        graphics.fill(x, y, x + w, y + h, alpha(ROW_HOVER, hover));
        int bar = Math.max(1, Math.round(2.0F * hover));
        graphics.fill(x, y, x + bar, y + h, alpha(BORDER_INNER, hover));
        frame(graphics, x, y, w, h, alpha(BORDER_GLOW, 0.10F * hover));
    }

    /** 水平分隔线。 */
    public static void divider(GuiGraphics graphics, int x, int y, int w) {
        graphics.fill(x, y, x + w, y + 1, DIVIDER);
    }

    // ===== 标题文字 =====

    /**
     * 只画标题文字（乱码记号 + 标题），不画底色与分隔线。
     *
     * <p>给"内容从面板顶端就开始"的界面用：功能控制台与剑设置的第一行在 panelY+1 附近，
     * 画整条标题栏会把它们盖住 —— 那是"字挤进框里"的真正原因，不是字号问题。</p>
     */
    public static void titleText(GuiGraphics graphics, Font font, Component title, int x, int y) {
        titleText(graphics, font, title, x, y, 1.0F);
    }

    /**
     * 打字机标题：{@code reveal} 从 0 到 1，文字逐字出现，末尾带闪烁光标。
     * {@code reveal >= 1} 时与普通标题完全一致。
     */
    public static void titleText(GuiGraphics graphics, Font font, Component title,
                                 int x, int y, float reveal) {
        float r = KunJinKaoAnim.clamp01(reveal);
        String glyph = KunJinKaoPalette.MOJIBAKE;
        graphics.drawString(font, glyph, x, y, alpha(CORNER, 0.35F + 0.65F * r), false);
        int glyphWidth = font.width(glyph) + 4;
        String full = title.getString();
        String shown = typed(full, r);
        int textX = x + glyphWidth;
        graphics.drawString(font, shown, textX, y, alpha(TEXT_TITLE, 0.3F + 0.7F * r), false);
        if (r < 1.0F && KunJinKaoAnim.cycle(600) < 0.5F) {
            int cursorX = textX + font.width(shown) + 1;
            graphics.fill(cursorX, y, cursorX + 1, y + font.lineHeight, CORNER);
        }
    }

    private static String typed(String text, float reveal) {
        if (reveal >= 1.0F) {
            return text;
        }
        int chars = (int) Math.floor(text.length() * reveal);
        return text.substring(0, Math.max(0, Math.min(text.length(), chars)));
    }

    /** 焦点环：键盘/手柄操作时指示当前控件。 */
    public static void focusRing(GuiGraphics graphics, int x, int y, int w, int h) {
        frame(graphics, x - 2, y - 2, w + 4, h + 4, CORNER);
    }

    // ===== 状态点 =====

    /** 小方块状态点：开=青色呼吸、关=灰。 */
    public static void statusDot(GuiGraphics graphics, int x, int y, boolean on) {
        if (!on) {
            graphics.fill(x, y, x + 6, y + 6, 0xFF3A4A55);
            graphics.fill(x + 1, y + 1, x + 5, y + 2, alpha(0xFF3A4A55, 0.4F));
            return;
        }
        float beat = 0.60F + 0.40F * KunJinKaoAnim.pulse(BREATH_PERIOD);
        // 外圈光晕：随呼吸一起明暗
        frame(graphics, x - 1, y - 1, 8, 8, alpha(BORDER_GLOW, 0.28F * beat));
        graphics.fill(x, y, x + 6, y + 6, alpha(BORDER_INNER, beat));
        graphics.fill(x + 1, y + 1, x + 5, y + 2, alpha(0xFFFFFFFF, 0.45F));
    }

    // ===== 工具 =====

    /** 界面首次绘制的时间戳，用来算"入场进度"。弱键：界面被回收后条目自动消失。 */
    private static final java.util.Map<Object, Long> OPEN_TIMES = new java.util.WeakHashMap<>();

    /**
     * 某个界面的"打开时刻"。第一次调用时记录，之后返回同一个值。
     *
     * <p>用弱键挂在界面实例上，而不是让每个界面自己加一个 {@code openAt} 字段：
     * 四个界面都要用同一个时间基准，各写一遍字段反而容易漏；弱引用保证界面关掉后
     * 这些记录会被回收，不会随开关界面无限增长。</p>
     */
    public static long openTimeOf(Object screen) {
        return OPEN_TIMES.computeIfAbsent(screen, key -> System.currentTimeMillis());
    }

    /** 竖直渐变。分段 fill 实现，避免依赖两个版本签名不一致的 fillGradient。 */
    public static void verticalGradient(GuiGraphics graphics, int x, int y, int w, int h,
                                        int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int strips = Math.max(1, Math.min(h, 12));
        for (int i = 0; i < strips; i++) {
            int sy = y + (h * i) / strips;
            int ey = y + (h * (i + 1)) / strips;
            if (ey <= sy) {
                continue;
            }
            float t = strips == 1 ? 0.0F : i / (float) (strips - 1);
            graphics.fill(x, sy, x + w, ey, KunJinKaoAnim.lerpColor(top, bottom, t));
        }
    }

    /** 按比例调整透明度，保留 RGB。 */
    public static int alpha(int argb, float scale) {
        return KunJinKaoPalette.alpha(argb, scale);
    }

    /** 把字符串截断到指定像素宽（超出加省略号）。 */
    public static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int budget = maxWidth - font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(builder.toString() + c) > budget) {
                break;
            }
            builder.append(c);
        }
        return builder + ellipsis;
    }
}
