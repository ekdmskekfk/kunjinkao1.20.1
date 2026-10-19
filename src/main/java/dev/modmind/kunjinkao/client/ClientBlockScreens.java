package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import dev.modmind.kunjinkao.client.gui.AcceleratorScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Physical-client block screen entry points. */
@OnlyIn(Dist.CLIENT)
public final class ClientBlockScreens {
    private ClientBlockScreens() {
    }

    public static void openAcceleratorScreen(AcceleratorBlockEntity accelerator) {
        Minecraft.getInstance().setScreen(new AcceleratorScreen(accelerator));
    }
}
