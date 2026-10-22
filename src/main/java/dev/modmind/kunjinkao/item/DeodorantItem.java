package dev.modmind.kunjinkao.item;

import dev.modmind.kunjinkao.entity.KunJinKaoEffects;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 除臭剂（1.20.1）：右键使用解除自己身上的「骚臭」。
 *
 * <p>只解自己、不解别人：骚臭会让周围生物逃跑，做成"帮别人擦"就变成 PvP 工具了。</p>
 */
public class DeodorantItem extends Item {

    public DeodorantItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!player.hasEffect(KunJinKaoEffects.STENCH.get())) {
            player.displayClientMessage(
                    Component.translatable("message.kunjinkao.deodorant_not_stinky"), true);
            return InteractionResultHolder.pass(stack);
        }
        player.removeEffect(KunJinKaoEffects.STENCH.get());
        player.displayClientMessage(
                Component.translatable("message.kunjinkao.deodorant_used"), true);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
