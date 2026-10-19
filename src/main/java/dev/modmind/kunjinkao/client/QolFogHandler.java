package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.qol.QolClientState;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * 碧波清眸（功能 87）：水下与岩浆里把雾推开，看得清远处。
 * <p>
 * <b>用事件而不是 mixin。</b> NeoForge 已经把雾的距离（{@link ViewportEvent.RenderFog}）
 * 与颜色（{@link ViewportEvent.ComputeFogColor}）开成了事件，能满足需求时就不该去改
 * {@code FogRenderer} 的方法体 —— mixin 注入外部渲染类最容易和其它模组（尤其是光影）打架，
 * 而事件是它们之间的公共契约。
 * <p>
 * <b>渲染距离与颜色必须一起改。</b> 只把雾推开会让远处变成一整片原版的水蓝色；
 * 只改颜色则还是只能看到眼前两格。两个事件配合才是"看得清"。
 * <p>
 * <b>与光影（Iris/Oculus）大概率冲突</b>：它们会整个接管雾的计算，本处理器可能压根收不到事件。
 * 所以这一项默认关闭，并且单独一个开关 —— 用户发现画面不对时可以只关它。
 */
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QolFogHandler {

    /** 推开后的近平面：0 表示从眼睛处就开始可见，不再有一层贴脸的浓雾。 */
    private static final float CLEAR_NEAR = 0.0F;
    /** 推开后的远平面（格）。原版水下大约 15 格，这里给到 128 —— 足够看清地形又不至于看到世界尽头。 */
    private static final float CLEAR_FAR = 128.0F;

    /**
     * 水下的雾色。
     * <p>
     * 比原版的水蓝更暗、更偏青：雾色是"整个画面的底色"，调得太亮会让水下看起来像白天的大雾，
     * 方块反而更难分辨。目标是"能看见"，不是"看不见水"。
     */
    private static final float WATER_RED = 0.02F;
    private static final float WATER_GREEN = 0.28F;
    private static final float WATER_BLUE = 0.38F;
    /** 岩浆里的雾色：压暗压红，不然推开雾之后整屏都是刺眼的橙。 */
    private static final float LAVA_RED = 0.45F;
    private static final float LAVA_GREEN = 0.10F;
    private static final float LAVA_BLUE = 0.01F;

    private QolFogHandler() {
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!isEnabled()) {
            return;
        }
        FogType type = event.getType();
        if (type != FogType.WATER && type != FogType.LAVA) {
            return;
        }
        // 必须 cancel：RenderFog 的约定是"不取消就不生效"。改了距离却忘了取消，
        // 表现就是"开关能点、设置也在，但画面一点没变"，而且不会有任何报错。
        event.setNearPlaneDistance(CLEAR_NEAR);
        event.setFarPlaneDistance(CLEAR_FAR);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!isEnabled()) {
            return;
        }
        // ComputeFogColor 没有 getType —— 原版算颜色时不区分液体种类，
        // 所以这里自己看相机泡在什么里。用相机而不是 Minecraft.player：
        // 相机才是这一帧真正在渲染的位置，旁观与第三人称时才不会按玩家的头算错。
        Entity cameraEntity = event.getCamera().getEntity();
        if (!(cameraEntity instanceof Player player)) {
            return;
        }
        if (player.isEyeInFluid(FluidTags.WATER)) {
            event.setRed(WATER_RED);
            event.setGreen(WATER_GREEN);
            event.setBlue(WATER_BLUE);
        } else if (player.isEyeInFluid(FluidTags.LAVA)) {
            event.setRed(LAVA_RED);
            event.setGreen(LAVA_GREEN);
            event.setBlue(LAVA_BLUE);
        }
    }

    /**
     * 开关状态。
     * <p>
     * 读的是服务端推来的客户端镜像。没收到同步时是 false —— 宁可不开，
     * 也不要在一个还没同步的状态下擅自改画面。
     */
    private static boolean isEnabled() {
        return QolClientState.isOn(QolSetting.FOG_VISION);
    }
}