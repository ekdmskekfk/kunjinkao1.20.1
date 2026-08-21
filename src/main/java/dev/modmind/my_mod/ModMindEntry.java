package dev.modmind.my_mod;

import dev.modmind.my_mod.event.KunJinKaoDeathEventHandler;
import dev.modmind.my_mod.event.KunJinKaoProtectionHandler;
import dev.modmind.my_mod.event.KunJinKaoTooltipHandler;
import dev.modmind.my_mod.event.AdminSwordCraftingHandler;
import dev.modmind.my_mod.overwrite.KunJinKaoOverwriteHandler;
import dev.modmind.my_mod.config.AdminToolConfig;
import dev.modmind.my_mod.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ModMindEntry.MOD_ID)
public final class ModMindEntry {

    public static final String MOD_ID = "my_mod";

    /**
     * Forge 1.20.1 的 FMLModContainer 反射实例化主类时，
     * 会优先尝试公有 {@code ModMindEntry()} 构造器，
     * 因此保留此无参构造器并通过 FMLJavaModLoadingContext 获取 mod 事件总线，
     * 委托给带总线的构造器完成全部注册，避免 NoSuchMethodException。
     */
    @SuppressWarnings("removal")
    public ModMindEntry() {
        this(FMLJavaModLoadingContext.get().getModEventBus());
    }

    /**
     * Forge 兜底匹配的带事件总线构造器，集中完成全部注册逻辑。
     */
    public ModMindEntry(IEventBus modEventBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AdminToolConfig.COMMON_SPEC, "kunjinkao-admin.toml");
        NetworkHandler.register();
        SwordRegistry.register(modEventBus);
        AcceleratorRegistry.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(new KunJinKaoDeathEventHandler());
        MinecraftForge.EVENT_BUS.register(new KunJinKaoTooltipHandler());
        MinecraftForge.EVENT_BUS.register(new KunJinKaoProtectionHandler());
        MinecraftForge.EVENT_BUS.register(new KunJinKaoOverwriteHandler());
        MinecraftForge.EVENT_BUS.register(AdminSwordCraftingHandler.class);
        System.out.println("[ModMind] KunJinKaoTangTangTang initialized");
    }
}
