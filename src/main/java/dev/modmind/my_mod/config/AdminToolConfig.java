package dev.modmind.my_mod.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;
import java.util.UUID;

public final class AdminToolConfig {
    public static final ForgeConfigSpec SERVER_SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ADMIN_TOOL_UUIDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ADMIN_TOOL_UUIDS = builder
                .comment("Minecraft UUIDs allowed to craft the Kun Jin Kao admin sword.")
                .defineListAllowEmpty("admin_tool_uuids", List.of("00000000-0000-0000-0000-000000000000"), AdminToolConfig::isUuid);
        SERVER_SPEC = builder.build();
    }

    private AdminToolConfig() {
    }

    public static boolean isAuthorized(UUID playerUuid) {
        return ADMIN_TOOL_UUIDS.get().stream()
                .map(AdminToolConfig::parseUuid)
                .anyMatch(playerUuid::equals);
    }

    private static boolean isUuid(Object value) {
        return value instanceof String string && parseUuid(string) != null;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
