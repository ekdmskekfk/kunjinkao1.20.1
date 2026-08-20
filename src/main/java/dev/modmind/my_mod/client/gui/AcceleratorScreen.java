package dev.modmind.my_mod.client.gui;

import dev.modmind.my_mod.block.entity.AcceleratorBlockEntity;
import dev.modmind.my_mod.network.AcceleratorConfigMessage;
import dev.modmind.my_mod.network.AcceleratorShowRangeMessage;
import dev.modmind.my_mod.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 加速方块配置 GUI。
 * 界面中间有两个"滑轮"（加速倍率 / 加速范围），下方有一个
 * "显示加速范围"开关按钮：按下显示蓝色半透明范围框，再按一次隐藏。
 * 支持点击滑轮（左半上一档 / 右半下一档）或滚动鼠标滚轮切换档位。
 */
public class AcceleratorScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 190;

    private static final int COLOR_PANEL = 0xD0101018;
    private static final int COLOR_BORDER = 0xFF33335A;
    private static final int COLOR_ACCENT = 0xFF55FFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUB = 0xFFAAAAAA;

    private final AcceleratorBlockEntity accelerator;

    private OptionWheel multiplierWheel;
    private OptionWheel radiusWheel;
    private ToggleRangeButton toggleButton;

    public AcceleratorScreen(AcceleratorBlockEntity accelerator) {
        super(Component.translatable("gui.my_mod.accelerator.title"));
        this.accelerator = accelerator;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        String[] multiplierOptions = new String[AcceleratorBlockEntity.MULTIPLIERS.length];
        for (int i = 0; i < multiplierOptions.length; i++) {
            multiplierOptions[i] = AcceleratorBlockEntity.MULTIPLIERS[i] + "x";
        }
        String[] radiusOptions = new String[AcceleratorBlockEntity.RADII.length];
        for (int i = 0; i < radiusOptions.length; i++) {
            int size = AcceleratorBlockEntity.RADII[i] * 2 + 1;
            radiusOptions[i] = size + "x" + size + "x" + size;
        }

        this.multiplierWheel = new OptionWheel(left + 20, top + 34, PANEL_WIDTH - 40, 26,
                Component.translatable("gui.my_mod.accelerator.multiplier"),
                multiplierOptions, AcceleratorBlockEntity.MULTIPLIERS,
                this.accelerator.getMultiplier(), this::sendConfig);
        this.radiusWheel = new OptionWheel(left + 20, top + 76, PANEL_WIDTH - 40, 26,
                Component.translatable("gui.my_mod.accelerator.radius"),
                radiusOptions, AcceleratorBlockEntity.RADII,
                this.accelerator.getRadius(), this::sendConfig);
        this.toggleButton = new ToggleRangeButton(left + 20, top + 112, PANEL_WIDTH - 40, 26,
                Component.translatable("gui.my_mod.accelerator.show_range"),
                this.accelerator.shouldShowRange(), this::sendShowRange);

        this.addRenderableWidget(this.multiplierWheel);
        this.addRenderableWidget(this.radiusWheel);
        this.addRenderableWidget(this.toggleButton);
    }

    /** 点击滑轮 / 按钮：左半部分上一档，右半部分下一档；开关按钮直接切换。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.toggleButton.isMouseOver(mouseX, mouseY)) {
                this.toggleButton.toggle();
                return true;
            }
            if (this.multiplierWheel.isMouseOver(mouseX, mouseY)) {
                this.multiplierWheel.clickAt(mouseX);
                return true;
            }
            if (this.radiusWheel.isMouseOver(mouseX, mouseY)) {
                this.radiusWheel.clickAt(mouseX);
                return true;
            }
        }
        return false;
    }

    /** 滚动鼠标滚轮切换档位。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.multiplierWheel.isMouseOver(mouseX, mouseY)) {
            this.multiplierWheel.scroll(delta);
            return true;
        }
        if (this.radiusWheel.isMouseOver(mouseX, mouseY)) {
            this.radiusWheel.scroll(delta);
            return true;
        }
        return false;
    }

    /** 每次调节后立即写入本地方块实体并发送给服务端，保证重开 GUI 时参数不回退。 */
    private void sendConfig() {
        int multiplier = this.multiplierWheel.getValue();
        int radius = this.radiusWheel.getValue();
        this.accelerator.setMultiplier(multiplier);
        this.accelerator.setRadius(radius);
        NetworkHandler.CHANNEL.sendToServer(new AcceleratorConfigMessage(
                this.accelerator.getBlockPos(), multiplier, radius));
    }

    /** 切换显示范围：写入本地方块实体并发送给服务端。 */
    private void sendShowRange() {
        boolean show = this.toggleButton.isToggled();
        this.accelerator.setShowRange(show);
        NetworkHandler.CHANNEL.sendToServer(new AcceleratorShowRangeMessage(
                this.accelerator.getBlockPos(), show));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_PANEL);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, COLOR_ACCENT);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_BORDER);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, COLOR_BORDER);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_BORDER);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 12, COLOR_ACCENT);

        int size = this.radiusWheel.getValue() * 2 + 1;
        Component footer = Component.translatable("gui.my_mod.accelerator.footer",
                this.multiplierWheel.getValue() + "x", size + "x" + size + "x" + size);
        graphics.drawCenteredString(this.font, footer, this.width / 2, top + 150, COLOR_SUB);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.my_mod.accelerator.hint"),
                this.width / 2, top + 172, 0xFF7777AA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 一个可切换档位的"滑轮"控件。 */
    private static class OptionWheel extends AbstractWidget {

        private final Component label;
        private final String[] options;
        private final int[] values;
        private final Runnable onChange;
        private int index;

        OptionWheel(int x, int y, int width, int height, Component label,
                    String[] options, int[] values, int initialValue, Runnable onChange) {
            super(x, y, width, height, label);
            this.label = label;
            this.options = options;
            this.values = values;
            this.onChange = onChange;
            this.index = clampIndex(initialValue);
        }

        private int clampIndex(int value) {
            for (int i = 0; i < this.values.length; i++) {
                if (this.values[i] == value) {
                    return i;
                }
            }
            return 0;
        }

        int getValue() {
            return this.values[this.index];
        }

        /** 点击切换：鼠标在左半部分则上一档，右半部分则下一档。 */
        void clickAt(double mouseX) {
            double midX = this.getX() + this.getWidth() / 2.0;
            this.step(mouseX < midX ? -1 : 1);
        }

        /** 滚轮切换：向上滚动下一档，向下滚动上一档。 */
        void scroll(double delta) {
            this.step(delta > 0 ? 1 : -1);
        }

        private void step(int direction) {
            int next = this.index + direction;
            if (next < 0) {
                next = this.options.length - 1;
            } else if (next >= this.options.length) {
                next = 0;
            }
            if (next != this.index) {
                this.index = next;
                this.onChange.run();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.isHovered = this.isMouseOver(mouseX, mouseY);
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            Font font = Minecraft.getInstance().font;
            int textY = y + (h - font.lineHeight) / 2;

            graphics.fill(x, y, x + w, y + h, this.isHovered ? 0xFF3A3A5A : 0xFF26263C);
            graphics.fill(x, y, x + w, y + 1, this.isHovered ? COLOR_ACCENT : 0xFF44446A);
            graphics.fill(x, y + h - 1, x + w, y + h, 0xFF1C1C2E);
            graphics.fill(x, y, x + 1, y + h, 0xFF44446A);
            graphics.fill(x + w - 1, y, x + w, y + h, 0xFF44446A);

            graphics.drawString(font, "<", x + 8, textY, COLOR_ACCENT);
            graphics.drawString(font, ">", x + w - 12, textY, COLOR_ACCENT);

            graphics.drawString(font, this.label, x + 20, textY, COLOR_SUB);
            graphics.drawCenteredString(font, this.options[this.index],
                    x + w / 2 + 10, textY, this.isHovered ? COLOR_ACCENT : COLOR_TEXT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
        }
    }

    /** 显示加速范围的开关按钮。 */
    private static class ToggleRangeButton extends AbstractWidget {

        private final Runnable onChange;
        private boolean toggled;

        ToggleRangeButton(int x, int y, int width, int height, Component label,
                          boolean toggled, Runnable onChange) {
            super(x, y, width, height, label);
            this.toggled = toggled;
            this.onChange = onChange;
        }

        boolean isToggled() {
            return this.toggled;
        }

        void toggle() {
            this.toggled = !this.toggled;
            this.onChange.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.isHovered = this.isMouseOver(mouseX, mouseY);
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            Font font = Minecraft.getInstance().font;
            int textY = y + (h - font.lineHeight) / 2;

            graphics.fill(x, y, x + w, y + h, this.isHovered ? 0xFF3A3A5A : 0xFF26263C);
            graphics.fill(x, y, x + w, y + 1, this.toggled ? COLOR_ACCENT : 0xFF44446A);
            graphics.fill(x, y + h - 1, x + w, y + h, 0xFF1C1C2E);
            graphics.fill(x, y, x + 1, y + h, 0xFF44446A);
            graphics.fill(x + w - 1, y, x + w, y + h, 0xFF44446A);

            graphics.drawString(font, this.getMessage(), x + 20, textY, COLOR_SUB);
            graphics.drawCenteredString(font,
                    Component.translatable(this.toggled ? "gui.my_mod.accelerator.on" : "gui.my_mod.accelerator.off"),
                    x + w - 30, textY, this.toggled ? COLOR_ACCENT : 0xFF666688);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
        }
    }
}