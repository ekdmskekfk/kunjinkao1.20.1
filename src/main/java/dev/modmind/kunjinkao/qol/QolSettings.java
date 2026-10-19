package dev.modmind.kunjinkao.qol;

import dev.modmind.kunjinkao.config.AdminToolConfig;

/**
 * 设定项的运行时取值规则：把服务器配置里的上限压到 {@link QolSetting} 的硬上限之上。
 * <p>
 * 分成两张表（枚举管"能不能超过"，配置管"服务器愿意给到多少"）是为了让服主
 * 调小上限时不需要动代码，同时代码里又有一条再怎么配也越不过去的底线。
 */
public final class QolSettings {

    private QolSettings() {
    }

    /**
     * 本项当前生效的上限。
     * <p>
     * 数值项取「硬上限」与「服务器配置上限」的较小值；开关类恒为 1。
     */
    public static int maxOf(QolSetting setting) {
        int configured = switch (setting) {
            case VILLAGE_HERO -> AdminToolConfig.qolVillageHeroMaxLevel();
            case REACH -> AdminToolConfig.qolReachMaxBlocks();
            default -> setting.hardMax();
        };
        return Math.max(setting.min(), Math.min(setting.hardMax(), configured));
    }

    /** 按当前上限夹取，供服务端校验客户端发来的值。 */
    public static int clamp(QolSetting setting, int value) {
        return Math.max(setting.min(), Math.min(maxOf(setting), value));
    }

    /** 开关类：把 int 收成 boolean。 */
    public static boolean asToggle(int value) {
        return value != 0;
    }
}