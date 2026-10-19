package dev.modmind.kunjinkao.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * 选区作业（127）的框选入口：手持木棍右键方块设角点，第一次设 A、第二次设 B、第三次从 A 重新开始。
 * <p>
 * <b>为什么是木棍右键，而不是一个按键。</b> 按键只能框到准星指着的那一格，玩家得先站到位置、
 * 对准、再按键，每设一个角点都要重复一遍；右键方块拿到的坐标本来就是"我点的这一块"，
 * 少一步对准，也少一个和别的模组抢默认键的机会。
 * <p>
 * <b>不碰不是木棍的右键。</b> 手里不是木棍时这个处理器什么都没做、也不取消事件 ——
 * 木棍在原版是个普通物品，别人（玩家自己、别的模组）拿它点方块不该被本模组拦下。
 * <p>
 * <b>是木棍就一定吃掉这一下。</b> 一旦确认是木棍，无论判定通过还是被拒（总闸关、未授权、
 * 有作业在跑、太远），都把事件取消掉：不取消的话，木棍这一下还会继续走到方块自己的交互上
 * （拉杆、按钮、门都会响应），于是玩家"框选"的同时顺手把机器开了。
 * <p>
 * <b>只在服务端处理。</b> 授权与总闸只有服务端知道，客户端那一份镜像（{@code QolClientState}）
 * 只覆盖面板上的项，拿它当依据会出现"客户端以为能用、服务端其实关着"。
 * 代价是木棍右键拉杆时客户端会先按本地预测把拉杆翻过去，服务端拒绝后同步回来纠正 ——
 * 一次瞬时闪烁，比在两处各判一遍权限（还得让两边永远一致）划算。
 */
public final class ZoneSelectionHandler {

    // 刻意不写私有构造器：本类要由 KunJinKaoEntry 用 new 出来注册到事件总线上
    // （与 KunJinKaoQolHandler 同一套做法），隐式的公开无参构造器正是为此存在的。

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            // 副手也拿着一根木棍时不该再设一次角点：一次右键一个角点，
            // 两个手各设一次会让"第几次右键"这件事变得没法数。
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!event.getItemStack().is(Items.STICK)) {
            // 不是木棍：直接放行，连取消都不取消 —— 别影响任何人用别的物品点方块。
            return;
        }
        KunJinKaoQolHandler.trySetZoneCorner(player, event.getPos());
        event.setCanceled(true);
    }
}