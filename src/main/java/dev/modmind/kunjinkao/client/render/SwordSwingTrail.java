package dev.modmind.kunjinkao.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 挥砍时拖在身后的那条能量带。
 * <p>
 * 做成一串<b>采样点</b>而不是一段写死的动画：每帧记录一次刀尖的世界坐标，
 * 只保留最近 {@link #LIFETIME_MS} 毫秒内的点，再把相邻两点连成一条四边形带。
 * 好处是拖尾的形状天然跟着玩家真实动作走 —— 甩得快就拉得长，停手就立刻消失。
 * <p>
 * <b>刀尖位置是算出来的，不是取出来的</b>：要把手里模型的顶点换算到世界空间，
 * 需要物品变换【之后】的矩阵，而渲染事件的时机拿不到它。所以这里用
 * "视线前伸 + 绕视线轴按挥动进度扫过一段弧"重建刀尖轨迹 ——
 * 这条弧正是原版挥砍动画让剑走的路径，观感一致。
 * <p>
 * 绘制用 {@code debugQuads}（POSITION_COLOR，无剔除），与
 * {@code AcceleratorBlockEntityRenderer} / {@code EyeHudLayer} 用的是同一种，
 * 不必自建 RenderType，也就不会与别的模组抢注入口。
 * <p>
 * 注意 {@link RenderLevelStageEvent} <b>不提供</b> MultiBufferSource（那是
 * {@code RenderHighlightEvent} 才有的），必须自己从 {@code renderBuffers()} 取，
 * 并且<b>画完一定要 endBatch()</b> —— 否则这一批顶点永远不会被提交，什么也看不到。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwordSwingTrail {

    /** 最多保留多少个采样点。 */
    private static final int MAX_POINTS = 20;
    /** 采样点存活时长（毫秒）。约 0.24 秒，够短促，像一道划过就散的光。 */
    private static final long LIFETIME_MS = 240L;
    /** 拖尾半宽（格）。 */
    private static final double HALF_WIDTH = 0.06D;
    /** 刀尖离眼睛多远。 */
    private static final double TIP_FORWARD = 1.35D;
    /** 挥砍弧的左右摆幅与上下摆幅。 */
    private static final double ARC_SIDE = 0.62D;
    private static final double ARC_VERTICAL = 0.34D;

    private record Point(double x, double y, double z, long time, float strength) {
    }

    private static final Deque<Point> POINTS = new ArrayDeque<>();

    private SwordSwingTrail() {
    }

    /** 手一停就把采样点丢掉 —— 否则拖尾会在原地凝固成一条不动的线。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 1.20.1 的客户端 tick 事件没有 .Post 子类，靠 Phase 区分。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || heldSword(player) == null || player.getAttackAnim(0.0F) <= 0.0F) {
            POINTS.clear();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || heldSword(player) == null) {
            POINTS.clear();
            return;
        }

        long now = System.currentTimeMillis();
        float swing = player.getAttackAnim(0.0F);
        if (swing > 0.0F) {
            Vec3 tip = tipPosition(player, swing);
            // strength 越接近挥动中点越亮：两端自然收细，像划出去又收回来
            float strength = (float) Math.sin(Math.PI * swing);
            POINTS.addLast(new Point(tip.x, tip.y, tip.z, now, strength));
            while (POINTS.size() > MAX_POINTS) {
                POINTS.removeFirst();
            }
        }
        // 挥完了就不再采样，但已有点继续淡出，不会突然断掉
        drawTrail(event, now, minecraft.renderBuffers().bufferSource());
    }

    private static void drawTrail(RenderLevelStageEvent event, long now,
                                  MultiBufferSource.BufferSource source) {
        POINTS.removeIf(point -> now - point.time() > LIFETIME_MS);
        if (POINTS.size() < 2) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        Matrix4f matrix = pose.last().pose();
        VertexConsumer buffer = source.getBuffer(RenderType.debugQuads());

        Point[] points = POINTS.toArray(new Point[0]);
        for (int i = 0; i < points.length - 1; i++) {
            Point a = points[i];
            Point b = points[i + 1];
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dz = b.z - a.z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0E-5D) {
                continue;
            }

            // 带宽方向 = 段方向 x 指向相机的方向。
            //
            // 必须叉乘【指向相机】而不是某个固定轴：固定轴会让带子在某些角度退化成
            // 一条线（段方向与轴平行时叉乘为零），挥到那个角度拖尾就凭空消失。
            // 叉乘视线方向则永远垂直于视线，带子始终正面朝向观察者。
            double mx = (a.x + b.x) * 0.5D;
            double my = (a.y + b.y) * 0.5D;
            double mz = (a.z + b.z) * 0.5D;
            double vx = camera.x - mx;
            double vy = camera.y - my;
            double vz = camera.z - mz;
            double vlen = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vlen < 1.0E-5D) {
                continue;
            }
            vx /= vlen;
            vy /= vlen;
            vz /= vlen;

            double ux = dx / length;
            double uy = dy / length;
            double uz = dz / length;

            double px = uy * vz - uz * vy;
            double py = uz * vx - ux * vz;
            double pz = ux * vy - uy * vx;
            double plen = Math.sqrt(px * px + py * py + pz * pz);
            if (plen < 1.0E-5D) {
                // 段方向与视线几乎平行（正对着看），此时带子朝向无所谓，任取一个垂直方向
                px = 0.0D;
                py = 1.0D;
                pz = 0.0D;
                plen = 1.0D;
            }
            px = px / plen * HALF_WIDTH;
            py = py / plen * HALF_WIDTH;
            pz = pz / plen * HALF_WIDTH;

            // 越老越淡
            float alphaA = clamp01(1.0F - (now - a.time()) / (float) LIFETIME_MS) * a.strength() * 0.75F;
            float alphaB = clamp01(1.0F - (now - b.time()) / (float) LIFETIME_MS) * b.strength() * 0.75F;
            if (alphaA <= 0.01F && alphaB <= 0.01F) {
                continue;
            }

            // 靠 a 的一侧用 alphaA、靠 b 的一侧用 alphaB，带子自带渐变
            vertex(buffer, matrix, camera, a.x + px, a.y + py, a.z + pz, alphaA);
            vertex(buffer, matrix, camera, a.x - px, a.y - py, a.z - pz, alphaA);
            vertex(buffer, matrix, camera, b.x - px, b.y - py, b.z - pz, alphaB);
            vertex(buffer, matrix, camera, b.x + px, b.y + py, b.z + pz, alphaB);
        }

        // 必须提交，否则上面写进缓冲的顶点永远不会画出来
        source.endBatch(RenderType.debugQuads());
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 camera,
                               double x, double y, double z, float alpha) {
        // 1.20.1 的顶点接口是旧版：vertex(...) 起头、color(...) 设色、【必须 endVertex()】收尾。
        // （1.21 换成了 addVertex/setColor，且没有 endVertex。）
        buffer.vertex(matrix,
                        (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                .color(0.42F, 0.88F, 1.00F, alpha)
                .endVertex();
    }

    /**
     * 刀尖的世界坐标：视线前伸，再绕视线轴按挥动进度扫过一段弧。
     * <p>
     * 挥动进度 0 -> 1 时弧角从 +1 扫到 -1（主手在右时自右向左），
     * 这正是原版挥砍动画让剑划过的方向。
     */
    private static Vec3 tipPosition(LocalPlayer player, float swing) {
        Vec3 eye = player.getEyePosition();
        Vec3 forward = player.getLookAngle();
        // 水平右方向：视线的水平分量绕 Y 转 90 度
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        if (right.lengthSqr() < 1.0E-6D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize();

        double theta = (swing - 0.5D) * Math.PI * 1.15D;
        double side = Math.sin(theta) * ARC_SIDE;
        double vertical = -Math.cos(theta) * ARC_VERTICAL - 0.15D;
        return eye.add(forward.scale(TIP_FORWARD))
                .add(right.scale(side))
                .add(0.0D, vertical, 0.0D);
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    private static ItemStack heldSword(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(stack)) {
                return stack;
            }
        }
        return null;
    }
}