package dev.modmind.my_mod.network;

import java.util.UUID;

/** 可安全同步给客户端的当前维度实体摘要，不包含 NBT 或服务端内部状态。 */
public record HudEntityData(UUID uuid, int entityId, String dimensionId, String typeTranslationKey,
                            String displayName, double x, double y, double z) {
}
