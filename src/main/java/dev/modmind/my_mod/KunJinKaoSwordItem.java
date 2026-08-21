package dev.modmind.my_mod;

import java.util.List;

import dev.modmind.my_mod.entity.DiamondProjectile;
import dev.modmind.my_mod.event.KunJinKaoDeathEventHandler;
import dev.modmind.my_mod.event.KunJinKaoProtectionHandler;
import dev.modmind.my_mod.overwrite.KunJinKaoOverwriteHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class KunJinKaoSwordItem extends SwordItem {

    private static final String LOOTING_MODE_KEY = "LootingMode";
    private static final String DISGUISE_KEY = "CustomModelData";
    private static final String OVERWRITE_KEY = "OverwriteEnabled";
    private static final String THEME_KEY = "OverwriteTheme";

    public KunJinKaoSwordItem(Tier tier, int attackDamageModifier, float attackSpeedModifier, Properties properties) {
        super(tier, attackDamageModifier, attackSpeedModifier, properties);
    }

    // ===== 伪装状态（NBT 标记，CustomModelData 驱动模型 override） =====

    public static boolean isDisguised(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getInt(DISGUISE_KEY) == 1;
    }

    public static void setDisguised(ItemStack stack, boolean disguised) {
        stack.getOrCreateTag().putInt(DISGUISE_KEY, disguised ? 1 : 0);
    }

    public static void toggleDisguise(ItemStack stack) {
        setDisguised(stack, !isDisguised(stack));
    }

    // ===== 覆写流程开关（OverwriteEnabled，默认关闭） =====
    // 开启：无条件触发覆写+断未（不检查泥土）
    // 关闭（默认）：左键攻击 = 指令杀（瞬杀 target.kill()，无覆写特效、无断未）

    public static boolean isOverwriteEnabled(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean(OVERWRITE_KEY);
    }

    public static void setOverwriteEnabled(ItemStack stack, boolean enabled) {
        stack.getOrCreateTag().putBoolean(OVERWRITE_KEY, enabled);
    }

    public static void toggleOverwrite(ItemStack stack) {
        setOverwriteEnabled(stack, !isOverwriteEnabled(stack));
    }

    // ===== 异象主题（OverwriteTheme，0..4，P 键循环切换） =====

    public static int getTheme(ItemStack stack) {
        int theme = stack.hasTag() ? stack.getTag().getInt(THEME_KEY) : 0;
        return Math.floorMod(theme, KunJinKaoTheme.COUNT);
    }

    public static void setTheme(ItemStack stack, int theme) {
        stack.getOrCreateTag().putInt(THEME_KEY, Math.floorMod(theme, KunJinKaoTheme.COUNT));
    }

    public static void cycleTheme(ItemStack stack) {
        setTheme(stack, getTheme(stack) + 1);
    }

    @Override
    public Component getName(ItemStack stack) {
        if (isDisguised(stack)) {
            return Component.translatable("item.minecraft.diamond_sword");
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if (isDisguised(stack)) {
            // 伪装时显示原版钻石剑属性行
            super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);
            return;
        }
        // 不调用 super.appendHoverText，屏蔽原版攻击伤害/攻击速度行
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("item.modifiers.mainhand").withStyle(ChatFormatting.GRAY));

        // 第 1 行：∞ 攻击伤害（∞ 保持独立子组件，供颜色流动处理器单独识别染色）
        MutableComponent damageLine = Component.literal("");
        damageLine.append(Component.literal("∞"));
        damageLine.append(Component.literal(" 攻击伤害").withStyle(ChatFormatting.DARK_GREEN));
        tooltipComponents.add(damageLine);

        // 第 2 行：-2.4 攻击速度
        MutableComponent speedLine = Component.literal("-2.4").withStyle(ChatFormatting.DARK_GREEN);
        speedLine.append(Component.literal(" 攻击速度").withStyle(ChatFormatting.DARK_GREEN));
        tooltipComponents.add(speedLine);

        // 背景故事（完整版灰色铭文；伪装状态在上方提前 return，因此不会显示）
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.literal("上古代码洪流中遗落的碎片所铸，").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("剑身无锋，却刻满流动的乱码铭文。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("此剑同时承载两种互斥的法则：").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.literal("覆写 —— 强制修改对手在“世界系统”中的底层属性。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("防御、速度、抗性、乃至“存在”本身，").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("在剑锋触及的瞬间，全部被覆盖成剑主定义的数值。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.literal("断未 —— 追加一击，不伤实体，只清除目标的“定义”。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("你不是被削弱、被封印、被击败，").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("你只是变成系统无法识别的“未定义项”，").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("系统会因无法处理你而自行将你忽略、遗忘、清零。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.literal("敌人的苦修、装备、Buff，在覆写面前").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("只是一行可被 Ctrl+C 覆盖的文本；").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("敌人引以为傲的底牌，在断未之后连“被记住”的资格都被剥夺。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("对手不是在对抗一个剑客，").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("而是在对抗一个手握“编辑世界源代码权限”的疯子。").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.literal("覆写流程：").withStyle(ChatFormatting.DARK_GRAY)
                .append(isOverwriteEnabled(stack)
                        ? Component.literal("开启（无条件覆写+断未）").withStyle(ChatFormatting.DARK_GREEN)
                        : Component.literal("关闭（瞬杀）").withStyle(ChatFormatting.DARK_RED)));
        tooltipComponents.add(Component.literal("异象主题：").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(KunJinKaoTheme.displayName(getTheme(stack))).withStyle(ChatFormatting.LIGHT_PURPLE)));
        tooltipComponents.add(Component.literal("按键 P 循环切换主题").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (isDisguised(player.getItemInHand(hand))) {
            return super.use(level, player, hand);
        }
        if (player.isShiftKeyDown()) {
            return cycleMode(level, player, hand);
        }
        // 普通右键：服务端发射钻石抛射物（命中后立即处决并伴随落雷+随机火焰）
        if (!level.isClientSide()) {
            DiamondProjectile projectile = new DiamondProjectile(level, player);
            projectile.setLootingMode(getLootingMode(player.getItemInHand(hand)));
            projectile.setOwnerId(player.getUUID());
            projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 1.0F);
            level.addFreshEntity(projectile);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (isDisguised(stack)) {
            return super.hurtEnemy(stack, target, attacker);
        }
        // 玩家目标不受覆写/瞬杀影响：走原版伤害判定，避免攻击玩家时误触发强制裁决
        if (target instanceof Player) {
            return super.hurtEnemy(stack, target, attacker);
        }
        if (!attacker.level().isClientSide()) {
            // 开关打开 → 无条件触发覆写+断未（不检查泥土）
            if (isOverwriteEnabled(stack)) {
                LOGGER.info("[HURT-ENEMY] overwriteEnabled=true -> startOverwrite target={}", target.getType());
                KunJinKaoOverwriteHandler.startOverwrite(attacker, target, stack, (ServerLevel) attacker.level());
                return true;
            }
            // 开关关闭 → 瞬杀（无覆写特效、无断未）
            LOGGER.info("[HURT-ENEMY] overwriteEnabled=false -> instant kill target={}", target.getType());
            applyKunJinKaoMark(target, stack);
            target.kill();
        }
        return true;
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        if (isDisguised(stack)) {
            return super.getDestroySpeed(stack, state);
        }
        // 破坏任意方块速度为原版金质工具速度
        return Tiers.GOLD.getSpeed();
    }

    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        if (isDisguised(stack)) {
            return super.isCorrectToolForDrops(stack, state);
        }
        // 任意方块都视为“正确工具”，可正常获得掉落
        return true;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        // Forge/Minecraft 1.20.1 uses this hook rather than DataComponents.
        // It covers swords created by creative tabs, commands, recipes, and combat.
        return false;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        // 兜底：任何路径产生的耐久损耗都立即清零，保证耐久条始终不显示
        if (!level.isClientSide() && stack.getDamageValue() > 0) {
            stack.setDamageValue(0);
        }
    }

    private InteractionResultHolder<ItemStack> cycleMode(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        // 1.20.1 NBT API：读取当前模式，循环切换 0→1→2→0
        CompoundTag tag = stack.getOrCreateTag();
        int currentMode = tag.getInt(LOOTING_MODE_KEY);
        int newMode = (currentMode + 1) % 3;
        tag.putInt(LOOTING_MODE_KEY, newMode);

        String modeText = switch (newMode) {
            case 1 -> "§6抢夺 25 级";
            case 2 -> "§6抢夺 50 级";
            default -> "§7无抢夺";
        };
        player.displayClientMessage(Component.literal("§e覆写·断未 - 当前模式: " + modeText), true);

        return InteractionResultHolder.consume(stack);
    }

    /**
     * 将锟斤拷击杀标记与当前抢夺模式写入目标实体持久 NBT，
     * 供 {@link KunJinKaoDeathEventHandler#onLivingDrops} 在指令杀后应用掉落加成。
     */
    public static void applyKunJinKaoMark(LivingEntity target, ItemStack stack) {
        CompoundTag data = target.getPersistentData();
        data.putBoolean(KunJinKaoDeathEventHandler.MARK_KEY, true);
        data.putInt(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY, getLootingMode(stack));
        // 断未同样可杀死背包持剑的玩家目标：写入穿透保护标记
        data.putBoolean(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY, true);
    }

    /**
     * 读取物品上保存的抢夺模式（0/1/2），供抛射物发射时快照使用。
     */
    public static int getLootingMode(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.getInt(LOOTING_MODE_KEY);
    }
}
