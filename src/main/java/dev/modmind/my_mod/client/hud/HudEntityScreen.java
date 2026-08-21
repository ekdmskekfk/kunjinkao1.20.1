package dev.modmind.my_mod.client.hud;

import dev.modmind.my_mod.network.HudEntityAction;
import dev.modmind.my_mod.network.HudEntityData;
import dev.modmind.my_mod.network.ManageHudEntityMessage;
import dev.modmind.my_mod.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 38;
    private static final int ROW_HEIGHT = 21;

    private final List<HudEntityData> entities;
    private int selectedIndex = -1;
    private int scrollOffset;

    public HudEntityScreen(List<HudEntityData> entities) {
        super(Component.translatable("screen.my_mod.entity_manager"));
        this.entities = new ArrayList<>(entities);
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
        graphics.drawString(font, Component.translatable("hud.my_mod.entity_count", entities.size()),
                panelX + panelWidth - 70, panelY + 10, 0xFF8FEAFF, false);

        int listTop = panelY + HEADER_HEIGHT;
        int visibleRows = visibleRows();
        for (int row = 0; row < visibleRows; row++) {
            int entityIndex = scrollOffset + row;
            if (entityIndex >= entities.size()) {
                break;
            }
            int rowY = listTop + row * ROW_HEIGHT;
            HudEntityData entity = entities.get(entityIndex);
            boolean selected = entityIndex == selectedIndex;
            boolean hovered = mouseX >= panelX + 4 && mouseX < panelX + panelWidth - 4
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected || hovered) {
                graphics.fill(panelX + 4, rowY, panelX + panelWidth - 4, rowY + ROW_HEIGHT - 1,
                        selected ? 0xB0206B86 : 0x80114157);
            }
            String name = entity.displayName().isBlank()
                    ? Component.translatable(entity.typeTranslationKey()).getString()
                    : entity.displayName();
            String type = Component.translatable(entity.typeTranslationKey()).getString();
            String position = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", entity.x(), entity.y(), entity.z());
            graphics.drawString(font, trim(name, 145), panelX + 10, rowY + 6, 0xFFE9FBFF, false);
            graphics.drawString(font, trim(type, 105), panelX + 158, rowY + 6, 0xFF8FEAFF, false);
            graphics.drawString(font, position, panelX + panelWidth - 104, rowY + 6, 0xFFB9F5FF, false);
        }

        @Nullable HudEntityData selected = selectedEntity();
        String detail = selected == null ? Component.translatable("hud.my_mod.entity_none_selected").getString()
                : selected.dimensionId() + " | " + selected.uuid();
        graphics.drawString(font, trim(detail, panelWidth - 180), panelX + 10, footerY + 5, 0xFF8FEAFF, false);
        drawActionButton(graphics, panelX + panelWidth - 164, footerY + 4, 72, 25,
                Component.translatable("hud.my_mod.entity_kill"), selected != null, mouseX, mouseY, 0xFFB9374B);
        drawActionButton(graphics, panelX + panelWidth - 84, footerY + 4, 72, 25,
                Component.translatable("hud.my_mod.entity_teleport"), selected != null, mouseX, mouseY, 0xFF2894C5);
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
            if (index >= 0 && index < entities.size()) {
                selectedIndex = index;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, entities.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta) * 3));
        return true;
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
            if (selectedIndex >= entities.size()) {
                selectedIndex = entities.size() - 1;
            }
            scrollOffset = Math.min(scrollOffset, Math.max(0, entities.size() - visibleRows()));
        } else if (action == HudEntityAction.TELEPORT) {
            Minecraft.getInstance().setScreen(new HudScreen());
        }
    }

    @Nullable
    private HudEntityData selectedEntity() {
        return selectedIndex >= 0 && selectedIndex < entities.size() ? entities.get(selectedIndex) : null;
    }

    private void requestAction(HudEntityAction action, UUID entityUuid) {
        NetworkHandler.CHANNEL.sendToServer(new ManageHudEntityMessage(action, entityUuid));
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
