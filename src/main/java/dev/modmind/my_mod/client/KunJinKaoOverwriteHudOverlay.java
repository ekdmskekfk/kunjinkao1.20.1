package dev.modmind.my_mod.client;

import dev.modmind.my_mod.KunJinKaoTheme;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

public final class KunJinKaoOverwriteHudOverlay {

    private static final int OVERWRITE_MAX_TICKS = 40;
    private static final int PHASE_START_TICKS = 20;

    private KunJinKaoOverwriteHudOverlay() {
    }

    public static void render(GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (!KunJinKaoClientOverwriteEffects.isOverwriteActive()) {
            return;
        }

        float intensity = Math.min(1.0F, KunJinKaoClientOverwriteEffects.getMaxRemainingTicks() / (float) OVERWRITE_MAX_TICKS);

        // 经验条变黑
        int xpLeft = screenWidth / 2 - 91;
        int xpTop = screenHeight - 32 + 3;
        guiGraphics.fill(xpLeft, xpTop, xpLeft + 182, xpTop + 5, 0xCC000000);

        // 四边灰白裂纹
        int band = (int) (24 + 40 * intensity);
        int arm = (int) (8 + 28 * intensity);
        int gray = 0x99C8C8C8;

        guiGraphics.fill(screenWidth / 2 - arm, 0, screenWidth / 2 + arm, band, gray);
        guiGraphics.fill(screenWidth / 2 - arm, screenHeight - band, screenWidth / 2 + arm, screenHeight, gray);
        guiGraphics.fill(0, screenHeight / 2 - arm, band, screenHeight / 2 + arm, gray);
        guiGraphics.fill(screenWidth - band, screenHeight / 2 - arm, screenWidth, screenHeight / 2 + arm, gray);

        renderAllPhases(guiGraphics, screenWidth, screenHeight);
        renderResidueMarkers(guiGraphics, screenWidth, screenHeight);
        renderEndMessage(guiGraphics, screenWidth, screenHeight);
        renderFadeOutFeedback(guiGraphics, screenWidth, screenHeight);
    }

    private static void renderAllPhases(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        // 渲染所有活跃的覆写实体
        for (int entityId : KunJinKaoClientOverwriteEffects.getActiveEntityIds()) {
            Minecraft mc = Minecraft.getInstance();
            Entity entity = mc.level != null ? mc.level.getEntity(entityId) : null;

            int phase = KunJinKaoClientOverwriteEffects.getCurrentPhase(entityId);
            int remaining = KunJinKaoClientOverwriteEffects.getRemainingTicks(entityId);
            int theme = KunJinKaoClientOverwriteEffects.getEntityTheme(entityId);

            switch (phase) {
                case 0 -> renderPhaseOne(guiGraphics, entity, screenWidth, screenHeight, remaining, theme);
                case 1 -> renderPhaseTwo(guiGraphics, entity, entityId, remaining, screenWidth, screenHeight, theme);
                case 2 -> renderPhaseThree(guiGraphics, entity, screenWidth, screenHeight, theme);
                default -> {
                }
            }
        }
    }

