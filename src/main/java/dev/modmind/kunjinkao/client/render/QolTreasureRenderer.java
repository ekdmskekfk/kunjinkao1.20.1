package dev.modmind.kunjinkao.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.qol.QolTreasureScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * 寻宝大师（功能 96）的世界内线框渲染：把 {@link QolTreasureScanner} 找到的位置画成青绿色方框。
 * <p>
 * <b>穿透方块</b>：画之前关掉深度测试，所以箱子在墙后面也看得见。这是这一项的功能核心
 * （不穿透就只是个描边），代价是它同时也是这项功能"像作弊"的原因 ——
 * 开关判定因此被收在扫描器里，见 {@link QolTreasureScanner}。
 * <p>
 * <b>取缓冲区的方式</b>：{@link RenderLevelStageEvent} 不带 MultiBufferSource
 * （那是 RenderHighlightEvent 才有的），要自己从 {@code Minecraft.renderBuffers()} 取。
 * 画完必须 {@code endBatch()} —— 否则顶点只躺在缓冲里永远不提交，表现是"代码都在跑、
 * 一个框也看不见"，且不会有任何报错。这是本工程踩过的坑，注释留在这里警告后来的人。
 * <p>
 * <b>已知限制：光影（Iris/Oculus）的 shadow pass。</b> 光影会把整个关卡重画进阴影贴图，
 * 那一遍里这个事件同样会被触发，于是线框也会被画进阴影贴图里，可能出现"墙上有线框的影子"
 * 这类异常。{@link RenderLevelStageEvent} 没有提供任何"当前是不是在画阴影"的字段 ——
 * 能拿到的东西（LevelRenderer 实例、缓冲、矩阵）在正片和阴影遍里长得一模一样，
 * 靠它们猜等于用一个不可靠的判据去赌，赌错的后果比"多画一条影子"严重得多。
 * 所以这里不做判断，把这个限制写明；实际使用中光影的阴影阶段通常会跳过 NeoForge 的这些阶段事件，
 * 多数情况下这一项干脆不会被调用。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QolTreasureRenderer {

    /**
     * 线框颜色（青绿）与透明度。
     * <p>
     * 取半透明而不是全不透明：半径 64 时视野里可能同时有几十个框，
     * 全不透明会糊成一片把地形盖住 —— 这个功能是"提示有什么"，不该把世界本身挡住。
     */
    private static final float RED = 0.2F;
    private static final float GREEN = 0.9F;
    private static final float BLUE = 0.8F;
    private static final float ALPHA = 0.55F;

    /**
     * 剔除距离（格）。
     * <p>
     * 扫描半径最大 64，但视野只有前面那一块。关掉深度测试之后原版的视锥剔除就不再适用于这些框，
     * 画出来的顶点在屏幕外一个像素都看不到，纯属白烧带宽。这里除了用视锥判，
     * 再加一道硬距离上限：两者都放行的才画，单帧顶点数就压住了。
     */
    private static final double CULL_DISTANCE = 64.0D;

    private QolTreasureRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 只认方块实体之后这一个阶段：此时地形与实体都已入屏，线框叠在最上层，
        // 而且还没轮到半透明与水，不会和它们的排序打架。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        List<AABB> boxes = QolTreasureScanner.getHighlights();
        // 空列表直接返回：这是最常见的情况（没开功能、或者附近确实没有容器），
        // 必须在取缓冲区之前就退出，否则每帧都会为一次什么都不画的绘制付一次状态切换的钱。
        if (boxes.isEmpty()) {
            return;
        }

        MultiBufferSource.BufferSource source = minecraft.renderBuffers().bufferSource();
        VertexConsumer buffer = source.getBuffer(RenderType.lines());
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        int drawn = 0;

        // 关掉深度测试并且【一定】在 finally 里恢复：这里改的是全局 GL 状态，
        // 一旦中间抛异常没恢复，后面所有阶段的深度测试就全废了，
        // 表现是整个世界的渲染错乱 —— 那是比这一项不工作严重得多的故障。
        RenderSystem.disableDepthTest();
        try {
            pose.pushPose();
            // 顶点坐标是相对相机的：世界坐标动辄上千，直接丢进 float 会掉精度，
            // 远处方框会抖。这也是原版画选中框的算法。
            pose.translate(-camera.x, -camera.y, -camera.z);
            for (int i = 0; i < boxes.size(); i++) {
                AABB box = boxes.get(i);
                if (!isNearCamera(box, camera)) {
                    continue;
                }
                // 视锥剔除交给原版：它内部是几个平面测试，比我们手写投影快也更准。
                if (!event.getFrustum().isVisible(box)) {
                    continue;
                }
                LevelRenderer.renderLineBox(pose, buffer, box, RED, GREEN, BLUE, ALPHA);
                drawn++;
            }
            pose.popPose();

            if (drawn > 0) {
                // 只在这一批真有顶点时才提交：endBatch 会把整批上传并走一次绘制状态切换，
                // 空批也调一次就是白付一次开销。
                source.endBatch(RenderType.lines());
            }
        } finally {
            RenderSystem.enableDepthTest();
        }
    }

    /**
     * 相机到方框的距离粗筛。
     * <p>
     * 用"点到轴对齐盒的距离平方"而不是中心距离：中心距离会把贴脸的大方块误杀掉。
     * 比较用平方值，省掉一次开方 —— 本方法每次渲染要对每个框调一次，不值得为可读性付开方。
     */
    private static boolean isNearCamera(AABB box, Vec3 camera) {
        double dx = Math.max(0.0D, Math.max(box.minX - camera.x, camera.x - box.maxX));
        double dy = Math.max(0.0D, Math.max(box.minY - camera.y, camera.y - box.maxY));
        double dz = Math.max(0.0D, Math.max(box.minZ - camera.z, camera.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= CULL_DISTANCE * CULL_DISTANCE;
    }
}