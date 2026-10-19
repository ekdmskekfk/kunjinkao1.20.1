package dev.modmind.kunjinkao.config;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「终极死亡」的排除名单：被终极死亡击杀过的玩家 UUID。
 * <p>
 * 名单保存在当前存档里。名单中的玩家在登录时会被立即断开，
 * 只有在 HUD 的排除列表里解除之后才能重新进入服务器。
 * <p>
 * 1.20.1 移植说明：同 {@link QolStateSavedData}，去掉 {@code HolderLookup.Provider} 参数。
 */
public final class UltimateDeathSavedData extends SavedData {

    private static final String DATA_NAME = "kunjinkao_ultimate_death";
    private static final String ENTRIES_KEY = "Excluded";
    private static final String UUID_KEY = "Uuid";
    private static final String NAME_KEY = "Name";

    /** 用 LinkedHashMap 保留加入顺序，让排除列表的显示顺序稳定。 */
    private final Map<UUID, String> excluded = new LinkedHashMap<>();

    public static UltimateDeathSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(UltimateDeathSavedData::load, UltimateDeathSavedData::new, DATA_NAME);
    }

    private static UltimateDeathSavedData load(CompoundTag tag) {
        UltimateDeathSavedData data = new UltimateDeathSavedData();
        for (Tag entry : tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag record = (CompoundTag) entry;
            try {
                data.excluded.put(UUID.fromString(record.getString(UUID_KEY)), record.getString(NAME_KEY));
            } catch (IllegalArgumentException ignored) {
                // 忽略损坏条目，不能借此进入排除名单。
            }
        }
        return data;
    }

    public boolean isExcluded(UUID playerUuid) {
        return excluded.containsKey(playerUuid);
    }

    public void exclude(UUID playerUuid, String playerName) {
        excluded.put(playerUuid, playerName == null ? "" : playerName);
        setDirty();
    }

    /** @return 是否确实移除了一个条目 */
    public boolean pardon(UUID playerUuid) {
        if (excluded.remove(playerUuid) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    /** 供 HUD 列表读取的快照。 */
    public List<Map.Entry<UUID, String>> entries() {
        return new ArrayList<>(excluded.entrySet());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> entry : excluded.entrySet()) {
            CompoundTag record = new CompoundTag();
            record.putString(UUID_KEY, entry.getKey().toString());
            record.putString(NAME_KEY, entry.getValue());
            list.add(record);
        }
        tag.put(ENTRIES_KEY, list);
        return tag;
    }
}