    /**
     * 阶段一：左上角终端框，逐字输出主题 terminalText，目标头顶常亮光标。
     */
    private static void renderPhaseOne(GuiGraphics guiGraphics, Entity entity, int screenWidth, int screenHeight,
                                      int remaining, int theme) {
        if (entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        KunJinKaoTheme.ThemeEntry entry = KunJinKaoTheme.get(theme);
        String text = resolveName(entry.stageOneText(), entity);
        String[] lines = text.split("\n", -1);
        int elapsed = Math.max(0, OVERWRITE_MAX_TICKS - remaining);
        int totalChars = 0;
        for (String line : lines) {
            totalChars += line.length();
        }
        int charsToShow = (int) (totalChars * Math.min(1.0F, elapsed / (float) PHASE_START_TICKS));

        int terminalX = 10;
        int terminalY = 10;
        int terminalWidth = 360;
        int terminalHeight = 24 + lines.length * 12;

        guiGraphics.fill(terminalX, terminalY, terminalX + terminalWidth, terminalY + terminalHeight, 0x99000000);

        int charOffset = 0;
        for (int i = 0; i < lines.length; i++) {
            int visible = Math.max(0, Math.min(lines[i].length(), charsToShow - charOffset));
            String line = lines[i].substring(0, visible);
            guiGraphics.drawString(mc.font, line, terminalX + 10, terminalY + 8 + i * 12, entry.phase1Color());
            charOffset += lines[i].length();
        }

        double[] screenPos = projectToScreen(mc, entity.getX(),
                entity.getY() + entity.getEyeHeight() + 0.45D, entity.getZ(), screenWidth, screenHeight);
        if (screenPos != null) {
            // 光标常亮，不做明暗交替
            guiGraphics.drawString(mc.font, "_",
                    (int) (screenPos[0] - 3), (int) (screenPos[1] - 4), 0xFFFFFFFF);
        }
    }

    /**
     * 阶段二：目标头顶半透明终端窗口，按主题行间隔逐行输出覆写日志。
     */
    private static void renderPhaseTwo(GuiGraphics guiGraphics, Entity entity, int entityId, int remaining,
                                      int screenWidth, int screenHeight, int theme) {
        Minecraft mc = Minecraft.getInstance();
        KunJinKaoTheme.ThemeEntry entry = KunJinKaoTheme.get(theme);

        int phaseTwoElapsed = Math.max(0, PHASE_START_TICKS - remaining);
        // 资源表定义阶段二为 40 ticks，客户端阶段二窗口仅 20 ticks，按 2 倍速压缩逐行呈现
        int linesToShow = Math.max(1, KunJinKaoTheme.stageTwoLinesVisible(theme, Math.min(40, phaseTwoElapsed * 2)));

        int windowWidth = 300;
        int windowHeight = Math.max(64, 30 + linesToShow * 18);

        double anchorX;
        double anchorY;
        if (entity != null) {
            double[] screenPos = projectToScreen(mc, entity.getX(), entity.getY() + entity.getEyeHeight() + 0.35D,
                    entity.getZ(), screenWidth, screenHeight);
            if (screenPos == null) {
                return;
            }
            anchorX = screenPos[0];
            anchorY = screenPos[1];
        } else {
            anchorX = screenWidth / 2.0D;
            anchorY = screenHeight / 2.0D;
        }

        int windowX = (int) (anchorX - windowWidth / 2.0D);
        int windowY = (int) (anchorY - windowHeight - 16);
        windowX = Math.max(4, Math.min(screenWidth - windowWidth - 4, windowX));
        windowY = Math.max(4, Math.min(screenHeight - windowHeight - 4, windowY));

        guiGraphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0x99000000);

        for (int i = 0; i < linesToShow; i++) {
            String line = resolveName(entry.stageTwoLogs()[i], entity);
            guiGraphics.drawString(mc.font, line, windowX + 10, windowY + 8 + i * 18, entry.phase2Color());
        }
        KunJinKaoClientOverwriteEffects.markKeyboardLinePlayed(entityId, linesToShow - 1);
    }

