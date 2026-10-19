package dev.modmind.kunjinkao.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全服的「放置记录」表：暂停场与范围加速都登记在这里，两者共用一张表。
 * <p>
 * 放进 {@link SavedData} 而不是静态 Map，是为了重启后还在 ——
 * 放置记录如果只在内存里，重启一次全部场地就悄悄失效了，而玩家还以为机器在加速。
 * <p>
 * 每一条记录是"一个以某格为中心的立方体"，边长由 {@code radius} 决定：
 * {@code radius=1} 是 3x3x3，{@code 2} 是 5x5x5，{@code 3} 是 7x7x7，{@code 4} 是 9x9x9。
 * 这几档与 {@code AcceleratorBlockEntity.RADII} 完全一致，范围加速直接复用那套。
 * <p>
 * 1.20.1 差异：工厂换成 {@code computeIfAbsent(load, ctor, name)}；
 * {@code save/load} 去掉 {@code HolderLookup.Provider}；
 * {@code ResourceLocation.parse} → 构造器（1.20.1 没有 parse）。
 */
public final class SwordPlacementRegistry extends SavedData {

    /** 暂停场：立方体里的生物与机器停止，玩家被钉住。 */
    public static final int KIND_PAUSE = 0;
    /** 范围加速：立方体里的方块实体被加速。 */
    public static final int KIND_AREA_ACCEL = 1;

    private static final String DATA_NAME = "kunjinkao_placements";
    private static final String ENTRIES_KEY = "Entries";

    private static final String K_KIND = "Kind";
    private static final String K_DIM = "Dimension";
    private static final String K_X = "X";
    private static final String K_Y = "Y";
    private static final String K_Z = "Z";
    private static final String K_RADIUS = "Radius";
    private static final String K_MULTIPLIER = "Multiplier";
    private static final String K_OWNER = "Owner";

    /** @param radius 半边长，见类注释。 */
    public record Entry(int kind, ResourceKey<Level> dimension, BlockPos pos, int radius,
                        int multiplier, UUID owner) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public static SwordPlacementRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                SwordPlacementRegistry::load, SwordPlacementRegistry::new, DATA_NAME);
    }

    private static SwordPlacementRegistry load(CompoundTag tag) {
        SwordPlacementRegistry data = new SwordPlacementRegistry();
        for (Tag element : tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            try {
                ResourceLocation dimensionId = new ResourceLocation(entry.getString(K_DIM));
                data.entries.add(new Entry(
                        entry.getInt(K_KIND),
                        ResourceKey.create(Registries.DIMENSION, dimensionId),
                        new BlockPos(entry.getInt(K_X), entry.getInt(K_Y), entry.getInt(K_Z)),
                        entry.getInt(K_RADIUS),
                        Math.max(1, entry.getInt(K_MULTIPLIER)),
                        UUID.fromString(entry.getString(K_OWNER))));
            } catch (RuntimeException ignored) {
                // 单条损坏就丢这一条，不能让整张表读不出来 ——
                // 否则一个坏字节会让所有场地一起失效。
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag t = new CompoundTag();
            t.putInt(K_KIND, entry.kind());
            t.putString(K_DIM, entry.dimension().location().toString());
            t.putInt(K_X, entry.pos().getX());
            t.putInt(K_Y, entry.pos().getY());
            t.putInt(K_Z, entry.pos().getZ());
            t.putInt(K_RADIUS, entry.radius());
            t.putInt(K_MULTIPLIER, entry.multiplier());
            t.putString(K_OWNER, entry.owner().toString());
            list.add(t);
        }
        tag.put(ENTRIES_KEY, list);
        return tag;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * 登记一条。同一位置同一类型只保留一条 ——
     * 否则反复 shift+右键同一格会堆出一串重叠的场，收回时也只收掉最上面那条。
     */
    public void add(Entry entry) {
        entries.removeIf(existing -> existing.kind() == entry.kind()
                && existing.dimension().equals(entry.dimension())
                && existing.pos().equals(entry.pos()));
        entries.add(entry);
        setDirty();
    }

    /** 按类型精确收回。 */
    public boolean remove(int kind, ResourceKey<Level> dimension, BlockPos pos) {
        boolean removed = entries.removeIf(entry -> entry.kind() == kind
                && entry.dimension().equals(dimension)
                && entry.pos().equals(pos));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** 收回该位置上的全部记录（不分类型），供"同一格再点一次就撤掉"用。 */
    public boolean removeAllAt(ResourceKey<Level> dimension, BlockPos pos) {
        boolean removed = entries.removeIf(entry -> entry.dimension().equals(dimension)
                && entry.pos().equals(pos));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    @Nullable
    public Entry find(int kind, ResourceKey<Level> dimension, BlockPos pos) {
        for (Entry entry : entries) {
            if (entry.kind() == kind && entry.dimension().equals(dimension) && entry.pos().equals(pos)) {
                return entry;
            }
        }
        return null;
    }
}