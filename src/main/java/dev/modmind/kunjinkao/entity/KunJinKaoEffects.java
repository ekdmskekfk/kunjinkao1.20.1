package dev.modmind.kunjinkao.entity;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 状态效果注册表（1.20.1）。目前只有「骚臭」一个。 */
public final class KunJinKaoEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, KunJinKaoEntry.MOD_ID);

    public static final RegistryObject<MobEffect> STENCH =
            EFFECTS.register("stench", StenchEffect::new);

    private KunJinKaoEffects() {
    }

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }
}
