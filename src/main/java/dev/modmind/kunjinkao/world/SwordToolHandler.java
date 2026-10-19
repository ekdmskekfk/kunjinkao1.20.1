package dev.modmind.kunjinkao.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;

/**
 * 剑的工具类行为：刷子 / 锄头 / 铲子 / 右键避雷针召雷。
 * <p>
 * 前三条都刻意复用<b>原版入口</b>，而不是自己写方块替换：
 * <ul>
 *   <li><b>刷子</b>：推进交给 {@link BrushableBlockEntity#brush}，与刷子走同一条路径，
 *       所以可疑的沙/砾石的进度贴图、完成音效与掉落表全都一致；</li>
 *   <li><b>锄头 / 铲子</b>：走 {@code BlockState.getToolModifiedState(context, ItemAbility, false)}，
 *       泥土→耕地、草方块→草径这些规则由方块自己决定，连别的模组注册的同族规则也能生效，
 *       不需要在本模组里硬编码任何方块表；</li>
 *   <li><b>避雷针</b>：原版没有"右键召雷"这个行为，属于本模组自加，闪电落在避雷针顶点。</li>
 * </ul>
 */
public final class SwordToolHandler {

    /** 工具模式：关。 */
    public static final int TOOL_MODE_OFF = 0;
    /** 工具模式：锄头（右键草/土 → 耕地）。 */
    public static final int TOOL_MODE_HOE = 1;
    /** 工具模式：铲子（右键草/土 → 草径）。 */
    public static final int TOOL_MODE_SHOVEL = 2;
    /** 工具模式的取值个数，用于循环切换。 */
    public static final int TOOL_MODE_COUNT = 3;

    /** 与原版刷子一致：一次右键最长按住 200 tick。 */
    public static final int BRUSH_USE_DURATION = 200;
    /** 与原版一致：每 10 tick 推进一次刷取进度。 */
    private static final int BRUSH_INTERVAL = 10;

    private SwordToolHandler() {
    }

    public static int clampToolMode(int mode) {
        return mode < 0 || mode >= TOOL_MODE_COUNT ? TOOL_MODE_OFF : mode;
    }

    // ===================== 锄头 / 铲子 =====================

