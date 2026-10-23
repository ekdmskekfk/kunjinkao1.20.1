package dev.modmind.kunjinkao.client.gui;

import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import dev.modmind.kunjinkao.network.AcceleratorConfigPayload;
import dev.modmind.kunjinkao.network.AcceleratorFilterPayload;
import dev.modmind.kunjinkao.network.AcceleratorShowRangePayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 加速方块配置 GUI。
 * 界面中间有两个"滑轮"（加速倍率 / 加速范围），下方有一个
 * "显示加速范围"开关按钮：按下显示蓝色半透明范围框，再按一次隐藏。
 * 支持点击滑轮（左半上一档 / 右半下一档）或滚动鼠标滚轮切换档位。
 */
public class AcceleratorScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    // 加高面板以容纳底部的过滤区（9 个格位 + 两个开关），原来的 190 排不下
    private static final int PANEL_HEIGHT = 244;

    /** 过滤格位边长与间距。 */
    private static final int FILTER_SLOT_SIZE = 18;
    private static final int FILTER_SLOT_STEP = 20;

    /** 过滤区尺寸：9 格一行。 */
    private static final int FILTER_ROW_WIDTH = AcceleratorBlockEntity.FILTER_SLOTS * FILTER_SLOT_STEP - 2;

    private static final int COLOR_PANEL = 0xD0101018;
    private static final int COLOR_BORDER = 0xFF33335A;
    private static final int COLOR_ACCENT = 0xFF55FFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUB = 0xFFAAAAAA;

    private final AcceleratorBlockEntity accelerator;

    private OptionWheel multiplierWheel;
    private OptionWheel radiusWheel;
    private ToggleRangeButton toggleButton;
    private DualLabelToggle whitelistToggle;
    private DualLabelToggle nbtToggle;
    /** 过滤列表的本地草稿：JEI 拖入或点击清除都改它，然后整份发回服务端。 */
    /** 上一次 render 时的鼠标坐标（1.20.1 的 renderBackground 拿不到，只能这样带过去）。 */
    private int hoverMouseX = -1;
    private int hoverMouseY = -1;

    private final List<ItemStack> filterDraft = new ArrayList<>();

    public AcceleratorScreen(AcceleratorBlockEntity accelerator) {
        super(Component.translatable("gui.kunjinkao.accelerator.title"));
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
                Component.translatable("gui.kunjinkao.accelerator.multiplier"),
                multiplierOptions, AcceleratorBlockEntity.MULTIPLIERS,
                this.accelerator.getMultiplier(), this::sendConfig);
        this.radiusWheel = new OptionWheel(left + 20, top + 76, PANEL_WIDTH - 40, 26,
                Component.translatable("gui.kunjinkao.accelerator.radius"),
                radiusOptions, AcceleratorBlockEntity.RADII,
                this.accelerator.getRadius(), this::sendConfig);
        this.toggleButton = new ToggleRangeButton(left + 20, top + 112, PANEL_WIDTH - 40, 26,
                Component.translatable("gui.kunjinkao.accelerator.show_range"),
                this.accelerator.shouldShowRange(), this::sendShowRange);

        this.filterDraft.clear();
        for (int slot = 0; slot < AcceleratorBlockEntity.FILTER_SLOTS; slot++) {
            this.filterDraft.add(this.accelerator.getFilterEntry(slot));
        }
        int toggleWidth = (PANEL_WIDTH - 40 - 8) / 2;
        this.whitelistToggle = new DualLabelToggle(left + 20, top + 184, toggleWidth, 22,
                Component.translatable("gui.kunjinkao.accelerator.filter_whitelist"),
                Component.translatable("gui.kunjinkao.accelerator.filter_blacklist"),
                this.accelerator.isWhitelist(), this::sendFilter);
        this.nbtToggle = new DualLabelToggle(left + 20 + toggleWidth + 8, top + 184, toggleWidth, 22,
                Component.translatable("gui.kunjinkao.accelerator.filter_match_nbt"),
                Component.translatable("gui.kunjinkao.accelerator.filter_ignore_nbt"),
                this.accelerator.isMatchNbt(), this::sendFilter);

        this.addRenderableWidget(this.multiplierWheel);
        this.addRenderableWidget(this.radiusWheel);
        this.addRenderableWidget(this.toggleButton);
        this.addRenderableWidget(this.whitelistToggle);
        this.addRenderableWidget(this.nbtToggle);
    }

    /** 第 slot 个过滤格位的屏幕矩形（JEI 用它做拖拽目标高亮，也用于点击命中）。 */
    public Rect2i filterSlotArea(int slot) {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int rowLeft = left + (PANEL_WIDTH - FILTER_ROW_WIDTH) / 2;
        return new Rect2i(rowLeft + slot * FILTER_SLOT_STEP, top + 158, FILTER_SLOT_SIZE, FILTER_SLOT_SIZE);
    }

    /**
     * 由 JEI 拖拽处理器调用：把拖进来的方块放进指定格位。
     * 只接受方块物品（与服务端校验一致），数量一律按 1 处理。
     */
    public void acceptFilterDrop(int slot, ItemStack stack) {
        if (slot < 0 || slot >= AcceleratorBlockEntity.FILTER_SLOTS || stack.isEmpty()) {
            return;
        }
        ItemStack entry = stack.copy();
        entry.setCount(1);
        this.filterDraft.set(slot, entry);
        this.sendFilter();
    }

    /** 点击滑轮 / 按钮：左半部分上一档，右半部分下一档；开关按钮直接切换。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.toggleButton.isMouseOver(mouseX, mouseY)) {
                this.toggleButton.toggle();
                return true;
            }
            if (this.whitelistToggle.isMouseOver(mouseX, mouseY)) {
                this.whitelistToggle.toggle();
                return true;
            }
            if (this.nbtToggle.isMouseOver(mouseX, mouseY)) {
                this.nbtToggle.toggle();
                return true;
            }
            // 左键点已占用的过滤格位 = 清空该格（JEI 拖入是另一条路径）
            for (int slot = 0; slot < AcceleratorBlockEntity.FILTER_SLOTS; slot++) {
                Rect2i area = filterSlotArea(slot);
                if (mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                        && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight()) {
                    if (!this.filterDraft.get(slot).isEmpty()) {
                        this.filterDraft.set(slot, ItemStack.EMPTY);
                        this.sendFilter();
                    }
                    return true;
                }
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
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        if (this.multiplierWheel.isMouseOver(mouseX, mouseY)) {
            this.multiplierWheel.scroll(deltaY);
            return true;
        }
        if (this.radiusWheel.isMouseOver(mouseX, mouseY)) {
            this.radiusWheel.scroll(deltaY);
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
        NetworkHandler.sendToServer(new AcceleratorConfigPayload(
                this.accelerator.getBlockPos(), multiplier, radius));
    }

    /** 过滤状态有改动时：写回本地 BE（供重开界面时显示）并把整份状态发给服务端。 */
    private void sendFilter() {
        boolean whitelist = this.whitelistToggle.isToggled();
        boolean matchNbt = this.nbtToggle.isToggled();
        this.accelerator.applyFilter(this.filterDraft, whitelist, matchNbt);
        NetworkHandler.sendToServer(new AcceleratorFilterPayload(
                this.accelerator.getBlockPos(), new ArrayList<>(this.filterDraft), whitelist, matchNbt));
    }

    /** 切换显示范围：写入本地方块实体并发送给服务端。 */
    private void sendShowRange() {
        boolean show = this.toggleButton.isToggled();
        this.accelerator.setShowRange(show);
        NetworkHandler.sendToServer(new AcceleratorShowRangePayload(
                this.accelerator.getBlockPos(), show));
    }

    /**
     * 1.21.1 的 Screen.render 第一步就会调用 renderBackground，随后才画控件。
     * 因此面板与标题必须画在这里（即 super.renderBackground 之后）：这是唯一能同时满足
     * "背景只画一遍"与"背景 → 面板/标题 → 控件"正确顺序的位置。
     * 若把面板画到 render 里 super.render 之后，alpha 0xD0 的面板会盖住滑轮与开关按钮；
     * 画到 super.render 之前则会被背景纹理压暗（即原来的 bug）。
     */
    /** 面板/标题/文字：1.20.1 的 Screen#render 不会调用 renderBackground，必须由 render 自己调。 */
    private void drawChrome(GuiGraphics graphics) {
        // 手动调用 renderBackground 已删除：super.render 自带背景，这里只保留唯一的一次背景绘制
        super.renderBackground(graphics);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        KunJinKaoUi.panel(graphics, left, top, PANEL_WIDTH, PANEL_HEIGHT);
        
        
        
        

        KunJinKaoUi.titleBar(graphics, this.font, this.title, left, top, PANEL_WIDTH);

        // ===== 过滤区 =====
        graphics.drawString(this.font, Component.translatable("gui.kunjinkao.accelerator.filter"),
                left + 20, top + 146, COLOR_ACCENT);
        boolean hasEntries = false;
        for (int slot = 0; slot < AcceleratorBlockEntity.FILTER_SLOTS; slot++) {
            ItemStack entry = this.filterDraft.get(slot);
            if (!entry.isEmpty()) {
                hasEntries = true;
            }
            Rect2i area = filterSlotArea(slot);
            boolean hovered = this.hoverMouseX >= area.getX() && this.hoverMouseX < area.getX() + area.getWidth()
                    && this.hoverMouseY >= area.getY() && this.hoverMouseY < area.getY() + area.getHeight();
            graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(),
                    area.getY() + area.getHeight(), hovered ? 0xFF2C2C48 : 0xFF1A1A2A);
            graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + 1,
                    entry.isEmpty() ? COLOR_BORDER : COLOR_ACCENT);
            if (!entry.isEmpty()) {
                graphics.renderItem(entry, area.getX() + 1, area.getY() + 1);
            }
        }
        if (!hasEntries) {
            // 列表为空 = 不过滤，明确写出来免得以为配置没生效
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.kunjinkao.accelerator.filter_none"),
                    this.width / 2, top + 232, 0xFF7777AA);
        }

        int size = this.radiusWheel.getValue() * 2 + 1;
        Component footer = Component.translatable("gui.kunjinkao.accelerator.footer",
                this.multiplierWheel.getValue() + "x", size + "x" + size + "x" + size);
        graphics.drawCenteredString(this.font, footer, this.width / 2, top + 212, COLOR_SUB);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.kunjinkao.accelerator.hint"),
                this.width / 2, top + 226, 0xFF7777AA);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 背景与控件都由 super.render 负责（背景只画这一遍）；面板绘制见上面的 renderBackground
        // 1.20.1 的 renderBackground 只有 (GuiGraphics) 一个参数，拿不到鼠标坐标；
        // 而面板里的悬停高亮要它 —— 所以在 render 里记下来，背景绘制时读这份副本。
        this.hoverMouseX = mouseX;
        this.hoverMouseY = mouseY;
        drawChrome(graphics);
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

    /** 双标签开关：按钮文字随状态切换（白名单/黑名单、匹配 NBT/不匹配 NBT）。 */
    private static class DualLabelToggle extends AbstractWidget {

        private final Component labelOn;
        private final Component labelOff;
        private final Runnable onChange;
        private boolean toggled;

        DualLabelToggle(int x, int y, int width, int height, Component labelOn, Component labelOff,
                        boolean toggled, Runnable onChange) {
            super(x, y, width, height, toggled ? labelOn : labelOff);
            this.labelOn = labelOn;
            this.labelOff = labelOff;
            this.toggled = toggled;
            this.onChange = onChange;
        }

        boolean isToggled() {
            return this.toggled;
        }

        void toggle() {
            this.toggled = !this.toggled;
            this.setMessage(this.toggled ? this.labelOn : this.labelOff);
            this.onChange.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.isHovered = this.isMouseOver(mouseX, mouseY);
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            graphics.fill(x, y, x + w, y + h, this.isHovered ? 0xFF3A3A5A : 0xFF26263C);
            graphics.fill(x, y, x + w, y + 1, this.toggled ? COLOR_ACCENT : 0xFF44446A);
            graphics.fill(x, y + h - 1, x + w, y + h, 0xFF1C1C2E);
            graphics.fill(x, y, x + 1, y + h, 0xFF44446A);
            graphics.fill(x + w - 1, y, x + w, y + h, 0xFF44446A);
            Font font = Minecraft.getInstance().font;
            graphics.drawCenteredString(font, this.getMessage(), x + w / 2, y + (h - font.lineHeight) / 2,
                    this.toggled ? COLOR_ACCENT : COLOR_SUB);
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
                    Component.translatable(this.toggled ? "gui.kunjinkao.accelerator.on" : "gui.kunjinkao.accelerator.off"),
                    x + w - 30, textY, this.toggled ? COLOR_ACCENT : 0xFF666688);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
        }
    }
}
