package dev.modmind.kunjinkao.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.KunJinKaoClientSwordVisuals;
import dev.modmind.kunjinkao.client.RemoteSwordDrawVisualState;
import dev.modmind.kunjinkao.client.TacticalHudInvisibilityVisualState;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 第三人称拔剑编译后半段的真实玩家手臂取剑动画。 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KunJinKaoThirdPersonGrabLayer
        extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public KunJinKaoThirdPersonGrabLayer(
            RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    public static void addToPlayerRenderers(EntityRenderersEvent.AddLayers event) {
        addLayer(event.getSkin("default"));
        addLayer(event.getSkin("slim"));
    }

    private static void addLayer(PlayerRenderer renderer) {
        if (renderer != null) {
            renderer.addLayer(new KunJinKaoThirdPersonGrabLayer(renderer));
        }
    }

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        if (!isReachActive(player)
                || TacticalHudInvisibilityVisualState.isTrueInvisible(player.getUUID())) {
            return;
        }
        setAnimatedArmVisible(event.getRenderer().getModel(),
                getCompileArm(player), false);
    }

    /**
     * 恢复共享 PlayerModel 的手臂可见性。
     *
     * <p>这里刻意不再判断 isReachActive：手臂只在 Pre 里被隐藏，而 Pre 与 Post 之间动画可能刚好结束
     * （或本层的 render 提前 return）。如果恢复也被同一个条件挡住，共享模型的手/袖子会永久留在
     * 不可见状态，之后每一帧的手臂都会消失。真隐形的判断则与 Pre 保持对称 —— 那种情况下整次玩家
     * 渲染都被取消，Post 本来也不会触发。</p>
     */
    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        if (TacticalHudInvisibilityVisualState.isTrueInvisible(player.getUUID())) {
            return;
        }
        setAnimatedArmVisible(event.getRenderer().getModel(),
                getCompileArm(player), true);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        // 守卫与 Pre 对称：真隐形时整次玩家渲染已被取消，这里也不应再自绘手臂。
        if (!isReachActive(player)
                || TacticalHudInvisibilityVisualState.isTrueInvisible(player.getUUID())) {
            return;
        }

        PlayerModel<AbstractClientPlayer> model = getParentModel();
        HumanoidArm side = getCompileArm(player);
        ModelPart arm = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        ModelPart sleeve = side == HumanoidArm.RIGHT ? model.rightSleeve : model.leftSleeve;
        var armPose = arm.storePose();
        var sleevePose = sleeve.storePose();
        float progress = getReachProgress(player);
        float inward = side == HumanoidArm.RIGHT ? -0.28F : 0.28F;

        try {
            renderRemoteCompileSword(poseStack, buffer, packedLight, player, side);

            arm.visible = true;
            sleeve.visible = true;
            // 从当前走路/待机姿势平滑抬起，并伸向角色正前方的编译剑。
            arm.xRot = Mth.lerp(progress, arm.xRot, -1.38F);
            arm.yRot = Mth.lerp(progress, arm.yRot, inward);
            arm.zRot = Mth.lerp(progress, arm.zRot, 0.0F);
            sleeve.copyFrom(arm);

            VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(player.getSkinTextureLocation()));
            arm.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
            sleeve.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        } finally {
            // PlayerModel 是渲染器级共享状态：无论渲染中途是否抛异常，都必须还原姿势与可见性，
            // 否则会把"变形的手臂"或"永远不可见的手臂"泄漏到后续帧。
            arm.loadPose(armPose);
            sleeve.loadPose(sleevePose);
            // 后续层不应再次画默认手臂；可见性由 RenderPlayerEvent.Post 无条件恢复。
            arm.visible = false;
            sleeve.visible = false;
        }
    }

    private static void setAnimatedArmVisible(PlayerModel<AbstractClientPlayer> model,
                                              HumanoidArm side, boolean visible) {
        if (side == HumanoidArm.RIGHT) {
            model.rightArm.visible = visible;
            model.rightSleeve.visible = visible;
        } else {
            model.leftArm.visible = visible;
            model.leftSleeve.visible = visible;
        }
    }

    private static boolean isReachActive(AbstractClientPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        return player == minecraft.player
                ? KunJinKaoClientSwordVisuals.isThirdPersonReachActive(player)
                : RemoteSwordDrawVisualState.isReachActive(player.getUUID());
    }

    private static float getReachProgress(AbstractClientPlayer player) {
        return player == Minecraft.getInstance().player
                ? KunJinKaoClientSwordVisuals.getCompileReachProgress()
                : RemoteSwordDrawVisualState.getReachProgress(player.getUUID());
    }

    private static HumanoidArm getCompileArm(AbstractClientPlayer player) {
        if (player == Minecraft.getInstance().player) {
            return KunJinKaoClientSwordVisuals.getCompileArm(player);
        }
        InteractionHand hand = RemoteSwordDrawVisualState.getHand(player.getUUID());
        if (hand == InteractionHand.MAIN_HAND) {
            return player.getMainArm();
        }
        return player.getMainArm() == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    // 远端编译剑的展示栈缓存：原先每个远端玩家每帧都要 copy() 物品栈 + copyTag() + new CustomData，
    // 这里按"玩家 + 当前手持物内容"缓存，只有手持物变化（ItemStack.matches 为假）时才重建。
    private static final Map<UUID, RemoteSwordVisual> REMOTE_SWORD_CACHE = new HashMap<>();

    /** 缓存项：source 为原始手持栈（副本），visual 为带编译标记的展示栈。 */
    private record RemoteSwordVisual(ItemStack source, ItemStack visual) {
    }

    private static ItemStack cachedRemoteVisualStack(AbstractClientPlayer player, ItemStack sword) {
        RemoteSwordVisual cached = REMOTE_SWORD_CACHE.get(player.getUUID());
        if (cached != null && ItemStack.matches(cached.source(), sword)) {
            return cached.visual();
        }
        ItemStack visualStack = sword.copy();
        // 1.20.1 没有 CUSTOM_DATA 组件：模型数据就存在物品自己的根 NBT 上。
        CompoundTag modelTag = visualStack.getTag() == null ? new CompoundTag() : visualStack.getTag().copy();
        visualStack.setTag(modelTag);
        // source 也必须 copy()：如果直接保存玩家手上的同一个实例，后续改动会让 ItemStack.matches 失去意义。
        REMOTE_SWORD_CACHE.put(player.getUUID(), new RemoteSwordVisual(sword.copy(), visualStack));
        return visualStack;
    }

    private void renderRemoteCompileSword(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                          AbstractClientPlayer player, HumanoidArm side) {
        if (player == Minecraft.getInstance().player) {
            return;
        }
        if (!RemoteSwordDrawVisualState.isCompiling(player.getUUID())) {
            // 编译结束后清掉缓存，避免为不再渲染的玩家长期保留物品栈副本。
            REMOTE_SWORD_CACHE.remove(player.getUUID());
            return;
        }

        InteractionHand hand = RemoteSwordDrawVisualState.getHand(player.getUUID());
        ItemStack sword = player.getItemInHand(hand);
        if (!(sword.getItem() instanceof KunJinKaoSwordItem) || KunJinKaoSwordItem.isDisguised(sword)) {
            return;
        }

        float progress = RemoteSwordDrawVisualState.getCompileProgress(player.getUUID());
        float sideOffset = side == HumanoidArm.RIGHT ? -0.16F : 0.16F;
        ItemStack visualStack = cachedRemoteVisualStack(player, sword);

        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        // 剑在角色胸前略偏持剑侧，尺寸小于正常第三人称剑，避免遮住头部。
        poseStack.translate(sideOffset, -0.36F, -0.56F);
        poseStack.mulPose(Axis.YP.rotationDegrees(side == HumanoidArm.RIGHT ? 22.0F : -22.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(8.0F));
        float scale = 0.28F + progress * 0.40F;
        poseStack.scale(scale, scale, scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(visualStack, ItemDisplayContext.FIXED,
                packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, player.level(), player.getId());
        poseStack.popPose();
    }
}
