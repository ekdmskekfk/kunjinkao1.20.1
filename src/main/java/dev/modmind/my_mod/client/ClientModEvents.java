package dev.modmind.my_mod.client;

import dev.modmind.my_mod.AcceleratorRegistry;
import dev.modmind.my_mod.ModMindEntry;
import dev.modmind.my_mod.SwordRegistry;
import dev.modmind.my_mod.client.render.AcceleratorBlockEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ModMindEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SwordRegistry.DIAMOND_PROJECTILE.get(), ThrownItemRenderer::new);
        event.registerBlockEntityRenderer(AcceleratorRegistry.ACCELERATOR_BE.get(), AcceleratorBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KunJinKaoKeyBindings.TOGGLE_DISGUISE);
        event.register(KunJinKaoKeyBindings.TOGGLE_OVERWRITE);
        event.register(KunJinKaoKeyBindings.CYCLE_THEME);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("kun_jin_kao_overwrite", (forgeGui, guiGraphics, partialTick, screenWidth, screenHeight) -> {
            KunJinKaoOverwriteHudOverlay.render(guiGraphics, partialTick, screenWidth, screenHeight);
        });
    }
}
