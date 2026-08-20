package dev.modmind.my_mod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.my_mod.block.entity.AcceleratorBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * 加速方块渲染器：当"显示加速范围"开启时，绘制一个以方块为中心的
 * 蓝色半透明立方体线框，边长对应当前加速范围（3x3x3 ~ 9x9x9）。
 */
public class AcceleratorBlockEntityRenderer implements BlockEntityRenderer<AcceleratorBlockEntity> {

    public AcceleratorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AcceleratorBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!blockEntity.shouldShowRange()) {
            return;
        }
        // 本渲染器矩阵已平移到方块坐标，描边/填充都必须用该矩阵变换坐标，
        // 否则填充会画到错误的位置（例如世界原点附近）。
        double r = blockEntity.getRadius();
        AABB box = new AABB(BlockPos.ZERO).inflate(r);
        Matrix4f pose = poseStack.last().pose();

        // 蓝色半透明填充
        VertexConsumer fill = bufferSource.getBuffer(RenderType.debugFilledBox());
        renderFilledBox(fill, pose, box, 0.15F, 0.45F, 1.0F, 0.16F);

        // 蓝色描边（renderLineBox 内部会用 poseStack 变换坐标）
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, lines, box, 0.25F, 0.65F, 1.0F, 0.9F);
    }

    /** 用 6 个四边形画出一个半透明填充立方体。 */
    private static void renderFilledBox(VertexConsumer consumer, Matrix4f pose, AABB box,
                                        float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        quad(consumer, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a); // -Z
        quad(consumer, pose, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a); // +Z
        quad(consumer, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a); // +Y
        quad(consumer, pose, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, r, g, b, a); // -Y
        quad(consumer, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a); // +X
        quad(consumer, pose, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, b, a); // -X
    }

    private static void quad(VertexConsumer consumer, Matrix4f pose,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float r, float g, float b, float a) {
        consumer.vertex(pose, x0, y0, z0).color(r, g, b, a).endVertex();
        consumer.vertex(pose, x1, y1, z1).color(r, g, b, a).endVertex();
        consumer.vertex(pose, x2, y2, z2).color(r, g, b, a).endVertex();
        consumer.vertex(pose, x3, y3, z3).color(r, g, b, a).endVertex();
    }
}