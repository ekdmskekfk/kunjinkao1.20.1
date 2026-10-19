package dev.modmind.kunjinkao.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.kunjinkao.client.ShieldHitVisualState;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

import java.util.List;

/** A short-lived, translucent cyan honeycomb shield just ahead of the player model. */
public final class HoneycombShieldLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private static final String ACCESS_DENIED_TEXT = "ACCESS DENIED";
    private static final float TEXT_HOLD_TICKS = 10.0F;
    private static final float CELL_RADIUS = 0.10F;
    private static final float SQRT_THREE = 1.7320508F;
    // Keep the shield well in front of the model so the player can see each patch clearly.
    private static final float MODEL_SURFACE_OFFSET = -0.62F;
    private static final float[][] CELL_CENTERS = {
            {0.0F, 0.0F},
            {1.5F, 0.8660254F}, {0.0F, SQRT_THREE}, {-1.5F, 0.8660254F},
            {-1.5F, -0.8660254F}, {0.0F, -SQRT_THREE}, {1.5F, -0.8660254F}
    };

    /**
     * 单位圆上正六边形顶点的偏移量（已乘 CELL_RADIUS），下标为 2 * 顶点序号，
     * 偶数位是 X、奇数位是 Y。预计算成常量表后，每帧不再分配数组、也不再调用 12 次 cos/sin。
     * 计算方法与原逐帧实现逐字一致，半径与角度换算不变，视觉结果保持不变。
     */
    private static final float[] HEX_VERTEX_OFFSETS = createHexVertexOffsets();

    private static float[] createHexVertexOffsets() {
        float[] offsets = new float[12];
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(i * 60.0D);
            offsets[i * 2] = (float) Math.cos(angle) * CELL_RADIUS;
            offsets[i * 2 + 1] = (float) Math.sin(angle) * CELL_RADIUS;
        }
        return offsets;
    }

    public HoneycombShieldLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    public static void addToPlayerRenderers(EntityRenderersEvent.AddLayers event) {
        addLayer(event.getSkin("default"));
        addLayer(event.getSkin("slim"));
    }

    private static void addLayer(PlayerRenderer renderer) {
        if (renderer != null) {
            renderer.addLayer(new HoneycombShieldLayer(renderer));
        }
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        List<ShieldHitVisualState.ShieldVisual> visuals = ShieldHitVisualState.getVisuals(player.getUUID());
        if (visuals.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        // Body is the model anchor. Minecraft model-space rotation is opposite world yaw,
        // so both the impact and view offsets are negated before they are applied.
        float viewRelativeToBody = player.getYRot() - player.yBodyRot;
        for (ShieldHitVisualState.ShieldVisual visual : visuals) {
            poseStack.pushPose();
            poseStack.translate(0.0F, visual.impactHeight(), 0.0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-visual.relativeImpactYaw() - viewRelativeToBody));
            poseStack.translate(0.0F, 0.0F, MODEL_SURFACE_OFFSET);

            PoseStack.Pose pose = poseStack.last();
            VertexConsumer lines = buffer.getBuffer(RenderType.lines());
            for (float[] center : CELL_CENTERS) {
                renderHexagon(lines, pose, center[0] * CELL_RADIUS, center[1] * CELL_RADIUS, visual.alpha());
            }
            renderAccessDenied(poseStack, buffer, visual, partialTick);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void renderAccessDenied(PoseStack poseStack, MultiBufferSource buffer,
                                           ShieldHitVisualState.ShieldVisual visual, float partialTick) {
        float age = visual.ageTicks() + partialTick;
        // 前 0.5 秒完全可见，之后在剩余的 0.5 秒中逐渐消失。
        float opacity = age <= TEXT_HOLD_TICKS
                ? 1.0F
                : Math.max(0.0F, visual.alpha() * 2.0F);
        if (opacity <= 0.01F) {
            return;
        }

        float travel = Math.min(1.0F, age / 20.0F);
        float driftX = -0.29F + travel * 0.58F;
        float driftY = -0.255F + (float) Math.sin(age * 0.42F) * 0.018F;
        int alpha = Math.max(0, Math.min(255, Math.round(235.0F * opacity)));
        int color = (alpha << 24) | 0x9DEBFF;
        Font font = Minecraft.getInstance().font;

        poseStack.pushPose();
        poseStack.translate(driftX, driftY, -0.008F);
        // 字体使用像素坐标，缩放到与蜂巢边框匹配的世界尺寸。
        poseStack.scale(0.0080F, -0.0080F, 0.0080F);
        float textX = -font.width(ACCESS_DENIED_TEXT) / 2.0F;
        font.drawInBatch(ACCESS_DENIED_TEXT, textX, 0.0F, color, true,
                poseStack.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH,
                0, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private static void renderHexagon(VertexConsumer lines, PoseStack.Pose pose,
                                      float centerX, float centerY, float opacity) {
        Matrix4f matrix = pose.pose();
        // Lines deliberately keep the shield transparent while remaining visible at a distance.
        int lineAlpha = Math.round(210.0F * opacity);
        for (int i = 0; i < 6; i++) {
            int next = (i + 1) % 6;
            line(lines, pose, matrix,
                    centerX + HEX_VERTEX_OFFSETS[i * 2], centerY + HEX_VERTEX_OFFSETS[i * 2 + 1],
                    centerX + HEX_VERTEX_OFFSETS[next * 2], centerY + HEX_VERTEX_OFFSETS[next * 2 + 1],
                    lineAlpha);
        }
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose, Matrix4f matrix,
                             float x0, float y0, float x1, float y1, int alpha) {
        consumer.vertex(matrix, x0, y0, 0.0F).color(125, 224, 255, alpha)
                .normal(pose.normal(), 0.0F, 0.0F, -1.0F).endVertex();
        consumer.vertex(matrix, x1, y1, 0.0F).color(125, 224, 255, alpha)
                .normal(pose.normal(), 0.0F, 0.0F, -1.0F).endVertex();
    }

}
