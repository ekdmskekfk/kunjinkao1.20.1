package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import org.joml.Vector3f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 管理员剑的纯客户端视觉层。
 *
 * 不改变第一人称 PoseStack，因此扫描和拔出效果不会再造成视角或模型抖动。
 */
public final class KunJinKaoClientSwordVisuals {

    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");
    private static final int DRAW_COMPtLE_TtCKS = 48;
    private static final int GRAB_SWORD_TtCKS = 14;

    /**
     * 屏幕中央的编译展示是否用 3D 模型（而不是八张扁平贴图）。
     * <p>
     * 早先这里写死走扁平贴图，注释里记着理由是"renderItem 在这套阶段模型上始终画不出东西"。
     * 真正的原因是那时阶段模型挂在 {@code builtin/generated} 下、元素被 ItemModelGenerator
     * 丢光，模型面数为 0 —— 换成 {@code block/block} 之后这条路才成立。
     * <p>
     * 万一在别的环境下仍然画不出来，把这个常量改成 false 就退回扁平贴图，
     * 不用改别的代码。模型本身为空时也会自动退回（见 {@code compileStageModelsUsable}）。
     */
    private static final boolean CENTER_COMPILE_USES_3D = true;

    /**
     * 3D 展示的额外放大倍数。
     * <p>
     * {@code renderItem} 按物品模型 {@code display.gui} 的缩放来画，而剑那份没有写 scale，
     * 会落到父模型 block/block 的 0.625。
     * <p>
     * 取值让整把剑正好占满 {@code size}：模型高约 18 单位，渲染高度 = 18 × 0.625 ×
     * (size/16 × ZOOM)，令它等于 size 得 ZOOM ≈ 16/11.25 ≈ 1.42。
     * 调大就会超出裁剪带（中央揭示是按高度裁的），底部或顶部会被切掉。
     */
    private static final float CENTER_COMPILE_3D_ZOOM = 1.42F;

    /**
     * 抓取阶段里，屏幕中央那把剑的淡出时长（tick）。
     * <p>
     * 只占抓取阶段的<b>开头</b>一小段：编译一结束就把剑交给手。
     * 早先这里铺满整个 {@link #GRAB_SWORD_TtCKS}，结果编译动画早就结束、
     * 手臂也把剑收回去了，屏幕中央却还挂着一把半透明的剑将近一秒。
     */
    private static final int CENTER_FADE_TICKS = 3;

    private static final DustParticleOptions SCAN_BLUE =
            new DustParticleOptions(new Vector3f(0.00F, 0.90F, 1.00F), 0.72F);
    private static final DustParticleOptions SCAN_WHtTE =
            new DustParticleOptions(new Vector3f(0.82F, 0.97F, 1.00F), 0.55F);
    private static final DustParticleOptions DATA_GREEN =
            new DustParticleOptions(new Vector3f(0.35F, 1.00F, 0.82F), 0.52F);

    private static boolean wasHoldingSword;
    private static InteractionHand compileHand = InteractionHand.MAIN_HAND;
    private static int drawCompileTicks;
    private static int grabSwordTicks;
    private static int rightClickCompileTicks;
    private static int attackHudTicks;
    private static int attackHudPhase;
    private static boolean compileRenderObserved;
    private static boolean compileModelLogged;

    /**
    /**
     * 是否让自己的手持剑在编译期间切换到 kun_jin_kao_compile_* 模型。
     * <p>
     * 打开，与 {@link #renderCompileModel} 的八级切换配套。
     * <p>
     * 【这条注释原先写的关闭理由已经失效，留在此处备查：
     * 当时剑本体是 kun_jin_kao_3d 经 ItemModelGenerator 生成的 1254x1254 平面贴图、占满 16 单位，
     * 而那 8 个编译阶段是 3.4~13 单位的 3D 方块几何，一切换就成了
     * 「手里的大剑消失、剩一个小方块」。现在两者都是扁平的 minecraft:item/handheld 贴图
     * （剑本体指向 kun_jin_kao，阶段指向 kun_jin_kao_stage_0..7），形态一致，这条理由不再成立。】
     * <p>
     * 远端编译玩家那条分支不受影响 —— 第三人称仍会隐藏其手持剑，由抓取层代画。
     */
    private static final boolean SWITCH_LOCAL_HAND_MODEL = true;

