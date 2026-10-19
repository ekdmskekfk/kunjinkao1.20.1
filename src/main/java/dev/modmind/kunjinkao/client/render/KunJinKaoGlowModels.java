package dev.modmind.kunjinkao.client.render;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.RenderTypeHelper;
import net.minecraftforge.client.event.ModelEvent;

import java.util.List;
import java.util.Map;

/**
 * 让剑的青色部件在暗处<b>自己发光</b>。
 * <p>
 * ## 走的是哪条路，以及为什么不是别的
 *
 * 先试过、也否决掉的路：
 * <ul>
 *   <li>{@code IClientItemExtensions} —— 它没有 getRenderType，只能改字体、手臂姿势、
 *       自定义渲染器，给不了"按物品换渲染类型"。</li>
 *   <li>在 {@code RenderHandEvent} 里自己再画一遍 —— 那个事件跑在原版渲染【之前】，
 *       自发光那一遍会被随后画上去的不透明本体盖掉。要它显示只能取消原版渲染自己重画，
 *       而取消会把玩家的手臂也一起取消掉（原版手臂是由被取消的那段代码画的），
 *       必须照抄一遍原版的第一人称手臂变换才能补回来 —— 脆，且只手部生效。</li>
 *   <li>原版附魔光效 —— 它的贴图路径是全局的，改了整个整合包里所有附魔物品都变色。</li>
 * </ul>
 *
 * 正路是 NeoForge 在废弃提示里指向的那个：
 * <b>{@code BakedModel#getRenderPasses(ItemStack, boolean)} 返回多个渲染通道，
 * 每个通道各自通过 {@code getRenderTypes(ItemStack, boolean)} 指定渲染类型。</b>
 * 这样顺序由原版保证（本体先画、发光后画，深度测试 LEQUAL 使得同深度也能覆盖），
 * 变换由原版施加，而且手里、背包、地上、展示框<b>全都会发光</b>。
 * <p>
 * 注册口用 {@code ModifyBakingResult} 直接换掉烘焙好的本体模型 ——
 * NeoForge 的 {@code ModelEvent} 没有提供注册自定义 {@code UnbakedModel} 反序列化器的入口，
 * 所以拼不了"加载时组合两个模型"，但烘焙完之后替换是允许的。
 * <p>
 * 发光那一遍用 {@code entityTranslucentEmissive}：它就是不采样光照贴图的着色器
 * （原版蜘蛛眼睛发光靠的也是它），因此"暗处仍亮"的效果成立。
 * <p>
 * ## 0.9.1 当初为什么把资源重载搞崩了
 *
 * {@code RegisterAdditional.register()} <b>只接受 standalone 变体</b>，传 inventory 会抛
 * {@code IllegalArgumentException: Side-loaded models must use the 'standalone' variant}，
 * 而异常发生在 ModelBakery 构造期间 —— 直接表现为"资源重载失败"。
 * <p>
 * 它的 javadoc 写得很明白：<i>传入的 MRL 必须用同一个来取回被加载的模型</i>。
 * 所以这里注册与取回都走 {@code standalone}。
 * <p>
 * 取回还特意做成<b>懒解析</b>：side-loaded 模型的烘焙时机与 {@code ModifyBakingResult}
 * 的先后顺序没有保证，放在构造期去查会拿到空值；推迟到第一次渲染时再取，
 * 那时 ModelManager 早就装好了。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KunJinKaoGlowModels {

    private static final ResourceLocation BASE_ID =
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "item/kun_jin_kao_3d");
    private static final ResourceLocation GLOW_ID =
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "item/kun_jin_kao_3d_glow");
    /** 两个模型都用这一张图集，所以发光那一遍的渲染类型也指向它。 */
    private static final ResourceLocation ATLAS =
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "item/kun_jin_kao_atlas");

    /** side-loaded 模型的身份：RegisterAdditional 与取回都必须用 standalone。 */
    private static final ModelResourceLocation GLOW_KEY = new ModelResourceLocation(GLOW_ID, "standalone");

    private KunJinKaoGlowModels() {
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        // 发光模型没有任何东西引用它，不注册就不会被烘焙。
        // 这里【必须】是 standalone —— 传 inventory 会让整个资源重载失败。
        event.register(GLOW_KEY);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        // 1.20.1 的 ModifyBakingResult#getModels() 是 Map<ModelResourceLocation, BakedModel>；
        // 1.21 改成了 Map<ResourceLocation, BakedModel>，所以这里不需要再做类型转换。
        Map<ResourceLocation, BakedModel> models = event.getModels();
        ModelResourceLocation baseKey = new ModelResourceLocation(BASE_ID, "inventory");
        BakedModel base = models.get(baseKey);
        if (base == null || base instanceof GlowBakedModel) {
            return;
        }
        models.put(baseKey, new GlowBakedModel(base));
    }

    /**
     * 两层通道的模型：第 0 层是本体，第 1 层是只含青色部件的发光层。
     * <p>
     * 除多通道相关的两个方法外，其余全部转交给本体 ——
     * 显示变换、overrides、粒子图标都必须来自本体，否则物品的
     * display 与八级编译的 overrides 会失效。
     * <p>
     * 不用 record：发光层要可变（懒解析后缓存）。
     */
    private static final class GlowBakedModel implements BakedModel {

        private final BakedModel base;
        private BakedModel glow;
        private boolean glowResolved;

        private GlowBakedModel(BakedModel base) {
            this.base = base;
        }

        /** 第一次需要时才去 ModelManager 取，取不到就退化成只有本体一层。 */
        private BakedModel glow() {
            if (!glowResolved) {
                glowResolved = true;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    BakedModel resolved = minecraft.getModelManager().getModel(GLOW_KEY);
                    if (resolved != null && resolved != minecraft.getModelManager().getMissingModel()) {
                        glow = resolved;
                    }
                }
            }
            return glow;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            BakedModel layer = glow();
            return layer == null ? List.of(base) : List.of(base, layer);
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            // 必须与 getRenderPasses 的长度一致，否则原版取渲染类型时会越界
            if (glow() == null) {
                return List.of(RenderTypeHelper.getFallbackItemRenderType(stack, base, fabulous));
            }
            return List.of(
                    // 本体沿用 NeoForge 的默认判定，避免自己写死 cutout 改变透明度处理
                    RenderTypeHelper.getFallbackItemRenderType(stack, base, fabulous),
                    RenderType.entityTranslucentEmissive(ATLAS));
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
            return base.getQuads(state, side, rand);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return base.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return base.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return base.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return base.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return base.getParticleIcon();
        }

        @Override
        public ItemTransforms getTransforms() {
            return base.getTransforms();
        }

        @Override
        public ItemOverrides getOverrides() {
            return base.getOverrides();
        }
    }
}