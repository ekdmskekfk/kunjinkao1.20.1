package dev.modmind.my_mod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.my_mod.ModMindEntry;
import dev.modmind.my_mod.client.ClientHudState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.EntityRenderersEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 绑定在 PlayerModel.head 上的第三人称眼部终端。只渲染本地玩家，因而无需网络同步。
 */
public final class EyeHudLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private static final ResourceLocation EYE_HUD_TEXTURE =
            new ResourceLocation(ModMindEntry.MOD_ID, "textures/gui/eye_hud.png");
    private static final float LOCAL_LEFT_EYE_X = -0.14F;
    // PlayerModel 坐标每个皮肤像素为 1/16 方块；正 Y 在头部局部坐标中向下。
    private static final float EYE_Y = -0.29F + (1.0F / 16.0F);
    private static final float FRONT_OF_FACE_Z = -0.258F;

    public EyeHudLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    public static void addToPlayerRenderers(EntityRenderersEvent.AddLayers event) {
        addLayer(event.getSkin("default"));
        addLayer(event.getSkin("slim"));
    }

    private static void addLayer(PlayerRenderer renderer) {
        if (renderer != null) {
            renderer.addLayer(new EyeHudLayer(renderer));
        }
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientHudState.isVisible()
                || minecraft.player != player
                || minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }

        float alphaProgress = ClientHudState.getAnimationProgress();
        int alpha = Math.round(190.0F * alphaProgress);
        float halfSize = 0.105F + 0.025F * alphaProgress;

        poseStack.pushPose();
        // ModelPart 会在此处应用头部的平移和旋转；位置不依赖世界绝对坐标。
        getParentModel().head.translateAndRotate(poseStack);
        poseStack.translate(LOCAL_LEFT_EYE_X, EYE_Y, FRONT_OF_FACE_Z);

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucentEmissive(EYE_HUD_TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        renderQuad(consumer, pose, halfSize, alpha);
        poseStack.popPose();
    }

    private static void renderQuad(VertexConsumer consumer, PoseStack.Pose pose, float halfSize, int alpha) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        vertex(consumer, matrix, normal, -halfSize, -halfSize, 0.0F, 0.0F, 0.0F, alpha);
        vertex(consumer, matrix, normal, halfSize, -halfSize, 0.0F, 1.0F, 0.0F, alpha);
        vertex(consumer, matrix, normal, halfSize, halfSize, 0.0F, 1.0F, 1.0F, alpha);
        vertex(consumer, matrix, normal, -halfSize, halfSize, 0.0F, 0.0F, 1.0F, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float z, float u, float v, int alpha) {
        consumer.vertex(matrix, x, y, z)
                .color(143, 234, 255, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normal, 0.0F, 0.0F, -1.0F)
                .endVertex();
    }
}