    /** 排查编译动画时打开：每次动画开始时打印一行诊断，排查完请关掉以免刷日志。 */
    private static final boolean DEBUG_COMPILE_LOG = false;

    private static boolean centerDrawLogged;

    /**
     * 编译阶段模型是否真的有几何体。
     * <p>
     * 若为 false，物品模型谓词一律返回 -1（完整剑），避免切换到空模型导致"手里的剑消失"。
     * 每次动画开始时重新探测一次。
     */
    private static boolean compileStageModelsUsable = true;

    private KunJinKaoClientSwordVisuals() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            return;
        }

        boolean holdingSword = isHoldingVisibleSword(player);
        if (holdingSword && !wasHoldingSword) {
            compileHand = findVisibleSwordHand(player);
            compileStageModelsUsable = areCompileStageModelsUsable(player);
            drawCompileTicks = DRAW_COMPtLE_TtCKS;
            compileRenderObserved = false;
            compileModelLogged = false;
            LOGGER.info("剑编译已开始，手：{}", compileHand);
            // 冰晶般的“咔哒”声，仅在本地切换到持剑状态时播放一次。
            minecraft.level.playSound(player, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.36F, 1.48F);
        }
        wasHoldingSword = holdingSword;

        if (!holdingSword) {
            drawCompileTicks = 0;
            grabSwordTicks = 0;
            rightClickCompileTicks = 0;
        }

        // 拿出剑时由物品模型的几何切片逐行出现，不使用粒子模拟。
        if (drawCompileTicks > 0) {
            emitCentralCompileParticles(player, drawCompileTicks);
            drawCompileTicks--;
            if (drawCompileTicks == 0) {
                grabSwordTicks = GRAB_SWORD_TtCKS;
                // 编译收束音：这一刻剑刚拼完、手正要伸过来，是整段动画唯一的"完成"瞬间。
                // 没有声音的话这段只有画面在动，拼完也没有交代。
                if (minecraft.level != null && minecraft.player != null) {
                    minecraft.level.playSound(minecraft.player, minecraft.player.getX(),
                            minecraft.player.getY(), minecraft.player.getZ(),
                            dev.modmind.kunjinkao.sound.KunJinKaoSounds.COMPILE_DONE.get(),
                            SoundSource.PLAYERS, 0.7F, 1.0F);
                }
            }
        } else if (grabSwordTicks > 0) {
            grabSwordTicks--;
        }
        // 右键编译仍保留独立的粒子与音效反馈。
        if (rightClickCompileTicks > 0) {
            emitCompileParticles(player, rightClickCompileTicks);
            rightClickCompileTicks--;
        }
        if (attackHudTicks > 0) {
            attackHudTicks--;
        }
    }

    public static void onLocalAttack(AttackEntityEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() != minecraft.player || minecraft.level == null
                || !isHoldingVisibleSword(minecraft.player)) {
            return;
        }

        Entity target = event.getTarget();
        emitStructuredAfterimage(minecraft.player, target);

        // 挥砍与命中两声叠在一起，读作"whoosh-thunk"。
        // 破空声刻意压低：让它当引子，金属冲击声当主角。
        // 反过来（破空大声、命中小声）听上去像在挥塑料。
        if (minecraft.level != null) {
            minecraft.level.playSound(minecraft.player, minecraft.player.getX(),
                    minecraft.player.getY(), minecraft.player.getZ(),
                    dev.modmind.kunjinkao.sound.KunJinKaoSounds.SWING.get(),
                    SoundSource.PLAYERS, 0.45F, 1.0F);
            minecraft.level.playSound(minecraft.player, target.getX(), target.getY(), target.getZ(),
                    dev.modmind.kunjinkao.sound.KunJinKaoSounds.HIT.get(),
                    SoundSource.PLAYERS, 0.9F, 1.0F);
        }

        // 命中的爆点：冲击波 + 屏幕故障。打出去要有回应，这是"爽"的全部来源。
        dev.modmind.kunjinkao.client.render.SwordHitFeedback.trigger(minecraft.player, target);

        attackHudTicks = 18;
        attackHudPhase = (attackHudPhase + 1) % 3;
    }

    /** 右键使用时重新播放一次短编译序列；不干预原有的右键清怪功能。 */
    public static void onLocalRightClick(Player player, InteractionHand hand) {
        if (player != Minecraft.getInstance().player || !isVisibleSword(player.getItemInHand(hand))) {
            return;
        }
        compileHand = hand;
        rightClickCompileTicks = 14;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.level.playSound(player, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.85F, 1.58F);
        }
    }

    /** 在准星右侧显示小而规整的命令数据字样，避免传统红色故障文字。 */
    public static void renderAttackData(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (attackHudTicks <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String text = switch (attackHudPhase) {
            case 0 -> "/execute";
            case 1 -> "/data";
            default -> "[SUCCESS]";
        };
        int alpha = Math.min(220, attackHudTicks * 14);
        int color = (alpha << 24) | (attackHudPhase == 2 ? 0x68FFD2 : 0xA9F6FF);
        int x = screenWidth / 2 + 12;
        int y = screenHeight / 2 + 8;
        graphics.drawString(minecraft.font, text, x, y, color, false);
    }

    /**
     * 屏幕中央的编译展示。
     * <p>
     * 这里用最原始的 2D 贴图绘制（{@code GuiGraphics.blit}），而不是绘制 3D 物品模型。
     * 理由：战术 HUD 证明本模组的 GUI 图层渲染正常，而
     * {@code GuiGraphics.renderItem} 与 {@code ItemRenderer.renderStatic} 在这套阶段模型上
     * 始终画不出任何东西（GUI 图层与世界渲染两条路都试过）。
     * <p>
     * {@code kun_jin_kao_stage_0..7.png} 是同一张剑图的递进揭示版本，
     * 逐帧切换就得到"剑在屏幕中央一块块拼起来"的效果，且完全不依赖模型烘焙。
     */
    public static void renderCompileModel(GuiGraphics graphics, int screenWidth, int screenHeight, float partialTick) {
        boolean compiling = drawCompileTicks > 0;
        boolean grabbing = grabSwordTicks > 0;
        if (!compiling && !grabbing) {
            return;
        }

        float reveal;   // 0..1 —— 剑的揭示进度（连续，不再离散跳级）
        float alpha;    // 0..1 —— 整体透明度
        if (compiling) {
            float elapsed = (DRAW_COMPtLE_TtCKS - drawCompileTicks) + partialTick;
            reveal = Mth.clamp(elapsed / DRAW_COMPtLE_TtCKS, 0.0F, 1.0F);
            // smoothstep：起步与收尾都放缓，中段推进，避免匀速的机械感
            reveal = reveal * reveal * (3.0F - 2.0F * reveal);
            // 开场 5 tick 淡入，避免第一帧突然冒出来
            alpha = Mth.clamp(elapsed / 5.0F, 0.0F, 1.0F);
        } else {
            reveal = 1.0F;
            // 抓取阶段：中央这一把在开头 CENTER_FADE_TICKS tick 内就交给手，不陪着整段淡出。
            float elapsedInGrab = GRAB_SWORD_TtCKS - grabSwordTicks;
            alpha = Mth.clamp(1.0F - elapsedInGrab / (float) CENTER_FADE_TICKS, 0.0F, 1.0F);
        }

        int size = Math.max(48, Math.round(Math.min(screenWidth, screenHeight) * 0.42F));
        int x = screenWidth / 2 - size / 2;
        int y = screenHeight / 2 - size / 2 + 6;

        // 连续揭示：直接画【完整】模型，再用剪刀按扫描线裁掉还没"编译"出来的部分。
        //
        // 以前是按 reveal 切成 8 级、每级换一个模型，于是每 6 tick 啪地多出一整个部件；
        // 而且 reveal 经过 smoothstep 之后到 0.875 就进了第 7 级，
        // 最后三分之一的时间都在显示完整模型 —— 看起来就是"停在那儿不动"。
        //
        // 部件的装配顺序本来就是自下而上（底座 -> 握把 -> 护手 -> 刀身 -> 刀尖），
        // 所以只要把模型裁到扫描线以下，揭示就是连续的，也不再需要分级模型。
        //
        // 裁剪带上下各留一段余量：裁剪的唯一作用是藏住扫描线【以上】的部分，
        // 绝不该去裁模型本身。模型实际多高取决于 renderItem 与 display.gui 两层缩放，
        // 按等式推算过一次、算错就把刀尖或底座削掉了 —— 留余量就不必再算准。
        int pad = Math.max(8, size / 4);
        int bandTop = y - pad;
        int bandBottom = y + size + pad;
        int cut = bandBottom - Math.round((bandBottom - bandTop) * reveal);

        Player holder = Minecraft.getInstance().player;
        ItemStack overlaySword = holder == null ? null : swordForOverlay(holder);
        boolean drewModel = false;
        if (cut < bandBottom) {
            // 裁剪矩形用的是 GUI 坐标，与上面那套 pose 变换无关 ——
            // drawStageModel 里的 translate/scale 不会被它影响，裁的是最终画面。
            graphics.enableScissor(x - pad, cut, x + size + pad, bandBottom);
            if (CENTER_COMPILE_USES_3D && compileStageModelsUsable && overlaySword != null) {
                // 3D：第 7 级就是整把剑，按屏幕中心摆。
                drawStageModel(graphics, overlaySword, 7, screenWidth / 2, screenHeight / 2 + 6, size, alpha);
            } else {
                // 退回扁平贴图：同样只用最后一张完整图，靠裁剪揭示。
                graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
                graphics.blit(new ResourceLocation(
                                KunJinKaoEntry.MOD_ID, "textures/item/kun_jin_kao_stage_7.png"),
                        x, y, 0, 0, size, size, size, size);
                graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
            drewModel = true;
            graphics.disableScissor();
        }
        // 扫描亮线正好画在裁剪边界上 —— 它就是"编译到哪儿了"的那条线。
        int scanAlpha = (int) (alpha * 190.0F);
        if (drewModel && scanAlpha > 4) {
            graphics.fill(x, cut - 1, x + size, cut + 1, (scanAlpha << 24) | 0xBFF6FF);
        }
        // 复位着色器颜色，避免影响同一图层里后续的绘制
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 在 GUI 图层里画某一级的 3D 编译模型。
     * <p>
     * 做法是拿一把带 {@code KunJinKaoCompileStage} 标记的剑副本交给 {@code renderItem} ——
     * 物品模型的 overrides 会据这个标记选出对应那一级的模型，也就是手里与第三人称
     * 正在用的同一批烘焙模型。标记的判断在 {@code getDrawCompileModelStage} 里排在
     * 所有可见性检查【之前】，所以副本不需要和玩家手持的那把完全一致。
     * <p>
     * 早先这里画不出东西、作者因此退回扁平贴图，真正的原因是那时阶段模型挂在
     * {@code builtin/generated} 下、元素被 ItemModelGenerator 丢光（面数为 0）。
     * 改成 {@code block/block} 之后这条路径才成立。
     */
    private static void drawStageModel(GuiGraphics graphics, ItemStack sword, int stage,
                                       int centerX, int centerY, int size, float alpha) {
        ItemStack probe = sword.copy();
        CompoundTag tag = customData(probe);
        tag.putInt("KunJinKaoCompileStage", stage);
        probe.setTag(tag);   // 1.20.1 没有 CUSTOM_DATA 组件：字段就存在物品自己的根 NBT 上

        PoseStack pose = graphics.pose();
        pose.pushPose();
        // 先挪到屏幕中心，再缩放。
        //
        // 坐标系是【先平移后缩放】：renderItem 内部会自己 translate(x + 8, y + 8, 150)，
        // 那个 +8 会被我们的 scale 放大 —— 直接传 (0,0) 的话模型会被推到右下角
        // （约 8 * scale 像素处）。所以传 (-8, -8) 把内部那 +8 抵消掉，
        // 模型中心才落在我们平移到的位置上。
        pose.translate(centerX, centerY, 100.0F);
        float scale = size / 16.0F * CENTER_COMPILE_3D_ZOOM;
        pose.scale(scale, scale, 1.0F);
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.renderItem(probe, -8, -8);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        pose.popPose();
    }

    /** 中央展示要画的那把剑；不是本模组的剑时返回 null，让调用方退回扁平贴图。 */
    private static ItemStack swordForOverlay(Player holder) {
        ItemStack stack = compileHand == null
                ? holder.getMainHandItem()
                : holder.getItemInHand(compileHand);
        return KunJinKaoSwordItem.isSwordLike(stack) ? stack : null;
    }

    /** 当前编译动画绑定的手；{@code RenderHandEvent} 用它保证一帧只画一次中央展示。 */
    public static InteractionHand getCompileHand() {
        return compileHand;
    }

    /**
     * 供物品模型谓词读取。返回 -1 时使用完整剑模型；0..0.875 对应八个逐行编译阶段。
     */
    public static float getDrawCompileModelStage(ItemStack stack, LivingEntity renderEntity) {
        CompoundTag tag = customData(stack);
        if (tag.getBoolean("KunJinKaoCompileHudModel")) {
            return -1.0F;
        }
        if (tag.contains("KunJinKaoCompileStage")) {
            return Math.max(0, Math.min(7, tag.getInt("KunJinKaoCompileStage"))) / 8.0F;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (renderEntity instanceof Player renderedPlayer
                && renderedPlayer != player
                && RemoteSwordDrawVisualState.isCompiling(renderedPlayer.getUUID())
                && isVisibleSword(stack)
                && ItemStack.isSameItemSameTags(stack,
                renderedPlayer.getItemInHand(RemoteSwordDrawVisualState.getHand(renderedPlayer.getUUID())))) {
            // 远端持剑玩家的原始手持剑在编译期间隐藏，由第三人称渲染层绘制独立动画剑。
            return 0.0F;
        }
        // 第一人称物品模型求值时 renderEntity 可能为 null；不能把它当作玩家身份依据，
        // 否则会直接回退完整模型，表现为数秒后整把剑突然出现。
        if (player == null || drawCompileTicks <= 0 || !isVisibleSword(stack)
                || !ItemStack.isSameItemSameTags(stack, player.getItemInHand(compileHand))) {
            return -1.0F;
        }
        if (!SWITCH_LOCAL_HAND_MODEL || !compileStageModelsUsable) {
            // 手持物品保持完整剑：编译阶段的 3D 方块与剑的平面贴图形态差异过大，
            // 切换只会让玩家以为剑消失了。
            return -1.0F;
        }
        int elapsed = DRAW_COMPtLE_TtCKS - drawCompileTicks;
        int stage = Math.min(7, elapsed / 6);
        return stage / 8.0F;
    }

    /**
     * 当前手是否正在显示位于屏幕中央的逐段编译模型。
     * <p>
     * 手持物品在编译期间【保持显示】（见 {@code KunJinKaoIdleDataRefreshHandler}），
     * 本方法只用于决定是否额外绘制"伸手抓剑"的手臂。
     * 带上 {@code compileStageModelsUsable} 是必要的：阶段模型不可用时中央什么都画不出来，
     * 那就没有剑可抓，手臂也不该出现。
     */
    public static boolean isDrawingInCenter(InteractionHand hand) {
        return drawCompileTicks > 0 && compileHand == hand && compileStageModelsUsable;
    }

    /** 当前应直接绘制的 3D 编译模型阶段。 */
    public static int getDrawCompileStage() {
        int elapsed = DRAW_COMPtLE_TtCKS - drawCompileTicks;
        return Math.max(0, Math.min(7, elapsed / 6));
    }

    /** 仅用于确认客户端的第一人称模型渲染入口确实被调用。 */
    public static void noteCompileRenderObserved() {
        if (!compileRenderObserved) {
            compileRenderObserved = true;
            LOGGER.info("剑编译 3D 渲染入口已进入");
        }
    }

    /**
     * 逐个探测 8 个编译阶段模型是否真的有面。
     * <p>
     * 用 {@code KunJinKaoCompileStage} 标记的副本来求值，因此不依赖当前动画状态。
     * 历史上这些模型曾因父模型挂在 builtin/generated 下且缺少 layer0 而被
     * ItemModelGenerator 生成为空模型 —— 那时本方法返回 false，谓词退回完整剑，
     * 至少不会出现"选中剑后手里空空如也"。
     */
    private static boolean areCompileStageModelsUsable(Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        ItemStack probe = player.getMainHandItem().copy();
        for (int stage = 0; stage < 8; stage++) {
            CompoundTag tag = customData(probe);
            tag.putInt("KunJinKaoCompileStage", stage);
            probe.setTag(tag);   // 1.20.1 没有 CUSTOM_DATA 组件：字段就存在物品自己的根 NBT 上
            BakedModel model = minecraft.getItemRenderer().getModel(probe, minecraft.level, player, 0);
            if (model == minecraft.getModelManager().getMissingModel() || countQuads(model) <= 0) {
                LOGGER.warn("剑编译阶段 {} 的模型没有几何体，本次动画退回完整剑模型", stage);
                return false;
            }
        }
        return true;
    }

    /** 统计烘焙模型的面数，用于诊断（完整剑约 3145 面，compile_0 只有个位数）。 */
    private static int countQuads(BakedModel model) {
        RandomSource random = RandomSource.create(0L);
        int quads = model.getQuads(null, null, random).size();
        for (Direction direction : Direction.values()) {
            random.setSeed(0L);
            quads += model.getQuads(null, direction, random).size();
        }
        return quads;
    }

    /** 编译完成后取剑动作的剩余进度，供第一人称手部渲染使用。 */
    public static float getGrabSwordProgress() {
        return grabSwordTicks / (float) GRAB_SWORD_TtCKS;
    }

    /** 编译后半段真实手臂伸向剑柄的平滑进度。 */
    public static float getCompileReachProgress() {
        int elapsed = DRAW_COMPtLE_TtCKS - drawCompileTicks;
        float linear = Math.max(0.0F, Math.min(1.0F, (elapsed - 25.0F) / 21.0F));
        return linear * linear * (3.0F - 2.0F * linear);
    }

    public static boolean isThirdPersonReachActive(Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        return player == minecraft.player
                && drawCompileTicks > 0
                && getCompileReachProgress() > 0.0F
                && minecraft.options.getCameraType() != CameraType.FIRST_PERSON;
    }

    public static HumanoidArm getCompileArm(Player player) {
        if (compileHand == InteractionHand.MAIN_HAND) {
            return player.getMainArm();
        }
        return player.getMainArm() == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    private static boolean isHoldingVisibleSword(Player player) {
        return isVisibleSword(player.getMainHandItem()) || isVisibleSword(player.getOffhandItem());
    }

    private static boolean isVisibleSword(ItemStack stack) {
        return KunJinKaoSwordItem.isSwordLike(stack) && !KunJinKaoSwordItem.isDisguised(stack);
    }

    private static void emitCompileParticles(Player player, int ticksLeft) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 side = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-4D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            side = side.normalize();
        }
        double progress = 1.0D - ticksLeft / 14.0D;
        // 第一人称没有可直接读取的“物品骨骼坐标”，因此按持剑手、主手方向和视线
        // 推导出稳定的剑位锚点。它位于准星侧下方，而不是相机/准星正中心。
        boolean rightSide = (compileHand == InteractionHand.MAIN_HAND)
                == (player.getMainArm() == HumanoidArm.RIGHT);
        // look × up 在当前相机坐标中为屏幕右侧。
        double handOffset = rightSide ? 0.38D : -0.38D;
        Vec3 swordAnchor = player.getEyePosition()
                .add(side.scale(handOffset))
                // 再向屏幕右侧推进到边缘附近。
                .add(look.scale(0.26D))
                .add(0.0D, -0.54D, 0.0D);
        // 粒子从剑前方的“虚空”向剑位收束，模拟编译拼合。
        Vec3 origin = swordAnchor.add(look.scale(0.58D - progress * 0.40D));
        for (int index = -1; index <= 1; index++) {
            Vec3 point = origin.add(side.scale(index * 0.075D)).add(0.0D, (index & 1) * 0.035D, 0.0D);
            minecraft.level.addParticle(index == 0 ? SCAN_WHtTE : SCAN_BLUE,
                    point.x, point.y, point.z, -look.x * 0.018D, -look.y * 0.018D, -look.z * 0.018D);
        }
        if ((ticksLeft & 1) == 0) {
            minecraft.level.addParticle(ParticleTypes.END_ROD, origin.x, origin.y, origin.z,
                    0.0D, 0.012D, 0.0D);
        }
    }

    private static void emitCentralCompileParticles(Player player, int ticksLeft) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 side = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-4D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            side = side.normalize();
        }
        Vec3 up = side.cross(look).normalize();
        double elapsed = DRAW_COMPtLE_TtCKS - ticksLeft;
        Vec3 center = player.getEyePosition().add(look.scale(1.18D));

        // 两条相反方向的淡蓝数据环围绕屏幕中央的剑旋转，并在完成时向剑身收束。
        double radius = 0.31D - elapsed / DRAW_COMPtLE_TtCKS * 0.10D;
        for (int index = 0; index < 4; index++) {
            double angle = elapsed * 0.68D + index * (Math.PI / 2.0D);
            double vertical = Math.sin(angle * 1.7D) * 0.11D;
            Vec3 radial = side.scale(Math.cos(angle) * radius)
                    .add(up.scale(Math.sin(angle) * radius + vertical));
            Vec3 point = center.add(radial);
            minecraft.level.addParticle((index & 1) == 0 ? SCAN_BLUE : SCAN_WHtTE,
                    point.x, point.y, point.z,
                    radial.x * -0.055D, radial.y * -0.055D, radial.z * -0.055D);
        }
    }

    private static void emitStructuredAfterimage(Player player, Entity target) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 origin = player.getEyePosition();
        Vec3 towardTarget = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).subtract(origin);
        if (towardTarget.lengthSqr() < 0.01D) {
            return;
        }
        Vec3 forward = towardTarget.normalize();
        Vec3 side = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-4D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            side = side.normalize();
        }
        Vec3 up = side.cross(forward).normalize();
        double length = Math.min(2.8D, towardTarget.length());
        // 规整的 3x4 矩形数据块拖尾。
        for (int row = 0; row < 4; row++) {
            Vec3 rowCenter = origin.add(forward.scale(0.35D + length * row / 5.0D));
            for (int column = -1; column <= 1; column++) {
                Vec3 point = rowCenter.add(side.scale(column * 0.12D)).add(up.scale((row & 1) * 0.055D));
                minecraft.level.addParticle((row + column) % 2 == 0 ? SCAN_BLUE : DATA_GREEN,
                        point.x, point.y, point.z, forward.x * 0.012D, forward.y * 0.012D, forward.z * 0.012D);
            }
        }
    }

    private static CompoundTag customData(ItemStack stack) {
        CompoundTag data = stack.getTag();
        return data == null ? new CompoundTag() : data.copy();   // 1.20.1 的 CompoundTag#copy()（1.21 叫 copyTag）
    }

    /**
     * 断线/退出世界时的清理入口：复用既有私有 reset()，不对外暴露内部计时字段。
     * 注意 tick() 在 player/level 为空时本来就会调用 reset()，因此该入口只保证
     * "退出世界立刻清空"，不改变进世界时的表现。
     */
    public static void clear() {
        reset();
    }

    private static void reset() {
        wasHoldingSword = false;
        compileHand = InteractionHand.MAIN_HAND;
        drawCompileTicks = 0;
        grabSwordTicks = 0;
        compileRenderObserved = false;
        compileModelLogged = false;
        centerDrawLogged = false;
        compileStageModelsUsable = true;
        rightClickCompileTicks = 0;
        attackHudTicks = 0;
    }

    private static InteractionHand findVisibleSwordHand(Player player) {
        return isVisibleSword(player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }
}
