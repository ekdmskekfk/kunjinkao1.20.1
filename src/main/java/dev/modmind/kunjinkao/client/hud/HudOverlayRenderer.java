package dev.modmind.kunjinkao.client.hud;

import dev.modmind.kunjinkao.client.ClientHudState;
import dev.modmind.kunjinkao.client.function.HudFunction;
import dev.modmind.kunjinkao.client.function.HudFunctionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Client-only 2D 功能栏；菜单交互将在后续 HudScreen 阶段接入。 */
public final class HudOverlayRenderer {

    /** 只保留真正有实现的功能：SCAN / TARGET_LOCK 是死功能（点击仅改高亮），已从栏位与命中检测中移除。 */
    private static final HudFunction[] BUTTONS = {
            HudFunction.ENTITY_INFO,
            HudFunction.NIGHT_VISION,
            HudFunction.TRUE_INVISIBILITY,
            HudFunction.MAGNET,
            HudFunction.EXCLUSION_LIST
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
        for (HudFunction function : BUTTONS) {
            if (mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT) {
                return function;
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

        for (HudFunction function : BUTTONS) {
            boolean hovered = mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
            renderButton(graphics, function, x, y, progress, hovered);
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

    /**
     * 按钮标签缓存。
     * <p>
     * 枚举的翻译键是固定的，原来每帧每个按钮都新建一次 {@code Component.translatable}；
     * 功能栏每帧都要画，缓存下来省掉这份无谓的分配。
     */
    private static final Component[] LABELS = buildLabels();

    private static Component[] buildLabels() {
        HudFunction[] functions = HudFunction.values();
        Component[] labels = new Component[functions.length];
        for (int index = 0; index < functions.length; index++) {
            labels[index] = Component.translatable(functions[index].translationKey());
        }
        return labels;
    }

    private static void renderButton(GuiGraphics graphics, HudFunction function, int x, int y,
                                     float progress, boolean hovered) {
        boolean active = HudFunctionManager.isActive(function);
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
        graphics.drawString(minecraft.font, function.id(), x + 8, y + 9, textColor, false);
        graphics.drawString(minecraft.font, LABELS[function.ordinal()], x + 31, y + 9, textColor, false);
    }

    private static float ease(float value) {
        return value * value * (3.0F - 2.0F * value);
    }
}
