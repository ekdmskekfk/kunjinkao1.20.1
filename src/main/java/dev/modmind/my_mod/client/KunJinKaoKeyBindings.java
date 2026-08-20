package dev.modmind.my_mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class KunJinKaoKeyBindings {
    public static final String CATEGORY = "key.categories.my_mod";
    public static final KeyMapping TOGGLE_DISGUISE = new KeyMapping(
            "key.my_mod.toggle_disguise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY
    );

    public static final KeyMapping TOGGLE_OVERWRITE = new KeyMapping(
            "key.my_mod.toggle_overwrite",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            CATEGORY
    );

    public static final KeyMapping CYCLE_THEME = new KeyMapping(
            "key.my_mod.cycle_theme",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            CATEGORY
    );
}
