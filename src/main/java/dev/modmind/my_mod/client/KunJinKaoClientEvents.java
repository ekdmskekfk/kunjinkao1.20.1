package dev.modmind.my_mod.client;

import dev.modmind.my_mod.KunJinKaoSwordItem;
import dev.modmind.my_mod.KunJinKaoTheme;
import dev.modmind.my_mod.ModMindEntry;
import dev.modmind.my_mod.network.NetworkHandler;
import dev.modmind.my_mod.network.ToggleDisguiseMessage;
import dev.modmind.my_mod.network.ToggleOverwriteMessage;
import dev.modmind.my_mod.network.ToggleThemeMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = ModMindEntry.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KunJinKaoClientEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        KunJinKaoClientOverwriteEffects.tick();
        for (int entityId : KunJinKaoClientOverwriteEffects.getActiveEntityIds()) {
            KunJinKaoClientOverwriteEffects.tickSounds(entityId);
        }
        handleToggleDisguise();
        handleToggleOverwrite();
        handleCycleTheme();
    }

    /**
     * 伪装切换：K 键（可在按键设置中自定义）。
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
        NetworkHandler.CHANNEL.sendToServer(new ToggleDisguiseMessage(targetHand));

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
        NetworkHandler.CHANNEL.sendToServer(new ToggleOverwriteMessage(targetHand));

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
        NetworkHandler.CHANNEL.sendToServer(new ToggleThemeMessage(targetHand, newTheme));
        player.displayClientMessage(
                Component.literal("§d异象主题：" + KunJinKaoTheme.displayName(newTheme)),
                true
        );
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
            if (player.getItemInHand(hand).getItem() instanceof KunJinKaoSwordItem) {
                return hand;
            }
        }
        return null;
    }
}