    /**
     * 按锄头或铲子处理一次右键，逻辑对齐原版 {@code HoeItem} / {@code ShovelItem}。
     *
     * @return 处理结果；该方块没有对应规则时返回 {@link InteractionResult#PASS}
     */
    public static InteractionResult applyTillOrFlatten(UseOnContext context, int mode) {
        if (mode != TOOL_MODE_HOE && mode != TOOL_MODE_SHOVEL) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        BlockState state = level.getBlockState(pos);

        if (mode == TOOL_MODE_SHOVEL) {
            // 原版铲子：不能从方块底面点，且上方必须是空气才铲得出草径。
            if (context.getClickedFace() == Direction.DOWN) {
                return InteractionResult.PASS;
            }
            BlockState flattened = state.getToolModifiedState(context, ToolActions.SHOVEL_FLATTEN, false);
            if (flattened == null || !level.getBlockState(pos.above()).isAir()) {
                return InteractionResult.PASS;
            }
            level.playSound(player, pos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (!level.isClientSide()) {
                level.setBlock(pos, flattened, 11);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // 原版锄头：草方块/泥土 → 耕地。
        BlockState tilled = state.getToolModifiedState(context, ToolActions.HOE_TILL, false);
        if (tilled == null) {
            return InteractionResult.PASS;
        }
        level.playSound(player, pos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (!level.isClientSide()) {
            level.setBlock(pos, tilled, 11);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    // ===================== 刷子 =====================

    /** 这个位置是不是可刷的方块（可疑的沙 / 可疑的砾石）。 */
    public static boolean isBrushable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BrushableBlock;
    }

    /**
     * 复刻 {@code BrushItem.onUseTick} 的推进节奏：每 {@link #BRUSH_INTERVAL} tick 推一次进度，
     * 真的刷完一次就消耗一点耐久（这里对齐原版行为；耐久消耗由调用方决定是否施加）。
     *
     * @return 这一次是否真的完成了一次刷取
     */
    public static boolean tickBrush(Level level, Player player, ItemStack stack, int remainingUseDuration) {
        if (remainingUseDuration < 0) {
            player.releaseUsingItem();
            return false;
        }
        // 与原版一样用视线取目标，而不是用右键那一刻的旧结果，
        // 这样中途移开视线会立刻中断（原版的"松手"就靠这一条）。
        if (!(player.pick(player.getAttributeValue(net.minecraftforge.common.ForgeMod.BLOCK_REACH.get()), 0.0F, false) instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            player.releaseUsingItem();
            return false;
        }
        int elapsed = BRUSH_USE_DURATION - remainingUseDuration + 1;
        if (elapsed % BRUSH_INTERVAL != 5) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        SoundEvent sound = state.getBlock() instanceof BrushableBlock brushable
                ? brushable.getBrushSound()
                : SoundEvents.BRUSH_GENERIC;
        level.playSound(player, pos, sound, SoundSource.BLOCKS);
        spawnDust(level, hit, state);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BrushableBlockEntity entity) {
            return entity.brush(level.getGameTime(), player, hit.getDirection());
        }
        return false;
    }

    /** 简化版扫尘：原版那套沿手臂方向的偏移不影响观感，这里只在命中面附近喷几粒方块碎屑。 */
    private static void spawnDust(Level level, BlockHitResult hit, BlockState state) {
        if (!state.shouldSpawnParticlesOnBreak()) {
            return;
        }
        Vec3 location = hit.getLocation();
        BlockParticleOption option = new BlockParticleOption(ParticleTypes.BLOCK, state);
        for (int i = 0; i < 6; i++) {
            level.addParticle(option,
                    location.x, location.y, location.z,
                    (level.getRandom().nextDouble() - 0.5D) * 0.3D,
                    level.getRandom().nextDouble() * 0.2D,
                    (level.getRandom().nextDouble() - 0.5D) * 0.3D);
        }
    }

    // ===================== 避雷针 =====================

    /**
     * 右键避雷针召唤一道闪电，落在避雷针顶端。
     * <p>
     * 原版没有这个交互，是本模组自加的；闪电按普通落雷处理（会伤害、会点燃），
     * 并把来源记在玩家名下，这样击杀统计能算到玩家头上。
     *
     * @return 是否命中了避雷针（命中即视为处理过这次右键）
     */
    public static boolean strikeLightningRod(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof LightningRodBlock)) {
            return false;
        }
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            // 客户端只负责吃掉这次右键，真正的落雷由服务端做，避免两边各召一道。
            return true;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(serverLevel);
        if (bolt == null) {
            return false;
        }
        bolt.moveTo(Vec3.atBottomCenterOf(pos.above()));
        if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
            bolt.setCause(serverPlayer);
        }
        // 按菜单里选的形态塑造这道雷。
        int mode = dev.modmind.kunjinkao.KunJinKaoSwordItem.getLightningMode(context.getItemInHand());
        if (mode == dev.modmind.kunjinkao.KunJinKaoSwordItem.LIGHTNING_VISUAL_ONLY) {
            bolt.setVisualOnly(true);
        } else if (mode == dev.modmind.kunjinkao.KunJinKaoSwordItem.LIGHTNING_NATURAL) {
            // 闪电科技（ae2lt）靠这个 NBT 键判定"自然闪电"，
            // 取自它自己的 DebugLightningRodItem 与 ServerLevelMixin。
            // 不打这个键，它只认人工闪电，相关配方与收集器都不会响应。
            bolt.getPersistentData().putBoolean("ae2lt.natural_weather_lightning", true);
        }
        serverLevel.addFreshEntity(bolt);
        return true;
    }
}