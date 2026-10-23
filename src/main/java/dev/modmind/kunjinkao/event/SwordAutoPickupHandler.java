package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 两件与"挖到的方块去哪"有关的事：
 * <ol>
 *   <li><b>R 键收集</b>：开着"可破坏不可破坏方块"时，对着方块按 R 直接把方块收进物品栏
 *       （带着方块实体数据），而不是掉在地上。服务端自己射线检测，客户端无法指定坐标。</li>
 *   <li><b>自动入包</b>：用剑挖到的任何方块，掉落算完之后立刻进背包，不在世界里弹一地。</li>
 * </ol>
 */
public final class SwordAutoPickupHandler {

    /** R 键收集的交互距离；与属性里的方块交互距离一致，回退到 4.5。 */
    private static final double DEFAULT_REACH = 4.5D;


    /** R 键：把正看着的方块直接收进物品栏。 */
    public static void collectTargetedBlock(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack sword = player.getMainHandItem();
        if (!(KunJinKaoSwordItem.isSwordLike(sword))
                || !KunJinKaoSwordItem.canBreakUnbreakableBlocks(sword)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.kunjinkao.collect_requires_unbreakable"), true);
            return;
        }
        double reach = serverPlayer.getAttributeValue(
                net.minecraftforge.common.ForgeMod.BLOCK_REACH.get());
        if (!(reach > 0.0D)) {
            reach = DEFAULT_REACH;
        }
        HitResult hit = serverPlayer.pick(reach, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < -1.0F) {
            // 空气，或硬到原版根本不允许破坏（-1 是基岩/屏障，-0.9 之类仍可收）
            return;
        }
        collectIntoInventory(serverPlayer, level, pos, state);
    }

    /** 取掉落（含方块实体数据），塞进背包，然后清掉方块。 */
    /**
    /**
     * <b>直接取走这个方块本身</b>，而不是"破坏它、再收走掉落物"。
     * <p>
     * 区别是实质性的：走战利品表的话，石头会变成圆石、矿石会变成原矿、
     * 草方块会变成泥土 —— 那都是"掉落物"，不是"这个方块"。
     * 这里改用原版"中键取方块"的那套 {@code getCloneItemStack}：
     * 它给出这个方块自己的物品，并带上方块实体数据与相关组件。
     * <p>
     * 取完直接把方块抹掉（不掉落任何东西），因为东西已经在包里了。
     */
    private static void collectIntoInventory(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        // 与中键取方块同源：命中结果只用于让方块决定自己的物品形态。
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        ItemStack picked = state.getBlock().getCloneItemStack(state, hit, level, pos, player);
        if (picked.isEmpty()) {
            // 极少数方块没有对应物品（例如纯技术方块），退回方块自身的物品。
            picked = new ItemStack(state.getBlock().asItem());
        }
        if (picked.isEmpty()) {
            return;
        }
        // 方块实体数据写进这一件物品（saveToItem 在这里是正解：
        // 我们要的就是"把这个方块取下来"，而不是让它走一遍掉落流程）。
        if (blockEntity != null) {
            blockEntity.saveToItem(picked);
        }

        // 抹掉方块：不掉落任何东西，因为东西已经进包了。
        state.getBlock().playerWillDestroy(level, pos, state, player);
        level.levelEvent(2001, pos, Block.getId(state));
        level.removeBlock(pos, false);
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, state));

        giveOrDrop(player, java.util.List.of(picked));
    }

    /**
     * 用剑挖到的方块：掉落算完就进背包。
     * <p>
     * 在 {@link BlockDropsEvent} 里把已经生成好的掉落实体收走 ——
     * 它们已经带着完整的组件数据（战利品表、copy_components 等都已生效），
     * 直接取 {@code getItem()} 即可，不需要自己重算一遍掉落。
     */
    @SubscribeEvent
    public void onBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        // 【1.20.1 重写】NeoForge 的 BlockDropsEvent（能拿到已生成的掉落实体）在 1.20.1 不存在，
        // Forge 1.20.1 的 BlockEvent 只有 BreakEvent 一类。这里改成"自己接管这一刀"：
        // 破坏前按战利品表算好掉落，取消原版破坏，再以不掉落的方式移除方块，最后直接塞背包。
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack tool = player.getMainHandItem();
        if (!(KunJinKaoSwordItem.isSwordLike(tool))) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, pos, blockEntity, player, tool));
        if (drops.isEmpty()) {
            return;
        }
        // 矿石挖掘倍率必须在这里生效：本方法取消了原版破坏并用 destroyBlock(pos, false, …)
        // 无掉落移除方块，原版 Block#playerDestroy 根本不会被调用，
        // 所以挂在 playerDestroy 上的混入对"用剑挖矿"永远是死代码。
        int multiplier = KunJinKaoSwordItem.getOreDropMultiplier(tool);
        if (multiplier > 1 && KunJinKaoOreDropHandler.isOreBlock(state)) {
            List<ItemStack> expanded = new ArrayList<>(drops.size() * multiplier);
            for (ItemStack drop : drops) {
                for (int i = 0; i < multiplier; i++) {
                    expanded.add(drop.copy());
                }
            }
            drops = expanded;
        }
        event.setCanceled(true);
        level.destroyBlock(pos, false, player);
        giveOrDrop(player, drops);
    }

    /** 先进背包，装不下的部分照原版做法丢在脚下。 */
    private static void giveOrDrop(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }
}
