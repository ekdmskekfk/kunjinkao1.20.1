package dev.modmind.kunjinkao.client;

/**
 * 客户端侧的「我是否通过了管理员密码验证」镜像（1.20.1 Forge 版，与 1.21.1 一致）。
 *
 * <p>用途只有一个：决定两个控制台（功能控制台 G / 管理员剑设置 .）能不能打开。
 * <b>它不是权限本身</b> —— 真正的授权判定在服务端，而且面板里每一个动作
 * （{@code QolSettingPayload} / {@code SwordSettingPayload} …）到服务端后都会
 * 再校验一次 {@code AdminToolConfig.isAuthorized}。所以这个标记只负责"不让人
 * 白开一个什么都点不动的面板"，改客户端也绕不过服务端那道闸。</p>
 *
 * <p>数据来源（两条，都来自服务端，客户端从不自己判定）：</p>
 * <ol>
 *   <li>{@code AdminEyeStatePayload}：登录时服务端会把每个玩家的授权状态广播给所有人，
 *       包含自己那一条 —— 这是"进服务器就知道"的初始值；</li>
 *   <li>{@code AdminPasswordResultPayload}：中途按 {@code ]} 输入密码后，服务端回执成功/失败。</li>
 * </ol>
 */
public final class AdminAuthClientState {

    /** volatile：写入来自网络包处理线程，读取在主线程。 */
    private static volatile boolean authorized;

    private AdminAuthClientState() {
    }

    public static boolean isAuthorized() {
        return authorized;
    }

    public static void setAuthorized(boolean value) {
        authorized = value;
    }

    /** 断线/退世界时归零：否则换个服务器进来，上一个存档的授权会带过来。 */
    public static void reset() {
        authorized = false;
    }
}
