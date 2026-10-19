package dev.modmind.kunjinkao.network;

import java.util.UUID;

/** 排除列表中的一行：玩家 UUID 与记录时的名字。 */
public record ExcludedPlayerData(UUID uuid, String name) {
}