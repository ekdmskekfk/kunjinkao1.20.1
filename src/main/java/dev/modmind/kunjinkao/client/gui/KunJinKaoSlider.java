package dev.modmind.kunjinkao.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * 本模组所有滑条的基类：每帧刷新一次文字，让"数字滚动"有时间轴。
 *
 * <p>原版 {@code AbstractSliderButton} 只在拖动改变值时调一次 {@code updateMessage()}，
 * 一次拖动只产生一帧文字，数字滚动完全看不出来。这里在 {@code renderWidget} 里
 * 每帧刷新一次，配合 {@link KunJinKaoAnim#roll} 就得到约 200ms 的滚动效果。</p>
 *
 * <p>之所以做成基类而不是在每个滑条里各写一遍 {@code renderWidget}：四个滑条的行为
 * 必须完全一致（同样的速度、同样的刷新时机），集中一处才不会哪天改了一个忘了另一个。</p>
 */
public abstract class KunJinKaoSlider extends AbstractSliderButton {

    protected KunJinKaoSlider(int x, int y, int width, int height, double value) {
        super(x, y, width, height, Component.empty(), value);
    }

    protected KunJinKaoSlider(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message, value);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 数字滚动需要"每帧都有一次机会更新文字"；原版只在值变化时调一次。
        updateMessage();
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /** 把真实值交给滚动器，返回这一刻该显示的整数。 */
    protected final int roll(int target) {
        return KunJinKaoAnim.roll(this, target, 16.0F);
    }
}
