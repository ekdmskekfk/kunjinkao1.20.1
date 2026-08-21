package dev.modmind.my_mod.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.block.Blocks;

/** Shared translucent square particle used for charge, trail and overwrite effects. */
public final class GlitchParticle extends TerrainParticle {
    private final float startAlpha;

    private GlitchParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                           float red, float green, float blue, float size, int lifetime, float alpha) {
        super(level, x, y, z, xd, yd, zd, Blocks.REDSTONE_BLOCK.defaultBlockState());
        this.quadSize = size;
        this.lifetime = lifetime;
        this.startAlpha = alpha;
        this.alpha = alpha;
        this.hasPhysics = false;
        this.setColor(red, green, blue);
    }

    @Override
    public void tick() {
        super.tick();
        if (!removed) {
            alpha = startAlpha * Math.max(0.0F, 1.0F - age / (float) lifetime);
        }
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final float red;
        private final float green;
        private final float blue;
        private final float size;
        private final int lifetime;
        private final float alpha;

        public Provider(float red, float green, float blue, float size, int lifetime, float alpha) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.size = size;
            this.lifetime = lifetime;
            this.alpha = alpha;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xd, double yd, double zd) {
            return new GlitchParticle(level, x, y, z, xd, yd, zd, red, green, blue, size, lifetime, alpha);
        }
    }
}
