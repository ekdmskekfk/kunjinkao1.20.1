package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 建筑手杖的选项表，对齐 Construction Wand KOTS 的 basics.option.WandOptions。
 * <p>
 * 参考实现把这些选项挂在手杖自己的组件上，每根手杖一份；这里同样存进剑的 CUSTOM_DATA，
 * 键名与参考实现一一对应：
 * <ul>
 *   <li>lock —— 只在哪个平面里扩散。NOLOCK 表示不限方向、8 个方向全扩散，
 *       这正是参考实现 testLock 的写法：NOLOCK 一律返回 true。</li>
 *   <li>direction —— TARGET 时放下去的方块跟随依托方块的朝向（朝向、半砖、轴、楼梯形），
 *       PLAYER 则保持玩家自己的朝向。</li>
 *   <li>replace —— 允许替换可替换方块（草、雪层这类）。</li>
 *   <li>match —— 判定同种的口径：严格 / 近似（配置里的同类组）/ 任意非空气。</li>
 *   <li>random —— 取材时从快捷栏与副手里随机抽一种，而不是固定用点击方块那一种。</li>
 * </ul>
 * 默认值与参考实现一致：NOLOCK / TARGET / replace=true / SIMILAR / random=false。
 */
public final class WandOptions {

    /** 扩散锁定面。NOLOCK = 不限，8 个方向都扩散。 */
    public enum Lock {
        HORIZONTAL, VERTICAL, NORTHSOUTH, EASTWEST, NOLOCK
    }

    /** 方块朝向的来源：跟随依托方块，还是跟随玩家。 */
    public enum Direction {
        TARGET, PLAYER
    }

    /** 同种方块的判定口径。 */
    public enum Match {
        EXACT, SIMILAR, ANY
    }

    private static final String LOCK_KEY = "WandLock";
    private static final String DIRECTION_KEY = "WandDirection";
    private static final String REPLACE_KEY = "WandReplace";
    private static final String MATCH_KEY = "WandMatch";
    private static final String RANDOM_KEY = "WandRandom";

    private WandOptions() {
    }

    // ===================== 读 =====================

    public static Lock lock(ItemStack stack) {
        return readEnum(stack, LOCK_KEY, Lock.class, Lock.NOLOCK);
    }

    public static Direction direction(ItemStack stack) {
        return readEnum(stack, DIRECTION_KEY, Direction.class, Direction.TARGET);
    }

    /** 没写过这项时返回 true —— 参考实现里 replace 的默认值就是 true。 */
    public static boolean replace(ItemStack stack) {
        CompoundTag tag = KunJinKaoSwordItem.dataTag(stack);
        return !tag.contains(REPLACE_KEY) || tag.getBoolean(REPLACE_KEY);
    }

    public static Match match(ItemStack stack) {
        return readEnum(stack, MATCH_KEY, Match.class, Match.SIMILAR);
    }

    public static boolean random(ItemStack stack) {
        return KunJinKaoSwordItem.dataTag(stack).getBoolean(RANDOM_KEY);
    }

    // ===================== 写 =====================

    public static void setLock(ItemStack stack, Lock value) {
        writeEnum(stack, LOCK_KEY, value);
    }

    public static void setDirection(ItemStack stack, Direction value) {
        writeEnum(stack, DIRECTION_KEY, value);
    }

    public static void setReplace(ItemStack stack, boolean value) {
        CompoundTag tag = KunJinKaoSwordItem.dataTag(stack);
        tag.putBoolean(REPLACE_KEY, value);
        KunJinKaoSwordItem.writeDataTag(stack, tag);
    }

    public static void setMatch(ItemStack stack, Match value) {
        writeEnum(stack, MATCH_KEY, value);
    }

    public static void setRandom(ItemStack stack, boolean value) {
        CompoundTag tag = KunJinKaoSwordItem.dataTag(stack);
        tag.putBoolean(RANDOM_KEY, value);
        KunJinKaoSwordItem.writeDataTag(stack, tag);
    }

    // ===================== 判定 =====================

    /**
     * 这个方向要不要扩散 —— 参考实现的 testLock：
     * 没锁（NOLOCK）时任何方向都返回 true，锁了就只认锁的那一面。
     */
    public static boolean testLock(ItemStack stack, Lock candidate) {
        Lock current = lock(stack);
        return current == Lock.NOLOCK || current == candidate;
    }

    /**
     * 两个方块算不算同一种 —— 参考实现的 matchBlocks。
     */
    public static boolean matchBlocks(ItemStack stack, Block first, Block second) {
        return switch (match(stack)) {
            case EXACT -> first == second;
            case SIMILAR -> ReplacementRegistry.matchBlocks(first, second);
            case ANY -> first != Blocks.AIR && second != Blocks.AIR;
        };
    }

    // ===================== 内部 =====================

    private static <E extends Enum<E>> E readEnum(ItemStack stack, String key, Class<E> type, E fallback) {
        CompoundTag tag = KunJinKaoSwordItem.dataTag(stack);
        if (!tag.contains(key)) {
            return fallback;
        }
        String name = tag.getString(key);
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return fallback;
    }

    private static void writeEnum(ItemStack stack, String key, Enum<?> value) {
        CompoundTag tag = KunJinKaoSwordItem.dataTag(stack);
        tag.putString(key, value.name());
        KunJinKaoSwordItem.writeDataTag(stack, tag);
    }
}