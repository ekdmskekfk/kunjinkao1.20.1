package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.AcceleratorRegistry;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.SwordRegistry;
import dev.modmind.kunjinkao.clientbridge.ClientHooks;
import dev.modmind.kunjinkao.client.qol.QolClientState;
import dev.modmind.kunjinkao.client.qol.ZoneClientState;
import dev.modmind.kunjinkao.client.function.HudFunctionManager;
import dev.modmind.kunjinkao.client.render.AcceleratorBlockEntityRenderer;
import dev.modmind.kunjinkao.client.render.EyeHudLayer;
import dev.modmind.kunjinkao.client.render.HoneycombShieldLayer;
import dev.modmind.kunjinkao.client.render.KunJinKaoHandRenderer;
import dev.modmind.kunjinkao.client.render.KunJinKaoThirdPersonGrabLayer;
import dev.modmind.kunjinkao.client.model.KunJinKaoHandModel;
import dev.modmind.kunjinkao.client.hud.HudOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    static {
        ClientHooks.registerClientHandlers(payload -> ClientPayloadHandlers.handle(payload, null), ClientBlockScreens::openAcceleratorScreen);
        // 给 common 侧的 mixin 提供"客户端这一项开着吗"的查询（见 ClientHooks 里的说明）。
        ClientHooks.registerQolStateQuery(QolClientState::isOn);
        // 显式注册，而不是依赖 @EventBusSubscriber 对"嵌套类"的扫描：
        // 这个订阅负责客户端静态状态的生命周期清理，万一没被扫到就会静默失效（状态永不清理）。
        MinecraftForge.EVENT_BUS.register(ClientStateCleanup.class);
    }

    public static ResourceLocation compileModelLocation(int stage) {
        return new ResourceLocation(KunJinKaoEntry.MOD_ID, "item/kun_jin_kao_compile_" + stage);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 谓词返回值语义（配合 models/item/kun_jin_kao.json 的 overrides）：
        //   -1.0 → 完整剑模型；0.0 / 0.125 / … / 0.875 → 八个逐行编译阶段。
        //
        // 注意：ItemOverrides.resolve() 是"第一个满足 value >= threshold 的条目直接返回"，
        // 所以 kun_jin_kao.json 里的阈值必须按【降序】排列（0.875 → 0.0）。
        // 若改回升序，则除 0.0 之外的所有阈值都会被 0.0 那条抢先命中，
        // 表现为动画期间模型恒为 compile_0（一个小方块），也就是"选中剑时贴图消失"。
        event.enqueueWork(() -> ItemProperties.register(
                SwordRegistry.KUN_JIN_KAO_SWORD.get(),
                new ResourceLocation(KunJinKaoEntry.MOD_ID, "draw_compile"),
                (stack, level, entity, seed) -> KunJinKaoClientSwordVisuals.getDrawCompileModelStage(stack, entity)
        ));
        // 拟态刷的刷取动画：原版 brush.json 那三条 brushing override 只作用于原版刷子，
        // 我们的物品要自己注册同名谓词，才能在自己的模型文件里切刷取三帧。
        // 语义照抄原版：正在用这件物品时返回剩余时长的个位数 / 10（0.0 ~ 0.9）。
        event.enqueueWork(() -> ItemProperties.register(
                SwordRegistry.MIMIC_BRUSH.get(),
                new ResourceLocation("brushing"),
                (stack, level, entity, seed) -> {
                    if (entity != null && entity.getUseItem() == stack) {
                        return (entity.getUseItemRemainingTicks() % 10) / 10.0F;
                    }
                    return 0.0F;
                }
        ));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AcceleratorRegistry.ACCELERATOR_BE.get(), AcceleratorBlockEntityRenderer::new);
        // 锟斤拷之手。
        event.registerEntityRenderer(dev.modmind.kunjinkao.entity.KunJinKaoEntities.HAND.get(),
                KunJinKaoHandRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(KunJinKaoHandModel.LAYER, KunJinKaoHandModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        // 编译阶段模型（kun_jin_kao_compile_0..7）注册成 standalone 模型。
        //
        // ⚠ kun_jin_kao_compile_base.json 的 parent 必须是 minecraft:block/block，
        //   【不能】改回 minecraft:item/handheld：后者的父链根是 builtin/generated，
        //   MC 会对这类模型运行 ItemModelGenerator —— 改用 layer0 贴图逐像素生成四边形，
        //   并把模型自带的 elements 整个丢弃。编译阶段模型没有 layer0，于是烘培结果是
        //   0 个面的空模型，表现为「选中剑时手里的剑直接消失」。
        for (int stage = 0; stage < 8; stage++) {
            event.register(new ModelResourceLocation(compileModelLocation(stage), "standalone"));
        }
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KunJinKaoKeyBindings.TOGGLE_DISGUISE);
        event.register(KunJinKaoKeyBindings.TOGGLE_OVERWRITE);
        event.register(KunJinKaoKeyBindings.CYCLE_THEME);
        event.register(KunJinKaoKeyBindings.TOGGLE_TACTICAL_HUD);
        event.register(KunJinKaoKeyBindings.OPEN_SWORD_OPTIONS);
        event.register(KunJinKaoKeyBindings.OPEN_ADMIN_PASSWORD);
        event.register(KunJinKaoKeyBindings.UNDO_PLACEMENT);
        event.register(KunJinKaoKeyBindings.CYCLE_AREA_MODE);
        event.register(KunJinKaoKeyBindings.TOGGLE_WRENCH);
        event.register(KunJinKaoKeyBindings.COLLECT_BLOCK);
        event.register(KunJinKaoKeyBindings.OPEN_QOL_PANEL);
        event.register(KunJinKaoKeyBindings.INJECT);
    }

    @SubscribeEvent
    public static void registerOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
        // 1.20.1 的 HUD 是 IGuiOverlay：render(ForgeGui, GuiGraphics, float partialTick, int width, int height)，
        // 注册用 String id + registerAboveAll；1.21 是 LayeredDraw + DeltaTracker + ResourceLocation。
        event.registerAboveAll("kun_jin_kao_overwrite", (gui, guiGraphics, partialTick, screenWidth, screenHeight) -> {
            KunJinKaoOverwriteHudOverlay.render(guiGraphics, partialTick, screenWidth, screenHeight);
            KunJinKaoClientSwordVisuals.renderCompileModel(guiGraphics, screenWidth, screenHeight, partialTick);
            KunJinKaoClientSwordVisuals.renderAttackData(guiGraphics, screenWidth, screenHeight);
        });
        event.registerAboveAll("tactical_eye_hud", (gui, guiGraphics, partialTick, screenWidth, screenHeight) ->
                HudOverlayRenderer.render(guiGraphics, partialTick, screenWidth, screenHeight));
        // 注入进度条：屏幕正中的那一条。只读客户端镜像状态，不参与判定。
        event.registerAboveAll("injection_progress", (gui, guiGraphics, partialTick, screenWidth, screenHeight) ->
                dev.modmind.kunjinkao.client.render.InjectionProgressOverlay.render(
                        guiGraphics, screenWidth, screenHeight));
    }

    @SubscribeEvent
    public static void addPlayerLayers(EntityRenderersEvent.AddLayers event) {
        EyeHudLayer.addToPlayerRenderers(event);
        HoneycombShieldLayer.addToPlayerRenderers(event);
        KunJinKaoThirdPersonGrabLayer.addToPlayerRenderers(event);
    }

    /**
     * 客户端静态状态的生命周期清理入口。
     *
     * <p>本模组的客户端状态全部是进程级 static 字段（State/Visual 工具类），断线、退出世界、
     * 单机退回主菜单时都不会自动归零；而多处状态以 {@code level.getGameTime()} 或上个存档的
     * UUID 集合为基准，跨存档串味会产生可见错误（例如 ShieldHitVisualState 的护盾定格）。
     * 因此在这里统一清空，而不是让每个读取点各自兜底。</p>
     *
     * <p>NeoForge 21.1 的 {@code ClientPlayerNetworkEvent.LoggingOut} 挂在游戏总线
     * （{@code MinecraftForge.EVENT_BUS}）上，而外层类的 {@code @EventBusSubscriber} 声明的是 MOD 总线，
     * 所以放在这个客户端专用的嵌套类里，并由外层 static 块显式注册
     * （不依赖注解扫描，避免嵌套类万一没被扫到导致清理静默失效）。</p>
     */
    public static final class ClientStateCleanup {

        private ClientStateCleanup() {
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientHudState.reset();
            AdminEyeVisualState.reset();
            TacticalHudInvisibilityVisualState.reset();
            ShieldHitVisualState.reset();
            RemoteSwordDrawVisualState.clear();
            KunJinKaoClientOverwriteEffects.reset();
            KunJinKaoClientSwordVisuals.clear();
            HudFunctionManager.resetAll();
            // 选区挖掘的框：不清的话换个世界进来，只要还是主世界就会在原地继续画上一局的框。
            ZoneClientState.clear();
            // 装饰状态是服务端整表推来的快照：不清的话换服务器/存档后还会按上一局的名字画三把剑。
            CosmeticClientState.clear();
            // 注入进度条：断线时不清会留一条永远走不完的进度在屏幕中间。
            InjectionClientState.reset();
            // 管理员授权镜像：不清的话退到主菜单再进别的服务器，控制台会保持上一局的解锁状态。
            AdminAuthClientState.reset();
        }
    }
}
