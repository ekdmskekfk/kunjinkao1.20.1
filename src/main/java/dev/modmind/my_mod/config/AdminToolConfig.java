package dev.modmind.my_mod.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;
import java.util.UUID;

public final class AdminToolConfig {
    /**
     * COMMON 配置位于实例/专用服务器根目录的 config 中，所有世界共用同一份白名单。
     */
    public static final ForgeConfigSpec COMMON_SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ADMIN_TOOL_UUIDS;
    public static final ForgeConfigSpec.IntValue SLASH_CHARGE_TIME;
    public static final ForgeConfigSpec.DoubleValue SLASH_SPEED;
    public static final ForgeConfigSpec.IntValue SLASH_LIFETIME;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ADMIN_TOOL_UUIDS = builder
                .comment("Minecraft UUIDs allowed to use Kun Jin Kao administrator features in every world.")
                .defineListAllowEmpty("admin_tool_uuids", List.of("00000000-0000-0000-0000-000000000000"), AdminToolConfig::isUuid);
        builder.push("slash_wave");
        SLASH_CHARGE_TIME = builder
                .comment("Ticks the Kun Jin Kao sword must be held before releasing its slash wave. 20 ticks = 1 second.")
                .defineInRange("charge_time", 40, 1, 72000);
        SLASH_SPEED = builder
                .comment("Slash wave travel speed in blocks per tick.")
                .defineInRange("speed", 1.8D, 0.1D, 16.0D);
        SLASH_LIFETIME = builder
                .comment("Maximum lifetime of a slash wave in ticks.")
                .defineInRange("lifetime", 60, 1, 1200);
        builder.pop();
        COMMON_SPEC = builder.build();
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
