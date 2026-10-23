package dev.modmind.kunjinkao.mixin;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.event.KunJinKaoOreDropHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 矿石挖掘倍率 —— <b>兜底路径</b>，不是主路径（1.20.1）。
 *
 * <p>主路径在 {@code SwordAutoPickupHandler#onBlockBreak}：它接住 {@code BlockEvent.BreakEvent}
 * 后取消原版破坏、自己算掉落、再用 {@code destroyBlock(pos, false, …)} 无掉落移除方块，
 * 于是原版 {@code Block#playerDestroy} 根本不会被调用 —— 倍率必须在那里生效。</p>
 *
 * <p>那么为什么还留这个混入？因为上面那条路只在"掉落非空"时才接管（掉落为空会直接返回、
 * 让原版继续走）。凡是没被取消、最终走原版 {@code destroyBlock(pos, true, …)} 的破坏
 * （例如某些模组方块、或将来新增的批量破坏路径），都会落到这里补上倍率。
 * 两条路径互斥：事件一旦被取消，{@code playerDestroy} 就不会执行，不会重复加倍。</p>
 */
@Mixin(Block.class)
public class BlockPlayerDestroyMixin {

    @Inject(method = "playerDestroy", at = @At("RETURN"))
    private void kunjinkao$multiplyOreDrops(Level level, Player player, BlockPos pos, BlockState state,
                                             BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!KunJinKaoSwordItem.isSwordLike(tool)) {
            return;
        }
        int multiplier = KunJinKaoSwordItem.getOreDropMultiplier(tool);
        if (multiplier <= 1 || !KunJinKaoOreDropHandler.isOreBlock(state)) {
            return;
        }
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, player, tool);
        for (ItemStack drop : drops) {
            for (int i = 1; i < multiplier; i++) {
                Block.popResource(serverLevel, pos, drop.copy());
            }
        }
    }
}