    /**
     * 阶段三：目标头顶放大裁决文字。多行文本按行渲染；以 "&gt;&gt;&gt;" 开头的行使用红色（主题 4 的终止行）。
     */
    private static void renderPhaseThree(GuiGraphics guiGraphics, Entity entity, int screenWidth, int screenHeight,
                                        int theme) {
        Minecraft mc = Minecraft.getInstance();
        KunJinKaoTheme.ThemeEntry entry = KunJinKaoTheme.get(theme);
        String raw = resolveName(entry.stageThreeText(), entity);
        String[] lines = raw.split("\n", -1);
        float scale = 1.3F;

        double anchorX = screenWidth / 2.0D;
        double anchorY = screenHeight / 2.0D - 80;
        if (entity != null) {
            double[] screenPos = projectToScreen(mc, entity.getX(), entity.getY() + entity.getEyeHeight() + 0.6D,
                    entity.getZ(), screenWidth, screenHeight);
            if (screenPos != null) {
                anchorX = screenPos[0];
                anchorY = screenPos[1] - 44;
            }
        }

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int color = line.startsWith(">>>") ? 0xFFFF0000 : entry.phase3Color();
            int textWidth = mc.font.width(line);
            int textX = (int) (anchorX - textWidth * scale / 2.0D);
            int textY = (int) (anchorY - lines.length * 18.0D * scale / 2.0D + i * 18.0D * scale);
            textX = Math.max(4, Math.min(screenWidth - (int) (textWidth * scale) - 4, textX));
            textY = Math.max(4, Math.min(screenHeight - 20, textY));

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(textX, textY, 0);
            guiGraphics.pose().scale(scale, scale, 1.0F);
            guiGraphics.drawString(mc.font, line, -1, 0, 0x000000);
            guiGraphics.drawString(mc.font, line, 1, 0, 0x000000);
            guiGraphics.drawString(mc.font, line, 0, -1, 0x000000);
            guiGraphics.drawString(mc.font, line, 0, 1, 0x000000);
            guiGraphics.drawString(mc.font, line, 0, 0, color);
            guiGraphics.pose().popPose();
        }
    }

    /**
     * 断未后目标位置按主题配色的 ? 残留标记，3 秒内淡出并轻微上浮。
     */
    private static void renderResidueMarkers(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<Integer, KunJinKaoClientOverwriteEffects.ResidueMarker> entry
                : KunJinKaoClientOverwriteEffects.getResidueMarkers().entrySet()) {
            KunJinKaoClientOverwriteEffects.ResidueMarker marker = entry.getValue();
            double y = marker.y() + marker.ageTicks() * 0.02D;
            double[] screenPos = projectToScreen(mc, marker.x(), y, marker.z(), screenWidth, screenHeight);
            if (screenPos == null) {
                continue;
            }
            int age = Math.max(0, 60 - marker.ageTicks());
            int alpha = (int) (255.0F * age / (float) KunJinKaoClientOverwriteEffects.getResidueTicks());
            int themeColor = KunJinKaoTheme.get(marker.theme()).phase3Color() & 0xFFFFFF;
            int color = (Math.min(255, alpha) << 24) | themeColor;
            String q = "?";
            int width = mc.font.width(q);
            int x = (int) (screenPos[0] - width / 2.0D);
            int py = (int) (screenPos[1] - 8);
            guiGraphics.drawString(mc.font, q, x + 1, py + 1, 0x88000000);
            guiGraphics.drawString(mc.font, q, x, py, color);
        }
    }

    /**
     * 结束语（主题 endText）：断未后 2 秒内淡出。
     */
    private static void renderEndMessage(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        int endTicks = KunJinKaoClientOverwriteEffects.getEndMessageTicks();
        if (endTicks <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int theme = KunJinKaoClientOverwriteEffects.getLastEndTheme();
        String text = resolveName(KunJinKaoTheme.get(theme).endText(), null);
        int textWidth = mc.font.width(text);
        int alpha = (int) (255.0F * Math.min(1.0F, endTicks / 20.0F));
        int color = (alpha << 24) | 0xC8C8C8;
        guiGraphics.drawString(mc.font, text, screenWidth / 2 - textWidth / 2, screenHeight - 48, color);
    }

    /**
     * 断未完成瞬间的灰色渐隐替代反馈：
     * 屏幕中央偏下（约 H=60%）显示一行灰色「> 目标已清除」，颜色 0xAAAAAA，
     * 前 5 tick 淡入到 alpha=1，后 15 tick 线性渐隐到 0，共 20 tick。
     * 不闪烁、不覆盖整个屏幕；与结束语、? 残留标记互不影响。
     */
    private static void renderFadeOutFeedback(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        int fadeTicks = KunJinKaoClientOverwriteEffects.getFadeOutTicks();
        if (fadeTicks <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float alpha;
        if (fadeTicks > 15) {
            // 前 5 tick 淡入：fadeTicks 20→16 对应 alpha 由 0 升到 1
            alpha = (20 - fadeTicks) / 5.0F;
        } else {
            // 后 15 tick 线性渐隐
            alpha = fadeTicks / 15.0F;
        }
        alpha = Math.max(0.0F, Math.min(1.0F, alpha));
        String text = "> 目标已清除";
        int textWidth = mc.font.width(text);
        int x = screenWidth / 2 - textWidth / 2;
        int y = (int) (screenHeight * 0.6D);
        int color = (Math.min(255, (int) (255.0F * alpha)) << 24) | 0xAAAAAA;
        guiGraphics.drawString(mc.font, text, x, y, color);
    }

    private static String resolveName(String text, Entity entity) {
        String name = "目标";
        if (entity != null) {
            try {
                name = entity.getDisplayName().getString();
            } catch (Exception ignored) {
                name = entity.getName().getString();
            }
        } else {
            name = KunJinKaoClientOverwriteEffects.getLastEndName();
        }
        return text.replace("<目标名称>", name).replace("<目标>", name);
    }

    /**
     * 世界坐标投影到屏幕坐标。返回 null 表示点在相机后方。
     */
    private static double[] projectToScreen(Minecraft mc, double x, double y, double z,
                                            int screenWidth, int screenHeight) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        double relX = x - camPos.x;
        double relY = y - camPos.y;
        double relZ = z - camPos.z;

        float yaw = (float) Math.toRadians(camera.getYRot());
        float pitch = (float) Math.toRadians(camera.getXRot());

        double viewX = relX * Math.cos(yaw) + relZ * Math.sin(yaw);
        double viewY = relY;
        double viewZ = -relX * Math.sin(yaw) + relZ * Math.cos(yaw);

        double pitchX = viewX;
        double pitchY = viewY * Math.cos(pitch) - viewZ * Math.sin(pitch);
        double pitchZ = viewY * Math.sin(pitch) + viewZ * Math.cos(pitch);

        if (pitchZ <= 0.1D) {
            return null;
        }

        double fov = 70.0D;
        double scale = (screenHeight / 2.0D) / Math.tan(Math.toRadians(fov) / 2.0D);
        double screenX = screenWidth / 2.0D + pitchX / pitchZ * scale;
        double screenY = screenHeight / 2.0D - pitchY / pitchZ * scale;
        return new double[]{screenX, screenY};
    }
}
