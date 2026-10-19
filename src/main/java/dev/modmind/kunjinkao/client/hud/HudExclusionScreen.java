package dev.modmind.kunjinkao.client.hud;

import dev.modmind.kunjinkao.network.ExcludedPlayerData;
import dev.modmind.kunjinkao.network.ManageExcludedPlayerPayload;
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

/**
 * 战术 HUD 的「排除列表」：显示被终极死亡排除、当前无法进入服务器的玩家。
 * 在这里解除之后，该玩家才能重新登录。
 */
public final class HudExclusionScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int HEADER_HEIGHT = 58;
    private static final int FOOTER_HEIGHT = 38;
    private static final int ROW_HEIGHT = 21;

    private final List<ExcludedPlayerData> players;
    private final List<ExcludedPlayerData> filteredPlayers = new ArrayList<>();
    private UUID selectedPlayerUuid;
    private int scrollOffset;
    private EditBox searchBox;

    public HudExclusionScreen(List<ExcludedPlayerData> players) {
        super(Component.translatable("screen.kunjinkao.exclusion_list"));
        this.players = new ArrayList<>(players);
        this.filteredPlayers.addAll(players);
    }

    @Override
    protected void init() {
        int panelX = panelX();
        int panelY = MARGIN;
        searchBox = new EditBox(font, panelX + 10, panelY + 30, panelWidth() - 20, 18,
                Component.translatable("hud.kunjinkao.exclusion_search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("hud.kunjinkao.exclusion_search_hint"));
        searchBox.setResponder(this::filterPlayers);
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
        graphics.drawString(font, Component.translatable("hud.kunjinkao.exclusion_count",
                        filteredPlayers.size() + "/" + players.size()),
                panelX + panelWidth - 82, panelY + 10, 0xFF8FEAFF, false);
        if (searchBox != null) {
            searchBox.render(graphics, mouseX, mouseY, partialTick);
        }

        int listTop = panelY + HEADER_HEIGHT;
        int visibleRows = visibleRows();
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= filteredPlayers.size()) {
                break;
            }
            int rowY = listTop + row * ROW_HEIGHT;
            ExcludedPlayerData entry = filteredPlayers.get(index);
            boolean selected = entry.uuid().equals(selectedPlayerUuid);
            boolean hovered = mouseX >= panelX + 4 && mouseX < panelX + panelWidth - 4
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected || hovered) {
                graphics.fill(panelX + 4, rowY, panelX + panelWidth - 4, rowY + ROW_HEIGHT - 1,
                        selected ? 0xB0206B86 : 0x80114157);
            }
            String name = entry.name().isBlank() ? entry.uuid().toString() : entry.name();
            graphics.drawString(font, trim(name, 140), panelX + 10, rowY + 6, 0xFFE9FBFF, false);
            graphics.drawString(font, entry.uuid().toString(), panelX + 158, rowY + 6, 0xFF8FEAFF, false);
        }
        if (filteredPlayers.isEmpty()) {
            graphics.drawString(font, Component.translatable("hud.kunjinkao.exclusion_empty"),
                    panelX + 10, listTop + 6, 0xFF8FEAFF, false);
        }

        @Nullable ExcludedPlayerData selected = selectedPlayer();
        String detail = selected == null
                ? Component.translatable("hud.kunjinkao.exclusion_none_selected").getString()
                : selected.name().isBlank() ? selected.uuid().toString() : selected.name();
        graphics.drawString(font, trim(detail, panelWidth - 120), panelX + 10, footerY + 5, 0xFF8FEAFF, false);
        drawActionButton(graphics, panelX + panelWidth - 84, footerY + 4, 72, 25,
                Component.translatable("hud.kunjinkao.exclusion_remove"), selected != null, mouseX, mouseY, 0xFF2894C5);
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
            if (index >= 0 && index < filteredPlayers.size()) {
                selectedPlayerUuid = filteredPlayers.get(index).uuid();
                return true;
            }
        }
        ExcludedPlayerData selected = selectedPlayer();
        if (selected != null && mouseY >= footerY + 4 && mouseY < footerY + 29
                && mouseX >= panelX + panelWidth - 84 && mouseX < panelX + panelWidth - 12) {
            NetworkHandler.sendToServer(new ManageExcludedPlayerPayload(selected.uuid()));
            return true;
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
        int maxScroll = Math.max(0, filteredPlayers.size() - visibleRows());
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

    /** 用服务端回传的权威名单整体替换本地列表。 */
    public void replacePlayers(List<ExcludedPlayerData> updated) {
        players.clear();
        players.addAll(updated);
        filterPlayers(searchBox == null ? "" : searchBox.getValue());
    }

    @Nullable
    private ExcludedPlayerData selectedPlayer() {
        if (selectedPlayerUuid == null) {
            return null;
        }
        for (ExcludedPlayerData entry : filteredPlayers) {
            if (entry.uuid().equals(selectedPlayerUuid)) {
                return entry;
            }
        }
        return null;
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

    /** 搜索只在客户端过滤；解除请求始终由服务端按 UUID 重新校验并执行。 */
    private void filterPlayers(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filteredPlayers.clear();
        for (ExcludedPlayerData entry : players) {
            String searchable = (entry.name() + " " + entry.uuid()).toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || searchable.contains(needle)) {
                filteredPlayers.add(entry);
            }
        }
        if (selectedPlayer() == null) {
            selectedPlayerUuid = null;
        }
        // 过滤后不能把 scrollOffset 直接归零：否则每敲一个字符列表都跳回顶部。
        // 只夹紧到合法范围，玩家原本的滚动位置在结果集仍够长时得以保留。
        int maxScroll = Math.max(0, filteredPlayers.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
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