package dev.modmind.kunjinkao.client.render;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import org.joml.Vector3f;

import java.util.Random;

/**
 * 手持本模组的剑时，在玩家身边持续冒出的粒子。
 * <p>
 * 现有的粒子都挂在【事件】上（编译、覆写、放置），也就是"做了什么才有反应"。
 * 缺的是<b>待机时的存在感</b> —— 剑拿在手里应当看起来像在不断漏出能量，
 * 而不是一件静止的贴图。
 * <p>
 * 这里只做两件事，都很克制：
 * <ul>
 *   <li><b>待机</b>：每隔几刻从手边冒 1~2 粒，缓慢下坠；</li>
 *   <li><b>挥砍</b>：挥动期间沿身前扫出一道弧线，把斩击轨迹画出来。</li>
 * </ul>
 * 数量刻意压得很低 —— 粒子多了会变成光污染，反而廉价。
 * <p>
 * <b>位置是近似的</b>：粒子需要世界坐标，而拿到"刀刃在世界里的确切位置"要先把
 * 手部 pose 从视图空间换算回世界空间，那需要物品变换【之后】的矩阵 ——
 * 手部事件触发时变换还没施加。所以这里用手的位置（躯干偏航旋转后的肩高偏移）代替。
 * 要精确到刀刃得改成在 RenderHandEvent 里采集矩阵，成本与收益不成比例。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwordAmbientParticles {

    /** 刀脊主色，与图集里的 T3 spine 一致。 */
    private static final DustParticleOptions BLADE_GLOW =
            new DustParticleOptions(new Vector3f(0.34F, 0.81F, 1.00F), 0.85F);
    /** 亮芯色：粒子小一点、白一点，做出层次。 */
    private static final DustParticleOptions BLADE_CORE =
            new DustParticleOptions(new Vector3f(0.69F, 0.95F, 1.00F), 0.55F);
    /** 故障绿，只偶尔出现，点一下"数据损坏"的味道。 */
    private static final DustParticleOptions GLITCH =
            new DustParticleOptions(new Vector3f(0.35F, 1.00F, 0.82F), 0.5F);

    /** 待机粒子间隔（tick）。3 刻约每秒 6~7 粒：够有存在感，又不刷屏。 */
    private static final int IDLE_INTERVAL = 3;

    /** 待机嗡鸣的重播间隔（tick）。比 hum.ogg 的 2 秒略短，形成轻微交叠。 */
    private static final int HUM_INTERVAL = 39;
    /** 底噪要很轻 —— 它该是"感觉到"而不是"听到"。 */
    private static final float HUM_VOLUME = 0.22F;

    private static final Random RANDOM = new Random();
    private static int idleTicker;
    private static int humTicker;

    private SwordAmbientParticles() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 1.20.1 的客户端 tick 事件没有 .Post 子类，靠 Phase 区分。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null || minecraft.isPaused()) {
            return;
        }
        // 真隐形时连粒子都不该有，否则等于自己把自己照出来。
        if (player.isInvisible()) {
            return;
        }

        ItemStack stack = heldVisibleSword(player);
        if (stack == null) {
            idleTicker = 0;
            humTicker = 0;
            return;
        }

        // 待机嗡鸣：hum.ogg 是 2 秒整周期的整数谐波，本身无缝。
        // 每 39 刻（1.95 秒）叠一次，比 2 秒略短，形成轻微交叠 ——
        // 正好补掉调度抖动造成的缝隙，听上去是连续的底噪而不是一段段重复。
        // 音高加一点随机，免得像坏掉的循环。
        if (++humTicker >= HUM_INTERVAL) {
            humTicker = 0;
            player.level().playLocalSound(player.getX(), player.getY(), player.getZ(),
                    dev.modmind.kunjinkao.sound.KunJinKaoSounds.HUM.get(),
                    net.minecraft.sounds.SoundSource.AMBIENT,
                    HUM_VOLUME, 0.97F + RANDOM.nextFloat() * 0.06F, false);
        }

        // ================= 挥砍轨迹 =================
        // getAttackAnim 在挥动期间从 0 走到 1，正合适当进度用。
        float swing = player.getAttackAnim(0.0F);
        if (swing > 0.0F) {
            emitSlashArc(player, level, swing);
        }

        // ================= 待机漏电 =================
        if (++idleTicker < IDLE_INTERVAL) {
            return;
        }
        idleTicker = 0;
        emitIdleMotes(player, level);
    }

    /**
     * 在手边冒一两粒，向下飘。
     * <p>
     * 速度给一个很小的向下分量，让它们像被照亮的灰尘一样缓缓沉；
     * 完全不动的粒子看起来是"贴上去的"，反而假。
     */
    private static void emitIdleMotes(Player player, Level level) {
        Vec3 hand = handAnchor(player, 0.0F);
        int count = 1 + RANDOM.nextInt(2);
        for (int i = 0; i < count; i++) {
            double x = hand.x + (RANDOM.nextDouble() - 0.5) * 0.34;
            double y = hand.y + RANDOM.nextDouble() * 0.42;
            double z = hand.z + (RANDOM.nextDouble() - 0.5) * 0.34;
            boolean glitch = RANDOM.nextInt(24) == 0;
            DustParticleOptions options = glitch ? GLITCH : (RANDOM.nextBoolean() ? BLADE_GLOW : BLADE_CORE);
            // 故障粒子往上跳、正常粒子往下沉 —— 方向相反才显得"不对劲"
            double vy = glitch ? 0.035 : -0.012 - RANDOM.nextDouble() * 0.018;
            level.addParticle(options, x, y, z,
                    (RANDOM.nextDouble() - 0.5) * 0.015, vy, (RANDOM.nextDouble() - 0.5) * 0.015);
        }
        // 极偶尔补一颗发光棒粒子，给一个明显的亮点，免得全是细尘看不出光
        if (RANDOM.nextInt(10) == 0) {
            level.addParticle(ParticleTypes.END_ROD,
                    hand.x + (RANDOM.nextDouble() - 0.5) * 0.25,
                    hand.y + RANDOM.nextDouble() * 0.3,
                    hand.z + (RANDOM.nextDouble() - 0.5) * 0.25,
                    0.0, -0.006, 0.0);
        }
    }

    /**
     * 沿身前扫一道弧。
     * <p>
     * 弧的半径随挥动进度先张开再收拢（sin(pi * swing)），角度从一侧扫到另一侧，
     * 连起来就是一道斩击轨迹。
     */
    private static void emitSlashArc(Player player, Level level, float swing) {
        float yaw = Mth.lerp(0.0F, player.yBodyRotO, player.yBodyRot);
        float spread = Mth.sin((float) Math.PI * swing);      // 0 -> 1 -> 0
        if (spread < 0.15F) {
            return;
        }
        // 挥动方向：主手在右就是从一侧扫到另一侧，反之亦然
        float dir = player.getMainArm() == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        double radius = 0.55 + 0.45 * spread;
        double baseAngle = Math.toRadians(yaw + 90.0F);

        int steps = 1 + (int) (spread * 4.0F);
        for (int i = 0; i < steps; i++) {
            double t = (RANDOM.nextDouble() - 0.5) * 1.6 * spread * dir;
            double angle = baseAngle + t;
            double x = player.getX() - Math.sin(angle) * radius;
            double z = player.getZ() + Math.cos(angle) * radius;
            double y = player.getY() + 1.05 + (RANDOM.nextDouble() - 0.5) * 0.5 + spread * 0.15;
            level.addParticle(RANDOM.nextInt(3) == 0 ? BLADE_CORE : BLADE_GLOW, x, y, z,
                    -Math.sin(angle) * 0.05, 0.01, Math.cos(angle) * 0.05);
        }
    }

    /**
     * 手所在的大致世界坐标：以躯干偏航旋转一个肩高偏移。
     * <p>
     * 见类注释：要精确到刀刃得把手部 pose 换算回世界空间，代价不值得。
     */
    private static Vec3 handAnchor(Player player, float partialTick) {
        float yaw = (float) Math.toRadians(Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot));
        double side = player.getMainArm() == HumanoidArm.RIGHT ? -0.34 : 0.34;
        double forward = 0.22;
        double x = player.getX() + side * Math.cos(yaw) - forward * Math.sin(yaw);
        double z = player.getZ() + side * Math.sin(yaw) + forward * Math.cos(yaw);
        return new Vec3(x, player.getY() + player.getEyeHeight() * 0.68, z);
    }

    /** 手上那把可见的剑；没拿、或处于伪装状态时返回 null。 */
    private static ItemStack heldVisibleSword(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof KunJinKaoSwordItem && !KunJinKaoSwordItem.isDisguised(stack)) {
                return stack;
            }
        }
        return null;
    }
}