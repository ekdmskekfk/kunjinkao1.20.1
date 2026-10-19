package dev.modmind.kunjinkao;

/**
 * 剑的语义配色。
 * <p>
 * 在它之前，配色是散在各处的字面量：暂停场写死青、范围加速写死琥珀、
 * 覆写的三个阶段又各写一套。加一处特效就得再挑一次颜色，
 * 结果就是"全是青" —— 单色等于没有层次。
 * <p>
 * 这里按<b>含义</b>定色，不按位置。乱码这个主题天生就该有"正常/加速/损坏/错误"四态，
 * 所以这套颜色不是随便加的，是主题自带的：
 * <ul>
 *   <li>{@link #NORMAL} 青 —— 常态、暂停场</li>
 *   <li>{@link #ACCEL} 琥珀 —— 加速、警告</li>
 *   <li>{@link #CORRUPT} 品红 —— 覆写、数据损坏</li>
 *   <li>{@link #ERROR} 红 —— 错误、蓝屏</li>
 *   <li>{@link #COSMETIC_AURA} 淡蓝 —— 管理员装饰的光环与拖尾</li>
 * </ul>
 * <p>
 * 另附主题符号 {@link #MOJIBAKE}：Unicode 替换字符 U+FFFD，
 * 就是"乱码"本身的那个方块问号。剑需要一个人一眼认得出的记号，
 * 而这个主题自带一个现成的。
 */
public final class KunJinKaoPalette {

    /** 常态：刀脊与暂停场用的青。 */
    public static final int NORMAL = 0xFF56E0FF;
    /** 加速：范围加速与警告用的琥珀。 */
    public static final int ACCEL = 0xFFFFC24D;
    /** 损坏：覆写与数据污染用的品红。 */
    public static final int CORRUPT = 0xFFFF3FD0;
    /** 错误：蓝屏打击与失败路径用的红。 */
    public static final int ERROR = 0xFFFF3B30;
    /**
     * 管理员装饰（光环、拖尾、反击命中火花）的淡蓝。
     * <p>
     * 它比 {@link #NORMAL} 的青更浅更冷：装饰是另一条线，不该去占剑那四个语义色的位置。
     * 之所以还是放在这里，是因为<b>服务端（命中火花）与客户端（光环与拖尾）都要用它</b> ——
     * 分头各写一份字面量，迟早会只改歪一边。
     */
    public static final int COSMETIC_AURA = 0xFF9ED8FF;

    /** 乱码符号。HUD、飘字、刻印都用它当记号。 */
    public static final String MOJIBAKE = "\uFFFD";

    /** 取一个颜色的分量，便于粒子/顶点缓冲使用。 */
    public static float red(int argb) {
        return ((argb >> 16) & 0xFF) / 255.0F;
    }

    public static float green(int argb) {
        return ((argb >> 8) & 0xFF) / 255.0F;
    }

    public static float blue(int argb) {
        return (argb & 0xFF) / 255.0F;
    }

    /** 按比例调整透明度，保留 RGB。 */
    public static int alpha(int argb, float scale) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, scale)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private KunJinKaoPalette() {
    }
}