package dev.modmind.my_mod.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

public final class KunJinKaoClientOverwriteEffects {

    private static final int OVERWRITE_MAX_TICKS = 40;
    private static final int PHASE_START_TICKS = 20;
    private static final int END_MESSAGE_TICKS = 40;
    private static final int RESIDUE_TICKS = 60;

    private static final Map<Integer, Integer> REMAINING = new HashMap<>();
    private static final Map<Integer, Integer> PHASE_TICKS = new HashMap<>();
    private static final Map<Integer, Integer> PHASE_DETAIL = new HashMap<>();
    private static int endMessageTicks = 0;
    private static int fadeOutTicks = -1;

    // 音效播放状态（按实体追踪）
    private static final Map<Integer, Boolean> PLAYED_BEEP = new HashMap<>();
    private static final Map<Integer, Boolean> PLAYED_TERMINAL = new HashMap<>();
    private static final Map<Integer, Integer> LAST_LINE_PLAYED = new HashMap<>();

    // 每个覆写目标对应的异象主题号（由 PHASE_START 消息 phaseDetail 下发）
    private static final Map<Integer, Integer> THEME_BY_ENTITY = new HashMap<>();
    private static int lastEndTheme = 0;
    private static String lastEndName = "目标";

    // 断未后目标位置的主题色 ? 残留标记（按目标实体 id 追踪，独立于已移除实体）
    private static final Map<Integer, ResidueMarker> RESIDUE_MARKERS = new HashMap<>();

    public record ResidueMarker(double x, double y, double z, int theme, int ageTicks) {
    }

    private KunJinKaoClientOverwriteEffects() {
    }

    public static void start(int entityId, int ticks, int theme) {
        REMAINING.put(entityId, Math.max(1, ticks));
        PHASE_TICKS.put(entityId, OVERWRITE_MAX_TICKS);
        PHASE_DETAIL.put(entityId, 0);
        THEME_BY_ENTITY.put(entityId, Math.floorMod(theme, 5));
        PLAYED_BEEP.put(entityId, false);
        PLAYED_TERMINAL.put(entityId, false);
        LAST_LINE_PLAYED.put(entityId, -1);
        RESIDUE_MARKERS.remove(entityId);
        fadeOutTicks = -1;
    }

    public static void update(int entityId, int ticks) {
        if (REMAINING.containsKey(entityId)) {
            REMAINING.put(entityId, Math.max(0, ticks));
        }
    }

    public static void setPhaseDetail(int entityId, int detail) {
        PHASE_DETAIL.put(entityId, detail);
    }

    public static void cancel(int entityId) {
        REMAINING.remove(entityId);
        PHASE_TICKS.remove(entityId);
        PHASE_DETAIL.remove(entityId);
        THEME_BY_ENTITY.remove(entityId);
        PLAYED_BEEP.remove(entityId);
        PLAYED_TERMINAL.remove(entityId);
        LAST_LINE_PLAYED.remove(entityId);
        RESIDUE_MARKERS.remove(entityId);
    }

    public static void endFlash(int entityId) {
        endFlash(entityId, null);
    }

