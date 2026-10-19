package dev.modmind.kunjinkao.config;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 当前服务器存档的密码管理员 UUID 集合。
 * <p>
 * 1.20.1 移植说明：同 {@link QolStateSavedData}，去掉 {@code HolderLookup.Provider} 参数。
 */
public final class PasswordAdminSavedData extends SavedData {
    private static final String DATA_NAME = "kunjinkao_password_admins";
    private static final String UUIDS_KEY = "AuthorizedUuids";
    private final Set<UUID> authorizedUuids = new HashSet<>();

    public static PasswordAdminSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(PasswordAdminSavedData::load, PasswordAdminSavedData::new, DATA_NAME);
    }

    private static PasswordAdminSavedData load(CompoundTag tag) {
        PasswordAdminSavedData data = new PasswordAdminSavedData();
        for (Tag entry : tag.getList(UUIDS_KEY, Tag.TAG_STRING)) {
            try {
                data.authorizedUuids.add(UUID.fromString(entry.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // 忽略损坏条目，不能借此取得管理员权限。
            }
        }
        return data;
    }

    public boolean isAuthorized(UUID playerUuid) {
        return authorizedUuids.contains(playerUuid);
    }

    public void authorize(UUID playerUuid) {
        if (authorizedUuids.add(playerUuid)) {
            setDirty();
        }
    }

    /**
     * 撤销一名玩家的管理员身份。
     * <p>
     * 改配置里的密码**不会**影响已经写入存档的 UUID，所以必须有一条显式的回收路径
     * （由 {@code /kunjinkao-admin revoke} 调用），否则误授权或密码泄露后无法补救。
     *
     * @return true 表示该玩家原本在名单里、现已移除
     */
    public boolean revoke(UUID playerUuid) {
        if (authorizedUuids.remove(playerUuid)) {
            setDirty();
            return true;
        }
        return false;
    }

    /** 只读快照，供管理命令列出当前管理员。 */
    public Set<UUID> snapshot() {
        return Set.copyOf(authorizedUuids);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (UUID uuid : authorizedUuids) {
            entries.add(StringTag.valueOf(uuid.toString()));
        }
        tag.put(UUIDS_KEY, entries);
        return tag;
    }
}