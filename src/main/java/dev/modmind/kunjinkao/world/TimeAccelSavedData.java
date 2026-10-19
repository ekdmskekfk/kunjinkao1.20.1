package dev.modmind.kunjinkao.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 时间/局部加速的会话存档。
 * <p>
 * 这一份是补出来的：在那之前 {@code SwordTimeAcceleration} 的两个会话表只是静态 {@code HashMap}，
 * 关掉游戏再进来就全空了 —— 表现就是「时间加速在关闭游戏再重开后失效」，
 * 而且静态表还会跨存档串味（单人换一个世界，上一个世界的加速场照样生效）。
 * <p>
 * 存的是<b>剩余毫秒</b>而不是"截止时刻"：截止时刻用的是墙上时钟，直接存它的话，
 * 关游戏的那段时间也会被算进去，限时加速一进游戏往往就已经过期。
 * 剩余毫秒在恢复时重新加到当前时刻上，等于"关着的那段时间不计时"。
 * 无限模式存 -1。
 * <p>
 * 1.20.1 差异：{@code SavedData.Factory} 不存在，改回
 * {@code DimensionDataStorage#computeIfAbsent(Function<CompoundTag,T>, Supplier<T>, String)}；
 * {@code save/load} 不再带 {@code HolderLookup.Provider} 参数。
 */
public final class TimeAccelSavedData extends SavedData {

    private static final String DATA_NAME = "kunjinkao_time_accel";
    private static final String ENTRIES_KEY = "Entries";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String POS_KEY = "Pos";
    private static final String MULTIPLIER_KEY = "Multiplier";
    private static final String REMAINING_KEY = "RemainingMillis";

    /**
     * 一条加速会话。
     *
     * @param pos             局部加速场的中心；<b>null 表示整体时间加速</b>（没有具体位置）
     * @param dimension       维度 id
     * @param multiplier      倍率
     * @param remainingMillis 剩余毫秒；负值表示无限
     */
    public record Entry(@Nullable BlockPos pos, String dimension, int multiplier, long remainingMillis) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public static TimeAccelSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                TimeAccelSavedData::load, TimeAccelSavedData::new, DATA_NAME);
    }

    private static TimeAccelSavedData load(CompoundTag tag) {
        TimeAccelSavedData data = new TimeAccelSavedData();
        for (Tag element : tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND)) {
            if (!(element instanceof CompoundTag compound)) {
                continue;
            }
            String dimension = compound.getString(DIMENSION_KEY);
            if (dimension.isEmpty()) {
                // 损坏条目直接跳过：宁可少恢复一个加速场，也不能让整份存档读不出来。
                continue;
            }
            data.entries.add(new Entry(
                    compound.contains(POS_KEY) ? BlockPos.of(compound.getLong(POS_KEY)) : null,
                    dimension,
                    Math.max(1, compound.getInt(MULTIPLIER_KEY)),
                    compound.getLong(REMAINING_KEY)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag compound = new CompoundTag();
            compound.putString(DIMENSION_KEY, entry.dimension());
            if (entry.pos() != null) {
                compound.putLong(POS_KEY, entry.pos().asLong());
            }
            compound.putInt(MULTIPLIER_KEY, entry.multiplier());
            compound.putLong(REMAINING_KEY, entry.remainingMillis());
            list.add(compound);
        }
        tag.put(ENTRIES_KEY, list);
        return tag;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * 用当前会话表整表覆盖。
     * <p>
     * 内容没变就不标脏 —— 这个方法的调用点在一次开/关或一次过期上，很稀疏，
     * 但玩家可以站在加速场里反复右键，加一道相等判断省掉无意义的写盘。
     */
    public void replace(List<Entry> incoming) {
        if (entries.equals(incoming)) {
            return;
        }
        entries.clear();
        entries.addAll(incoming);
        setDirty();
    }

    /** 诊断/测试用：把内容打成一行。 */
    @Override
    public String toString() {
        return "TimeAccelSavedData" + Objects.toString(entries);
    }
}