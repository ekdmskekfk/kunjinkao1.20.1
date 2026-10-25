package dev.modmind.kunjinkao.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

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
 * <p>{@code accent} 用于"主操作"，底色更青、左侧带一条强调竖条。</p>
 *
 * <p><b>为什么带一个 Builder</b>：各界面原来写的是 {@code Button.builder(msg, b -> ...).bounds(...).build()}，
 * 提供一个同名同形的方法就能<b>只改类名</b>完成替换，不用把几十处调用点全部改写成构造器形式；
 * 回调参数也从 {@code Button} 变成 {@code KunJinKaoButton}，{@code setMessage} 等用法保持不变。</p>
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

    private Runnable action;
    private Consumer<KunJinKaoButton> consumer;
    private boolean accent;

    /** 按下强度 1 → 0，用于"按下去再弹回来"的位移。 */
    private float press;
    private long lastFrameMillis = System.currentTimeMillis();

    public KunJinKaoButton(int x, int y, int width, int height, Component message, Runnable action) {
        this(x, y, width, height, message, action, false);
    }

    public KunJinKaoButton(int x, int y, int width, int height, Component message,
                           Runnable action, boolean accent) {
        super(x, y, width, height, message);
        this.action = action;
        this.accent = accent;
    }

    // ===== Builder（与 Button.builder 同形，便于整体替换）=====

    public static Builder builder(Component message, Consumer<KunJinKaoButton> onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {
        private final Component message;
        private final Consumer<KunJinKaoButton> onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;
        private boolean accent;
        private Tooltip tooltip;

        Builder(Component message, Consumer<KunJinKaoButton> onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        /** 主操作样式：底色更青、左侧强调条。 */
        public Builder accent() {
            this.accent = true;
            return this;
        }

        public KunJinKaoButton build() {
            KunJinKaoButton button = new KunJinKaoButton(x, y, width, height, message, null, accent);
            button.consumer = onPress;
            if (tooltip != null) {
                button.setTooltip(tooltip);
            }
            return button;
        }
    }

    // ===== 绘制 =====

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
            graphics.fill(x, y, x + 2, y + height,
                    KunJinKaoUi.alpha(KunJinKaoUi.BORDER_INNER, 0.7F + 0.3F * hover));
        }
        // 顶部一条极淡高光，让按钮看起来是"玻璃块"而不是色块
        if (active) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2,
                    KunJinKaoUi.alpha(0xFFFFFFFF, 0.05F + 0.04F * hover));
        }

        int textColor = active ? KunJinKaoUi.TEXT_TITLE : TEXT_DISABLED;
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                x + width / 2, y + (height - 8) / 2, textColor);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        press = 1.0F;
        if (consumer != null) {
            consumer.accept(this);
        } else if (action != null) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
