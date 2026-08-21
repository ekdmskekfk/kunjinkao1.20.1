package dev.modmind.my_mod.client.hud;

import dev.modmind.my_mod.client.ClientHudState;
import dev.modmind.my_mod.client.function.HudFunction;
import dev.modmind.my_mod.client.function.HudFunctionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Client-only 2D 功能栏；菜单交互将在后续 HudScreen 阶段接入。 */
public final class HudOverlayRenderer {

    private static final HudFunctionButton[] BUTTONS = {
            new HudFunctionButton(HudFunction.SCAN),
            new HudFunctionButton(HudFunction.TARGET_LOCK),
            new HudFunctionButton(HudFunction.ENTITY_INFO),
            new HudFunctionButton(HudFunction.NIGHT_VISION)
    };
    private static final int MARGIN = 8;
    private static final int WIDTH = 112;
    private static final int HEIGHT = 27;
    private static final int GAP = 5;

    private HudOverlayRenderer() {
    }

    public static void render(GuiGraphics graphics, float partialTick, int guiWidth, int guiHeight) {
        if (!ClientHudState.isVisible() || Minecraft.getInstance().screen != null) {
            return;
        }

        float progress = ease(ClientHudState.getAnimationProgress());
        renderMenu(graphics, guiWidth, guiHeight, -1, -1, progress);
    }

    /** 由 HudScreen 调用，用于绘制可鼠标悬停的透明菜单。 */
    public static void renderInteractive(GuiGraphics graphics, int guiWidth, int guiHeight, int mouseX, int mouseY) {
        if (!ClientHudState.isVisible()) {
            return;
        }
        renderMenu(graphics, guiWidth, guiHeight, mouseX, mouseY, ease(ClientHudState.getAnimationProgress()));
    }

    public static HudFunction getFunctionAt(int guiWidth, int guiHeight, double mouseX, double mouseY) {
        float progress = ease(ClientHudState.getAnimationProgress());
        int x = menuX(progress);
        int y = menuY(guiHeight);
        for (HudFunctionButton button : BUTTONS) {
            if (mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT) {
                return button.function();
            }
            y += HEIGHT + GAP;
        }
        return null;
    }

    private static void renderMenu(GuiGraphics graphics, int guiWidth, int guiHeight,
                                   int mouseX, int mouseY, float progress) {
        int totalHeight = BUTTONS.length * HEIGHT + (BUTTONS.length - 1) * GAP;
        int x = menuX(progress);
        int y = Math.max(MARGIN, (guiHeight - totalHeight) / 2);

        for (HudFunctionButton button : BUTTONS) {
            boolean hovered = mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
            renderButton(graphics, button, x, y, progress, hovered);
            y += HEIGHT + GAP;
        }
    }

    private static int menuX(float progress) {
        return MARGIN + Math.round((progress - 1.0F) * (WIDTH + MARGIN));
    }

    private static int menuY(int guiHeight) {
        int totalHeight = BUTTONS.length * HEIGHT + (BUTTONS.length - 1) * GAP;
        return Math.max(MARGIN, (guiHeight - totalHeight) / 2);
    }

    private static void renderButton(GuiGraphics graphics, HudFunctionButton button, int x, int y,
                                     float progress, boolean hovered) {
        boolean active = HudFunctionManager.isActive(button.function());
        int alpha = Math.round((active ? 150 : hovered ? 128 : 84) * progress);
        int borderAlpha = Math.round((active ? 240 : hovered ? 220 : 160) * progress);
        int background = (alpha << 24) | (active ? 0x0D5270 : hovered ? 0x0B3F59 : 0x08283A);
        int border = (borderAlpha << 24) | (active ? 0xB9F5FF : 0x57CFFF);

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, background);
        graphics.fill(x, y, x + WIDTH, y + 1, border);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, border);
        graphics.fill(x, y, x + 1, y + HEIGHT, border);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, border);

        Minecraft minecraft = Minecraft.getInstance();
        int textAlpha = Math.round(255 * progress);
        int textColor = (textAlpha << 24) | (active || hovered ? 0xE9FBFF : 0x8FEAFF);
        graphics.drawString(minecraft.font, button.function().id(), x + 8, y + 9, textColor, false);
        graphics.drawString(minecraft.font, Component.translatable(button.function().translationKey()), x + 31, y + 9, textColor, false);
    }

    private static float ease(float value) {
        return value * value * (3.0F - 2.0F * value);
    }
}
