package dev.modmind.my_mod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.modmind.my_mod.entity.SlashWaveEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Draws a large crossed, black-red vertical wave without requiring a model or texture asset. */
public final class SlashWaveRenderer extends EntityRenderer<SlashWaveEntity> {
    public SlashWaveRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.0F;
    }

    @Override
    public void render(SlashWaveEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entity.getYRot()));
        VertexConsumer vertices = buffer.getBuffer(RenderType.lightning());
        quad(vertices, poseStack.last(), -1.45F, -1.75F, 1.45F, 1.75F, 70, 0, 0, 185);
        poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
        quad(vertices, poseStack.last(), -1.25F, -1.55F, 1.25F, 1.55F, 25, 0, 0, 145);
        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static void quad(VertexConsumer vertices, PoseStack.Pose pose, float left, float bottom, float right, float top,
                             int red, int green, int blue, int alpha) {
        vertices.vertex(pose.pose(), left, bottom, 0.0F).color(red, green, blue, alpha).endVertex();
        vertices.vertex(pose.pose(), right, bottom, 0.0F).color(red, green, blue, alpha).endVertex();
        vertices.vertex(pose.pose(), right, top, 0.0F).color(red, green, blue, alpha).endVertex();
        vertices.vertex(pose.pose(), left, top, 0.0F).color(red, green, blue, alpha).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(SlashWaveEntity entity) {
        return new ResourceLocation("minecraft", "textures/misc/white.png");
    }
}
