package dev.modmind.kunjinkao.client.render;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.TacticalHudInvisibilityVisualState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Hides armour and held items by suppressing rendering of true-invisible players. */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrueInvisibilityRenderHandler {

    private TrueInvisibilityRenderHandler() {
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (TacticalHudInvisibilityVisualState.isTrueInvisible(event.getEntity().getUUID())) {
            // PlayerRenderer includes armour and third-person held-item layers.
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player != null && TacticalHudInvisibilityVisualState.isTrueInvisible(player.getUUID())) {
            // First-person hand rendering would otherwise leave the sword visible.
            event.setCanceled(true);
        }
    }
}
