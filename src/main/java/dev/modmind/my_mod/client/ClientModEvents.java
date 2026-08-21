package dev.modmind.my_mod.client;

import dev.modmind.my_mod.AcceleratorRegistry;
import dev.modmind.my_mod.ModMindEntry;
import dev.modmind.my_mod.SwordRegistry;
import dev.modmind.my_mod.client.render.AcceleratorBlockEntityRenderer;
import dev.modmind.my_mod.client.render.EyeHudLayer;
import dev.modmind.my_mod.client.render.GlitchParticle;
import dev.modmind.my_mod.client.render.SlashWaveRenderer;
import dev.modmind.my_mod.client.hud.HudOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ModMindEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SwordRegistry.DIAMOND_PROJECTILE.get(), ThrownItemRenderer::new);
        event.registerEntityRenderer(SwordRegistry.SLASH_WAVE.get(), SlashWaveRenderer::new);
        event.registerBlockEntityRenderer(AcceleratorRegistry.ACCELERATOR_BE.get(), AcceleratorBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpecial(SwordRegistry.INK_TRAIL.get(),
                new GlitchParticle.Provider(0.32F, 0.01F, 0.02F, 0.85F, 18, 0.62F));
        event.registerSpecial(SwordRegistry.PIXEL_SHATTER.get(),
                new GlitchParticle.Provider(0.72F, 0.03F, 0.05F, 0.32F, 12, 0.75F));
        event.registerSpecial(SwordRegistry.GLITCH_CHUNK.get(),
                new GlitchParticle.Provider(0.92F, 0.08F, 0.16F, 0.48F, 16, 0.82F));
        event.registerSpecial(SwordRegistry.CHARGE_SPARK.get(),
                new GlitchParticle.Provider(0.55F, 0.02F, 0.06F, 0.22F, 10, 0.9F));
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KunJinKaoKeyBindings.TOGGLE_DISGUISE);
        event.register(KunJinKaoKeyBindings.TOGGLE_OVERWRITE);
        event.register(KunJinKaoKeyBindings.CYCLE_THEME);
        event.register(KunJinKaoKeyBindings.TOGGLE_TACTICAL_HUD);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("kun_jin_kao_overwrite", (forgeGui, guiGraphics, partialTick, screenWidth, screenHeight) -> {
            KunJinKaoOverwriteHudOverlay.render(guiGraphics, partialTick, screenWidth, screenHeight);
        });
        event.registerAboveAll("kun_jin_kao_slash_wave_impact", (forgeGui, guiGraphics, partialTick, screenWidth, screenHeight) -> {
            SlashWaveClientEffects.render(guiGraphics, screenWidth, screenHeight);
        });
        event.registerAboveAll("tactical_eye_hud", (forgeGui, guiGraphics, partialTick, screenWidth, screenHeight) -> {
            HudOverlayRenderer.render(guiGraphics, partialTick, screenWidth, screenHeight);
        });
    }

    @SubscribeEvent
    public static void addPlayerLayers(EntityRenderersEvent.AddLayers event) {
        EyeHudLayer.addToPlayerRenderers(event);
    }
}
