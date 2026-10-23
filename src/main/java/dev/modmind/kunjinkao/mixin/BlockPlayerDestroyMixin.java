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
 * Ore drop multiplier, injected at Block#playerDestroy (1.20.1).
 *
 * WHY A MIXIN: on 1.20.1 there is no BlockDropsEvent, and the BlockEvent.BreakEvent
 * path proved unreliable here (fires before the block is removed, while
 * MinecraftServer#execute runs inline). playerDestroy is called exactly once per
 * destroyed block, after the harvest checks, with the old state still in hand -
 * so the drops can simply be multiplied there, with no timing games.
 */
@Mixin(Block.class)
public class BlockPlayerDestroyMixin {

    @Inject(method = "playerDestroy", at = @At("RETURN"))
    private void kunjinkao$multiplyOreDrops(Player player, Level level, BlockPos pos, BlockState state,
                                             BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!KunJinKaoSwordItem.isSwordLike(tool)) {
            return;
        }
        int multiplier = KunJinKaoSwordItem.getOreDropMultiplier(tool);
        if (multiplier <= 1) {
            return;
        }
        if (!KunJinKaoOreDropHandler.isOreBlock(state)) {
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