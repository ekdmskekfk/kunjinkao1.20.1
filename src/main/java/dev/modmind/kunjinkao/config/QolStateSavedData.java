package dev.modmind.kunjinkao.config;

import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 功能面板里"个人级"设定的存档：按玩家 UUID 记一整排取值。
 * <p>
 * 存放方式是 {@code UUID -> int[QolSetting.COUNT]}，下标就是 {@link QolSetting#id()}。
 * 用定长数组而不是每个设置一条 NBT 键：设置项只会往后追加，数组天然对齐，
 * 读的时候也不用为"某一项从来没被写过"写特例 —— 缺位一律回落到该项的默认值。
 * <p>
 * 服务器级设定<b>不在这里</b>：那些属于 {@link AdminToolConfig} 的 {@code [qol]} 分节，
 * 因为服主要能直接编辑文件、也要能随整合包分发默认值。
 * <p>
 * 1.20.1 移植说明：1.21.1 的 {@code load}/{@code save} 多一个
 * {@code HolderLookup.Provider} 参数（1.21 起序列化要拿注册表），1.20.1 没有，
 * 因此这里用的是只有 {@code CompoundTag} 的旧签名。
 */
public final class QolStateSavedData extends SavedData {

    private static final String DATA_NAME = "kunjinkao_qol_state";
    private static final String ENTRIES_KEY = "Entries";
    private static final String UUID_KEY = "Uuid";
    private static final String VALUES_KEY = "Values";

    private final Map<UUID, int[]> valuesByPlayer = new HashMap<>();

    public static QolStateSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(QolStateSavedData::load, QolStateSavedData::new, DATA_NAME);
    }

    private static QolStateSavedData load(CompoundTag tag) {
        QolStateSavedData data = new QolStateSavedData();
        for (Tag entry : tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND)) {
            if (!(entry instanceof CompoundTag compound)) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(compound.getString(UUID_KEY));
            } catch (IllegalArgumentException malformed) {
                // 损坏条目直接跳过：宁可让这名玩家回到默认值，也不能让整份存档读不出来。
                continue;
            }
            data.valuesByPlayer.put(uuid, normalize(compound.getIntArray(VALUES_KEY)));
        }
        return data;
    }

    /** 把读进来的数组补齐/截断到当前设置项数量 —— 存档是旧版本写的时会短一截。 */
    private static int[] normalize(int[] raw) {
        int[] fixed = new int[QolSetting.COUNT];
        for (int i = 0; i < fixed.length; i++) {
            fixed[i] = i < raw.length ? raw[i] : QolSetting.values()[i].defaultValue();
        }
        return fixed;
    }

    /**
     * 取某玩家某项的当前值。没写过或存档是旧版本写的一律回落默认值。
     */
    public int getValue(UUID playerUuid, QolSetting setting) {
        int[] values = valuesByPlayer.get(playerUuid);
        if (values == null || setting.id() >= values.length) {
            return setting.defaultValue();
        }
        return values[setting.id()];
    }

    /**
     * 取某玩家一整排取值（含未写过的项的默认值），供打开面板时全量下发。
     */
    public int[] snapshot(UUID playerUuid) {
        int[] values = valuesByPlayer.get(playerUuid);
        return values == null ? normalize(new int[0]) : values.clone();
    }

    /**
     * 写入一项。
     * <p>
     * 存的是<b>已经夹过范围</b>的值（调用方负责夹），这里不再二次夹取 ——
     * 夹取要用到服务器配置里的上限，而那个上限以后可能被调大，
     * 提前夹会把玩家原本设置的高值永久压掉。
     */
    public void setValue(UUID playerUuid, QolSetting setting, int value) {
        int[] values = valuesByPlayer.computeIfAbsent(playerUuid, key -> normalize(new int[0]));
        if (values[setting.id()] == value) {
            return;
        }
        values[setting.id()] = value;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, int[]> entry : valuesByPlayer.entrySet()) {
            CompoundTag compound = new CompoundTag();
            compound.putString(UUID_KEY, entry.getKey().toString());
            compound.putIntArray(VALUES_KEY, entry.getValue());
            entries.add(compound);
        }
        tag.put(ENTRIES_KEY, entries);
        return tag;
    }
}