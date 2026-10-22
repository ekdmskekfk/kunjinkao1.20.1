package dev.modmind.kunjinkao;

import dev.modmind.kunjinkao.entity.KunJinKaoEntities;
import dev.modmind.kunjinkao.sound.KunJinKaoSounds;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 1.20.1 入口。
 * <p>
 * 这是 1.21.1 那边 128 行"总装车间"的分批版本：每移植一批内容，就在这里加一条注册。
 * 目前有：物品表/配方表（{@link SwordRegistry}）、实体（{@link KunJinKaoEntities}）、
 * 音效（{@link KunJinKaoSounds}）、加速方块（{@link AcceleratorRegistry}）、网络层。
 * 事件处理器与客户端在后续批次加入。
 */
@Mod(KunJinKaoEntry.MOD_ID)
public final class KunJinKaoEntry {
    public static final String MOD_ID = "kunjinkao";

    public KunJinKaoEntry() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        SwordRegistry.register(modEventBus);
        KunJinKaoEntities.register(modEventBus);
        // 状态效果（骚臭）。
        dev.modmind.kunjinkao.entity.KunJinKaoEffects.register(modEventBus);
        KunJinKaoSounds.register(modEventBus);
        AcceleratorRegistry.register(modEventBus);
        WorldGateRegistry.register(modEventBus);
        dev.modmind.kunjinkao.network.NetworkHandler.register();

        // ===== FORGE 事件总线：已移植的处理器（照 1.21.1 的注册顺序补） =====
        // 1.21.1 是 NeoForge.EVENT_BUS，1.20.1 是 MinecraftForge.EVENT_BUS，注册方式一致。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new dev.modmind.kunjinkao.event.KunJinKaoDeathEventHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new dev.modmind.kunjinkao.event.KunJinKaoProtectionHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new dev.modmind.kunjinkao.overwrite.KunJinKaoOverwriteHandler());

        // —— 本批新增（照 1.21.1 的注册顺序）：实例式处理器 ——
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoHandHateHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoAnvilHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoTooltipHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.TacticalHudTrueInvisibilityHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.TacticalHudMagnetHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.SwordDrawAnimationSyncHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.UltimateDeathHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.AdminAdventureSwordGrantHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoUnbreakableBlockHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoExtraLootHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoOreDropHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.SwordAutoPickupHandler());
        // —— 静态处理器：1.20.1 用 register(Class) 与 1.21.1 的 register(Class) 等价 ——
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.AdminSwordCraftingHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.CommandProtectedSwordHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.AdminEyeSyncHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.AdminCommandHandler.class);
        // —— 选区挖掘链（Ae2ZoneSupply → ZoneExcavationJob）落地后补回的处理器 ——
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoColdDataEffectsHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoQolHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoStandFirmHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.ZoneSelectionHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.KunJinKaoTradeHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.SwordMiningPenaltyHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new dev.modmind.kunjinkao.event.SwordAreaFieldHandler());
        // 注入（替代原「管理员剑」的获取方式）：两个处理器都是静态方法订阅，按 class 注册。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.InjectionHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.InjectedItemBehaviorHandler.class);
        // 骚臭：挤尿、双君仪式、每 tick 逻辑。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.StenchInteractionHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.modmind.kunjinkao.event.StenchTickHandler.class);
        // TODO(下一批): QolHandler / ColdDataEffectsHandler / ZoneSelection / StandFirm / Trade /
        //               SwordMiningPenalty / AutoSmelt 的事件钩子 / SwordAreaField —— 都等 ZoneExcavationJob
        // TODO(后续批次): WorldGateRegistry / 事件处理器 / 客户端
    }
}
