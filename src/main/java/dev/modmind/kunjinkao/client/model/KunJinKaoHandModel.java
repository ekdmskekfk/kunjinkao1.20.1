package dev.modmind.kunjinkao.client.model;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.entity.KunJinKaoHandEntity;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 君君的模型 —— **几何由用户在 Blockbench 5.2.1 里制作**（junjun.java），这里原样移植：
 * 所有 {@code texOffs}/{@code addBox}/{@code PartPose} 一概未动。
 * <p>
 * 在用户几何之上只做了三件事：
 * <ol>
 *   <li><b>包一层父节点 {@code body}</b>（零偏移，不改外观）：所有部件挂到它下面，
 *       于是"整体转向/整体起伏"有了单一的落点，而不用逐个部件去改；</li>
 *   <li><b>朝向对齐</b>：用户建模时手指指向模型 <b>-X</b>（五根指骨在 x=-14~-16，向 +X 伸展），
 *       而实体模型的脸朝 <b>-Z</b>（原版四足动物的头在 -Z），所以要整体转 -90°，
 *       否则君君会横着爬。做成常量 {@link #FACING_YAW}，进游戏方向不对就改它（0 / ±90）；</li>
 *   <li><b>蠕动动画</b>：五根手指各带相位差做波浪（蜷伸），掌身缓慢起伏侧倾 ——
 *       全是模型动画，实体层用普通寻路。</li>
 * </ol>
 * <p>
 * <b>坐标系备忘：</b>模型坐标 Y 翻转，{@code y_model = 24} 是地面；用户的 {@code bb_main}
 * 放在 y=24（Boxbench 的习惯），所以贴地这件事天然正确。
 * <p>
 * <b>贴图约定（上色按这个来）：</b>皮肤部件共用几块皮肤区（底色+受光+血管+褶皱+腕部小痣），
 * 指尖的 5 个 1x1x1 小块用白甲区 —— 指骨链最远端（离掌最远）是尖端，尖端就是指甲。
 */
public class KunJinKaoHandModel extends EntityModel<KunJinKaoHandEntity> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "junjun"), "main");

    /**
     * 朝向对齐：建模时手指朝 -X，实体脸朝 -Z，差一个 -90° 的绕 Y 旋转。
     * 进游戏如果横着爬/倒着爬，改这一个数（0 = 不转，90 = 反向）。
     */
    private static final float FACING_YAW = -(float) (Math.PI / 2.0D);

    /** 模型坐标里的"地面"（Y 翻转，24 = 脚底）。 */
    private static final float GROUND = 24.0F;

    private final ModelPart body;
    private final ModelPart[] fingers = new ModelPart[5];
    private final ModelPart palm;
    private final ModelPart plate;

    public KunJinKaoHandModel(ModelPart root) {
        this.body = root.getChild("body");
        // 指骨顺序照用户类的字段顺序：bone3 / bone / bone2 / bone5 / bone4。
        this.fingers[0] = this.body.getChild("bone3");
        this.fingers[1] = this.body.getChild("bone");
        this.fingers[2] = this.body.getChild("bone2");
        this.fingers[3] = this.body.getChild("bone5");
        this.fingers[4] = this.body.getChild("bone4");
        this.plate = this.body.getChild("bone6");
        this.palm = this.body.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();
        // 零偏移父节点：给"整体转向/起伏"一个落点，不改变任何几何。
        PartDefinition partdefinition2 = partdefinition.addOrReplaceChild("body",
                CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));

        PartDefinition bone3 = partdefinition2.addOrReplaceChild("bone3", CubeListBuilder.create().texOffs(12, 31).addBox(0.0F, -2.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
        .texOffs(16, 31).addBox(2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-14.0F, 21.0F, 0.0F, 3.1416F, 0.0F, 0.0F));

        PartDefinition cube_r1 = bone3.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(12, 28).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition cube_r2 = bone3.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(8, 28).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition bone = partdefinition2.addOrReplaceChild("bone", CubeListBuilder.create().texOffs(28, 29).addBox(2.0F, -2.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
        .texOffs(0, 31).addBox(2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-16.0F, 21.0F, 2.0F, 3.1416F, 0.0F, 0.0F));

        PartDefinition cube_r3 = bone.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(4, 28).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition cube_r4 = bone.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(20, 26).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition bone2 = partdefinition2.addOrReplaceChild("bone2", CubeListBuilder.create().texOffs(4, 31).addBox(0.0F, -2.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
        .texOffs(8, 31).addBox(2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-14.0F, 21.0F, 1.0F, 3.1416F, 0.0F, 0.0F));

        PartDefinition cube_r5 = bone2.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(0, 28).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition cube_r6 = bone2.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(24, 26).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition bone5 = partdefinition2.addOrReplaceChild("bone5", CubeListBuilder.create().texOffs(24, 32).addBox(0.0F, -2.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
        .texOffs(32, 26).addBox(2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-14.0F, 21.0F, -2.0F, 3.1416F, 0.0F, 0.0F));

        PartDefinition cube_r7 = bone5.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(24, 29).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition cube_r8 = bone5.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(20, 29).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition bone4 = partdefinition2.addOrReplaceChild("bone4", CubeListBuilder.create().texOffs(28, 31).addBox(0.0F, -2.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
        .texOffs(20, 32).addBox(2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-14.0F, 21.0F, -1.0F, 3.1416F, 0.0F, 0.0F));

        PartDefinition cube_r9 = bone4.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(28, 26).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition cube_r10 = bone4.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(16, 28).addBox(0.0F, -2.0F, 0.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition bone6 = partdefinition2.addOrReplaceChild("bone6", CubeListBuilder.create().texOffs(20, 23).addBox(-3.0F, -1.0F, 0.0F, 6.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
        .texOffs(20, 19).addBox(-3.0F, -1.0F, -3.0F, 6.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(-10.0F, 23.0F, 0.0F));

        PartDefinition bb_main = partdefinition2.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-7.0F, -6.0F, -7.0F, 11.0F, 6.0F, 13.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition cube_r11 = bb_main.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(0, 19).addBox(-2.0F, -2.0F, -5.0F, 3.0F, 2.0F, 7.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -4.0F, 1.0F, 0.0F, 0.0F, -1.5708F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(KunJinKaoHandEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float amplitude = entity.squirmAmplitude(limbSwingAmount);
        // 蠕动相位：limbSwing 随移动推进（拱动节奏与真实位移同步），idle 提供站立的慢波。
        float wave = limbSwing * 0.8F;
        float idle = ageInTicks * 0.05F;

        // 整体：转向（含 -90° 的朝向对齐）+ 轻微起伏侧倾。
        // Y 翻转坐标系里"往上"是 y 减小，所以抬升用减法。
        this.body.yRot = FACING_YAW + netHeadYaw * (float) (Math.PI / 180.0D) * 0.3F;
        this.body.y = -Math.max(0.0F, Mth.sin(wave + idle)) * 0.6F * amplitude;
        this.body.zRot = Mth.sin(idle * 0.8F) * 0.05F * amplitude;

        // 掌身：缓慢的呼吸式俯仰。
        this.palm.xRot = Mth.sin(wave + idle + 0.8F) * 0.06F * amplitude;

        // 五指：相位差波浪（蜷-伸），像肉虫的体节波。
        // 注意每个指骨的基础 xRot 是 3.1416F（用户建模时转了 180°），必须带着它叠加，不能覆盖。
        for (int i = 0; i < this.fingers.length; i++) {
            float phase = i * 0.9F;
            ModelPart finger = this.fingers[i];
            finger.xRot = 3.1416F + Mth.sin(wave + idle + phase) * 0.30F * amplitude;
            finger.yRot = Mth.sin(wave * 0.6F + idle + phase + 1.2F) * 0.12F * amplitude;
        }

        // 拇指板：慢半拍，轻轻搭着。
        this.plate.xRot = Mth.sin(wave * 0.7F + idle + 2.0F) * 0.08F * amplitude;
        this.plate.yRot = Mth.sin(wave * 0.5F + idle) * 0.06F * amplitude;
    }

    @Override
    public void renderToBuffer(com.mojang.blaze3d.vertex.PoseStack poseStack,
                               com.mojang.blaze3d.vertex.VertexConsumer buffer,
                               int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        // 1.20.1 的 ModelPart#render 只有 (PoseStack, VertexConsumer, int, int)（1.21 才多一个 color）。
        this.body.render(poseStack, buffer, packedLight, packedOverlay);
    }
}
