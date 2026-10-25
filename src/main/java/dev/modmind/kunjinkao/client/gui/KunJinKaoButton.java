package dev.modmind.kunjinkao.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 自绘按钮：玻璃底 + 青色描边 + 悬停渐亮 + 按下回弹，替掉原版 {@code Button}。
 *
 * <p><b>为什么不用原版按钮</b>：原版是固定贴图（灰底黑边），放进玻璃面板里像贴上去的，
 * 而且原版没有过渡 —— 悬停瞬间变色、按下没有反馈。这里全部用 {@code fill} 画，
 * 颜色取自 {@link KunJinKaoUi}，与面板、滑条、列表是同一套视觉语言。</p>
 *
 * <p><b>三个状态</b>：</p>
 * <ul>
 *   <li>悬停：底色调亮 + 描边变亮，由 {@link KunJinKaoAnim#hover} 平滑插值（约 120ms）；</li>
 *   <li>按下：整体下移 1px，然后 160ms 内弹回；</li>
 *   <li>禁用：压暗文字与描边，不响应点击。</li>
 * </ul>
 *
 * <p>{@code accent} 用于"主操作"（例如附魔工坊的执行），底色更青、左侧带一条强调竖条，
 * 让玩家一眼看出该点哪个。</p>
 */
public class KunJinKaoButton extends AbstractWidget {

    /** 底色：常态 / 悬停。 */
    private static final int FILL_IDLE = 0xE00E1620;
    private static final int FILL_HOVER = 0xE01B3A4A;
    /** 主操作按钮的底色。 */
    private static final int FILL_ACCENT_IDLE = 0xE0123A46;
    private static final int FILL_ACCENT_HOVER = 0xE02A6E86;
    /** 禁用底色与文字。 */
    private static final int FILL_DISABLED = 0xC0101418;
    private static final int TEXT_DISABLED = 0xFF55636B;

    private final Runnable action;
    private final boolean accent;

    /** 按下强度 1 → 0，用于"按下去再弹回来"的位移。 */
    private float press;
    private long lastFrameMillis;

    public KunJinKaoButton(int x, int y, int width, int height, Component message, Runnable action) {
        this(x, y, width, height, message, action, false);
    }

    public KunJinKaoButton(int x, int y, int width, int height, Component message,
                           Runnable action, boolean accent) {
        super(x, y, width, height, message);
        this.action = action;
        this.accent = accent;
        this.lastFrameMillis = System.currentTimeMillis();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 按下回弹：每帧衰减，160ms 左右归零
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1F, (now - lastFrameMillis) / 1000.0F);
        lastFrameMillis = now;
        press = Math.max(0.0F, press - dt * 6.0F);

        boolean hovered = active
                && mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height;
        float hover = KunJinKaoAnim.hover(getX(), getY(), width, height, hovered, 16.0F);

        int x = getX();
        int y = getY() + (press > 0.5F ? 1 : 0);

        int fill;
        if (!active) {
            fill = FILL_DISABLED;
        } else if (accent) {
            fill = KunJinKaoAnim.lerpColor(FILL_ACCENT_IDLE, FILL_ACCENT_HOVER, hover);
        } else {
            fill = KunJinKaoAnim.lerpColor(FILL_IDLE, FILL_HOVER, hover);
        }
        graphics.fill(x, y, x + width, y + height, fill);

        int border;
        if (!active) {
            border = 0xFF2A333A;
        } else {
            border = KunJinKaoAnim.lerpColor(0xFF3E7E96, KunJinKaoUi.BORDER_GLOW, hover);
        }
        KunJinKaoUi.frame(graphics, x, y, width, height, border);

        // 主操作：左侧强调竖条，悬停时更亮
        if (accent && active) {
            graphics.fill(x, y, x + 2, y + height, KunJinKaoUi.alpha(KunJinKaoUi.BORDER_INNER, 0.7F + 0.3F * hover));
        }
        // 顶部一条极淡高光，让按钮看起来是"玻璃块"而不是色块
        if (active) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, KunJinKaoUi.alpha(0xFFFFFFFF, 0.05F + 0.04F * hover));
        }

        int textColor = active ? KunJinKaoUi.TEXT_TITLE : TEXT_DISABLED;
        graphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                getMessage(), x + width / 2, y + (height - 8) / 2, textColor);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        press = 1.0F;
        if (action != null) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
