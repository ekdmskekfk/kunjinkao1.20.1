package dev.modmind.my_mod.client.function;

public enum HudFunction {
    SCAN("01", "hud.my_mod.function.scan"),
    TARGET_LOCK("02", "hud.my_mod.function.target_lock"),
    ENTITY_INFO("03", "hud.my_mod.function.entity_info"),
    NIGHT_VISION("04", "hud.my_mod.function.night_vision");

    private final String id;
    private final String translationKey;

    HudFunction(String id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }
}
