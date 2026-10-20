package dev.modmind.kunjinkao.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.WandPreviewRequestPayload;
import dev.modmind.kunjinkao.wand.WandCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderHighlightEvent;

import java.util.Set;

/**
 * 建筑手杖 / 破坏核心的预览，按 Construction Wand KOTS 的 RenderBlockPreview 重写。
 * <p>
 * 与旧实现的根本差别：<b>预览不再由客户端自己算</b>，而是客户端把"看着哪一格"发给服务端，
 * 服务端用同一个 {@link dev.modmind.kunjinkao.wand.WandJob} 算一遍，把坐标发回来。
 * 旧实现是客户端照抄一遍洪泛逻辑 —— 两份代码一旦有一处不同步，预览就开始骗人。
 * <p>
 * 三处刻意偏离参考实现（参考实现的对应问题在注释里点明）：
 * <ul>
 *   <li>参考实现没有节流，预览集合小于 2 格时逐帧发包；这里最短 {@link #REQUEST_INTERVAL_TICKS} tick 一次，
 *       并且结果超过 {@link #STALE_TICKS} tick 没更新就重发、超过 {@link #EXPIRE_TICKS} tick 直接清空；</li>
 *   <li>参考实现用 {@code seq} 之外的任何手段都挡不住迟到的旧结果，这里按 seq 丢弃；</li>
 *   <li>参考实现整批一个颜色（纯黑，暗处几乎看不见），这里按核心上色：建筑青、天使琥珀、破坏红，
 *       与模组自己的语义色一致。</li>
 * </ul>
 * 画法与参考实现一致：{@code RenderType.LINES} 的整块线框、不内缩不外扩、alpha 0.4，
 * 并且<b>只在列表非空时</b>取消原版描边 —— 手里拿着剑但一格都放不下时，原版描边照常显示。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WandPreviewRenderer {

    private static final int REQUEST_INTERVAL_TICKS = 4;
    private static final int STALE_TICKS = 20;
    private static final int EXPIRE_TICKS = 60;
    private static final float ALPHA = 0.4F;
    /**
     * "从来没有过"的哨兵值。
     * <p>
     * 刻意<b>不用</b> {@code Integer.MIN_VALUE}：{@code tick - Integer.MIN_VALUE} 会溢出成负数，
     * 于是"从没请求过"被当成"刚刚才请求过"，第一次请求永远发不出去 —— 预览整个失效过。
     * 取 -1000 既远离 tickCount 的正常取值，也不会溢出。
     */
    private static final int NEVER = -1000;

    private static Set<BlockPos> positions = Set.of();
    private static int acceptedSeq = -1;
    private static int nextSeq = 0;
    private static int resultTick = NEVER;
    private static BlockPos lastPos;
    private static Direction lastFace;
    private static ItemStack lastStack = ItemStack.EMPTY;
    private static int lastRequestTick = NEVER;

    private WandPreviewRenderer() {
    }

    /** 收到服务端算好的坐标。迟到的旧结果（seq 更小）直接丢。 */
    public static void accept(int seq, Set<BlockPos> blocks) {
        if (seq < acceptedSeq) {
            return;
        }
        acceptedSeq = seq;
        positions = Set.copyOf(blocks);
        resultTick = currentTick();
    }

    /** 换维度、退出世界、卸下剑时清空，免得残留线框。 */
    public static void reset() {
        positions = Set.of();
        acceptedSeq = -1;
        resultTick = NEVER;
        lastPos = null;
        lastFace = null;
        lastStack = ItemStack.EMPTY;
    }

    @SubscribeEvent
    public static void onHighlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        BlockHitResult target = event.getTarget();
        if (target.getType() != HitResult.Type.BLOCK) {
            return;
        }
        ItemStack sword = player.getMainHandItem();
        if (!(KunJinKaoSwordItem.isSwordLike(sword))) {
            reset();
            return;
        }
        // 区域场预览归 PlacementPreviewRenderer 管，两者不叠加（叠在一起会互相盖住）。
        if (KunJinKaoSwordItem.getAreaMode(sword) != 0) {
            reset();
            return;
        }
        WandCore core = WandCore.active(sword);
        if (core == null) {
            reset();
            return;
        }
        int tick = player.tickCount;
        if (elapsed(tick, resultTick) > EXPIRE_TICKS) {
            positions = Set.of();
        }
        requestIfNeeded(player, sword, target, tick);
        if (positions.isEmpty()) {
            return;
        }
        draw(event, core);
        event.setCanceled(true);
    }

    private static void requestIfNeeded(Player player, ItemStack sword, BlockHitResult target, int tick) {
        boolean moved = lastPos == null
                || !lastPos.equals(target.getBlockPos())
                || lastFace != target.getDirection();
        boolean stackChanged = !ItemStack.isSameItemSameTags(lastStack, sword);
        boolean stale = elapsed(tick, resultTick) > STALE_TICKS;
        if (!moved && !stackChanged && !stale) {
            return;
        }
        if (elapsed(tick, lastRequestTick) < REQUEST_INTERVAL_TICKS) {
            return;
        }
        if (stackChanged) {
            positions = Set.of();
        }
        lastRequestTick = tick;
        lastPos = target.getBlockPos();
        lastFace = target.getDirection();
        lastStack = sword.copy();
        NetworkHandler.sendToServer(new WandPreviewRequestPayload(++nextSeq, target));
    }

    private static void draw(RenderHighlightEvent.Block event, WandCore core) {
        float red;
        float green;
        float blue;
        switch (core) {
            case DESTRUCTION -> {
                red = 1.0F;
                green = 0.30F;
                blue = 0.32F;
            }
            case ANGEL -> {
                red = 1.0F;
                green = 0.72F;
                blue = 0.22F;
            }
            default -> {
                red = 0.32F;
                green = 0.86F;
                blue = 1.0F;
            }
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        var buffer = event.getMultiBufferSource().getBuffer(RenderType.lines());
        for (BlockPos pos : positions) {
            pose.pushPose();
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            LevelRenderer.renderLineBox(pose, buffer, new net.minecraft.world.phys.AABB(BlockPos.ZERO),
                    red, green, blue, ALPHA);
            pose.popPose();
        }
    }

    private static int currentTick() {
        Player player = Minecraft.getInstance().player;
        return player == null ? NEVER : player.tickCount;
    }

    /**
     * 当前 tick 与某个记录点之间的间隔。
     * <p>
     * 差值为负时一律返回 {@link Integer#MAX_VALUE}：那说明 {@code tickCount} 归过零
     * （换维度、复活都会换一个玩家实体），应当按"很久以前"处理 ——
     * 否则换维度之后会有几十秒既不清空也不重发预览的静默期。
     */
    private static int elapsed(int tick, int since) {
        int delta = tick - since;
        return delta < 0 ? Integer.MAX_VALUE : delta;
    }
}