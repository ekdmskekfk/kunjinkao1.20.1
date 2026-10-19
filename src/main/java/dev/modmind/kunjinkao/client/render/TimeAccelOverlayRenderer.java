package dev.modmind.kunjinkao.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.network.TimeAccelStatusPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界空间里的加速悬浮提示：方块上方、生物头顶、太阳方向各一条广告牌。
 * <p>
 * 姿态换算（这里反复踩过坑，结论都是实机验证出来的，别照直觉改）：
 * <ul>
 *   <li>这个姿态是<b>世界朝向</b>，不含相机旋转。依据是实机现象：曾按"它含相机旋转"去处理
 *       （把相机旋转共轭掉来写世界坐标系的文字），结果文字又歪又跟着视角转 ——
 *       可见它本来就在世界里。</li>
 *   <li>所以广告牌必须<b>自己</b> {@code mulPose(camera.rotation())} 转向相机。
 *       少了这一步，文字在世界里朝向固定，从某些角度看到的是背面（表现为镜像）。</li>
 *   <li>缩放取 {@code (+, -, +)}：Y 取负把字翻正；X 保持正数，
 *       两个轴都取负会让整行字水平镜像。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class TimeAccelOverlayRenderer {

    /** 原版铭牌的缩放。 */
    private static final float TAG_SCALE = 0.025F;
    /** 方块上方的高度。 */
    private static final double BLOCK_HEIGHT = 1.6D;
    /** 生物头顶再抬多少。 */
    private static final double ENTITY_HEIGHT = 0.6D;
    /** 太阳方向的提示离相机多远（只是方向，取多大都不影响观感）。 */
    private static final double SUN_DISTANCE = 100.0D;
    /** 文字颜色。 */
    private static final int COLOR_TEXT = 0xFF9BE9FF;

    private TimeAccelOverlayRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        List<TimeAccelStatusPayload.Entry> entries = TimeAccelClientState.entries();
        if (entries.isEmpty()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        Font font = minecraft.font;
        int background = ((int) (minecraft.options.getBackgroundOpacity(0.25F) * 255.0F)) << 24;

        boolean drewAnything = false;
        for (TimeAccelStatusPayload.Entry entry : entries) {
            long remaining = TimeAccelClientState.remainingMillis(entry);
            if (entry.remainingMillis() >= 0L && remaining <= 0L) {
                continue;
            }
            Vec3 at = resolvePosition(minecraft, event, entry, camera);
            if (at == null) {
                continue;
            }
            Component text = Component.translatable(entry.kind() == TimeAccelStatusPayload.KIND_TIME
                            ? "hud.kunjinkao.time_accel_time_tag"
                            : "hud.kunjinkao.time_accel_tag",
                    entry.multiplier(), formatRemaining(remaining));

            pose.pushPose();
            pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            // 姿态是世界朝向，广告牌要自己转向相机；少了这一步会看到文字背面（镜像）。
            pose.mulPose(event.getCamera().rotation());
            // Y 取负把字翻正；X 保持正数，两个轴都取负会让整行字镜像。
            pose.scale(TAG_SCALE, -TAG_SCALE, TAG_SCALE);
            font.drawInBatch(text, -font.width(text) / 2.0F, 0.0F, COLOR_TEXT, false,
                    pose.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);
            pose.popPose();
            drewAnything = true;
        }
        if (drewAnything) {
            // 世界渲染阶段没有别人替我们 flush。
            buffer.endBatch();
        }
    }

    /** 三种加速对象各自的提示位置；取不到（生物已消失等）返回 null。 */
    private static Vec3 resolvePosition(Minecraft minecraft, RenderLevelStageEvent event,
                                        TimeAccelStatusPayload.Entry entry, Vec3 camera) {
        if (entry.kind() == TimeAccelStatusPayload.KIND_BLOCK) {
            return new Vec3(entry.pos().getX() + 0.5D,
                    entry.pos().getY() + BLOCK_HEIGHT,
                    entry.pos().getZ() + 0.5D);
        }
        if (entry.kind() == TimeAccelStatusPayload.KIND_ENTITY) {
            Entity entity = minecraft.level.getEntity(entry.entityId());
            if (entity == null) {
                return null;
            }
            return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() + ENTITY_HEIGHT, entity.getZ());
        }
        return sunPosition(minecraft, event, camera);
    }

    /**
     * 太阳方向上的一个点。
     * <p>
     * 方向取自原版天空：{@code Axis.XP.rotationDegrees(getTimeOfDay * 360)} 再绕 Y 转 -90，
     * 化简后是 {@code (-sin(2πt), cos(2πt), 0)} —— 太阳沿 X 轴东升西落。
     * 太阳落到地平线以下时改画到正上方，免得夜里提示跑到地底下。
     */
    private static Vec3 sunPosition(Minecraft minecraft, RenderLevelStageEvent event, Vec3 camera) {
        double angle = minecraft.level.getTimeOfDay(
                event.getPartialTick()) * Math.PI * 2.0D;   // 1.20.1 的 getPartialTick() 直接是 float
        double x = -Math.sin(angle);
        double y = Math.cos(angle);
        if (y <= 0.05D) {
            x = 0.0D;
            y = 1.0D;
        }
        return new Vec3(camera.x + x * SUN_DISTANCE, camera.y + y * SUN_DISTANCE, camera.z);
    }

    /** 倒计时文本：无限显示 ∞，否则显示一位小数的秒。 */
    private static String formatRemaining(long remainingMillis) {
        if (remainingMillis < 0L) {
            return "∞";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", remainingMillis / 1000.0D);
    }
}