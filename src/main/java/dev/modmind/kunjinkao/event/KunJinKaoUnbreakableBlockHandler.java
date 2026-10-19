package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side removal of blocks whose destroy speed is negative, such as bedrock.
 * The normal Forge break hook is called first so protection handlers can veto it.
 */
public final class KunJinKaoUnbreakableBlockHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof KunJinKaoSwordItem)
                || !KunJinKaoSwordItem.canBreakUnbreakableBlocks(stack)) {
            return;
        }

        BlockState state = level.getBlockState(event.getPos());
        if (state.isAir() || state.getDestroySpeed(level, event.getPos()) >= 0.0F) {
            return;
        }

        // 放行条件：本模组的管理员（密码授权）或原版游戏管理员方块权限。
        // 只认 canUseGameMasterBlocks() 会把"生存模式 OP"和"创造但未开作弊的单机玩家"一起挡掉，
        // 与本模组"密码即权限"的模型自相矛盾（审计指出）；但也不能让普通持剑者绕过原版对
        // 命令方块/屏障的保护，所以两条满足其一即可。
        // 不满足时给一句提示并放行原版逻辑：fireBlockBreak 的探针会让别的保护插件
        // 看到一次并未真正发生的破坏。
        if (!player.canUseGameMasterBlocks() && !AdminToolConfig.isAuthorized(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.admin_required"), true);
            return;
        }

        BlockPos pos = event.getPos().immutable();
        // A negative result means an existing Forge protection or mod handler denied the break.
        if (ForgeHooks.onBlockBreakEvent(level, player.gameMode.getGameModeForPlayer(), player, pos) == -1) {
            event.setCanceled(true);
            return;
        }

        event.setCanceled(true);

        // 掉落必须在破坏之前算：方块一没，方块实体与战利品表的判定上下文就跟着没了。
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, pos, blockEntity, player, stack));

        // 基岩、屏障、命令方块等不可破坏方块的原版战利品表为空；此时回退为方块自身。
        // 若存在方块实体，则在移除前把其数据写进物品 NBT。
        if (drops.isEmpty() && state.getBlock().asItem() != Items.AIR) {
            ItemStack fallback = new ItemStack(state.getBlock().asItem());
            if (blockEntity != null) {
                blockEntity.saveToItem(fallback);
            }
            drops.add(fallback);
        }

        // 挖掘熔炼：这条路径自己算掉落、自己 popResource，绕开了 BlockDropsEvent，
        // 所以要在这里补一次，否则"挖基岩/屏障"和"挖普通方块"的表现对不上。
        drops = KunJinKaoAutoSmeltHandler.smeltList(level, stack, drops);

        state.getBlock().playerWillDestroy(level, pos, state, player);

        // 刻意不用 Level.destroyBlock：它的返回值就是 setBlock 的结果，
        // 而 setBlock 在"新旧状态相同"等情况下返回 false —— 那样下面这个 return
        // 会把上面辛苦收集的掉落整个丢掉，表现正是"方块破坏了但什么都不掉"。
        // 这里按 destroyBlock 的等价步骤自己置空，结果不再受 setBlock 返回值影响。
        level.levelEvent(2001, pos, Block.getId(state));
        level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(), 3);
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, state));

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                Block.popResource(level, pos, drop.copy());
            }
        }
    }
}
