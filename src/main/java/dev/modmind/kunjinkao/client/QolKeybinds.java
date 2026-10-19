package dev.modmind.kunjinkao.client.qol;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.QolSettingPayload;
import dev.modmind.kunjinkao.network.SimpleActionPayload;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.client.Minecraft;

/**
 * 面板里绑定的两类快捷键：开关键与执行键。
 * <p>
 * <b>开关键只对开关类（{@link QolSetting.Kind#TOGGLE}）生效</b>：数值项（村庄英雄等级、交互距离）
 * 和选项项都不是"按一下切一下"的东西，给它们各占一个全局按键不划算，
 * 所以哪怕有人手改配置文件把键绑上去了，这里也一律忽略。
 * <p>
 * <b>执行键只对"有执行动作"的项生效</b>，目前是选区作业（127）的破坏与放置两行：
 * 触发后发的是 {@link SimpleActionPayload#ZONE_EXECUTE_BREAK} 或
 * {@link SimpleActionPayload#ZONE_EXECUTE_PLACE}，<b>客户端不判定该挖还是该填</b> ——
 * 按的是哪个键就是哪个动作，没有"当前模式"这个中间状态可以猜错。
 * <p>
 * 与 {@code KunJinKaoKeyBindings} 里那些键不同，这些绑定不进原版按键设置界面：
 * 它们属于面板的一部分，在面板里点格子改，改完立刻生效。
 */
public final class QolKeybinds {

    /**
     * 上一 tick 每个设定项的开关键按下状态，用来做边沿检测。
     * <p>
     * 没有它的话，按住一个键会每 tick 翻转一次（20 次/秒），玩家看到的是开关在乱闪。
     * 按设定项 id 当下标而不是枚举名查表：id 就是线格式里那个稳定序号，加项也不会错位。
     */
    private static final boolean[] WAS_DOWN = new boolean[QolSetting.COUNT];

    /**
     * 执行键的按下状态，与开关键<b>分开一份</b>。
     * <p>
     * 同一个设定项可以同时绑开关键和执行键（选区作业就是这样：一个切模式、一个开工），
     * 共用一份状态的话，两个键会互相把对方的边沿吃掉 —— 表现是"其中一个键时灵时不灵"。
     */
    private static final boolean[] WAS_DOWN_ACTION = new boolean[QolSetting.COUNT];

    private QolKeybinds() {
    }

    /**
     * 每个客户端 tick 调一次。
     * <p>
     * 触发条件刻意收得很紧：没开任何界面、人在游戏里、服务端已经授权。
     * 发出去的是普通的状态改动包 —— 客户端只负责说"我按了"，值合不合法、有没有权限，
     * 服务端会照 {@link QolSettingPayload} 那套再判一遍。
     */
    public static void tick(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        boolean inGame = minecraft.screen == null && minecraft.player != null;
        for (QolSetting setting : QolSetting.values()) {
            tickToggle(window, inGame, setting);
            tickAction(window, inGame, setting);
        }
    }

    /** 开关键：按一下等于在面板上点了这一行的开关。 */
    private static void tickToggle(long window, boolean inGame, QolSetting setting) {
        int id = setting.id();
        int code = QolPanelConfig.toggleKey(setting);
        boolean down = code != 0
                && setting.kind() == QolSetting.Kind.TOGGLE
                && InputConstants.isKeyDown(window, code);
        boolean edge = down && !WAS_DOWN[id];
        // 先记状态、再判其它条件：这样"开着界面按住键、关掉界面时手还没松"不会补触发一次。
        WAS_DOWN[id] = down;
        if (!edge || !inGame) {
            return;
        }
        if (!QolClientState.authorized(setting)) {
            // 没授权时这一行是只读的，发过去也会被服务端丢掉，不如一个包都不发。
            return;
        }
        NetworkHandler.sendToServer(new QolSettingPayload(id, QolClientState.isOn(setting) ? 0 : 1));
    }

    /**
     * 执行键：按一下等于"对当前选区开工"。
     * <p>
     * 按住 Shift 按执行键是取消（停作业或清选区），与原来那个独立的执行键保持同一套语义：
     * 一正一反用同一个键加修饰键，比再多占一个全局按键好记，也少一次和别的模组抢键的机会。
     * <p>
     * 这里<b>不检查"选区设好了没有"</b>：那是服务端的话，客户端提前判一遍只会两边说法不一致。
     */
    private static void tickAction(long window, boolean inGame, QolSetting setting) {
        int id = setting.id();
        int code = QolPanelConfig.actionKey(setting);
        boolean down = code != 0
                && setting.hasExecuteAction()
                && InputConstants.isKeyDown(window, code);
        boolean edge = down && !WAS_DOWN_ACTION[id];
        WAS_DOWN_ACTION[id] = down;
        if (!edge || !inGame) {
            return;
        }
        if (!QolClientState.authorized(setting)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.isShiftKeyDown()) {
            // 按住 Shift = 取消。取消不区分破坏还是放置：正在跑的作业只有一个。
            NetworkHandler.sendToServer(new SimpleActionPayload(SimpleActionPayload.ZONE_CANCEL));
            return;
        }
        int actionId = actionIdFor(setting);
        if (actionId != 0) {
            NetworkHandler.sendToServer(new SimpleActionPayload(actionId));
        }
    }

    /**
     * 这一行的执行键按下时该发哪个动作 id。
     * <p>
     * 映射写在这里，而不是让 {@link QolSetting} 自己带一个动作 id：那会让 qol 包反过来依赖
     * network 包（现在只有 network 依赖 qol），为一个只有客户端用得上的对应关系绕出循环依赖不划算。
     * <p>
     * 返回 0 表示这一行没有可执行的动作 —— 调用方据此不发包，而不是发一个无意义的 id。
     */
    private static int actionIdFor(QolSetting setting) {
        return switch (setting) {
            case ZONE_BREAK -> SimpleActionPayload.ZONE_EXECUTE_BREAK;
            case ZONE_PLACE -> SimpleActionPayload.ZONE_EXECUTE_PLACE;
            default -> 0;
        };
    }
}