    /**
     * 阶段三结束（断未）：使用 PHASE_END 下发的坐标记录主题色 ? 残留标记，并启动白闪/结束语倒计时。
     */
    public static void endFlash(int entityId, BlockPos pos) {
        int theme = THEME_BY_ENTITY.getOrDefault(entityId, 0);
        lastEndTheme = theme;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Entity entity = mc.level.getEntity(entityId);
                if (entity != null) {
                    lastEndName = entity.getName().getString();
                }
            }
        } catch (Exception ignored) {
        }
        THEME_BY_ENTITY.remove(entityId);
        REMAINING.remove(entityId);
        PHASE_TICKS.remove(entityId);
        PHASE_DETAIL.remove(entityId);
        PLAYED_BEEP.remove(entityId);
        PLAYED_TERMINAL.remove(entityId);
        LAST_LINE_PLAYED.remove(entityId);
        if (pos != null) {
            RESIDUE_MARKERS.put(entityId, new ResidueMarker(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, theme, 0));
        }
        endMessageTicks = END_MESSAGE_TICKS;
        // 断未完成瞬间：屏幕中央偏下的灰色渐隐替代反馈，共 20 tick
        fadeOutTicks = 20;
    }

    /**
     * 阶段三：服务端在断未裁决前 4 tick 通知客户端，目标头顶显示裁决文字。
     */
    public static void startDecision(int entityId, int ticks) {
        REMAINING.put(entityId, Math.max(1, ticks));
        PHASE_TICKS.put(entityId, 0);
        PHASE_DETAIL.put(entityId, 0);
    }

    public static void tick() {
        if (endMessageTicks > 0) {
            endMessageTicks--;
        }
        if (fadeOutTicks > 0) {
            fadeOutTicks--;
            if (fadeOutTicks <= 0) {
                fadeOutTicks = -1;
            }
        }
        REMAINING.entrySet().removeIf(entry -> entry.getValue() <= 1);
        REMAINING.replaceAll((entityId, ticks) -> ticks - 1);
        PHASE_TICKS.replaceAll((entityId, ticks) -> ticks - 1);
        // 防泄漏：裁决状态最多保留 100 tick，避免目标异常消失后 HUD 永久残留
        PHASE_TICKS.entrySet().removeIf(entry -> entry.getValue() < -100);
        // 残留标记淡出计时
        RESIDUE_MARKERS.entrySet().removeIf(entry -> entry.getValue().ageTicks() >= RESIDUE_TICKS);
        RESIDUE_MARKERS.replaceAll((id, marker) -> new ResidueMarker(marker.x(), marker.y(), marker.z(), marker.theme(), marker.ageTicks() + 1));
    }

    public static Set<Integer> getActiveEntityIds() {
        Set<Integer> ids = new HashSet<>(REMAINING.keySet());
        ids.addAll(PHASE_TICKS.keySet());
        return ids;
    }

    public static int getRemainingTicks(int entityId) {
        return REMAINING.getOrDefault(entityId, 0);
    }

    public static boolean isOverwriteActive() {
        return endMessageTicks > 0 || fadeOutTicks > 0 || !REMAINING.isEmpty()
                || !PHASE_TICKS.isEmpty() || !RESIDUE_MARKERS.isEmpty();
    }

    public static int getMaxRemainingTicks() {
        int max = 0;
        for (int ticks : REMAINING.values()) {
            max = Math.max(max, ticks);
        }
        return max;
    }

    public static int getEndMessageTicks() {
        return endMessageTicks;
    }

    public static int getFadeOutTicks() {
        return fadeOutTicks;
    }

    public static int getPhaseTicks(int entityId) {
        return PHASE_TICKS.getOrDefault(entityId, OVERWRITE_MAX_TICKS);
    }

    public static int getPhaseDetail(int entityId) {
        return PHASE_DETAIL.getOrDefault(entityId, 0);
    }

    // 获取当前阶段（0=阶段一，1=阶段二，2=阶段三）
    public static int getCurrentPhase(int entityId) {
        int phaseTicks = getPhaseTicks(entityId);
        int remaining = REMAINING.getOrDefault(entityId, 0);
        if (phaseTicks <= 0) return 2;
        if (remaining > PHASE_START_TICKS) return 0;
        if (remaining > 0) return 1;
        return 2;
    }

    /**
     * 阶段一「正在加载」：任一位目标处于指令输入阶段时，剑身 DEL 文字保持最亮帧。
     */
    public static boolean isLoadingPhaseActive() {
        return getLoadingTheme() >= 0;
    }

    /**
     * 阶段一活动目标的主题号；无阶段一目标时返回 -1。
     */
    public static int getLoadingTheme() {
        for (int entityId : REMAINING.keySet()) {
            if (getCurrentPhase(entityId) == 0) {
                return THEME_BY_ENTITY.getOrDefault(entityId, 0);
            }
        }
        return -1;
    }

    public static int getEntityTheme(int entityId) {
        return THEME_BY_ENTITY.getOrDefault(entityId, 0);
    }

    public static int getLastEndTheme() {
        return lastEndTheme;
    }

    public static String getLastEndName() {
        return lastEndName;
    }

    public static Map<Integer, ResidueMarker> getResidueMarkers() {
        return RESIDUE_MARKERS;
    }

    public static int getResidueTicks() {
        return RESIDUE_TICKS;
    }

    public static void playBeep() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                mc.level.playSound(null, mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.GENERIC_HURT, SoundSource.AMBIENT, 0.3F, 1.5F);
            }
        } catch (Exception ignored) {
        }
    }

    public static void playKeyboard(Entity target) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && target != null) {
                mc.level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.AMBIENT, 0.2F, 0.8F);
            }
        } catch (Exception ignored) {
        }
    }

    public static void playTerminalSound(Entity target) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && target != null) {
                mc.level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.AMBIENT, 0.15F, 0.3F);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 客户端每 tick 调用：阶段一播放一次 beep，阶段三播放一次低沉终端音。
     */
    public static void tickSounds(int entityId) {
        int phase = getCurrentPhase(entityId);
        if (phase == 0 && !Boolean.TRUE.equals(PLAYED_BEEP.get(entityId))) {
            playBeep();
            PLAYED_BEEP.put(entityId, true);
        }
        if (phase == 2 && !Boolean.TRUE.equals(PLAYED_TERMINAL.get(entityId))) {
            Entity target = null;
            try {
                if (Minecraft.getInstance().level != null) {
                    target = Minecraft.getInstance().level.getEntity(entityId);
                }
            } catch (Exception ignored) {
            }
            playTerminalSound(target != null ? target : Minecraft.getInstance().player);
            PLAYED_TERMINAL.put(entityId, true);
        }
        LAST_LINE_PLAYED.computeIfAbsent(entityId, k -> -1);
    }

    /**
     * 阶段二出现新日志行时播放一次键盘声（由 HUD 每帧对比行号触发）。
     */
    public static void markKeyboardLinePlayed(int entityId, int lineIndex) {
        Integer lastPlayed = LAST_LINE_PLAYED.getOrDefault(entityId, -1);
        if (lineIndex > lastPlayed) {
            playKeyboard(Minecraft.getInstance().player);
            LAST_LINE_PLAYED.put(entityId, lineIndex);
        }
    }
}
