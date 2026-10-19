package dev.modmind.kunjinkao.block;

import dev.modmind.kunjinkao.world.MainWorldGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 主世界之门方块。
 * <p>
 * 右键传送进本模组自带的维度 {@code kunjinkao:main_world}；
 * 在该维度中右键同一个方块，则传送回原版主世界。
 * <p>
 * 1.20.1 差异：没有 {@code useWithoutItem}，右键统一走
 * {@link #use(BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)}。
 */
public class WorldGateBlock extends Block {

    public WorldGateBlock() {
        super(Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(3.0F)
                .sound(SoundType.GLASS)
                .lightLevel(state -> 11));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MainWorldGate.use(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}