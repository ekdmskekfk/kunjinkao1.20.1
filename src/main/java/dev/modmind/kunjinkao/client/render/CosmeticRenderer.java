package dev.modmind.kunjinkao.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoPalette;
import dev.modmind.kunjinkao.SwordRegistry;
import dev.modmind.kunjinkao.client.CosmeticClientState;
import dev.modmind.kunjinkao.client.TacticalHudInvisibilityVisualState;
import dev.modmind.kunjinkao.network.CosmeticStatePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 管理员装饰的客户端渲染：身后三把剑（含反击刺击）与头顶乱码称号牌。
 * <p>
 * <b>为什么在世界渲染阶段画、而不是挂在玩家渲染层上：</b>
 * 这个姿态是"世界朝向、相机相对"的，位置可以直接算成
 * {@code 世界坐标 - 相机坐标}，不需要先把偏移换算进模型空间；
 * 而且这三把剑是按玩家<b>本地的、插值过的</b>位置与朝向算出来的 ——
 * 这就是零延迟的来源（服务端那套实体渲染已经删掉了，见 {@link CosmeticStatePayload}）。
 * <p>
 * 姿态与广告牌的坑与 {@code TimeAccelOverlayRenderer} 里记的一样：这个世界姿态不含相机旋转，
 * 所以文字要自己 {@code mulPose(camera.rotation())} 转过去，缩放取 {@code (+, -, +)}。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CosmeticRenderer {

    /** 剑的个数。 */
    private static final int SWORD_COUNT = 3;
    /** 排在身后多远（格）。 */
    private static final double BACK_DISTANCE = 0.95D;
    /** 左右两把各偏出去多少（格）；中间那把正好在正中。 */
    private static final double SIDE_SPREAD = 0.55D;
    /** 剑心高度（相对脚底，格）。 */
    private static final double SWORD_HEIGHT = 1.2D;
    /** 左右轻摆幅度（格）。三把同步摆，所以间距不变。 */
    private static final double SWORD_SWAY = 0.1D;
    /** 上下浮动幅度（格）。每把有自己的相位差，看起来才不是一整块。 */
    private static final double SWORD_BOB = 0.05D;
    /** 摆动/浮动速度（弧度每 tick）。 */
    private static final double SWAY_SPEED = 0.06D;
    /** 剑的缩放。FIXED 上下文把物品按展示框那样竖起来，这个尺寸看着正好。 */
    private static final float SWORD_SCALE = 1.0F;
    /** 称号牌浮在头顶多高（格）。正好落在原版名牌上方一点。 */
    private static final double TITLE_HEIGHT = 0.55D;
    /** 原版铭牌的缩放。 */
    private static final float TAG_SCALE = 0.025F;
    /** 剑尖停在目标头顶上方多高（格）。{@link CosmeticClientState} 追击时也用这个值。 */
    public static final double STRIKE_HOVER = 0.45D;
    /** 两下之间抬起多少（格）：每一下都是先抬起来再刺下去，不然看起来只是贴着不动。 */
    private static final double STRIKE_RETRACT = 0.4D;
    /** 离下一击还有几 tick 之内开始压下去。 */
    private static final int STRIKE_THRUST_WINDOW = 3;

    /** 装饰用的剑物品栈：强制完整剑模型，见 {@link #swordStack()}。 */
    @Nullable
    private static ItemStack swordStack;

    private CosmeticRenderer() {
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
        if (CosmeticClientState.tracked().isEmpty()) {
            return;
        }
        float partialTick = event.getPartialTick();   // 1.20.1 直接是 float（1.21 才是 DeltaTracker）
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        boolean drewAnything = false;

        for (CosmeticClientState.Tracked tracked : CosmeticClientState.tracked()) {
            if (tracked.flags() == 0) {
                continue;
            }
            AbstractClientPlayer player = CosmeticClientState.findPlayer(minecraft.level, tracked.player());
            if (player == null) {
                continue;
            }
            // 真隐形的管理员不该被自己的装饰出卖 —— 与整个玩家渲染被取消的语义保持一致。
            if (TacticalHudInvisibilityVisualState.isTrueInvisible(tracked.player())) {
                continue;
            }
            Vec3 at = player.getPosition(partialTick);
            float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);

            if ((tracked.flags() & CosmeticStatePayload.FLAG_ORBIT) != 0) {
                drewAnything |= renderSwords(event, buffer, minecraft, player, tracked, at, bodyYaw, partialTick, camera);
            }
            if ((tracked.flags() & CosmeticStatePayload.FLAG_TITLE) != 0) {
                renderTitle(event, buffer, minecraft.font, player, at, camera);
                drewAnything = true;
            }
        }

        if (drewAnything) {
            // 世界渲染阶段没有别人替我们 flush。
            buffer.endBatch();
        }
    }

    /** 身后第 slot 把剑的位置（0 = 正中）。反击起飞时也用它，保证"从队伍里飞出去"接得上。 */
    public static Vec3 idleSwordPosition(AbstractClientPlayer player, int slot) {
        return idleSwordPosition(player, slot, player.position(), player.yBodyRot, 0.0F);
    }

    /**
     * 身后第 slot 把剑的位置。
     * <p>
     * 按玩家朝向算，而不是绕圈：朝向的水平分量是 {@code (-sin, cos)}，身后就是它的反向，
     * 身侧是 {@code (cos, sin)}。三把排成<b>一条直线</b>（与肩同向、离背等距），随朝向一起转。
     * 刻意不做成弧线：弧线上等角放三把，弦距就不等了，看起来就是"间隔不一致"。
     */
    private static Vec3 idleSwordPosition(AbstractClientPlayer player, int slot, Vec3 at, float bodyYaw,
                                          float partialTick) {
        double yaw = Math.toRadians(bodyYaw);
        double behindX = Math.sin(yaw);
        double behindZ = -Math.cos(yaw);
        double sideX = Math.cos(yaw);
        double sideZ = Math.sin(yaw);
        double offset = slot - (SWORD_COUNT - 1) / 2.0D;
        // 左右摆动【不带】每把自己的相位：三把同步摆，间距才是恒定的 0.55 格。
        // 相位差只放在上下浮动那一项上 —— 高度差不会让人对间距产生误判。
        double phase = (player.tickCount + partialTick) * SWAY_SPEED;
        double sway = Math.sin(phase) * SWORD_SWAY;
        double bob = Math.sin(phase + offset * 1.7D) * SWORD_BOB;
        return new Vec3(
                at.x + behindX * BACK_DISTANCE + sideX * (offset * SIDE_SPREAD + sway),
                at.y + SWORD_HEIGHT + bob,
                at.z + behindZ * BACK_DISTANCE + sideZ * (offset * SIDE_SPREAD + sway));
    }

    private static boolean renderSwords(RenderLevelStageEvent event, MultiBufferSource.BufferSource buffer,
                                        Minecraft minecraft, AbstractClientPlayer player,
                                        CosmeticClientState.Tracked tracked,
                                        Vec3 at, float bodyYaw, float partialTick, Vec3 camera) {
        PoseStack pose = event.getPoseStack();
        boolean drewAnything = false;
        UUID target = tracked.strikeTarget();

        for (int slot = 0; slot < SWORD_COUNT; slot++) {
            if (slot == 0 && target != null) {
                continue;   // 飞出去的那一把在下面单独画
            }
            Vec3 where = idleSwordPosition(player, slot, at, bodyYaw, partialTick);
            renderSword(pose, buffer, minecraft, where, bodyYaw, false, camera);
            drewAnything = true;
        }

        if (target == null) {
            return drewAnything;
        }
        Vec3 previous = tracked.swordPrev();
        Vec3 current = tracked.swordPos();
        if (previous == null || current == null) {
            return drewAnything;
        }
        // 挨打的人真隐形时，剑飞过去等于把人指出来 —— 宁可不画这一剑。
        if (TacticalHudInvisibilityVisualState.isTrueInvisible(target)) {
            return drewAnything;
        }
        // 每两击之间先抬起来再压下去：抬起的时机由"离下一击还有几 tick"决定，
        // 用服务端同步下来的同一个时间参数算，刺下去的瞬间才是伤害落地的那一下。
        int cycle = Math.floorMod(tracked.strikeTicks() - CosmeticStatePayload.STRIKE_FLIGHT_TICKS,
                CosmeticStatePayload.STRIKE_DAMAGE_INTERVAL);
        boolean thrusting = tracked.strikeTicks() < CosmeticStatePayload.STRIKE_FLIGHT_TICKS
                || cycle >= CosmeticStatePayload.STRIKE_DAMAGE_INTERVAL - STRIKE_THRUST_WINDOW;
        double lift = thrusting ? 0.0D : STRIKE_RETRACT;
        Vec3 where = previous.add(current.subtract(previous).scale(partialTick)).add(0.0D, lift, 0.0D);
        renderSword(pose, buffer, minecraft, where, bodyYaw, true, camera);
        return true;
    }

    /** 画一把剑。{@code stabbing} 为 true 时剑尖朝下（绕 X 翻 180°）。 */
    private static void renderSword(PoseStack pose, MultiBufferSource.BufferSource buffer,
                                    Minecraft minecraft, Vec3 where, float bodyYaw, boolean stabbing,
                                    Vec3 camera) {
        int light = LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(where));
        pose.pushPose();
        pose.translate(where.x - camera.x, where.y - camera.y, where.z - camera.z);
        // 剑跟着玩家朝向转：转视角时整队剑是"跟着人一起转"的，不是各自固定朝向。
        pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        if (stabbing) {
            // FIXED 上下文把剑竖起来（剑尖朝上），绕 X 翻 180° 就朝下了。
            pose.mulPose(Axis.XP.rotationDegrees(180.0F));
        }
        pose.scale(SWORD_SCALE, SWORD_SCALE, SWORD_SCALE);
        minecraft.getItemRenderer().renderStatic(swordStack(), ItemDisplayContext.FIXED, light,
                OverlayTexture.NO_OVERLAY, pose, buffer, minecraft.level, 0);
        pose.popPose();
    }

    /** 头顶的乱码称号牌。文字自己转向相机，否则从某些角度看是镜像的背面。 */
    private static void renderTitle(RenderLevelStageEvent event, MultiBufferSource.BufferSource buffer, Font font,
                                    AbstractClientPlayer player, Vec3 at, Vec3 camera) {
        Component text = Component.literal(KunJinKaoPalette.MOJIBAKE + KunJinKaoPalette.MOJIBAKE)
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(player.getGameProfile().getName()).withStyle(ChatFormatting.WHITE));
        int background = ((int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F)) << 24;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(at.x - camera.x, at.y + player.getBbHeight() + TITLE_HEIGHT - camera.y, at.z - camera.z);
        pose.mulPose(event.getCamera().rotation());
        // Y 取负把字翻正；X 保持正数，两个轴都取负会让整行字镜像。
        pose.scale(TAG_SCALE, -TAG_SCALE, TAG_SCALE);
        font.drawInBatch(text, -font.width(text) / 2.0F, 0.0F, 0xFFFFFFFF, false,
                pose.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);
        pose.popPose();
    }

    /**
     * 装饰用的剑物品栈。
     * <p>
     * 打上 {@code KunJinKaoCompileHudModel} 标记是必须的：剑的物品模型谓词会看
     * "手上那一把是不是正在编译"，而我们这个栈与玩家手上的剑
     * {@code isSameItemSameComponents} 很可能成立（都是干净的剑） ——
     * 不标记的话，玩家自己抽剑的那一两秒里，所有装饰剑都会跟着变成编译阶段的小方块。
     */
    private static ItemStack swordStack() {
        ItemStack stack = swordStack;
        if (stack == null) {
            stack = new ItemStack(SwordRegistry.KUN_JIN_KAO_SWORD.get());
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("KunJinKaoCompileHudModel", true);
            stack.setTag(tag);
            swordStack = stack;
        }
        return stack;
    }
}
