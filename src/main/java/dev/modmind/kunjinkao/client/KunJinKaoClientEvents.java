package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.KunJinKaoTheme;
import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.network.NetworkHandler;
import dev.modmind.kunjinkao.network.SimpleActionPayload;
import dev.modmind.kunjinkao.network.SwordSettingPayload;
import dev.modmind.kunjinkao.network.ZoneAdjustPayload;
import dev.modmind.kunjinkao.world.SwordTimeAcceleration;
import net.minecraftforge.client.event.InputEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.modmind.kunjinkao.network.ToggleDisguisePayload;
import dev.modmind.kunjinkao.network.ToggleOverwritePayload;
import dev.modmind.kunjinkao.network.ToggleThemePayload;
import dev.modmind.kunjinkao.network.ToggleTacticalHudPayload;
import dev.modmind.kunjinkao.client.hud.HudScreen;
import dev.modmind.kunjinkao.client.hud.HudEntityScreen;
import dev.modmind.kunjinkao.client.gui.SwordOptionsScreen;
import dev.modmind.kunjinkao.client.gui.AdminPasswordScreen;
import dev.modmind.kunjinkao.client.gui.QolControlScreen;
import dev.modmind.kunjinkao.client.qol.QolClientState;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = KunJinKaoEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KunJinKaoClientEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 1.20.1 的客户端 tick 事件没有 .Pre/.Post 子类，靠 Phase 区分：
        // 原来叫 onClientTickPre 的用 START，叫 onClientTickPost 的用 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
                KunJinKaoClientOverwriteEffects.tick();
        KunJinKaoClientSwordVisuals.tick();
        RemoteSwordDrawVisualState.tick();
        ClientHudState.tick();
        for (int entityId : KunJinKaoClientOverwriteEffects.getActiveEntityIds()) {
            KunJinKaoClientOverwriteEffects.tickSounds(entityId);
        }
        handleToggleDisguise();
        handleToggleOverwrite();
        handleCycleTheme();
        handleToggleTacticalHud();
        handleOpenSwordOptions();
        handleOpenQolPanel();
        // 面板里绑的开关快捷键：按键格只在面板里改，实际触发在游戏里，
        // 所以放在这一串 tick 处理里，与其它界面开关同一时机。边界检测在 QolKeybinds 里做。
        // 用全限定名而不是加 import：本文件只允许在 onClientTick 里动一行。
        dev.modmind.kunjinkao.client.qol.QolKeybinds.tick(Minecraft.getInstance());
        // 寻宝大师（96）的扫描：每 client tick 调一次，内部自己按 tick 计数决定何时真扫，
        // 并且把区块分批摊到多次扫描里，避免单帧卡顿。
        dev.modmind.kunjinkao.client.qol.QolTreasureScanner.tick(Minecraft.getInstance());
        // 装饰（201–203）的客户端推进：反击进度外推 + 光环/拖尾粒子。画面在 CosmeticRenderer 里。
        dev.modmind.kunjinkao.client.CosmeticClientState.tick();
        InjectionClientState.tick();
        handleInject();
        handleOpenAdminPassword();
        handleUndoPlacement();
        handleCycleAreaMode();
        handleToggleWrench();
        handleCollectBlock();
        // 选区作业没有固定按键：框选走木棍右键，执行走面板里绑的执行键（QolKeybinds）。
    }

    @SubscribeEvent
    public static void onLocalSwordAttack(AttackEntityEvent event) {
        KunJinKaoClientSwordVisuals.onLocalAttack(event);
    }

    @SubscribeEvent
    public static void onLocalSwordRightClick(PlayerInteractEvent.RightClickItem event) {
        KunJinKaoClientSwordVisuals.onLocalRightClick(event.getEntity(), event.getHand());
    }

    /**
     * 伪装切换：J 键（可在按键设置中自定义）。
     * <p>
     * 原本绑在 K 上，K 已经让给"撤销放置"，伪装挪到相邻的 J。
     */
    private static void handleToggleDisguise() {
        if (!KunJinKaoKeyBindings.TOGGLE_DISGUISE.consumeClick()) {
            return;
        }
        Player player = getLocalPlayer();
        if (player == null) {
            return;
        }
        InteractionHand targetHand = findSwordHand(player);
        if (targetHand == null) {
            return;
        }

        ItemStack stack = player.getItemInHand(targetHand);
        KunJinKaoSwordItem.toggleDisguise(stack);
        NetworkHandler.sendToServer(new ToggleDisguisePayload(targetHand));

        boolean disguised = KunJinKaoSwordItem.isDisguised(stack);
        player.displayClientMessage(
                Component.literal(disguised ? "§7已伪装为钻石剑" : "§e已解除伪装"),
                true
        );
    }

    /**
     * 覆写流程开关切换：I 键（可在按键设置中自定义）。
     * 开启 → 无条件覆写+断未；关闭 → 瞬杀。
     */
    private static void handleToggleOverwrite() {
        if (!KunJinKaoKeyBindings.TOGGLE_OVERWRITE.consumeClick()) {
            return;
        }
        Player player = getLocalPlayer();
        if (player == null) {
            return;
        }
        InteractionHand targetHand = findSwordHand(player);
        if (targetHand == null) {
            return;
        }

        ItemStack stack = player.getItemInHand(targetHand);
        if (KunJinKaoSwordItem.isDisguised(stack)) {
            return; // 伪装时不切换覆写流程，二者互不干扰
        }

        KunJinKaoSwordItem.toggleOverwrite(stack);
        NetworkHandler.sendToServer(new ToggleOverwritePayload(targetHand));

        boolean enabled = KunJinKaoSwordItem.isOverwriteEnabled(stack);
        player.displayClientMessage(
                Component.literal(enabled ? "§a覆写流程已开启（无条件覆写+断未）" : "§c覆写流程已关闭（瞬杀）"),
                true
        );
    }

    /**
     * 异象主题循环切换：P 键（0→1→2→3→4→0），伪装时不切换。
     * 本地更新物品 NBT 用于渲染预览，并向服务端发送目标主题号。
     */
    private static void handleCycleTheme() {
        if (!KunJinKaoKeyBindings.CYCLE_THEME.consumeClick()) {
            return;
        }
        Player player = getLocalPlayer();
        if (player == null) {
            return;
        }
        InteractionHand targetHand = findSwordHand(player);
        if (targetHand == null) {
            return;
        }

        ItemStack stack = player.getItemInHand(targetHand);
        if (KunJinKaoSwordItem.isDisguised(stack)) {
            return;
        }

        int newTheme = (KunJinKaoSwordItem.getTheme(stack) + 1) % KunJinKaoTheme.COUNT;
        KunJinKaoSwordItem.setTheme(stack, newTheme);
        NetworkHandler.sendToServer(new ToggleThemePayload(targetHand, newTheme));
        player.displayClientMessage(
                Component.literal("§d异象主题：" + KunJinKaoTheme.displayName(newTheme)),
                true
        );
    }

    /**
     * 战术 HUD 开关：使用 consumeClick，按住 H 不会重复切换。
     */
    private static void handleToggleTacticalHud() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            ClientHudState.reset();
            return;
        }
        if (minecraft.screen != null && !(minecraft.screen instanceof HudScreen)
                && !(minecraft.screen instanceof HudEntityScreen)) {
            return;
        }
        if (!KunJinKaoKeyBindings.TOGGLE_TACTICAL_HUD.consumeClick()) {
            return;
        }
        // HUD 是否打开由服务端白名单决定；客户端只提交请求，不自行切换状态。
        NetworkHandler.sendToServer(new ToggleTacticalHudPayload(!ClientHudState.isEnabled()));
    }

    /** 】键打开管理员密码窗口；所有密码校验均由服务端完成。 */
    /**
     * 按住 shift 滚动滚轮：快速调节时间加速倍率。
     * <p>
     * 接管条件刻意收得很紧 —— 必须同时满足"没开任何界面 + 按着 shift + 手里是管理员剑 +
     * 时间加速不是关闭"。只要有一条不满足就完全放行，不抢正常的快捷栏切换。
     */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null || !player.isShiftKeyDown()) {
            return;
        }
        InteractionHand hand = findSwordHand(player);
        if (hand == null) {
            return;
        }
        // 主手是木棍时让给选区缩放（见下面 onZoneScrollAdjust）。
        // 两条处理器都吃 shift+滚轮，而事件被取消并不会阻止其它监听器 ——
        // 主手木棍、副手剑、且时间加速开着时，不加这道互斥就会一次滚轮同时改两样东西。
        // 按主手物品分胜负：手上拿的是木棍，意图就是调整选区。
        if (player.getMainHandItem().is(Items.STICK)) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (KunJinKaoSwordItem.getTimeAccelMode(stack) == SwordTimeAcceleration.MODE_OFF) {
            return;
        }
        int next = SwordTimeAcceleration.stepMultiplier(KunJinKaoSwordItem.getTimeAccelMultiplier(stack),
                event.getScrollDelta() > 0.0D ? 1 : -1);
        // 本地先改，滚轮手感才跟得上；服务端那份由下面的包同步。
        KunJinKaoSwordItem.setTimeAccelMultiplier(stack, next);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand,
                SwordSettingPayload.TIME_ACCEL_MULTIPLIER, next));
        player.displayClientMessage(Component.translatable("message.kunjinkao.time_accel_multiplier", next), true);
        event.setCanceled(true);
    }

    /**
     * 木棍 + Shift + 滚轮：缩放选区（127）。
     * <p>
     * <b>接管条件与上面那条时间加速一样收得很紧。</b> 必须是「没开任何界面 + 按着 shift +
     * 主手拿着木棍」，只要有一条不满足就完全放行 —— 滚轮首先是切换快捷栏的键，
     * 本模组没有理由在别的场合把它吃掉。
     * <p>
     * <b>满足条件就必须取消事件。</b> 不取消的话，这一下滚轮在把选区放大一格的同时
     * 还会去切换快捷栏，玩家会看到手里的东西跟着跳。
     * <p>
     * 这里只发一个「往上/往下滚了几格」的整数：轴向与正负由服务端按玩家朝向算
     * （见 {@code KunJinKaoQolHandler#handleZoneAdjust}），客户端说了不算。
     */
    @SubscribeEvent
    public static void onZoneScrollAdjust(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (minecraft.screen != null || player == null || !player.isShiftKeyDown()) {
            return;
        }
        if (!player.getMainHandItem().is(Items.STICK)) {
            return;
        }
        double scroll = event.getScrollDelta();
        if (scroll == 0.0D) {
            // 只有横向滚动（触控板的左右滑）时没有「上/下」可谈：放行。
            // 吃掉它等于把一次根本没打算调整选区的操作变成静默无反应。
            return;
        }
        // 一格滚轮就是 1 格。取整后再兜一个下限：触控板的惯性滚动可能一次只有 0.1，
        // 四舍五入会变成 0，那样包发出去等于没反应 —— 玩家会以为功能坏了。
        int steps = Math.min(ZoneAdjustPayload.MAX_STEPS,
                Math.max(1, (int) Math.round(Math.abs(scroll))));
        NetworkHandler.sendToServer(new ZoneAdjustPayload(scroll > 0.0D ? steps : -steps));
        event.setCanceled(true);
    }

    /**
     * B 键：请求开始一次注入。
     *
     * <p>客户端只发"请开始"，不发任何进度：进度由服务端按 tick 计时、到点自己结算。
     * 这样即使客户端改包，也拿不到按 tick 判定的那 3 秒。</p>
     *
     * <p>手的选择：主手有东西就用主手；主手空着才用副手 ——
     * 否则副手拿着盾牌/火把时会莫名其妙把副手的东西注入了。</p>
     */
    private static void handleInject() {
        if (!KunJinKaoKeyBindings.INJECT.consumeClick()) {
            return;
        }
        Player player = getLocalPlayer();
        if (player == null) {
            return;
        }
        InteractionHand hand = player.getMainHandItem().isEmpty()
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        if (player.getItemInHand(hand).isEmpty()) {
            return;
        }
        NetworkHandler.sendToServer(new dev.modmind.kunjinkao.network.InjectionStartPayload(hand));
    }

    private static void handleOpenAdminPassword() {
        Minecraft minecraft = Minecraft.getInstance();
        if (KunJinKaoKeyBindings.OPEN_ADMIN_PASSWORD.consumeClick() && minecraft.player != null
                && minecraft.screen == null) {
            minecraft.setScreen(new AdminPasswordScreen());
        }
    }

    /**
     * 句号键只在手持管理员剑且没有其他界面占用输入时打开设置界面。
     */
    private static void handleOpenSwordOptions() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!KunJinKaoKeyBindings.OPEN_SWORD_OPTIONS.consumeClick()
                || minecraft.player == null
                || minecraft.screen != null) {
            return;
        }
        if (!AdminAuthClientState.isAuthorized()) {
            deniedAdminPassword(minecraft);
            return;
        }
        InteractionHand hand = findSwordHand(minecraft.player);
        if (hand != null) {
            minecraft.setScreen(new SwordOptionsScreen(hand));
        }
    }

    /**
     * G 键：打开「功能控制台」面板。
     * <p>
     * 不要求手持剑 —— 面板管的是玩家自己的功能开关（夜视、呼吸、交易…），
     * 跟手上拿什么没关系。全量状态由面板自己在 init 里要一次。
     */
    private static void handleOpenQolPanel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!KunJinKaoKeyBindings.OPEN_QOL_PANEL.consumeClick()
                || minecraft.player == null
                || minecraft.screen != null) {
            return;
        }
        if (!AdminAuthClientState.isAuthorized()) {
            deniedAdminPassword(minecraft);
            return;
        }
        minecraft.setScreen(new QolControlScreen());
    }

    /**
     * 控制台被权限挡下时的统一提示。
     *
     * <p>只提示、不弹密码框：密码面板的入口固定在 {@code ]}，
     * 在这里自动弹会让"按错键"变成"被塞了一个输入框"，反而更烦。</p>
     */
    public static void deniedAdminPassword(Minecraft minecraft) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.kunjinkao.console_locked"),
                    true);
        }
    }

    /**
     * 断开连接时清掉面板的客户端镜像。
     * <p>
     * 不清的话，退出去再进另一个服务器，面板会先画上一局的旧状态，
     * 直到新的全量同步到达 —— 那一瞬间看起来就是"我明明没开夜视，它显示开着"。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        QolClientState.clear();
    }

    /**
     * 快速开关扳手模式：L 键。
     * <p>
     * 扳手状态平时在游戏里没有任何显示，所以切换后必须给一句提示，
     * 否则玩家不知道 shift+右键 现在是用于加速还是让给扳手。
     */
    /**
     * R 键：把正看着的方块直接收进物品栏。
     * <p>
     * 这里刻意不做客户端射线检测、也不带坐标 —— 只发一个"我按了 R"的空包，
     * 由服务端用自己的射线重新确定目标，客户端无法指定任意坐标。
     */
    private static void handleCollectBlock() {
        if (!KunJinKaoKeyBindings.COLLECT_BLOCK.consumeClick()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        if (findSwordHand(player) == null) {
            return;
        }
        NetworkHandler.sendToServer(new dev.modmind.kunjinkao.network.CollectBlockPayload());
    }

    /**
     * 选区作业（127）：N 键确认执行；按住 Shift 再按是取消。
     * <p>
     * 二次确认、权限、服务器总闸全在服务端（见 {@code KunJinKaoQolHandler} 里的 handleZoneXxx），
     * 客户端只把按键翻译成一条动作包，从不自己改世界 —— 所以这里连"选区够不够大"都不判，
     * 判了也只是把服务端的话提前说一遍，还会两边不一致。
     * <p>
     * 框选（设角点）不在这里：它改成手持木棍右键方块，由服务端的
     * {@code ZoneSelectionHandler} 接住，客户端一个包都不用发。
     */
    private static void handleCycleAreaMode() {
        if (!KunJinKaoKeyBindings.CYCLE_AREA_MODE.consumeClick()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        InteractionHand hand = findSwordHand(player);
        if (hand == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        int mode = KunJinKaoSwordItem.nextAreaMode(KunJinKaoSwordItem.getAreaMode(stack));
        // 本地先改，按键反馈才跟得上；服务端那份由下面的包同步。
        KunJinKaoSwordItem.setAreaMode(stack, mode);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.AREA_MODE, mode));
        player.displayClientMessage(Component.translatable(switch (mode) {
            case 1 -> "message.kunjinkao.area_mode_pause";
            case 2 -> "message.kunjinkao.area_mode_accel";
            default -> "message.kunjinkao.area_mode_off";
        }), true);
    }

    private static void handleToggleWrench() {
        if (!KunJinKaoKeyBindings.TOGGLE_WRENCH.consumeClick()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        InteractionHand hand = findSwordHand(player);
        if (hand == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        boolean enabled = !KunJinKaoSwordItem.isWrenchEnabled(stack);
        // 本地先改，按键反馈才跟得上；服务端那份由下面的包同步。
        KunJinKaoSwordItem.setWrenchEnabled(stack, enabled);
        NetworkHandler.sendToServer(new SwordSettingPayload(hand, SwordSettingPayload.WRENCH,
                enabled ? 1 : 0));
        player.displayClientMessage(Component.translatable(enabled
                ? "message.kunjinkao.wrench_enabled" : "message.kunjinkao.wrench_disabled"), true);
    }

    /**
     * 撤销最近一步放置/破坏：K 键。
     * <p>
     * 是否真的执行由服务端判定 —— 只有手里的剑开着"撤销"才生效，
     * 这里只负责把按键转成一条动作包，避免在客户端改动世界。
     */
    private static void handleUndoPlacement() {
        if (KunJinKaoKeyBindings.UNDO_PLACEMENT.consumeClick()
                && Minecraft.getInstance().player != null
                && Minecraft.getInstance().screen == null) {
            NetworkHandler.sendToServer(new SimpleActionPayload(SimpleActionPayload.UNDO_PLACEMENT));
        }
    }

    @Nullable
    private static Player getLocalPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player;
    }

    /**
     * 查找手持锟斤拷之剑的手（主手优先，副手其次）。
     */
    @Nullable
    private static InteractionHand findSwordHand(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (KunJinKaoSwordItem.isSwordLike(player.getItemInHand(hand))) {
                return hand;
            }
        }
        return null;
    }
}
