package dev.modmind.kunjinkao.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.qol.ZoneClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * 选区挖掘（127）的世界内线框：把当前选区画成一个外框，作业进行中时用颜色表示进度。
 * <p>
 * <b>只画 12 条棱的外框，绝不逐方块画。</b> 选区单边上限 64，逐方块就是最多 26 万个方框、
 * 一百多万条线段，一帧画下去直接卡死。外框一样能说明"框住了哪一块"，
 * 而且顶点数是常数。这也是本文件与 {@link QolTreasureRenderer} 最大的不同：
 * 那边一个框就是一个方块，这边一个框是一整片地形。
 * <p>
 * <b>进度为什么是颜色而不是文字。</b> 这里是世界渲染，拿不到 {@code GuiGraphics}，
 * 没有现成的字体绘制通道；要在世界里画字就得自己走一遍字体渲染器、还要做 Billboard 朝向，
 * 收益（一行数字）远不抵复杂度。改成外框颜色从青绿渐变到琥珀：
 * 一眼能看出"快挖完了"，实现只有几个线性插值。
 * <p>
 * <b>取缓冲区的方式与 endBatch</b>：{@link RenderLevelStageEvent} 不带 MultiBufferSource，
 * 得自己从 levelRenderer 的 renderBuffers 取；画完必须 {@code endBatch()}，
 * 否则顶点只躺在缓冲里永远不提交，表现是"代码都在跑、一个框也看不见"，且不会有任何报错。
 * <p>
 * <b>维度判定</b>：选区是绑在某个维度上的，玩家换维度后旧的框不该跟着飘过来。
 * 客户端拿服务端下发的维度与自己的比一下，不一样就整个不画。
 * <p>
 * 已知限制与 {@link QolTreasureRenderer} 相同：光影（Iris/Oculus）的 shadow pass 里
 * 这个阶段事件同样会触发，线框可能被画进阴影贴图。事件没有提供"当前是不是在画阴影"的字段，
 * 不猜、不判，把限制写在这里。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZoneSelectionRenderer {

    // 进度 0 的颜色：青绿。与寻宝线框同一族，玩家一眼能认出"这是本模组画的框"。
    private static final float START_RED = 0.2F;
    private static final float START_GREEN = 0.9F;
    private static final float START_BLUE = 0.8F;

    // 进度 1 的颜色：琥珀。与起点在色相上隔得够远，中间任何一档都能看出方向。
    private static final float END_RED = 1.0F;
    private static final float END_GREEN = 0.65F;
    private static final float END_BLUE = 0.1F;

    /** 只是框选、还没开挖：画淡一点，别把地形整个盖住。 */
    private static final float SELECTION_ALPHA = 0.55F;

    /** 作业进行中：调高不透明度，渐变才看得出走向。 */
    private static final float RUNNING_ALPHA = 0.9F;

    /**
     * 单格小框往外撑一点。
     * <p>
     * 不撑的话它和原版的方块选中轮廓完全重合，两条线共面会闪（z-fighting），
     * 表现是"按了键但看起来什么都没变"。
     */
    private static final double SINGLE_CORNER_INFLATE = 0.005D;

    private ZoneSelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 只认方块实体之后这一个阶段：地形与实体都已入屏，线框叠在最上层，与寻宝线框同一时机。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        // 换维度之后旧框不该飘过来 —— 坐标在新维度里没有意义，画出来就是骗人。
        // 没有角点时 dimension() 是 null，这一条同时把"从来没框过"的情况挡掉。
        ResourceLocation zoneDimension = ZoneClientState.dimension();
        if (zoneDimension == null || !zoneDimension.equals(level.dimension().location())) {
            return;
        }

        BlockPos cornerA = ZoneClientState.cornerA();
        BlockPos cornerB = ZoneClientState.cornerB();
        if (cornerA == null && cornerB == null) {
            return;
        }

        // 两个角点都有才画整片选区的外框；只有一个时画那个角点的单格小框，
        // 否则玩家按了键屏幕上什么都不变，会以为键没生效。
        AABB box;
        if (cornerA != null && cornerB != null) {
            box = boxOf(cornerA, cornerB);
        } else {
            box = new AABB(cornerA != null ? cornerA : cornerB).inflate(SINGLE_CORNER_INFLATE);
        }

        boolean running = ZoneClientState.isRunning();
        float progress = running ? progressRatio() : 0.0F;

        float red = lerp(START_RED, END_RED, progress);
        float green = lerp(START_GREEN, END_GREEN, progress);
        float blue = lerp(START_BLUE, END_BLUE, progress);
        float alpha = running ? RUNNING_ALPHA : SELECTION_ALPHA;

        // 缓冲区只能从 Minecraft 上取：1.21.1 的 LevelRenderer 里那个 renderBuffers 字段是私有的，
        // 也没有任何 getter（NeoForge 没有补），就算事件给了 LevelRenderer 实例也拿不到它的缓冲。
        // 寻宝线框走的是同一条路，两边保持一致。
        MultiBufferSource.BufferSource source = minecraft.renderBuffers().bufferSource();
        VertexConsumer buffer = source.getBuffer(RenderType.lines());
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();

        // 关掉深度测试：框在墙后面也看得见，玩家才能隔着地形确认自己框到了哪里。
        // 并且【一定】在 finally 里恢复 —— 这是全局 GL 状态，中途抛异常没恢复的话，
        // 后面所有阶段的深度测试全废，整个世界的渲染会错乱。
        RenderSystem.disableDepthTest();
        try {
            pose.pushPose();
            // 顶点坐标相对相机：世界坐标动辄上千，直接丢进 float 会掉精度，远处框会抖。
            pose.translate(-camera.x, -camera.y, -camera.z);
            // 一帧只有一个框，顶点数是常数，不做视锥剔除 —— 剔除判错反而会让框"该在时不在"。
            LevelRenderer.renderLineBox(pose, buffer, box, red, green, blue, alpha);
            pose.popPose();
            source.endBatch(RenderType.lines());
        } finally {
            RenderSystem.enableDepthTest();
        }
    }

    /**
     * 选区外框：两个角点各取小/大值，再补上 1 格。
     * <p>
     * 补 1 格是因为角点是"方块坐标"而不是"顶点坐标"：只取到 max 的话，
     * 框会正好切在最后一个方块的内侧面上，看起来少了一圈。
     */
    private static AABB boxOf(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0D,
                Math.max(a.getY(), b.getY()) + 1.0D,
                Math.max(a.getZ(), b.getZ()) + 1.0D);
    }

    /**
     * 进度比例，落在 0..1。
     * <p>
     * total 为 0（没收到过状态、或服务端给了个空作业）时返回 0 而不是让它除出 NaN：
     * NaN 传进顶点颜色会让整个框消失，且没有任何报错可查。
     */
    private static float progressRatio() {
        int total = ZoneClientState.total();
        if (total <= 0) {
            return 0.0F;
        }
        int done = ZoneClientState.done();
        if (done <= 0) {
            return 0.0F;
        }
        if (done >= total) {
            return 1.0F;
        }
        return (float) done / (float) total;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}