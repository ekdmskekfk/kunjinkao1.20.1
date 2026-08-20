package dev.modmind.my_mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.my_mod.KunJinKaoSwordItem;
import dev.modmind.my_mod.KunJinKaoTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class KunJinKaoItemRenderer extends net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer {

    private static final ResourceLocation[] DEL_FRAMES = {
            new ResourceLocation("my_mod", "item/kun_jin_kao_frame_dark"),
            new ResourceLocation("my_mod", "item/kun_jin_kao_frame_mid"),
            new ResourceLocation("my_mod", "item/kun_jin_kao_frame_bright"),
            new ResourceLocation("my_mod", "item/kun_jin_kao_frame_mid")
    };

    private static final int DEL_BRIGHT_FRAME = 2;
    private static final ResourceLocation CURSOR_LIGHT = new ResourceLocation("my_mod", "item/kun_jin_kao_cursor_light");
    private static final ResourceLocation DIAMOND_SWORD = new ResourceLocation("minecraft", "item/diamond_sword");

    public KunJinKaoItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext transformType, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (KunJinKaoSwordItem.isDisguised(stack)) {
            // 伪装：直接渲染原版钻石剑精灵，避免任何状态污染
            renderSprite(poseStack, buffer, packedLight, packedOverlay, DIAMOND_SWORD, 0.0F, 1.0F, 1.0F, 1.0F);
            return;
        }

        // 两层均保持同一深度：同一 RenderType 缓冲区内后提交的四边形后绘制（BufferSource 未启用距离排序），
        // 光标必定叠加在剑框/DEL 之上，避免深度偏移方向在 GUI 与手持视角不一致导致光标被遮挡
        // 剑身 DEL 恒定显示中间帧，不再脉冲闪烁；阶段一「正在加载」期间切换至最亮帧并按主题染色
        float[] delTint = {1.0F, 1.0F, 1.0F};
        ResourceLocation delFrame = DEL_FRAMES[1];
        int loadingTheme = KunJinKaoClientOverwriteEffects.getLoadingTheme();
        if (loadingTheme >= 0) {
            delFrame = DEL_FRAMES[DEL_BRIGHT_FRAME];
            delTint = KunJinKaoTheme.tint(loadingTheme);
        }
        renderSprite(poseStack, buffer, packedLight, packedOverlay, delFrame, 0.0F, delTint[0], delTint[1], delTint[2]);
        // 终端光标恒定显示亮帧，不再明暗闪烁
        renderSprite(poseStack, buffer, packedLight, packedOverlay, CURSOR_LIGHT, 0.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderSprite(PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay,
                              ResourceLocation spritePath, float zOffset, float r, float g, float b) {
        // zOffset 参数保留供未来扩展。当前所有叠加层均使用 0，靠 entityTranslucent 同一缓冲区内
        // 顶点追加顺序保证后提交的光标层绘制在剑框之上；同深度下深度测试 LEQUAL 放行，无闪烁
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(spritePath);
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        float f = zOffset;
        float minU = sprite.getU0();
        float maxU = sprite.getU1();
        float minV = sprite.getV0();
        float maxV = sprite.getV1();
        float one = 1.0F;
        float zero = 0.0F;
        // 与 item/generated 一致：平铺 0..1 单位四边形，物品图层沿 +Y 法线，背向剔除保证单面可见
        consumer.vertex(matrix, zero, one, f).color(r, g, b, 1.0F).uv(minU, maxV).overlayCoords(packedOverlay).uv2(packedLight).normal(normal, 0.0F, 1.0F, 0.0F).endVertex();
        consumer.vertex(matrix, one, one, f).color(r, g, b, 1.0F).uv(maxU, maxV).overlayCoords(packedOverlay).uv2(packedLight).normal(normal, 0.0F, 1.0F, 0.0F).endVertex();
        consumer.vertex(matrix, one, zero, f).color(r, g, b, 1.0F).uv(maxU, minV).overlayCoords(packedOverlay).uv2(packedLight).normal(normal, 0.0F, 1.0F, 0.0F).endVertex();
        consumer.vertex(matrix, zero, zero, f).color(r, g, b, 1.0F).uv(minU, minV).overlayCoords(packedOverlay).uv2(packedLight).normal(normal, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
