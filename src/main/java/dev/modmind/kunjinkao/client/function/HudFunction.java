package dev.modmind.kunjinkao.client.function;

/**
 * 战术 HUD 功能栏条目。
 * 原先的 SCAN 与 TARGET_LOCK 没有任何实现：点击只切换高亮，不发包、无任何效果，
 * 属于死代码，已连同功能栏绘制、鼠标命中与点击分支一并删除。
 * 注意：本枚举仅用于客户端内存中的界面选择状态，序号与 id 都不做持久化、
 * 也不参与任何网络包字段比对，因此删除枚举项不会影响存档或联机兼容性。
 */
public enum HudFunction {
    ENTITY_INFO("01", "hud.kunjinkao.function.entity_info"),
    NIGHT_VISION("02", "hud.kunjinkao.function.night_vision"),
    TRUE_INVISIBILITY("03", "hud.kunjinkao.function.true_invisibility"),
    MAGNET("04", "hud.kunjinkao.function.magnet"),
    EXCLUSION_LIST("05", "hud.kunjinkao.function.exclusion_list");

    private final String id;
    private final String translationKey;

    HudFunction(String id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }
}
