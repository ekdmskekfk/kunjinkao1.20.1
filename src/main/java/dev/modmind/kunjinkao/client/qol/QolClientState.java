package dev.modmind.kunjinkao.client.qol;

import dev.modmind.kunjinkao.network.QolStatePayload;
import dev.modmind.kunjinkao.qol.QolSetting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能面板在客户端的只读镜像：服务端推来什么就存什么。
 * <p>
 * <b>这里不做任何权威判定。</b> 它只回答"面板该画成什么样"，
 * 点下去能不能生效由服务端说了算 —— 就算有人改了客户端内存把整页画成"已开启"，
 * 服务端那边该不该生效还是照旧。
 * <p>
 * 用 {@link ConcurrentHashMap} 而不是普通 Map：写入发生在网络包的处理线程，
 * 读取发生在渲染线程，两边没有共同的锁。
 */
public final class QolClientState {

    private static final Map<Integer, QolStatePayload> ROWS = new ConcurrentHashMap<>();

    private QolClientState() {
    }

    public static void accept(QolStatePayload payload) {
        ROWS.put(payload.settingId(), payload);
    }

    /** 取整行原始数据；从未收到过返回 null（面板据此显示"同步中"）。 */
    public static QolStatePayload row(QolSetting setting) {
        return ROWS.get(setting.id());
    }

    public static boolean isOn(QolSetting setting) {
        QolStatePayload row = row(setting);
        return row != null && row.enabled();
    }

    public static int value(QolSetting setting) {
        QolStatePayload row = row(setting);
        return row != null ? row.value() : setting.defaultValue();
    }

    /** 有效上限：服务端下发的那个（含服务器配置），没收到过就退回本项硬上限。 */
    public static int max(QolSetting setting) {
        QolStatePayload row = row(setting);
        return row != null ? row.max() : setting.hardMax();
    }

    public static boolean authorized(QolSetting setting) {
        QolStatePayload row = row(setting);
        return row != null && row.authorized();
    }

    /**
     * 断开连接时清空。
     * <p>
     * 这些值只对当前服务器有效：不清的话，退出去再进另一个服务器，
     * 面板会先画上一局的旧状态，直到新的全量同步到达。
     */
    public static void clear() {
        ROWS.clear();
    }
}