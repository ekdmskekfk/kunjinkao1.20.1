package dev.modmind.my_mod.block.entity;

import dev.modmind.my_mod.AcceleratorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 时间加速器方块实体。
 * <p>
 * 每个游戏刻按设定倍率对周围范围内的方块进行"加速"：
 * <ul>
 *   <li>有方块实体（熔炉、漏斗、信标等机器）的方块，额外调用其原版 ticker 若干次；</li>
 *   <li>没有方块实体但会随机刻（作物、树苗、草方块等）的方块，额外调用其 randomTick 若干次。</li>
 * </ul>
 * 倍率与范围通过右键打开的 GUI 调节，并持久化到 NBT。
 */
public class AcceleratorBlockEntity extends BlockEntity {

    /** 可选加速倍率：4 / 8 / 16 / 32 / 64 / 128 / 256 / 512 / 1024 倍。 */
    public static final int[] MULTIPLIERS = {4, 8, 16, 32, 64, 128, 256, 512, 1024};

    /** 可选范围（半径的一半）：1 → 3x3x3，2 → 5x5x5，3 → 7x7x7，4 → 9x9x9。 */
    public static final int[] RADII = {1, 2, 3, 4};

    private static final String TAG_MULTIPLIER = "Multiplier";
    private static final String TAG_RADIUS = "Radius";
    private static final String TAG_SHOW_RANGE = "ShowRange";

    private int multiplier = MULTIPLIERS[0];
    private int radius = RADII[0];
    private boolean showRange = false;

    public AcceleratorBlockEntity(BlockPos pos, BlockState state) {
        super(AcceleratorRegistry.ACCELERATOR_BE.get(), pos, state);
    }

    /** 由方块 {@code getTicker} 注册的服务端 ticker。 */
    public static void serverTick(Level level, BlockPos pos, BlockState state, AcceleratorBlockEntity self) {
        if (level.isClientSide) {
            return;
        }
        self.accelerateSurroundings(level, pos);
    }

    private void accelerateSurroundings(Level level, BlockPos pos) {
        int half = this.radius;
        int extra = this.multiplier - 1;
        if (extra <= 0) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        RandomSource random = serverLevel.random;

        for (int dx = -half; dx <= half; dx++) {
            for (int dy = -half; dy <= half; dy++) {
                for (int dz = -half; dz <= half; dz++) {
                    BlockPos target = pos.offset(dx, dy, dz);
                    if (target.equals(pos)) {
                        continue;
                    }
                    BlockState targetState = level.getBlockState(target);
                    if (targetState.isAir()) {
                        continue;
                    }
                    BlockEntity targetEntity = level.getBlockEntity(target);
                    if (targetEntity != null) {
                        tickBlockEntityExtra(level, target, targetState, targetEntity, extra);
                    } else if (targetState.isRandomlyTicking()) {
                        for (int i = 0; i < extra; i++) {
                            targetState.randomTick(serverLevel, target, random);
                        }
                    }
                }
            }
        }
    }

    /** 额外调用目标方块实体的原版 ticker（使用原始类型规避泛型捕获问题）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void tickBlockEntityExtra(Level level, BlockPos pos, BlockState state, BlockEntity entity, int extra) {
        if (entity instanceof AcceleratorBlockEntity) {
            return;
        }
        Block block = state.getBlock();
        if (!(block instanceof EntityBlock entityBlock)) {
            return;
        }
        BlockEntityTicker ticker = entityBlock.getTicker(level, state, entity.getType());
        if (ticker == null) {
            return;
        }
        for (int i = 0; i < extra; i++) {
            ticker.tick(level, pos, state, entity);
        }
    }

    public int getMultiplier() {
        return this.multiplier;
    }

    public void setMultiplier(int multiplier) {
        this.multiplier = clampMultiplier(multiplier);
    }

    /** 返回范围半径的一半（1..4），对应 3x3x3 ~ 9x9x9。 */
    public int getRadius() {
        return this.radius;
    }

    public void setRadius(int radius) {
        this.radius = clampRadius(radius);
    }

    /** 是否显示蓝色半透明加速范围框。 */
    public boolean shouldShowRange() {
        return this.showRange;
    }

    public void setShowRange(boolean showRange) {
        this.showRange = showRange;
    }

    public static int clampMultiplier(int value) {
        for (int candidate : MULTIPLIERS) {
            if (candidate == value) {
                return value;
            }
        }
        return MULTIPLIERS[0];
    }

    public static int clampRadius(int value) {
        for (int candidate : RADII) {
            if (candidate == value) {
                return value;
            }
        }
        return RADII[0];
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(TAG_MULTIPLIER, this.multiplier);
        tag.putInt(TAG_RADIUS, this.radius);
        tag.putBoolean(TAG_SHOW_RANGE, this.showRange);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.multiplier = clampMultiplier(tag.getInt(TAG_MULTIPLIER));
        this.radius = clampRadius(tag.getInt(TAG_RADIUS));
        this.showRange = tag.getBoolean(TAG_SHOW_RANGE);
    }
}