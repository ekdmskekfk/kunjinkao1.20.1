package dev.modmind.kunjinkao.client.hud;

import dev.modmind.kunjinkao.network.HudEntityAction;
import dev.modmind.kunjinkao.network.HudEntityData;
import dev.modmind.kunjinkao.network.ManageHudEntityPayload;
import dev.modmind.kunjinkao.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 管理员实体列表：展示服务端同步的所有已加载维度实体。 */
public final class HudEntityScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int HEADER_HEIGHT = 58;
    private static final int FOOTER_HEIGHT = 38;
    private static final int ROW_HEIGHT = 21;

    private final List<HudEntityData> entities;
    private final List<HudEntityData> filteredEntities = new ArrayList<>();
    // 行显示文本缓存：Component.translatable(...).getString() 与坐标 String.format 只在
    // 数据或过滤条件变化时算一次，否则列表打开期间每帧每行都要重算一遍。
    private final List<RowText> rowTexts = new ArrayList<>();
    private UUID selectedEntityUuid;
    private int scrollOffset;
    private EditBox searchBox;

    public HudEntityScreen(List<HudEntityData> entities) {
        super(Component.translatable("screen.kunjinkao.entity_manager"));
        this.entities = new ArrayList<>(entities);
        this.filteredEntities.addAll(entities);
        rebuildRowTexts();
    }

    /** 一行预先算好的显示文本（与 filteredEntities 下标一一对应）。 */
    private record RowText(String name, String type, String position) {
    }

    @Override
    protected void init() {
        int panelX = panelX();
        int panelY = MARGIN;
        searchBox = new EditBox(font, panelX + 10, panelY + 30, panelWidth() - 20, 18,
                Component.translatable("hud.kunjinkao.entity_search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("hud.kunjinkao.entity_search_hint"));
        searchBox.setResponder(this::filterEntities);
        addRenderableWidget(searchBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x8A000000);
        int panelX = panelX();
        int panelY = MARGIN;
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int footerY = panelY + panelHeight - FOOTER_HEIGHT;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE0082030);
        drawBorder(graphics, panelX, panelY, panelWidth, panelHeight, 0xFF57CFFF);
        graphics.drawString(font, title, panelX + 10, panelY + 10, 0xFFE9FBFF, false);
        graphics.drawString(font, Component.translatable("hud.kunjinkao.entity_count",
                        filteredEntities.size() + "/" + entities.size()),
                panelX + panelWidth - 82, panelY + 10, 0xFF8FEAFF, false);
        if (searchBox != null) {
            searchBox.render(graphics, mouseX, mouseY, partialTick);
        }

        int listTop = panelY + HEADER_HEIGHT;
        int visibleRows = visibleRows();
        for (int row = 0; row < visibleRows; row++) {
            int entityIndex = scrollOffset + row;
            if (entityIndex >= filteredEntities.size()) {
                break;
            }
            int rowY = listTop + row * ROW_HEIGHT;
            HudEntityData entity = filteredEntities.get(entityIndex);
            boolean selected = entity.uuid().equals(selectedEntityUuid);
            boolean hovered = mouseX >= panelX + 4 && mouseX < panelX + panelWidth - 4
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected || hovered) {
                graphics.fill(panelX + 4, rowY, panelX + panelWidth - 4, rowY + ROW_HEIGHT - 1,
                        selected ? 0xB0206B86 : 0x80114157);
            }
            RowText text = rowTexts.get(entityIndex);
            graphics.drawString(font, trim(text.name(), 145), panelX + 10, rowY + 6, 0xFFE9FBFF, false);
            graphics.drawString(font, trim(text.type(), 105), panelX + 158, rowY + 6, 0xFF8FEAFF, false);
            // 坐标列此前是唯一没有做宽度裁剪的列：坐标量级大时（如 30000000.0, ...）会画出面板外。
            // 该列起点是 panelX+panelWidth-104，到面板右内边只剩约 100px 可用宽度。
            graphics.drawString(font, trim(text.position(), 100), panelX + panelWidth - 104, rowY + 6, 0xFFB9F5FF, false);
        }

        @Nullable HudEntityData selected = selectedEntity();
        String detail = selected == null ? Component.translatable("hud.kunjinkao.entity_none_selected").getString()
                : selected.dimensionId() + " | " + selected.uuid();
        graphics.drawString(font, trim(detail, panelWidth - 180), panelX + 10, footerY + 5, 0xFF8FEAFF, false);
        drawActionButton(graphics, panelX + panelWidth - 164, footerY + 4, 72, 25,
                Component.translatable("hud.kunjinkao.entity_kill"), selected != null, mouseX, mouseY, 0xFFB9374B);
        drawActionButton(graphics, panelX + panelWidth - 84, footerY + 4, 72, 25,
                Component.translatable("hud.kunjinkao.entity_teleport"), selected != null, mouseX, mouseY, 0xFF2894C5);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int panelX = panelX();
        int panelY = MARGIN;
        int panelWidth = panelWidth();
        int footerY = panelY + panelHeight() - FOOTER_HEIGHT;
        int listTop = panelY + HEADER_HEIGHT;
        if (mouseX >= panelX + 4 && mouseX < panelX + panelWidth - 4 && mouseY >= listTop && mouseY < footerY - 4) {
            int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
            int index = scrollOffset + row;
            if (index >= 0 && index < filteredEntities.size()) {
                selectedEntityUuid = filteredEntities.get(index).uuid();
                return true;
            }
        }

        HudEntityData selected = selectedEntity();
        if (selected != null && mouseY >= footerY + 4 && mouseY < footerY + 29) {
            if (mouseX >= panelX + panelWidth - 164 && mouseX < panelX + panelWidth - 92) {
                requestAction(HudEntityAction.KILL, selected.uuid());
                return true;
            }
            if (mouseX >= panelX + panelWidth - 84 && mouseX < panelX + panelWidth - 12) {
                requestAction(HudEntityAction.TELEPORT, selected.uuid());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (HudScreen.handleToggleKeyPressed(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        // 只在指针确实位于列表区域时滚动：否则（例如指针停在搜索框上）滚轮事件应交给上层，
        // 而不是被列表无条件吞掉。
        if (!isOverListArea(mouseX, mouseY) || deltaY == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, deltaY);
        }
        int maxScroll = Math.max(0, filteredEntities.size() - visibleRows());
        // 按 deltaY 的方向滚动一行（正值为向上滚，列表向前）。
        int step = deltaY > 0.0D ? -1 : 1;
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + step));
        return true;
    }

    /** 指针是否位于可滚动的列表区域（与 mouseClicked 的命中范围保持一致）。 */
    private boolean isOverListArea(double mouseX, double mouseY) {
        int panelX = panelX();
        int listTop = MARGIN + HEADER_HEIGHT;
        int footerY = MARGIN + panelHeight() - FOOTER_HEIGHT;
        return mouseX >= panelX + 4 && mouseX < panelX + panelWidth() - 4
                && mouseY >= listTop && mouseY < footerY - 4;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new HudScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void applyActionResult(HudEntityAction action, UUID entityUuid, boolean success) {
        if (!success) {
            return;
        }
        if (action == HudEntityAction.KILL) {
            entities.removeIf(entity -> entity.uuid().equals(entityUuid));
            filterEntities(searchBox == null ? "" : searchBox.getValue());
        } else if (action == HudEntityAction.TELEPORT) {
            Minecraft.getInstance().setScreen(new HudScreen());
        }
    }

    /**
     * 就地替换实体数据（服务端刷新列表时使用）：保留选中项、搜索词与滚动位置，
     * 避免每次刷新都重建界面（重建会丢掉玩家正在浏览的位置）。
     */
    public void replaceEntities(List<HudEntityData> updated) {
        entities.clear();
        entities.addAll(updated);
        filterEntities(searchBox == null ? "" : searchBox.getValue());
    }

    @Nullable
    private HudEntityData selectedEntity() {
        if (selectedEntityUuid == null) {
            return null;
        }
        for (HudEntityData entity : filteredEntities) {
            if (entity.uuid().equals(selectedEntityUuid)) {
                return entity;
            }
        }
        return null;
    }

    private void requestAction(HudEntityAction action, UUID entityUuid) {
        NetworkHandler.sendToServer(new ManageHudEntityPayload(action, entityUuid));
    }

    private int panelX() {
        return (width - panelWidth()) / 2;
    }

    private int panelWidth() {
        return Math.min(440, width - MARGIN * 2);
    }

    private int panelHeight() {
        return height - MARGIN * 2;
    }

    private int visibleRows() {
        return Math.max(1, (panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT - 4) / ROW_HEIGHT);
    }

    /**
     * Search is deliberately client-side: the server still supplies the list and
     * still validates every kill and teleport request by UUID.
     */
    private void filterEntities(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filteredEntities.clear();
        for (HudEntityData entity : entities) {
            String translatedType = Component.translatable(entity.typeTranslationKey()).getString();
            String searchable = (entity.displayName() + " " + entity.typeTranslationKey() + " "
                    + translatedType + " " + entity.dimensionId() + " " + entity.uuid())
                    .toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || searchable.contains(needle)) {
                filteredEntities.add(entity);
            }
        }
        if (selectedEntity() == null) {
            selectedEntityUuid = null;
        }
        // 过滤后不能把 scrollOffset 直接归零：否则每敲一个字符列表都跳回顶部。
        // 只夹紧到合法范围，玩家原本的滚动位置在结果集仍够长时得以保留。
        int maxScroll = Math.max(0, filteredEntities.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        rebuildRowTexts();
    }

    /** 在过滤结果变化后重建行文本缓存（渲染时直接取用，不再每帧做翻译与格式化）。 */
    private void rebuildRowTexts() {
        rowTexts.clear();
        for (HudEntityData entity : filteredEntities) {
            String translatedType = Component.translatable(entity.typeTranslationKey()).getString();
            String name = entity.displayName().isBlank() ? translatedType : entity.displayName();
            String position = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", entity.x(), entity.y(), entity.z());
            rowTexts.add(new RowText(name, translatedType, position));
        }
    }

    private String trim(String text, int maxWidth) {
        return font.plainSubstrByWidth(text, Math.max(10, maxWidth));
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private void drawActionButton(GuiGraphics graphics, int x, int y, int width, int height, Component text,
                                  boolean enabled, int mouseX, int mouseY, int color) {
        boolean hovered = enabled && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        int fill = enabled ? (hovered ? 0xE0FFFFFF & color : 0xA0FFFFFF & color) : 0x66333333;
        graphics.fill(x, y, x + width, y + height, fill);
        drawBorder(graphics, x, y, width, height, enabled ? color : 0xFF666666);
        int textColor = enabled ? 0xFFFFFFFF : 0xFF999999;
        graphics.drawCenteredString(font, text, x + width / 2, y + 8, textColor);
    }
}
