package dev.modmind.kunjinkao.client.render;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.model.KunJinKaoHandModel;
import dev.modmind.kunjinkao.entity.KunJinKaoHandEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 锟斤拷之手的渲染器。
 * <p>
 * 阴影半径 0.3：它趴在地上、很扁，阴影太大看起来像悬空。
 * 没有加任何 layer —— 那些细节（血管、痣、指甲的白）都画在贴图里，
 * 模型的盒子数已经不少，多一层渲染只会更慢。
 */
public class KunJinKaoHandRenderer extends MobRenderer<KunJinKaoHandEntity, KunJinKaoHandModel> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "textures/entity/hand.png");

    public KunJinKaoHandRenderer(EntityRendererProvider.Context context) {
        super(context, new KunJinKaoHandModel(context.bakeLayer(KunJinKaoHandModel.LAYER)), 0.3F);
    }

    @Override
    public ResourceLocation getTextureLocation(KunJinKaoHandEntity entity) {
        return TEXTURE;
    }
}