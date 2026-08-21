package dev.modmind.my_mod.client;

import dev.modmind.my_mod.SwordRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

/** Lightweight client-only impact feedback; no world state is changed. */
public final class SlashWaveClientEffects {
    private static BlockPos groundPosition;
    private static int ticksRemaining;

    private SlashWaveClientEffects() {
    }

    public static void start(BlockPos position, int duration) {
        groundPosition = position;
        ticksRemaining = Math.max(ticksRemaining, duration);
    }

    public static void tick() {
        if (ticksRemaining <= 0) {
            return;
        }
        ticksRemaining--;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && groundPosition != null && ticksRemaining % 2 == 0) {
            double x = groundPosition.getX() + 0.5D;
            double y = groundPosition.getY() + 0.03D;
            double z = groundPosition.getZ() + 0.5D;
            for (int i = 0; i < 6; i++) {
                minecraft.level.addParticle(SwordRegistry.GLITCH_CHUNK.get(),
                        x + (minecraft.level.random.nextDouble() - 0.5D) * 1.8D, y,
                        z + (minecraft.level.random.nextDouble() - 0.5D) * 1.8D,
                        0.0D, 0.01D, 0.0D);
            }
        }
        if (ticksRemaining == 0) {
            groundPosition = null;
        }
    }

    public static int getTicksRemaining() {
        return ticksRemaining;
    }

    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (ticksRemaining <= 0) {
            return;
        }
        int alpha = Math.min(145, 30 + ticksRemaining * 3);
        int barHeight = Math.max(4, screenHeight / 36);
        int offset = (ticksRemaining * 7) % Math.max(1, screenWidth / 3);
        graphics.fill(0, screenHeight / 2 - barHeight * 3, screenWidth, screenHeight / 2 - barHeight * 2,
                (alpha << 24) | 0x360000);
        graphics.fill(offset, screenHeight / 2 + barHeight, Math.min(screenWidth, offset + screenWidth / 2),
                screenHeight / 2 + barHeight * 2, (alpha << 24) | 0x8A0000);
        graphics.fill(screenWidth / 2 - 2, 0, screenWidth / 2 + 2, screenHeight, (alpha / 3 << 24) | 0xAA0000);
    }
}
