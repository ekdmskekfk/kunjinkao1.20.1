package dev.modmind.kunjinkao.client.gui;

import dev.modmind.kunjinkao.network.EnchantPayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 附魔工坊（1.20.1）：从全部已注册附魔里挑一个，选等级，决定做成附魔书还是写到手持物上。
 *
 * <p><b>附魔名字</b>：1.20.1 用 {@code enchantment.<命名空间>.<路径>} 翻译键，
 * 客户端语言是中文时就是「锋利」「保护」这些原名；只有真缺翻译时才退回显示注册 id。
 * 显示名带等级用 {@link Enchantment#getFullname(int)}，它自带罗马数字，与物品提示一致。</p>
 *
 * <p><b>与 1.21.1 的差异只在数据来源</b>：那边是 {@code Holder} + 注册表访问器，
 * 这边是 {@link ForgeRegistries#ENCHANTMENTS} 的普通对象。界面绘制逻辑两边一致，
 * 因为 UI 原语是同一套。</p>
 */
public final class EnchantScreen extends Screen {

    private static final int ROW_H = 12;
    private static final int LIST_H = 150;
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 236;

    private final Screen parent;

    private final List<Enchantment> entries = new ArrayList<>();
    private int scroll;
    private int selected = -1;
    private int level = 1;
    private int mode = EnchantPayload.MODE_BOOK;

    private int panelX;
    private int panelY;
    private int listTop;
    private int listLeft;
    private int listWidth;

    private Button applyButton;

    public EnchantScreen(Screen parent) {
        super(Component.translatable("screen.kunjinkao.enchant.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelX = (width - PANEL_W) / 2;
        this.panelY = (height - PANEL_H) / 2;
        this.listLeft = panelX + 12;
        this.listWidth = PANEL_W - 24;
        this.listTop = panelY + 26;
        collectEnchantments();

        int footerY = panelY + 26 + LIST_H + 10;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> level = Math.max(1, level - 1))
                .bounds(panelX + 12, footerY, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> level = Math.min(255, level + 1))
                .bounds(panelX + 36, footerY, 20, 18).build());
        addRenderableWidget(Button.builder(modeLabel(), b -> {
            mode = mode == EnchantPayload.MODE_BOOK ? EnchantPayload.MODE_ITEM : EnchantPayload.MODE_BOOK;
            b.setMessage(modeLabel());
        }).bounds(panelX + 76, footerY, 150, 18).build());
        applyButton = addRenderableWidget(Button.builder(Component.translatable("screen.kunjinkao.enchant.apply"),
                b -> apply()).bounds(panelX + 234, footerY, 134, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX + PANEL_W - 96, panelY + PANEL_H - 26, 84, 18).build());
        updateApplyState();
    }

    /** 按显示名排序：按注册顺序排的话玩家根本找不到想要的附魔。 */
    private void collectEnchantments() {
        entries.clear();
        entries.addAll(ForgeRegistries.ENCHANTMENTS.getValues());
        entries.sort(Comparator.comparing(this::displayName, String.CASE_INSENSITIVE_ORDER));
    }

    private String displayName(Enchantment enchantment) {
        String text = Component.translatable(enchantment.getDescriptionId()).getString();
        if (text.startsWith("enchantment.")) {
            ResourceLocation key = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
            if (key != null) {
                return key.getPath().replace('_', ' ');
            }
        }
        return text;
    }

    private Component modeLabel() {
        return Component.translatable(mode == EnchantPayload.MODE_BOOK
                ? "screen.kunjinkao.enchant.mode_book"
                : "screen.kunjinkao.enchant.mode_item");
    }

    private void updateApplyState() {
        if (applyButton != null) {
            applyButton.active = selected >= 0;
        }
    }

    private void apply() {
        if (selected < 0 || selected >= entries.size()) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(entries.get(selected));
        if (id == null) {
            return;
        }
        NetworkHandler.sendToServer(new EnchantPayload(id.toString(), level, mode));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 的 Screen#render 不会自己调 renderBackground，必须在这里显式画。
        renderBackground(graphics);
        KunJinKaoUi.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        KunJinKaoUi.titleBar(graphics, font, title, panelX, panelY, PANEL_W);
        drawList(graphics, mouseX, mouseY);
        drawFooter(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        int visibleRows = LIST_H / ROW_H;
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = 0; i < visibleRows; i++) {
            int index = scroll + i;
            if (index >= entries.size()) {
                break;
            }
            int rowY = listTop + i * ROW_H;
            boolean hovered = mouseX >= listLeft && mouseX < listLeft + listWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            KunJinKaoUi.rowBackground(graphics, listLeft, rowY, listWidth, ROW_H, hovered);
            if (index == selected) {
                graphics.fill(listLeft, rowY, listLeft + listWidth, rowY + ROW_H, 0x4057CFFF);
                graphics.fill(listLeft, rowY, listLeft + 2, rowY + ROW_H, KunJinKaoUi.BORDER_GLOW);
            }
            graphics.drawString(font, KunJinKaoUi.fit(font, displayName(entries.get(index)), listWidth - 10),
                    listLeft + 5, rowY + 2,
                    index == selected ? KunJinKaoUi.TEXT_TITLE : KunJinKaoUi.TEXT_LABEL, false);
        }
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.kunjinkao.enchant.empty"),
                    listLeft + 5, listTop + 4, KunJinKaoUi.TEXT_MUTED, false);
        }
        if (maxScroll > 0) {
            int trackX = listLeft + listWidth + 2;
            graphics.fill(trackX, listTop, trackX + 2, listTop + LIST_H, 0x30FFFFFF);
            int thumbH = Math.max(10, LIST_H * visibleRows / entries.size());
            int thumbY = listTop + (LIST_H - thumbH) * scroll / maxScroll;
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, KunJinKaoUi.BORDER_INNER);
        }
    }

    private void drawFooter(GuiGraphics graphics) {
        int footerY = panelY + 26 + LIST_H + 10;
        String selectedName = selected >= 0 && selected < entries.size()
                ? displayName(entries.get(selected))
                : Component.translatable("screen.kunjinkao.enchant.none").getString();
        graphics.drawString(font,
                Component.translatable("screen.kunjinkao.enchant.level").getString() + " " + level,
                panelX + 60, footerY + 5, KunJinKaoUi.TEXT_TITLE, false);
        graphics.drawString(font, KunJinKaoUi.fit(font, selectedName, 120),
                panelX + 12, footerY + 24, KunJinKaoUi.TEXT_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listLeft && mouseX < listLeft + listWidth
                && mouseY >= listTop && mouseY < listTop + LIST_H) {
            int row = (int) ((mouseY - listTop) / ROW_H);
            int index = scroll + row;
            if (index >= 0 && index < entries.size()) {
                selected = index;
                updateApplyState();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int visibleRows = LIST_H / ROW_H;
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 265) {
            selected = Math.max(0, selected - 1);
            ensureVisible();
            updateApplyState();
            return true;
        }
        if (keyCode == 264) {
            selected = Math.min(entries.size() - 1, selected + 1);
            ensureVisible();
            updateApplyState();
            return true;
        }
        if (keyCode == 257 && selected >= 0) {
            apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void ensureVisible() {
        int visibleRows = LIST_H / ROW_H;
        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + visibleRows) {
            scroll = selected - visibleRows + 1;
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
