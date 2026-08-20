package dev.modmind.my_mod;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Vector3f;

/**
 * 覆写·断未「五主题异象」主题数据表（对应 docs/overwrite_themes.md）。
 * 仅供数据与逻辑使用，不触碰任何客户端渲染类型，服务端可安全引用。
 * 主题号 0..4，P 键循环切换，持久化于物品 NBT {@code OverwriteTheme}。
 */
public final class KunJinKaoTheme {

    public static final int COUNT = 5;
    public static final int PHASE_ONE = 0;
    public static final int PHASE_TWO = 1;
    public static final int PHASE_THREE = 2;

    public record ThemeEntry(
            int id,
            String displayName,
            int phase1Color,
            int phase2Color,
            int phase3Color,
            float[] tint,
            String stageOneText,
            String[] stageTwoLogs,
            int[] stageTwoIntervals,
            String stageThreeText,
            String endText
    ) {
    }

    private static final ThemeEntry[] THEMES = {
            new ThemeEntry(0, "命令行执行·逐级递归型",
                    0xFFE0E0E0, 0xFFE0E0E0, 0xFFE53935,
                    new float[]{1.0F, 1.0F, 1.0F},
                    "> 正在加载\"覆写·断未\"...",
                    new String[]{
                            "> 正在覆写目标属性...",
                            "> 防御: 已覆盖 [原值 → 0]",
                            "> 速度: 已覆盖 [原值 → -60%]",
                            "> 增益状态: 已清空",
                            "> 主手武器: 已替换为 [损坏的泥土]",
                            "> 覆写完成。等待执行断未..."
                    },
                    new int[]{0, 8, 8, 8, 8, 8},
                    ">>> 执行断未... 目标已标记为 [未定义] <<<",
                    "> 执行完毕。共清除 1 个对象。"),
            new ThemeEntry(1, "诊断扫描型",
                    0xFFE0E0E0, 0xFFFF3D00, 0xFFFF3D00,
                    new float[]{1.0F, 1.0F, 1.0F},
                    "> 正在扫描可疑对象...",
                    new String[]{
                            "[分析] 对象类型: 生物/玩家",
                            "[分析] 权限等级: 低级",
                            "[分析] 持有物品: 泥土 (1)",
                            "[分析] 威胁等级: 高危 — 需立即处置"
                    },
                    new int[]{0, 6, 6, 6},
                    ">>> 已判定: 高危。执行终止进程... <<<",
                    "> 扫描完成。清除 1 个威胁。"),
            new ThemeEntry(2, "编译失败型",
                    0xFFB00020, 0xFFFF0000, 0xFFFF0000,
                    new float[]{0.78F, 0.12F, 0.16F},
                    "> 正在编译 <目标名称>...",
                    new String[]{
                            "[错误] 第2行: 类型不匹配 — 预期\"未定义\" 获得\"已定义\"",
                            "[错误] 第3行: 不可达代码 — 无法绕过\"护甲\"",
                            "[错误] 第4行: 非法访问 — 试图调用\"防御\"",
                            "[错误] 第5行: 未捕获异常 — \"生命值\"溢出",
                            "[错误] 第6行: 空指针引用 — <目标> 未初始化",
                            "[错误] 第7行: 权限不足 — 无法访问\"世界\"",
                            "[错误] 第8行: ... (继续滚动)"
                    },
                    new int[]{0, 4, 4, 4, 4, 4, 4},
                    "编译失败。检测到 127 个错误。正在丢弃...",
                    "> <目标名称> 已丢弃 (原因: 无法编译)"),
            new ThemeEntry(3, "权限覆盖型",
                    0xFFFFFFFF, 0xFFBDBDBD, 0xFFE0E0E0,
                    new float[]{1.0F, 1.0F, 1.0F},
                    "> 正在获取最高权限...",
                    new String[]{
                            "> 正在覆写 ACL 表...",
                            "> [写入] 目标权限: NULL",
                            "> [写入] 目标状态: 待回收"
                    },
                    new int[]{0, 10, 10},
                    ">>> 权限已归零。正在回收资源... <<<",
                    "> 资源 <目标名称> 已回收。"),
            new ThemeEntry(4, "深度调试型",
                    0xFF448AFF, 0xFF448AFF, 0xFF448AFF,
                    new float[]{0.3F, 0.55F, 1.0F},
                    "> 正在 <目标名称> 处设置断点...",
                    new String[]{
                            "[DBG] 正在执行: <目标>.update()",
                            "[DBG] 变量 \"防御\" = 0 (已覆写)",
                            "[DBG] 变量 \"速度\" = -60% (已覆写)",
                            "[DBG] 变量 \"增益\" = NULL (已清空)",
                            "[DBG] 调用: <目标>.death_check()",
                            "[DBG] 返回值: TRUE"
                    },
                    new int[]{0, 5, 5, 5, 5, 5},
                    "[DBG] 检测到致命错误: 对象 \"<目标>\" 已无法修复",
                    "> 调试完成。对象已释放。")
    };

    private KunJinKaoTheme() {
    }

    public static ThemeEntry get(int theme) {
        return THEMES[Math.floorMod(theme, COUNT)];
    }

    public static String displayName(int theme) {
        return get(theme).displayName();
    }

    public static String stageOneText(int theme) {
        return get(theme).stageOneText();
    }

    public static String[] stageTwoLogs(int theme) {
        return get(theme).stageTwoLogs();
    }

    public static String stageThreeText(int theme) {
        return get(theme).stageThreeText();
    }

    public static String endText(int theme) {
        return get(theme).endText();
    }

    public static int color(int theme, int phase) {
        ThemeEntry entry = get(theme);
        return switch (phase) {
            case PHASE_TWO -> entry.phase2Color();
            case PHASE_THREE -> entry.phase3Color();
            default -> entry.phase1Color();
        };
    }

    public static float[] tint(int theme) {
        return get(theme).tint();
    }

    /**
     * 阶段二可见日志行数。elapsedTicks 为阶段二内的进度（0 起步，每 tick +1）。
     * 行 i 在累计间隔不超过当前进度时显示（行 0 立即显示）。
     */
    public static int stageTwoLinesVisible(int theme, int elapsedTicks) {
        ThemeEntry entry = get(theme);
        String[] logs = entry.stageTwoLogs();
        int[] intervals = entry.stageTwoIntervals();
        int acc = 0;
        int lines = 0;
        for (int i = 0; i < logs.length; i++) {
            if (acc <= elapsedTicks) {
                lines++;
            }
            if (i < intervals.length) {
                acc += intervals[i];
            }
        }
        return Math.max(1, Math.min(logs.length, lines));
    }

    /**
     * 服务端覆写/断未粒子的主题对应类型（docs/overwrite_themes.md 主题索引总览）。
     * 主题 4 使用蓝色调色尘粒子（ENTITY_EFFECT 需额外载荷，Dust 为安全等价实现）。
     */
    public static ParticleOptions particle(int theme) {
        return switch (get(theme).id()) {
            case 1 -> ParticleTypes.CLOUD;
            case 2 -> ParticleTypes.SOUL_FIRE_FLAME;
            case 3 -> ParticleTypes.SMOKE;
            case 4 -> new DustParticleOptions(new Vector3f(0.25F, 0.55F, 1.0F), 1.0F);
            default -> ParticleTypes.END_ROD;
        };
    }
}
