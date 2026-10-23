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
 * 纯代码画在任意缩放下都是 1px 锐利，也没有资源包冲突 —— 这正是"混合方案"
 * 里"代码骨架"那一半的取舍。</p>
 *
 * <p>视觉语言：深色底 + 青色双层描边 + 四角括号 + 扫描线 + 乱码符号前缀。
 * 乱码符号 {@link KunJinKaoPalette#MOJIBAKE} 是本模组自带的记号，直接当标题装饰用。</p>
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
    /** 角标颜色。 */
    public static final int CORNER = 0xFF8FE6FF;
    /** 扫描线。 */
    public static final int SCANLINE = 0x38000000;
    /** 行悬停高亮。 */
    public static final int ROW_HOVER = 0x3357CFFF;
    /** 分隔线。 */
    public static final int DIVIDER = 0x4057CFFF;

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

    private KunJinKaoUi() {
    }

    // ===== 面板 =====

    /** 画一个完整面板：底、双层描边、扫描线、四角括号。 */
    public static void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        // 外描边：四边各 1px 暗色
        frame(graphics, x, y, w, h, BORDER_OUTER);
        // 内描边：再缩 1px 画亮色
        frame(graphics, x + 1, y + 1, w - 2, h - 2, BORDER_INNER);
        scanlines(graphics, x + 2, y + 2, w - 4, h - 4);
        corners(graphics, x, y, w, h);
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

    // ===== 标题与分区 =====

    /**
     * 标题栏：左侧乱码记号 + 标题文字，底部一条渐隐横线。
     *
     * @return 标题栏底部的 y（内容从这里往下排）
     */
    public static int titleBar(GuiGraphics graphics, Font font, Component title,
                               int x, int y, int w) {
        graphics.fill(x + 2, y + 2, x + w - 2, y + TITLE_BAR, TITLE_BG);
        // 左侧竖条：状态灯
        graphics.fill(x + 2, y + 4, x + 4, y + TITLE_BAR - 3, BORDER_INNER);
        String glyph = KunJinKaoPalette.MOJIBAKE;
        graphics.drawString(font, glyph, x + PAD, y + 5, CORNER, false);
        int glyphWidth = font.width(glyph) + 4;
        graphics.drawString(font, title, x + PAD + glyphWidth, y + 5, TEXT_TITLE, false);
        // 底部渐隐线：左亮右暗
        int lineY = y + TITLE_BAR;
        graphics.fill(x + 2, lineY, x + w - 2, lineY + 1, BORDER_INNER);
        return lineY + 1;
    }

    /**
     * 分区标题：文字 + 右侧一条渐隐横线。
     *
     * @return 下一行内容的 y
     */
    public static int sectionHeader(GuiGraphics graphics, Font font, Component text,
                                    int x, int y, int w) {
        graphics.drawString(font, text, x, y, TEXT_LABEL, false);
        int textWidth = font.width(text);
        int lineStart = x + textWidth + 8;
        int lineEnd = x + w;
        if (lineEnd > lineStart) {
            // 分三段画，做出从亮到透明的过渡
            int third = (lineEnd - lineStart) / 3;
            graphics.fill(lineStart, y + 4, lineStart + third, y + 5, alpha(DIVIDER, 1.0F));
            graphics.fill(lineStart + third, y + 4, lineStart + third * 2, y + 5, alpha(DIVIDER, 0.6F));
            graphics.fill(lineStart + third * 2, y + 4, lineEnd, y + 5, alpha(DIVIDER, 0.25F));
        }
        return y + font.lineHeight + 6;
    }

    // ===== 行 / 列表 =====

    /** 行背景：悬停时加一条高亮，左侧再加一条 2px 竖线做"选中感"。 */
    public static void rowBackground(GuiGraphics graphics, int x, int y, int w, int h, boolean hovered) {
        if (!hovered) {
            return;
        }
        graphics.fill(x, y, x + w, y + h, ROW_HOVER);
        graphics.fill(x, y, x + 2, y + h, BORDER_INNER);
    }

    /** 水平分隔线。 */
    public static void divider(GuiGraphics graphics, int x, int y, int w) {
        graphics.fill(x, y, x + w, y + 1, DIVIDER);
    }

    /**
     * 只画标题文字（乱码记号 + 标题），不画底色与分隔线。
     *
     * <p>给"内容从面板顶端就开始"的界面用：功能控制台与剑设置的第一行在 panelY+1 附近，
     * 画整条标题栏会把它们盖住 —— 那是"字挤进框里"的真正原因，不是字号问题。</p>
     */
    public static void titleText(GuiGraphics graphics, Font font, Component title, int x, int y) {
        String glyph = KunJinKaoPalette.MOJIBAKE;
        graphics.drawString(font, glyph, x, y, CORNER, false);
        int glyphWidth = font.width(glyph) + 4;
        graphics.drawString(font, title, x + glyphWidth, y, TEXT_TITLE, false);
    }

    /** 焦点环：键盘/手柄操作时指示当前控件。 */
    public static void focusRing(GuiGraphics graphics, int x, int y, int w, int h) {
        frame(graphics, x - 2, y - 2, w + 4, h + 4, CORNER);
    }

    // ===== 状态点 =====

    /** 小方块状态点：开=青、关=灰。 */
    public static void statusDot(GuiGraphics graphics, int x, int y, boolean on) {
        int color = on ? BORDER_INNER : 0xFF3A4A55;
        graphics.fill(x, y, x + 6, y + 6, color);
        graphics.fill(x + 1, y + 1, x + 5, y + 2, alpha(color, 0.4F));
    }

    // ===== 工具 =====

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
