package dev.modmind.my_mod.block;

import dev.modmind.my_mod.AcceleratorRegistry;
import dev.modmind.my_mod.block.entity.AcceleratorBlockEntity;
import dev.modmind.my_mod.client.gui.AcceleratorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 时间加速器方块。
 * 右键打开配置 GUI（加速倍率 + 加速范围），并在服务端持续加速周围方块。
 */
public class AcceleratorBlock extends Block implements EntityBlock {

    public AcceleratorBlock() {
        super(Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(3.0F)
                .sound(SoundType.METAL)
                .lightLevel(state -> 7));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof AcceleratorBlockEntity accelerator) {
                Minecraft.getInstance().setScreen(new AcceleratorScreen(accelerator));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AcceleratorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == AcceleratorRegistry.ACCELERATOR_BE.get()
                ? (lvl, pos, st, be) -> AcceleratorBlockEntity.serverTick(lvl, pos, st, (AcceleratorBlockEntity) be)
                : null;
    }
}