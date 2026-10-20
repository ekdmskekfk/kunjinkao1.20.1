package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.inject.InjectionData;
import dev.modmind.kunjinkao.world.SwordTimeAcceleration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 让「被注入的任意物品」拥有原管理员剑的行为（1.20.1 Forge 版）。
 *
 * <p>原剑的行为写在物品类的 override 里（{@code use} / {@code useOn} /
 * {@code onItemUseFirst} / {@code getDestroySpeed} / {@code isCorrectToolForDrops}）。
 * 一个泥土方块不是那个类，原版永远不会调这些方法 —— 所以对注入物品改由本处理器在
 * 事件里转调 {@code KunJinKaoSwordItem} 里抽出来的同名静态实现。</p>
 *
 * <p><b>不会重复执行</b>：{@link #applies} 明确排除了剑本体
 * （{@code !(KunJinKaoSwordItem.isSwordLike(stack))}）。剑仍然走它自己的
 * override，注入物品走这里，两条路互斥。</p>
 *
 * <p>与 1.21.1 版本的差别只在事件包名（{@code net.minecraftforge.event.*}）和
 * 一个 {@code consumesAction()} 的用法 —— 逻辑一一对应。</p>
 */
public final class InjectedItemBehaviorHandler {

    private InjectedItemBehaviorHandler() {
    }

    /** 只处理「注入过、且不是剑本体」的栈。 */
    private static boolean applies(ItemStack stack) {
        return InjectionData.isInjected(stack) && !(stack.getItem() instanceof KunJinKaoSwordItem);
    }

    // ===== 右键方块：onItemUseFirst + useOn =====

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!applies(stack)) {
            return;
        }
        Player player = event.getEntity();
        if (player == null) {
            return;
        }

        // ---- 1) 潜行路径：原版在 useOn 之前会先调 onItemUseFirst ----
        if (player.isShiftKeyDown() && !KunJinKaoSwordItem.isWrenchEnabled(stack)) {
            if (KunJinKaoSwordItem.tryPlaceAreaField(player, event.getLevel(), event.getPos(), stack)) {
                consume(event, InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
                return;
            }
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                SwordTimeAcceleration.tryToggleBlock(player, serverLevel, event.getPos(), stack);
            }
            consume(event, InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
            return;
        }

        // ---- 2) useOn：放置类核心 / 时间加速 / 刷子 / 锄头铲子 / 避雷针 ----
        UseOnContext context = new UseOnContext(player, event.getHand(), event.getHitVec());
        InteractionResult result = KunJinKaoSwordItem.useOnImpl(stack, context);
        if (result != InteractionResult.PASS) {
            consume(event, result);
        }
        // PASS：刻意不取消，原版会接着调 use()，由下面的 onRightClickItem 接住。
    }

    // ===== 右键（空气）：use =====

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (!applies(stack)) {
            return;
        }
        Player player = event.getEntity();
        if (player == null) {
            return;
        }
        InteractionResultHolder<ItemStack> result =
                KunJinKaoSwordItem.useImpl(stack, player.level(), player, event.getHand());
        if (result.getResult() != InteractionResult.PASS) {
            event.setCancellationResult(result.getResult());
            event.setCanceled(true);
        }
    }

    private static void consume(PlayerInteractEvent.RightClickBlock event, InteractionResult result) {
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    // ===== 攻击：补上 hurtEnemy 的瞬杀路径 =====

    /**
     * 原剑的 {@code hurtEnemy} 在命中瞬间直接 {@code kill()} 目标（覆写开关关闭时），
     * 对玩家目标则打处决标记后处决。{@code KunJinKaoProtectionHandler} 只负责"打标记 +
     * 放行伤害"，真正的瞬杀写在物品 override 里 —— 注入物品拿不到那个 override，
     * 所以必须在这里补一刀，否则注入物品只有面板攻击力、没有"砍中即秒杀"。
     *
     * <p>三处刻意跳过：伤害上限被调低（按普通伤害）、覆写开关打开（由
     * {@code onLivingAttack} 启动覆写流程）、客户端（只做服务端权威结算）。</p>
     */
    @SubscribeEvent
    public static void onAttack(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        Player attacker = event.getEntity();
        ItemStack stack = attacker.getMainHandItem();
        if (!applies(stack)) {
            return;
        }
        if (!(event.getTarget() instanceof net.minecraft.world.entity.LivingEntity target)) {
            return;
        }
        if (KunJinKaoSwordItem.getAttackDamageLimit(stack)
                < KunJinKaoSwordItem.MAX_ATTACK_DAMAGE_LIMIT) {
            return;
        }
        if (attacker.level().isClientSide()) {
            return;
        }
        if (target instanceof Player) {
            KunJinKaoSwordItem.applyExecutionMark(target, stack);
            KunJinKaoSwordItem.killIgnoringImmunity(target,
                    KunJinKaoSwordItem.swordKillSource(attacker));
            return;
        }
        if (KunJinKaoSwordItem.isOverwriteEnabled(stack)) {
            return;
        }
        KunJinKaoSwordItem.applyKunJinKaoMark(target, stack);
        KunJinKaoSwordItem.killIgnoringImmunity(target, KunJinKaoSwordItem.swordKillSource(attacker));
    }

    // ===== 挖掘：getDestroySpeed / isCorrectToolForDrops =====

    /** 挖掘速度。原剑的 {@code getDestroySpeed} 直接返回设置值（1..100），这里照搬。 */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        ItemStack stack = event.getEntity().getMainHandItem();
        if (!applies(stack)) {
            return;
        }
        event.setNewSpeed(KunJinKaoSwordItem.getMiningSpeed(stack));
    }

    /** 原剑的 {@code isCorrectToolForDrops} 恒为 true，这里等价地强制可掉落。 */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        ItemStack stack = event.getEntity().getMainHandItem();
        if (!applies(stack)) {
            return;
        }
        event.setCanHarvest(true);
    }
}
