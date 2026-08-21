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
        int totalHeight = BUTTONS.length * HEIGHT + (BUTTONS.length - 1) * GAP;
        int x = MARGIN + Math.round((progress - 1.0F) * (WIDTH + MARGIN));
        int y = Math.max(MARGIN, (guiHeight - totalHeight) / 2);

        for (HudFunctionButton button : BUTTONS) {
            renderButton(graphics, button, x, y, progress);
            y += HEIGHT + GAP;
        }
    }

    private static void renderButton(GuiGraphics graphics, HudFunctionButton button, int x, int y, float progress) {
        boolean active = HudFunctionManager.isActive(button.function());
        int alpha = Math.round((active ? 150 : 84) * progress);
        int borderAlpha = Math.round((active ? 240 : 160) * progress);
        int background = (alpha << 24) | (active ? 0x0D5270 : 0x08283A);
        int border = (borderAlpha << 24) | (active ? 0xB9F5FF : 0x57CFFF);

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, background);
        graphics.fill(x, y, x + WIDTH, y + 1, border);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, border);
        graphics.fill(x, y, x + 1, y + HEIGHT, border);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, border);

        Minecraft minecraft = Minecraft.getInstance();
        int textAlpha = Math.round(255 * progress);
        int textColor = (textAlpha << 24) | (active ? 0xE9FBFF : 0x8FEAFF);
        graphics.drawString(minecraft.font, button.function().id(), x + 8, y + 9, textColor, false);
        graphics.drawString(minecraft.font, Component.translatable(button.function().translationKey()), x + 31, y + 9, textColor, false);
    }

    private static float ease(float value) {
        return value * value * (3.0F - 2.0F * value);
    }
}
