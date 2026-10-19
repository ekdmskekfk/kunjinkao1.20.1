package dev.modmind.kunjinkao.client.qol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 功能面板的界面状态：选中哪个分类、滚到哪、缩放多少。存在
 * {@code config/kunjinkao-qol-panel.json}。
 * <p>
 * <b>只存"看起来怎么样"，不存"功能开没开"。</b> 功能状态是服务端权威
 * （{@link QolClientState} 只是它的一份镜像，退出即清），而界面状态纯属本地偏好 ——
 * 放进存档或服务端都会变成"我在 A 服调的分类，去 B 服还是它"这种莫名其妙的串台。
 * <p>
 * 读写全程不抛异常：配置文件坏了就退回默认值。这份文件只影响画得好不好看，
 * 为它让玩家进不去游戏是不划算的。
 */
public final class QolPanelConfig {

    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");
    private static final String FILE_NAME = "kunjinkao-qol-panel.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 面板打开时的本地偏好。字段名就是 JSON 里的键名，改名会导致旧文件读不出来（回落默认值，无害）。 */
    public static final class Data {
        /** 上次选中的分类（枚举名）。找不到对应分类时回落到第一个有内容的分类。 */
        public String selectedCategory = "";
        /** 列表滚动位置（像素）。负数会被夹回 0。 */
        public int scrollY;
        /** 手动缩放偏移，-50 ~ 100，对应 50% ~ 200%。 */
        public int guiScaleOffset;
        /** 是否跟着分辨率自适应缩放；关掉则只用手动偏移。 */
        public boolean adaptive = true;
        /**
         * 开关类设定项的快捷键：键 = 枚举名小写，值 = GLFW key code。
         * <p>
         * 用枚举名而不是枚举序号：以后往 {@link QolSetting} 中间插一项，序号全部错位，
         * 玩家的按键会静悄悄地跑到别的功能上；名字至少读得懂，加项也不受影响。
         * <p>
         * 没绑的项干脆不写进这个表，而不是写一个 0 —— 手改文件的人一眼能看出哪些绑了。
         */
        public Map<String, Integer> toggleBinds = new LinkedHashMap<>();
        /**
         * 需要"执行键"的设定项的快捷键：键 = 枚举名小写，值 = GLFW key code。
         * <p>
         * 与 {@link #toggleBinds} 分两张表而不是塞进同一张：同一个功能上"开关键"和"执行键"
         * 是两件不同的事（一个是开关状态，一个是对当前选区动手），共用一张表就没法表达
         * "同一个键位含义不同"，清除其中一个也会连带清掉另一个。
         * <p>
         * 目前只有选区作业的模式项（127）用得上：开关键管"破坏还是放置"，执行键管"开始干"。
         */
        public Map<String, Integer> actionBinds = new LinkedHashMap<>();
    }

    private static Data data;
    private static boolean loaded;

    private QolPanelConfig() {
    }

    /**
     * 取当前偏好对象。
     * <p>
     * 首次访问时才读盘（懒加载）：这个类只被面板用到，不该在游戏启动阶段就去碰文件系统。
     */
    public static Data get() {
        if (!loaded) {
            data = load();
            loaded = true;
        }
        return data;
    }

    /** 写回磁盘。面板关闭时调一次即可，不需要每帧存。 */
    public static void save() {
        if (!loaded) {
            return;
        }
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            // 存不下就算了：下次打开面板只是回到默认的分类与滚动位置，不影响任何功能。
            LOGGER.warn("[Kunjinkao] 功能面板界面状态写入失败：{}", failure.toString());
        }
    }

    /**
     * 取某项的开关快捷键，没绑过返回 0。
     * <p>
     * 拿 0 当"未绑定"是安全的：GLFW 的键值从 32 起，0 不可能是一个真按键，
     * 所以调用方不需要额外判空。
     */
    public static int toggleKey(QolSetting setting) {
        Integer code = get().toggleBinds.get(bindKey(setting));
        return code == null ? 0 : code;
    }

    /**
     * 重绑或解绑某项的开关快捷键，{@code code} 传 0 表示解绑（直接把条目删掉）。
     * <p>
     * 这里不落盘：调用方按自己的时机 {@link #save()} —— 面板是改一次存一次，
     * 别的地方将来可能想攒着一起存。
     */
    public static void setToggleKey(QolSetting setting, int code) {
        Data data = get();
        if (code == 0) {
            data.toggleBinds.remove(bindKey(setting));
        } else {
            data.toggleBinds.put(bindKey(setting), code);
        }
    }

    private static String bindKey(QolSetting setting) {
        return setting.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 取某项的"执行键"，没绑过返回 0。
     * <p>
     * 与 {@link #toggleKey} 一样拿 0 当"未绑定"：GLFW 的键值从 32 起，0 不可能是真按键。
     */
    public static int actionKey(QolSetting setting) {
        Integer code = get().actionBinds.get(bindKey(setting));
        return code == null ? 0 : code;
    }

    /** 重绑或解绑某项的执行键，{@code code} 传 0 表示解绑。同样不落盘。 */
    public static void setActionKey(QolSetting setting, int code) {
        Data data = get();
        if (code == 0) {
            data.actionBinds.remove(bindKey(setting));
        } else {
            data.actionBinds.put(bindKey(setting), code);
        }
    }

    private static Data load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return new Data();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Data parsed = GSON.fromJson(json, Data.class);
            return sanitize(parsed);
        } catch (IOException | JsonSyntaxException broken) {
            LOGGER.warn("[Kunjinkao] 功能面板界面状态读取失败，改用默认值：{}", broken.toString());
            return new Data();
        }
    }

    /**
     * 把读到的值夹回合法区间。
     * <p>
     * 手改过这个文件、或者它来自一个以后版本的面板时，值可能越界；
     * 这里统一收口，免得一个 {@code scrollY: -99999} 让列表画到屏幕外、看起来像面板坏了。
     */
    private static Data sanitize(Data parsed) {
        Data fixed = parsed == null ? new Data() : parsed;
        fixed.scrollY = Math.max(0, fixed.scrollY);
        fixed.guiScaleOffset = Math.max(-50, Math.min(100, fixed.guiScaleOffset));
        if (fixed.selectedCategory == null) {
            fixed.selectedCategory = "";
        }
        // 手改过的文件里可能根本没有这个字段（老文件），也可能被写成 null；
        // 不兜住的话，进游戏第一次读快捷键就是一次空指针。
        if (fixed.toggleBinds == null) {
            fixed.toggleBinds = new LinkedHashMap<>();
        }
        // 执行键那张表同样要兜：它是后加的字段，所有已经存在的旧配置文件里都没有它。
        if (fixed.actionBinds == null) {
            fixed.actionBinds = new LinkedHashMap<>();
        }
        // 只认正整数：0 在语义上是"未绑定"，负数和 null 都不是合法的 GLFW 键值，
        // 留着它们只会让按键格画出一串问号。
        fixed.toggleBinds.values().removeIf(code -> code == null || code <= 0);
        fixed.actionBinds.values().removeIf(code -> code == null || code <= 0);
        return fixed;
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }
}