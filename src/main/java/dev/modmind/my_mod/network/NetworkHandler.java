package dev.modmind.my_mod.network;

import dev.modmind.my_mod.ModMindEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    // Entity-management packets were added; require matching client and server channel layouts.
    private static final String PROTOCOL_VERSION = "3";
    private static boolean registered = false;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ModMindEntry.MOD_ID, "protocol"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.registerMessage(0, ToggleDisguiseMessage.class,
                ToggleDisguiseMessage::encode,
                ToggleDisguiseMessage::decode,
                ToggleDisguiseMessage::handle);
        CHANNEL.registerMessage(1, OverwriteEffectMessage.class,
                OverwriteEffectMessage::encode,
                OverwriteEffectMessage::decode,
                OverwriteEffectMessage::handle);
        CHANNEL.registerMessage(2, ToggleOverwriteMessage.class,
                ToggleOverwriteMessage::encode,
                ToggleOverwriteMessage::decode,
                ToggleOverwriteMessage::handle);
        CHANNEL.registerMessage(3, ToggleThemeMessage.class,
                ToggleThemeMessage::encode,
                ToggleThemeMessage::decode,
                ToggleThemeMessage::handle);
        CHANNEL.registerMessage(4, AcceleratorConfigMessage.class,
                AcceleratorConfigMessage::encode,
                AcceleratorConfigMessage::decode,
                AcceleratorConfigMessage::handle);
        CHANNEL.registerMessage(5, AcceleratorShowRangeMessage.class,
                AcceleratorShowRangeMessage::encode,
                AcceleratorShowRangeMessage::decode,
                AcceleratorShowRangeMessage::handle);
        CHANNEL.registerMessage(6, ToggleTacticalHudMessage.class,
                ToggleTacticalHudMessage::encode,
                ToggleTacticalHudMessage::decode,
                ToggleTacticalHudMessage::handle);
        CHANNEL.registerMessage(7, TacticalHudStateMessage.class,
                TacticalHudStateMessage::encode,
                TacticalHudStateMessage::decode,
                TacticalHudStateMessage::handle);
        CHANNEL.registerMessage(8, ToggleHudNightVisionMessage.class,
                ToggleHudNightVisionMessage::encode,
                ToggleHudNightVisionMessage::decode,
                ToggleHudNightVisionMessage::handle);
        CHANNEL.registerMessage(9, HudNightVisionStateMessage.class,
                HudNightVisionStateMessage::encode,
                HudNightVisionStateMessage::decode,
                HudNightVisionStateMessage::handle);
        CHANNEL.registerMessage(10, RequestHudEntityListMessage.class,
                RequestHudEntityListMessage::encode,
                RequestHudEntityListMessage::decode,
                RequestHudEntityListMessage::handle);
        CHANNEL.registerMessage(11, HudEntityListMessage.class,
                HudEntityListMessage::encode,
                HudEntityListMessage::decode,
                HudEntityListMessage::handle);
        CHANNEL.registerMessage(12, ManageHudEntityMessage.class,
                ManageHudEntityMessage::encode,
                ManageHudEntityMessage::decode,
                ManageHudEntityMessage::handle);
        CHANNEL.registerMessage(13, HudEntityActionResultMessage.class,
                HudEntityActionResultMessage::encode,
                HudEntityActionResultMessage::decode,
                HudEntityActionResultMessage::handle);
        CHANNEL.registerMessage(14, SlashWaveImpactMessage.class,
                SlashWaveImpactMessage::encode,
                SlashWaveImpactMessage::decode,
                SlashWaveImpactMessage::handle);
    }
}
