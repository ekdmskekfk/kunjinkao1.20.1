package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.client.qol.QolClientState;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;

/**
 * 屹立不倒（204）的客户端半边：不让任何外部来源把玩家挪走。
 * <p>
 * <b>为什么这一半必须在客户端做。</b> 玩家的位置是客户端权威的：服务端只接收客户端报上来的坐标，
 * 它自己<b>不模拟</b>玩家物理。而"玩家有没有按方向键"这件事，1.21.1 的服务端根本拿不到 ——
 * {@code ServerboundPlayerInputPacket} 里确实带着 xxa/zza，但 {@code ServerPlayer#setPlayerInput}
 * 只在<b>骑乘</b>时把它们写进字段（那是给船和马转向用的），走路的输入服务端一无所知。
 * 所以"推开/拉走/炸飞"这一类只能由客户端自己拒绝：按着方向键就一切照旧，松开就把这一 tick
 * 的位移收回 —— 位置不由自己产生，就不接受。
 * <p>
 * 服务端那一半（{@code KunJinKaoStandFirmHandler}）负责两件客户端管不了的事：取消击退事件、
 * 取消换维度。
 * <p>
 * <b>时序</b>：{@code ClientTickEvent.Pre} 是 {@code Minecraft#tick()} 的开头（记下 tick 起点），
 * {@code Post} 是结尾（把这一 tick 里"不该有的"位移收回），中间正好夹着一次完整的
 * 客户端世界 tick —— 玩家自己的行走、别人的推动、爆炸的推力都发生在这一段里。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class StandFirmClient {

    /** 这一 tick 开始时玩家在哪；null 表示这一 tick 不需要管（没开、在骑乘、在旁观）。 */
    @Nullable
    private static Vec3 tickStart;

    private StandFirmClient() {
    }

    @SubscribeEvent
    public static void onClientTickPre(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        tickStart = active(player) ? player.position() : null;
    }

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Vec3 start = tickStart;
        tickStart = null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (start == null || !active(player)) {
            return;
        }
        // 自己按着方向键：这一 tick 的一切照旧，连惯性滑行都不干预。
        if (pressingMoveKeys(player)) {
            return;
        }

        // 速度：横向与被"弹起来"（+Y）清零。向下留着 —— 那是重力与下落，
        // 收掉的话人会停在空中，连台阶都下不去。
        Vec3 delta = player.getDeltaMovement();
        player.setDeltaMovement(0.0D, Math.min(delta.y, 0.0D), 0.0D);

        // 位置：有的模组不用速度、直接改坐标（拽人、抓取、强制位移），所以位置也要收一次。
        // 纵向只在"被抬高"时收：被弹飞要拦，往下掉不能拦。
        double y = player.getY() > start.y ? start.y : player.getY();
        player.setPos(start.x, y, start.z);
    }

    /** 这一项开着、而且位置确实该由玩家自己决定时才生效。 */
    private static boolean active(@Nullable LocalPlayer player) {
        if (player == null || !QolClientState.isOn(QolSetting.STAND_FIRM)) {
            return false;
        }
        // 骑乘时位置由载具决定，那不是"被推"；旁观者本来就不参与物理。
        if (player.isPassenger() || player.isSpectator()) {
            return false;
        }
        // 鞘翅滑翔同样放行：它的推力和方向来自滑翔本身，不是玩家每 tick 按键产生的，
        // 拦下来会变成"松手就停在半空往下掉" —— 那不是"屹立不倒"，那是坏掉。
        return !player.isFallFlying();
    }

    /**
     * 玩家自己有没有在操作移动。
     * <p>
     * 读的是 {@code LocalPlayer#input}（客户端每 tick 按按键填好的 up/down/left/right/jumping），
     * 不是按键映射：打开界面时输入会被清掉，用按键映射读会以为玩家还在走。
     * 跳跃也算"自己在动" —— 不算的话按住空格跳不起来。
     */
    private static boolean pressingMoveKeys(LocalPlayer player) {
        Input input = player.input;
        return input.up || input.down || input.left || input.right || input.jumping;
    }
}