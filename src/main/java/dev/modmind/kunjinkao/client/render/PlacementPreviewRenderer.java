package dev.modmind.kunjinkao.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderHighlightEvent;

/**
 * 区域场预览：把"以点击方块为中心、按当前边长"的那个立方体画成线框。
 * <p>
 * 这个类原来还兼着建筑手杖与破坏核心的预览 —— 那部分已经按 Construction Wand KOTS
 * 重写成 {@link WandPreviewRenderer}（改由服务端算、客户端只画）。这里只剩区域场：
 * 它的范围严格按判定口径来（中心格 ±radius），预览画多大，实际就停多大。
 * <p>
 * 两种区域模式用颜色与脉动速度区分：暂停场是冷青、慢脉动；范围加速是暖黄、快脉动。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PlacementPreviewRenderer {

    private PlacementPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onHighlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        if (event.getTarget().getType() != HitResult.Type.BLOCK) {
            return;
        }
        ItemStack sword = player.getMainHandItem();
        if (!(sword.getItem() instanceof KunJinKaoSwordItem)) {
            return;
        }
        int areaMode = KunJinKaoSwordItem.getAreaMode(sword);
        if (areaMode == 0) {
            return;
        }
        drawAreaFieldPreview(event, sword, event.getTarget().getBlockPos(), areaMode);
    }

    private static void drawAreaFieldPreview(RenderHighlightEvent.Block event, ItemStack sword,
                                             BlockPos center, int areaMode) {
        boolean pause = areaMode == 1;
        int radius = pause
                ? KunJinKaoSwordItem.getPauseSize(sword)
                : KunJinKaoSwordItem.getAreaAccelSize(sword);

        double minX = center.getX() - radius;
        double minY = center.getY() - radius;
        double minZ = center.getZ() - radius;
        double size = radius * 2 + 1;
        AABB box = new AABB(minX, minY, minZ, minX + size, minY + size, minZ + size);

        float phase = (float) ((System.currentTimeMillis() % 100000L) / 1000.0D);
        float pulse = 0.55F + 0.45F * Math.abs((float) Math.sin(phase * (pause ? 1.6F : 3.2F)));
        float red = pause ? 0.34F : 1.0F;
        float green = pause ? 0.82F : 0.78F;
        float blue = pause ? 1.0F : 0.22F;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        var buffer = event.getMultiBufferSource().getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(pose, buffer, box, red * pulse, green * pulse, blue * pulse, 1.0F);
        pose.popPose();
    }
}