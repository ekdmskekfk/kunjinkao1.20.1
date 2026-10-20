package dev.modmind.kunjinkao;

import java.util.List;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.event.KunJinKaoDeathEventHandler;
import dev.modmind.kunjinkao.event.KunJinKaoProtectionHandler;
import dev.modmind.kunjinkao.overwrite.KunJinKaoOverwriteHandler;
import dev.modmind.kunjinkao.recipe.AdminSwordRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import dev.modmind.kunjinkao.wand.WandCore;
import dev.modmind.kunjinkao.wand.WandJob;
import dev.modmind.kunjinkao.world.PlacementUndoHistory;
import dev.modmind.kunjinkao.world.SwordAreaFields;
import dev.modmind.kunjinkao.world.SwordPlacementRegistry;
import dev.modmind.kunjinkao.world.SwordTimeAcceleration;
import dev.modmind.kunjinkao.world.SwordToolHandler;
import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import javax.annotation.Nullable;

public class KunJinKaoSwordItem extends SwordItem {

    /**
     * 抢夺等级。取值范围 1..1000，含义是「增强等级 + 1」——
     * 也就是 1 = 原版掉落（不加任何东西），和 {@link #ORE_DROP_MULTIPLIER_KEY} 的 1 = 无加成同一套约定。
     * <p>
     * 旧版本这里是 0/1/2 三档的「抢夺模式」（0 关、1 = 25 级、2 = 50 级）。
     * 档位换成连续值之后键名也换了（{@code LootingMode} → {@code LootingLevel}），
     * 正是为了能把老存档里的 0/1/2 认出来做一次迁移 —— 沿用旧键名的话，
     * 新值 1 和 2 与旧的 "25 级 / 50 级" 会撞在一起，没法区分。
     */
    private static final String LOOTING_LEVEL_KEY = "LootingLevel";
    /** 旧键：只在读取时用来迁移，迁移后不再写入。 */
    private static final String LEGACY_LOOTING_MODE_KEY = "LootingMode";
    private static final String OVERWRITE_KEY = "OverwriteEnabled";
    private static final String THEME_KEY = "OverwriteTheme";
    private static final String BLUE_SCREEN_ATTACK_KEY = "BlueScreenAttackEnabled";
    private static final String BREAK_UNBREAKABLE_BLOCKS_KEY = "BreakUnbreakableBlocksEnabled";
    private static final String MINING_SPEED_KEY = "MiningSpeed";
    private static final String ORE_DROP_MULTIPLIER_KEY = "OreDropMultiplier";
    private static final String AREA_CLEAR_TARGET_MODE_KEY = "AreaClearTargetMode";
    private static final String ATTACK_DAMAGE_LIMIT_KEY = "AttackDamageLimit";
    private static final String ULTIMATE_DEATH_KEY = "UltimateDeathEnabled";
    private static final String QUIT_STRIKE_KEY = "QuitStrikeEnabled";
    // ===== 放置类核心（对应 Construction Wand 的三种核心）=====
    /** 建筑手杖：朝面向的那一侧延伸建造，一次放一排。 */
    private static final String CONSTRUCTION_WAND_KEY = "ConstructionWandEnabled";
    /** 天使核心：放在所视方块的背面；对空右键可在半空放置（需副手有方块）。 */
    private static final String ANGEL_CORE_KEY = "AngelCoreEnabled";
    /** 破坏核心：破坏所视那一侧的方块，直接消失不留掉落。 */
    private static final String DESTRUCTION_CORE_KEY = "DestructionCoreEnabled";
    /** 撤销：开启后按 K 键可撤销最近 10 步内的放置/破坏。 */
    private static final String PLACEMENT_UNDO_KEY = "PlacementUndoEnabled";
    // ===== 工具类行为（刷子 / 锄头铲子 / 避雷针）=====
    /** 刷子：长按右键可疑的沙 / 可疑的砾石，可像原版刷子一样刷取。 */
    private static final String BRUSH_KEY = "BrushEnabled";
    /** 工具模式：0 关 / 1 锄头（→耕地）/ 2 铲子（→草径）。 */
    private static final String TOOL_MODE_KEY = "SwordToolMode";
    /** 避雷针：右键避雷针召唤闪电。 */
    private static final String LIGHTNING_ROD_KEY = "LightningRodEnabled";
    /**
     * 召唤出来的闪电形态。
     * <ul>
     *   <li>0 = 自然：额外打上 {@code ae2lt.natural_weather_lightning} 标记，
     *       让闪电科技（ae2lt）把它当成自然闪电 —— 这是它自己调试物品的做法；</li>
     *   <li>1 = 普通：什么都不加，就是一道寻常的雷；</li>
     *   <li>2 = 仅视觉：{@code setVisualOnly(true)}，不点火、不伤人。</li>
     * </ul>
     */
    private static final String LIGHTNING_MODE_KEY = "LightningMode";
    /** 客户端模型选择用的整型键（1.20.1 直接用这个 NBT 键，替代 1.21 的 CUSTOM_MODEL_DATA 组件）。 */
    private static final String CUSTOM_MODEL_DATA_KEY = "CustomModelData";

    /** 闪电形态：自然（默认，兼容闪电科技）。 */
    public static final int LIGHTNING_NATURAL = 0;
    /** 闪电形态：普通。 */
    public static final int LIGHTNING_PLAIN = 1;
    /** 闪电形态：仅视觉。 */
    public static final int LIGHTNING_VISUAL_ONLY = 2;
    /** 闪电形态的取值个数。 */
    public static final int LIGHTNING_MODE_COUNT = 3;

    public static int getLightningMode(ItemStack stack) {
        int mode = dataTag(stack).getInt(LIGHTNING_MODE_KEY);
        return Math.max(0, Math.min(LIGHTNING_MODE_COUNT - 1, mode));
    }

    public static void setLightningMode(ItemStack stack, int mode) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(LIGHTNING_MODE_KEY, Math.max(0, Math.min(LIGHTNING_MODE_COUNT - 1, mode)));
        writeDataTag(stack, tag);
    }
    /** 时间加速模式：0 关 / 1 限时（30 秒）/ 2 无限。 */
    /** 斩首：击杀生物时额外掉落对应头颅。 */
    private static final String BEHEADING_KEY = "BeheadingEnabled";
    /** 精准采集：开启后剑上会真的挂上原版精准采集附魔。 */
    private static final String SILK_TOUCH_KEY = "SilkTouchEnabled";
    /** 挖掘熔炼：挖到的掉落自动过一遍熔炉（熔炼）配方。 */
    private static final String AUTO_SMELT_KEY = "AutoSmeltEnabled";
    /** 刷怪蛋掉落：击杀生物时额外掉落它的刷怪蛋。 */
    private static final String SPAWN_EGG_DROP_KEY = "SpawnEggDropEnabled";
    /** 扳手：给剑带上扳手标记。同时剑在 c:tools/wrench 标签里，模组会直接把它当扳手。 */
    private static final String WRENCH_KEY = "Wrench";

    /** 区域模式：0=关（shift+右键 照旧加速机器）、1=暂停场、2=范围加速。 */
    private static final String AREA_MODE_KEY = "AreaMode";
    /** 暂停场的半边长（1..4，对应 3x3x3 .. 9x9x9）。 */
    private static final String PAUSE_SIZE_KEY = "PauseSize";
    /** 范围加速的半边长（1..4，对应 3x3x3 .. 9x9x9）。 */
    private static final String AREA_ACCEL_SIZE_KEY = "AreaAccelSize";
    /**
     * 范围加速的倍率。
     * <p>
     * 刻意与"机器加速"的倍率分开：范围加速一次要带动一整个立方体的机器，
     * 跟单台机器共用一个倍率会很别扭。放置时这个值会记进放置记录里。
     */
    private static final String AREA_ACCEL_MULTIPLIER_KEY = "AreaAccelMultiplier";
    private static final String TIME_ACCEL_MODE_KEY = "TimeAccelMode";
    /** 时间加速倍率，取值必须在 AcceleratorBlockEntity.MULTIPLIERS 里。 */
    private static final String TIME_ACCEL_MULTIPLIER_KEY = "TimeAccelMultiplier";

    /**
     * 击杀时写在受害者身上的标记，供 UltimateDeathHandler 判断这一刀用的是哪个开关。
     * 范围清除这类路径没有伤害来源，只能靠标记把开关状态传给死亡处理。
     */
    public static final String ULTIMATE_DEATH_MARK = "KunJinKaoUltimateDeath";
    public static final String QUIT_STRIKE_MARK = "KunJinKaoQuitStrike";

    public static final int MIN_MINING_SPEED = 1;
    public static final int MAX_MINING_SPEED = 100;
    public static final int DEFAULT_MINING_SPEED = 12;
    public static final int MIN_ORE_DROP_MULTIPLIER = 1;
    public static final int MAX_ORE_DROP_MULTIPLIER = 1000;
    public static final int DEFAULT_ORE_DROP_MULTIPLIER = 1;
    /** 抢夺设置值范围。取值 = 增强等级 + 1，所以 1 是"无抢夺"，与挖矿倍数同一套约定。 */
    public static final int MIN_LOOTING_LEVEL = 1;
    public static final int MAX_LOOTING_LEVEL = 1000;
    public static final int DEFAULT_LOOTING_LEVEL = 1;
    public static final int MAX_ATTACK_DAMAGE_LIMIT = 1000;
    public static final int DEFAULT_ATTACK_DAMAGE_LIMIT = MAX_ATTACK_DAMAGE_LIMIT;

    public KunJinKaoSwordItem(Tier tier, Properties properties) {
        // 1.20.1：剑的伤害/攻速是构造函数参数（1.21 才改成 properties.attributes(...)）。
        super(tier, 3, -2.4F, properties);
    }

    // ===== DataComponent 读写辅助：所有非 CustomModelData 字段都存放在 CUSTOM_DATA 的 NBT 内 =====

    /**
     * 读剑的 NBT（副本：改完必须 writeDataTag 写回）。建筑手杖那套选项也走这里。
     * <p>
     * 1.20.1 没有 DataComponent，1.21.1 的 {@code DataComponents.CUSTOM_DATA} 在这里
     * 就是物品自己的根标签 —— 所有 40+ 个字段键读写方式完全不变。
     */
    public static CompoundTag dataTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.copy() : new CompoundTag();
    }

    /** 把改好的 NBT 写回物品。 */
    public static void writeDataTag(ItemStack stack, CompoundTag tag) {
        stack.setTag(tag);
    }

    // ===== 伪装状态：使用 DataComponents.CUSTOM_MODEL_DATA =====

    public static boolean isDisguised(ItemStack stack) {
        return dataTag(stack).getInt(CUSTOM_MODEL_DATA_KEY) == 1;
    }

    public static void setDisguised(ItemStack stack, boolean disguised) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(CUSTOM_MODEL_DATA_KEY, disguised ? 1 : 0);
        writeDataTag(stack, tag);
    }

    public static void toggleDisguise(ItemStack stack) {
        setDisguised(stack, !isDisguised(stack));
    }

    // ===== 覆写流程开关 =====

    public static boolean isOverwriteEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(OVERWRITE_KEY);
    }

    public static void setOverwriteEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(OVERWRITE_KEY, enabled);
        writeDataTag(stack, tag);
    }

    public static void toggleOverwrite(ItemStack stack) {
        setOverwriteEnabled(stack, !isOverwriteEnabled(stack));
    }

    // ===== 异象主题 =====

    public static int getTheme(ItemStack stack) {
        int theme = dataTag(stack).getInt(THEME_KEY);
        return Math.floorMod(theme, KunJinKaoTheme.COUNT);
    }

    public static void setTheme(ItemStack stack, int theme) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(THEME_KEY, Math.floorMod(theme, KunJinKaoTheme.COUNT));
        writeDataTag(stack, tag);
    }

    public static void cycleTheme(ItemStack stack) {
        setTheme(stack, getTheme(stack) + 1);
    }

    public static boolean isBlueScreenAttackEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(BLUE_SCREEN_ATTACK_KEY);
    }

    public static void setBlueScreenAttackEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(BLUE_SCREEN_ATTACK_KEY, enabled);
        writeDataTag(stack, tag);
    }

    public static boolean canBreakUnbreakableBlocks(ItemStack stack) {
        return dataTag(stack).getBoolean(BREAK_UNBREAKABLE_BLOCKS_KEY);
    }

    public static void setBreakUnbreakableBlocks(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(BREAK_UNBREAKABLE_BLOCKS_KEY, enabled);
        writeDataTag(stack, tag);
    }

    public static int getMiningSpeed(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        if (!tag.contains(MINING_SPEED_KEY)) return DEFAULT_MINING_SPEED;
        return clampMiningSpeed(tag.getInt(MINING_SPEED_KEY));
    }

    public static void setMiningSpeed(ItemStack stack, int speed) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(MINING_SPEED_KEY, clampMiningSpeed(speed));
        writeDataTag(stack, tag);
    }

    public static int getOreDropMultiplier(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        if (!tag.contains(ORE_DROP_MULTIPLIER_KEY)) return DEFAULT_ORE_DROP_MULTIPLIER;
        return clampOreDropMultiplier(tag.getInt(ORE_DROP_MULTIPLIER_KEY));
    }

    public static void setOreDropMultiplier(ItemStack stack, int multiplier) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(ORE_DROP_MULTIPLIER_KEY, clampOreDropMultiplier(multiplier));
        writeDataTag(stack, tag);
    }

    public static AreaClearTargetMode getAreaClearTargetMode(ItemStack stack) {
        int id = dataTag(stack).getInt(AREA_CLEAR_TARGET_MODE_KEY);
        return AreaClearTargetMode.byId(id);
    }

    public static void setAreaClearTargetMode(ItemStack stack, AreaClearTargetMode mode) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(AREA_CLEAR_TARGET_MODE_KEY, mode.id());
        writeDataTag(stack, tag);
    }

    public static int getAttackDamageLimit(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        if (!tag.contains(ATTACK_DAMAGE_LIMIT_KEY)) return DEFAULT_ATTACK_DAMAGE_LIMIT;
        return clampAttackDamageLimit(tag.getInt(ATTACK_DAMAGE_LIMIT_KEY));
    }

    public static void setAttackDamageLimit(ItemStack stack, int damageLimit) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(ATTACK_DAMAGE_LIMIT_KEY, clampAttackDamageLimit(damageLimit));
        writeDataTag(stack, tag);
    }

    // ===== 终极死亡 / 退出打击 =====

    /** 终极死亡：击杀玩家后将其加入排除名单，使其无法再进入服务器，直到管理员解除。 */
    public static boolean isUltimateDeathEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(ULTIMATE_DEATH_KEY);
    }

    public static void setUltimateDeathEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(ULTIMATE_DEATH_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 退出打击：击杀玩家后立即把他踢出服务器，但仍可重新进入。 */
    public static boolean isQuitStrikeEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(QUIT_STRIKE_KEY);
    }

    public static void setQuitStrikeEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(QUIT_STRIKE_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 建筑手杖：右键对着方块面时，沿该面延伸放置一排方块。 */
    public static boolean isConstructionWandEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(CONSTRUCTION_WAND_KEY);
    }

    public static void setConstructionWandEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(CONSTRUCTION_WAND_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 天使核心：把方块放在所视方块的背面，或对着空气在半空放置。 */
    public static boolean isAngelCoreEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(ANGEL_CORE_KEY);
    }

    public static void setAngelCoreEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(ANGEL_CORE_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 破坏核心：挖掉一个方块时连带清除面向那一侧的整排方块，且不留掉落。 */
    public static boolean isDestructionCoreEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(DESTRUCTION_CORE_KEY);
    }

    public static void setDestructionCoreEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(DESTRUCTION_CORE_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 撤销：开启后按 K 键可撤销最近 10 步内的放置/破坏操作。 */
    public static boolean isPlacementUndoEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(PLACEMENT_UNDO_KEY);
    }

    public static void setPlacementUndoEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(PLACEMENT_UNDO_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 两只手中任意一只的剑开了撤销即可 —— 玩家可能把剑放在副手。 */
    public static boolean isPlacementUndoEnabledInEitherHand(Player player) {
        return isPlacementUndoEnabled(player.getMainHandItem())
                || isPlacementUndoEnabled(player.getOffhandItem());
    }

    /** 刷子：长按右键可疑的沙 / 可疑的砾石即可刷取。 */
    public static boolean isBrushEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(BRUSH_KEY);
    }

    public static void setBrushEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(BRUSH_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 工具模式：0 关 / 1 锄头 / 2 铲子。越界值一律回落成 0，避免旧存档写出奇怪数值。 */
    public static int getToolMode(ItemStack stack) {
        return SwordToolHandler.clampToolMode(dataTag(stack).getInt(TOOL_MODE_KEY));
    }

    public static void setToolMode(ItemStack stack, int mode) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(TOOL_MODE_KEY, SwordToolHandler.clampToolMode(mode));
        writeDataTag(stack, tag);
    }

    /** 避雷针：右键避雷针召唤闪电。 */
    public static boolean isLightningRodEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(LIGHTNING_ROD_KEY);
    }

    public static void setLightningRodEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(LIGHTNING_ROD_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 斩首：击杀生物时额外掉落对应头颅。 */
    public static boolean isBeheadingEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(BEHEADING_KEY);
    }

    public static void setBeheadingEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(BEHEADING_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /**
     * 精准采集。
     * <p>
     * 实现方式是把原版精准采集附魔真的挂到剑上（以及摘掉），而不是自己去替换方块掉落 ——
     * 这样所有原版与模组的战利品表都会按它们原本的逻辑正确处理，
     * 不需要本模组为每种方块写特例。
     *
     * @param level 用来取附魔注册表；拿不到就只记标记、不动附魔
     */
    public static boolean isSilkTouchEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(SILK_TOUCH_KEY);
    }

    public static void setSilkTouchEnabled(ItemStack stack, boolean enabled, @Nullable Level level) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(SILK_TOUCH_KEY, enabled);
        writeDataTag(stack, tag);
        applySilkTouch(stack, enabled, level);
    }

    private static void applySilkTouch(ItemStack stack, boolean enabled, @Nullable Level level) {
        if (level == null) {
            return;
        }
        // 1.20.1：附魔是静态字段 + EnchantmentHelper（1.21 才改成 Holder + ItemEnchantments 组件）。
        if (enabled) {
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH, 1);
            return;
        }
        java.util.Map<net.minecraft.world.item.enchantment.Enchantment, Integer> enchantments =
                net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack);
        if (enchantments.remove(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH) == null) {
            return;
        }
        net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(enchantments, stack);
    }

    /**
     * 挖掘熔炼：挖到的掉落自动过一遍熔炉（熔炼）配方 —— 原矿出锅成锭、沙子成玻璃、圆石成石头。
     * <p>
     * 这里只存取开关，不在这查配方：物品类在客户端也会加载，而配方表只在服务端拿得到。
     * 真正的替换在 {@link dev.modmind.kunjinkao.event.KunJinKaoAutoSmeltHandler} 里。
     */
    public static boolean isAutoSmeltEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(AUTO_SMELT_KEY);
    }

    public static void setAutoSmeltEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(AUTO_SMELT_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 刷怪蛋掉落：击杀生物时额外掉落它的刷怪蛋。 */
    public static boolean isSpawnEggDropEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(SPAWN_EGG_DROP_KEY);
    }

    public static void setSpawnEggDropEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(SPAWN_EGG_DROP_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /**
     * 扳手开关。
     * <p>
     * 打开后：shift+右键 整个让给扳手 —— 本模组不消费这次右键，
     * AE2 那类绑在 shift+右键 上的模组扳手逻辑才能被触发。
     * 代价是同时无法用 shift+右键 加速机器，两种用途靠这个开关互斥切换。
     * <p>
     * 另外剑本身也在 c:tools/wrench 与 ae2:quartz_wrench 物品标签里，
     * 所以模组从"手里拿的是不是扳手"这一层就已经认它了。
     */
    public static boolean isWrenchEnabled(ItemStack stack) {
        return dataTag(stack).getBoolean(WRENCH_KEY);
    }

    /** 区域模式：0=关、1=暂停场、2=范围加速。 */
    public static int getAreaMode(ItemStack stack) {
        int mode = dataTag(stack).getInt(AREA_MODE_KEY);
        return mode < 0 || mode > 2 ? 0 : mode;
    }

    public static void setAreaMode(ItemStack stack, int mode) {
        // 必须 writeDataTag 写回去：dataTag() 返回的是 CUSTOM_DATA 的【拷贝】，
        // 直接 putInt 到拷贝上等于什么都没做 —— 这一处漏掉就是"区域模式怎么点都开不了"的原因。
        CompoundTag tag = dataTag(stack);
        tag.putInt(AREA_MODE_KEY, Math.max(0, Math.min(2, mode)));
        writeDataTag(stack, tag);
    }

    public static int nextAreaMode(int current) {
        return current >= 2 ? 0 : current + 1;
    }

    public static int getPauseSize(ItemStack stack) {
        return SwordAreaFields.clampRadius(dataTag(stack).getInt(PAUSE_SIZE_KEY));
    }

    public static void setPauseSize(ItemStack stack, int radius) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(PAUSE_SIZE_KEY, SwordAreaFields.clampRadius(radius));
        writeDataTag(stack, tag);
    }

    public static int getAreaAccelSize(ItemStack stack) {
        return SwordAreaFields.clampRadius(dataTag(stack).getInt(AREA_ACCEL_SIZE_KEY));
    }

    public static void setAreaAccelSize(ItemStack stack, int radius) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(AREA_ACCEL_SIZE_KEY, SwordAreaFields.clampRadius(radius));
        writeDataTag(stack, tag);
    }

    /** 范围加速倍率：取值同样必须是 AcceleratorBlockEntity.MULTIPLIERS 里的档位。 */
    public static int getAreaAccelMultiplier(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        return tag.contains(AREA_ACCEL_MULTIPLIER_KEY)
                ? AcceleratorBlockEntity.clampMultiplier(tag.getInt(AREA_ACCEL_MULTIPLIER_KEY))
                : AcceleratorBlockEntity.MULTIPLIERS[0];
    }

    public static void setAreaAccelMultiplier(ItemStack stack, int multiplier) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(AREA_ACCEL_MULTIPLIER_KEY, AcceleratorBlockEntity.clampMultiplier(multiplier));
        writeDataTag(stack, tag);
    }

    /**
     * 按当前区域模式在那一格立/撤一个场。
     * <p>
     * <b>区域场的"建哪种、多大、多少倍"只在这一处决定。</b>
     * 之前 {@code SwordAreaFieldHandler.onRightClickBlock} 里另抄了一份，
     * 结果那边漏改、写死了机器加速的倍率 —— 而那条路才是实际生效的，
     * 表现就是"范围加速用的倍率是加速分区那个"。两边现在都调这里。
     *
     * @return false 表示区域模式是关的，调用方该继续走原来的加速逻辑
     */
    public static boolean tryPlaceAreaField(Player player, Level level, BlockPos pos, ItemStack stack) {
        int mode = getAreaMode(stack);
        if (mode == 0) {
            return false;
        }
        int radius = mode == 1 ? getPauseSize(stack) : getAreaAccelSize(stack);
        int kind = mode == 1
                ? SwordPlacementRegistry.KIND_PAUSE
                : SwordPlacementRegistry.KIND_AREA_ACCEL;
        SwordAreaFields.tryToggle(player, level, pos, kind, radius,
                mode == 1 ? getTimeAccelMultiplier(stack) : getAreaAccelMultiplier(stack));
        return true;
    }

    public static void setWrenchEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = dataTag(stack);
        tag.putBoolean(WRENCH_KEY, enabled);
        writeDataTag(stack, tag);
    }

    /** 时间加速模式：0 关 / 1 限时（30 秒）/ 2 无限。 */
    public static int getTimeAccelMode(ItemStack stack) {
        return SwordTimeAcceleration.clampMode(dataTag(stack).getInt(TIME_ACCEL_MODE_KEY));
    }

    public static void setTimeAccelMode(ItemStack stack, int mode) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(TIME_ACCEL_MODE_KEY, SwordTimeAcceleration.clampMode(mode));
        writeDataTag(stack, tag);
    }

    /** 时间加速倍率：取值必须是 AcceleratorBlockEntity.MULTIPLIERS 里的档位。 */
    public static int getTimeAccelMultiplier(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        return tag.contains(TIME_ACCEL_MULTIPLIER_KEY)
                ? AcceleratorBlockEntity.clampMultiplier(tag.getInt(TIME_ACCEL_MULTIPLIER_KEY))
                : AcceleratorBlockEntity.MULTIPLIERS[0];
    }

    public static void setTimeAccelMultiplier(ItemStack stack, int multiplier) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(TIME_ACCEL_MULTIPLIER_KEY, AcceleratorBlockEntity.clampMultiplier(multiplier));
        writeDataTag(stack, tag);
    }

    public static void setLootingLevel(ItemStack stack, int level) {
        CompoundTag tag = dataTag(stack);
        tag.putInt(LOOTING_LEVEL_KEY, clampLootingLevel(level));
        // 迁移完成，旧键留着没意义；不清掉的话以后改名会再撞一次。
        tag.remove(LEGACY_LOOTING_MODE_KEY);
        writeDataTag(stack, tag);
    }

    /**
     * 抢夺设置值（1..1000）。1 表示不加任何额外掉落。
     * <p>
     * 老存档迁移在这里做、且<b>只读不写</b>：只有"新键不存在"的剑才会走到迁移分支，
     * 而新键一旦被菜单写过就永远存在，所以同一个旧值不会在别的含义下被再解释一次。
     * 顺带也避免了在读取路径（每次命中都会调）里写物品 NBT。
     */
    public static int getLootingLevelSetting(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        if (tag.contains(LOOTING_LEVEL_KEY)) {
            return clampLootingLevel(tag.getInt(LOOTING_LEVEL_KEY));
        }
        return migrateLegacyLootingMode(tag.getInt(LEGACY_LOOTING_MODE_KEY));
    }

    /**
     * 真正参与掉落计算的抢夺等级（0..999）。
     * 设置值 1 对应等级 0 —— 也就是"无抢夺"，与旧版 {@code mode == 0} 的行为对齐。
     */
    public static int getLootingLevel(ItemStack stack) {
        return getLootingLevelSetting(stack) - 1;
    }

    /** 旧版三档 → 新值：0（关）→ 1、1（25 级）→ 26、2（50 级）→ 51。 */
    private static int migrateLegacyLootingMode(int legacyMode) {
        return switch (legacyMode) {
            case 1 -> 26;
            case 2 -> 51;
            default -> DEFAULT_LOOTING_LEVEL;
        };
    }

    private static int clampLootingLevel(int level) {
        return Math.max(MIN_LOOTING_LEVEL, Math.min(MAX_LOOTING_LEVEL, level));
    }

    /**
     * 剑是否处于"惰性"状态：伪装中，或仍是未经验证的合成成品（pending 标记）。
     * <p>
     * pending 剑必须在能力入口就被拒绝，不能只靠 {@code inventoryTick} 事后销毁 ——
     * 对抗性复核指出"捡起 → 下一 tick 销毁"之间存在窗口（ItemEntity 不 tick，
     * 同刻丢回地上还能保住标记），期间 pending 剑是一把满配武器。
     * 惰性的剑在这里一律退化成普通剑行为（等同于伪装分支的处理）。
     */
    /**
     * 这个物品栈是否"算作管理员剑" —— 注入机制引入后的<b>统一判定入口</b>。
     *
     * <p>注入机制让能力可以挂在任意物品上，原来散落全库的
     * {@code KunJinKaoSwordItem.isSwordLike(stack)} 全部改成调用这里，
     * 于是「谁拥有管理员能力」只剩一个定义点。</p>
     *
     * <p>它<b>不</b>检查伪装/未完成合成（那是 {@link #isInert} 的事）。</p>
     */
    public static boolean isSwordLike(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof KunJinKaoSwordItem
                || dev.modmind.kunjinkao.inject.InjectionData.isInjected(stack);
    }

    public static boolean isInert(ItemStack stack) {
        return isDisguised(stack) || AdminSwordRecipe.isPendingAdminSword(stack);
    }

    /** 是否仍是"未验证的合成成品"（Crafter 等绕过 ItemCraftedEvent 的路径留下的标记）。 */
    public static boolean isPendingCraft(ItemStack stack) {
        return AdminSwordRecipe.isPendingAdminSword(stack);
    }

    private static int clampMiningSpeed(int speed) {
        return Math.max(MIN_MINING_SPEED, Math.min(MAX_MINING_SPEED, speed));
    }

    private static int clampOreDropMultiplier(int multiplier) {
        return Math.max(MIN_ORE_DROP_MULTIPLIER, Math.min(MAX_ORE_DROP_MULTIPLIER, multiplier));
    }

    private static int clampAttackDamageLimit(int damageLimit) {
        return Math.max(0, Math.min(MAX_ATTACK_DAMAGE_LIMIT, damageLimit));
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
            super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);
            return;
        }
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("item.modifiers.mainhand").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal("∞ ")
                .append(Component.translatable("tooltip.kunjinkao.kun_jin_kao.attack_damage").withStyle(ChatFormatting.DARK_GREEN)));
        tooltipComponents.add(Component.literal("+2.0 ")
                .append(Component.translatable("tooltip.kunjinkao.kun_jin_kao.attack_speed").withStyle(ChatFormatting.DARK_GREEN)));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_1").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_2").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_3").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_4").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_5").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_6").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_7").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_8").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_9").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_10").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_11").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_12").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_13").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_14").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.line_15").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.empty());
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.overwrite_state").withStyle(ChatFormatting.DARK_GRAY)
                .append(isOverwriteEnabled(stack)
                        ? Component.translatable("tooltip.kunjinkao.kun_jin_kao.overwrite_on").withStyle(ChatFormatting.DARK_GREEN)
                        : Component.translatable("tooltip.kunjinkao.kun_jin_kao.overwrite_off").withStyle(ChatFormatting.DARK_RED)));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.theme").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(KunJinKaoTheme.displayName(getTheme(stack))).withStyle(ChatFormatting.LIGHT_PURPLE)));
        tooltipComponents.add(Component.translatable("tooltip.kunjinkao.kun_jin_kao.theme_hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return useImpl(player.getItemInHand(hand), level, player, hand);
    }

    /**
     * {@link #use} 的实现本体，抽成静态方法供「被注入的任意物品」复用。
     *
     * <p>注入物品不是本类实例，原版永远不会调用它的 {@code use}，只能由
     * {@code InjectedItemBehaviorHandler} 在交互事件里转调这里。逻辑只留一份。</p>
     */
    public static InteractionResultHolder<ItemStack> useImpl(ItemStack stack, Level level,
                                                            Player player, InteractionHand hand) {
        if (isInert(stack)) {
            return InteractionResultHolder.pass(stack);
        }
        if (player.isShiftKeyDown()) {
            // 对着【上方】shift+右键：加速时间（整体拉高服务器每秒 tick 数）。
            // 刻意要求真的抬头（至少 45 度），而不是"点到空气就算" ——
            // 整体加速影响全服，不该在平视或低头点到空气时被误触。
            // 这条也是唯一能带动 AE2 那类"按 gameTime 算进度"的机器的办法。
            int accelMode = SwordTimeAcceleration.clampMode(getTimeAccelMode(stack));
            if (accelMode != SwordTimeAcceleration.MODE_OFF) {
                // 开了加速模式后，shift+右键 只服务于加速，不再顺手切换抢夺模式 ——
                // 否则右键机器时（事件落回这里）屏幕下方会弹出"抢夺模式"提示，
                // 看起来像是把机器加速换成了改抢夺模式，实际是两件事同时发生了。
                if (level.getServer() != null && !isWrenchEnabled(stack)
                        && SwordTimeAcceleration.isFacingSky(player)) {
                    SwordTimeAcceleration.tryToggleTime(player, level.getServer(), stack);
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
            }
            // 加速模式关着时，shift+右键 什么都不做。
            // 这里原本是 cycleMode()（切换抢夺模式）—— 对着方块按 shift+右键 时 useOn 返回 PASS
            // 会落回 use()，于是"点一下方块"就顺手把抢夺模式换了，屏幕下方还弹一句
            // 看起来毫不相干的提示。抢夺等级改由 . 菜单里的滑条（LootingLevelSlider）调节。
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        // 天使核心：对着空气右键可以在半空放置方块。
        // 落点按参考实现算：眼睛位置 + 视线 × 2；生存模式下落超过 10 格时由动作自己拒绝。
        if (isAngelCoreEnabled(stack)) {
            net.minecraft.world.phys.Vec3 look = player.getLookAngle();
            net.minecraft.world.phys.BlockHitResult miss = net.minecraft.world.phys.BlockHitResult.miss(
                    look, dev.modmind.kunjinkao.wand.WandUtil.fromVector(look), player.blockPosition());
            WandJob airJob = new WandJob(player, level, miss, stack);
            airJob.compute();
            if (airJob.blockCount() > 0) {
                if (!level.isClientSide()) {
                    PlacementUndoHistory.push(player, airJob.execute());
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
            }
        }
        // 只有在「没点到方块」时才做范围清除。
        // use() 会在 useOn 返回 PASS 之后被原版调用 —— 也就是说玩家其实是对着方块按的右键。
        // 不挡这一下，点个避雷针、点台机器都会顺手把周围生物清一遍。
        if (player.pick(player.getAttributeValue(net.minecraftforge.common.ForgeMod.BLOCK_REACH.get()), 1.0F, false).getType()
                == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            int removed = clearAreaTargets(serverLevel, player, stack, getAreaClearTargetMode(stack));
            player.displayClientMessage(Component.translatable("message.kunjinkao.area_clear_result", removed), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /*
     * 【1.20.1 整段删除】扳手能力钩子
     *
     * 原实现是 @Override canPerformAction(ItemStack, ItemAbility)：把 Mekanism 等模组自己的
     * ItemAbility（WRENCH_DISMANTLE 之类）补齐，让剑和它们自家的扳手走完全相同的分支。
     * ItemAbility 是 NeoForge 的概念，1.20.1 没有对应物（那一代只认 c:tools/wrench 标签），
     * 所以这里连同 WrenchAbilities 整类一起删掉。
     *
     * 影响：1.20.1 上"扳手能力"只能靠 c:tools/wrench 标签被识别；
     * 看 ItemAbility 的模组（如 Mekanism）不会把它当自家扳手，可能走另一条分支
     * （实测现象是拆卸后的机器丢 NBT）。这是版本能力差，不是移植遗漏。
     */

    /**
     * 潜行右键时原版会先走这里（{@code isSecondaryUseActive()} 分支），而它排在
     * {@code PlayerInteractEvent.RightClickBlock} <b>之前</b>。
     * <p>
     * 这一层必须自己接管，否则"关掉扳手模式"对走这条路的模组无效：
     * 剑在 {@code c:tools/wrench} 标签里，模组认它，而标签无法按 NBT 开关。
     * 好在 onItemUseFirst 是对<b>手持物品</b>调用的 —— 物品就是本剑，
     * 所以这里能完全决定要不要把这次右键交出去。
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || isInert(stack) || !player.isShiftKeyDown()) {
            return super.onItemUseFirst(stack, context);
        }
        // 扳手模式开着：照旧让给模组。
        if (isWrenchEnabled(stack)) {
            return super.onItemUseFirst(stack, context);
        }
        // 关着：这次右键不该再被当成扳手。目标是可加速方块就开/关加速场，
        // 否则单纯什么都不做 —— 两种情况都要吃掉，不能返回 PASS 把右键放回去。
            // 区域模式开着：这次 shift+右键 用来立/撤区域场，不再碰加速。
            if (tryPlaceAreaField(player, context.getLevel(), context.getClickedPos(), stack)) {
                return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
            }
        if (context.getLevel() instanceof ServerLevel serverLevel) {
            SwordTimeAcceleration.tryToggleBlock(player, serverLevel, context.getClickedPos(), stack);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /**
     * 放置类核心走这里：右键<b>方块</b>时按当前核心动手。
     * <p>
     * 三个核心（破坏 / 天使 / 建筑）共用 {@link WandJob}：先算这次会动哪些格子，再执行。
     * 一格都算不出来（没有材料、位置不合法、核心被服主关掉）就交回原版 ——
     * 原版剑返回 PASS，于是继续走 {@link #use}，别的模组的 shift+右键 也不受影响。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        return useOnImpl(context.getItemInHand(), context);
    }

    /**
     * {@link #useOn} 的实现本体，抽成静态方法的理由同 {@link #useImpl}。
     * 物品栈改为显式参数：注入物品的调用方拿的就是同一个栈。
     */
    public static InteractionResult useOnImpl(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        // 潜行时一律交回原版，只有一个例外：时间加速把 shift 当作触发键。
        if (player == null || isInert(stack)) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();

        // ---- 0) 放置类核心：破坏核心 / 天使核心 / 建筑手杖 ----
        // 三个核心共用同一条 WandJob 流水线（与参考实现一致）：先算出这次会动哪些格子，
        // 再执行。优先级由 WandCore.active 决定（破坏 > 天使 > 建筑）。
        // 一格都算不出来就**交回原版**：不吞这次右键，别的模组的 shift+右键 照常可用。
        if (WandCore.active(stack) != null) {
            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                    context.getClickLocation(), context.getClickedFace(), clickedPos, false);
            WandJob job = new WandJob(player, level, hit, stack);
            job.compute();
            if (job.blockCount() > 0) {
                if (!level.isClientSide()) {
                    // 这一步的所有改动入撤销栈；破坏不掉落、放置会退物品。
                    PlacementUndoHistory.push(player, job.execute());
                }
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
        }

        // ---- 2) shift + 右键：加速 ----
        // 注意它排在放置类核心【之后】：shift+右键 也要能放置，
        // 否则按住 shift 时右键会被这里截走，建筑手杖/天使核心永远收不到点击。
        // 开了加速时，shift+右键 一律在这里处理完 —— 关键是不能漏到 use() 去：
        // useOn 返回 PASS 时原版会接着调 use()，那里也会看 shift + 加速开关，
        // 于是"点了一个不可加速的方块"会莫名其妙触发整体时间加速，
        // 而真正想立的机器加速场根本没建起来（悬浮提示因此也一直是空的）。
        if (player.isShiftKeyDown()) {
            // 扳手开关打开时，shift+右键 整个让给扳手：本模组一点都不碰。
            // 必须这么做，因为 AE2 那类模组的扳手逻辑绑死在 shift+右键 上 ——
            // 只要本模组消费了这次右键，它们就永远不会被触发；换个组合键也没用（模组认不出）。
            // 代价是开着扳手时无法加速机器，两种用途靠这个开关互斥切换。
            if (isWrenchEnabled(stack)) {
                return InteractionResult.PASS;
            }
                // 区域模式开着：shift+右键 立/撤区域场，这次不再走加速那条路。
                if (tryPlaceAreaField(player, level, clickedPos, stack)) {
                    return InteractionResult.sidedSuccess(level.isClientSide());
                }
            int accelMode = SwordTimeAcceleration.clampMode(getTimeAccelMode(stack));
            if (accelMode == SwordTimeAcceleration.MODE_OFF) {
                return InteractionResult.PASS;
            }
            if (SwordTimeAcceleration.tryToggleBlock(player, level, clickedPos, stack)) {
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
            // 目标不可加速：吃掉这次右键并说明原因，而不是让它落到 use() 触发整体加速。
            if (!level.isClientSide()) {
                player.displayClientMessage(
                        Component.translatable("message.kunjinkao.time_accel_not_acceleratable"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // ---- 3) 刷子：长按可刷的方块 ----
        // 只负责进入"使用中"状态，真正的推进在 onUseTick 里，和原版刷子一样。
        if (isBrushEnabled(stack) && SwordToolHandler.isBrushable(level, clickedPos)) {
            player.startUsingItem(context.getHand());
            return InteractionResult.CONSUME;
        }

        // ---- 4) 锄头 / 铲子：草方块 → 耕地 / 草径 ----
        InteractionResult toolResult = SwordToolHandler.applyTillOrFlatten(context, getToolMode(stack));
        if (toolResult != InteractionResult.PASS) {
            return toolResult;
        }

        // ---- 5) 避雷针：召唤闪电 ----
        if (isLightningRodEnabled(stack) && SwordToolHandler.strikeLightningRod(context)) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        return InteractionResult.PASS;
    }

    // ===== 刷子的长按机制（对齐原版 BrushItem）=====

    /**
     * 剑的"使用中"动画固定为刷子动作。
     * <p>
     * 这不影响平时：只有 {@link #useOn} 命中可刷方块并调用 {@code startUsingItem} 之后，
     * 玩家才会进入"使用中"状态，这个动画也才会被用到。
     */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return SwordToolHandler.BRUSH_USE_DURATION;
    }

    /** 长按期间每 tick 推进一次刷取；视线移开或松手立刻中断（与原版一致）。 */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (!(entity instanceof Player player)) {
            return;
        }
        if (isInert(stack) || !isBrushEnabled(stack)) {
            player.releaseUsingItem();
            return;
        }
        SwordToolHandler.tickBrush(level, player, stack, remainingUseDuration);
    }

    // 破坏核心原本在这里挂了一个"挖掉一格就顺带清一整排"的钩子。参考实现里没有这个行为 ——
    // 它的破坏核心只由右键触发，所以按"全部对齐参考实现"的要求把这一段去掉了。
    // 要恢复的话，入口在 WandCore.DESTRUCTION 的 ActionDestruction。
    private static int clearAreaTargets(ServerLevel level, Player player, ItemStack stack, AreaClearTargetMode mode) {
        double halfWidth = 50.0D;
        AABB area = new AABB(player.getX() - halfWidth, level.getMinBuildHeight(), player.getZ() - halfWidth,
                player.getX() + halfWidth, level.getMaxBuildHeight(), player.getZ() + halfWidth);
        int removed = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area,
                mob -> mob.isAlive() && mode.matches(mob))) {
            killIgnoringImmunity(mob, swordKillSource(player));
            removed++;
        }
        if (mode.targetsPlayers()) {
            for (ServerPlayer target : level.players()) {
                if (target != player && target.isAlive() && area.contains(target.position())) {
                    // 范围清除没有伤害来源，处决开关只能通过击杀标记传递。
                    // 玩家用只含处决信息的标记，避免把对方背包按抢夺等级复制。
                    applyExecutionMark(target, stack);
                    recordKiller(target, player);
                    killIgnoringImmunity(target, swordKillSource(player));
                    removed++;
                }
            }
        }
        return removed;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (isInert(stack)) {
            return super.hurtEnemy(stack, target, attacker);
        }
        if (target instanceof Player) {
            // 玩家目标：砍中即秒杀。覆写流程要 40 tick，不符合"砍中即秒杀"，因此对玩家不启用。
            // 伤害上限被人为调低时按普通伤害结算，与生物目标的行为保持一致。
            if (getAttackDamageLimit(stack) < MAX_ATTACK_DAMAGE_LIMIT) {
                return super.hurtEnemy(stack, target, attacker);
            }
            if (!attacker.level().isClientSide()) {
                LOGGER.debug("[HURT-ENEMY] player target -> instant kill target={}", target.getName().getString());
                applyExecutionMark(target, stack);
                killIgnoringImmunity(target, swordKillSource(attacker));
            }
            return true;
        }
        if (getAttackDamageLimit(stack) < MAX_ATTACK_DAMAGE_LIMIT) {
            return true;
        }
        if (!attacker.level().isClientSide()) {
            if (isOverwriteEnabled(stack)) {
                LOGGER.debug("[HURT-ENEMY] overwriteEnabled=true -> startOverwrite target={}", target.getType());
                KunJinKaoOverwriteHandler.startOverwrite(attacker, target, stack, (ServerLevel) attacker.level());
                return true;
            }
            LOGGER.debug("[HURT-ENEMY] overwriteEnabled=false -> instant kill target={}", target.getType());
            applyKunJinKaoMark(target, stack);
            killIgnoringImmunity(target, swordKillSource(attacker));
        }
        return true;
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunJinKao");

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        if (isInert(stack)) {
            return super.getDestroySpeed(stack, state);
        }
        return getMiningSpeed(stack);
    }

    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        if (isInert(stack)) {
            return super.isCorrectToolForDrops(stack, state);
        }
        return true;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        // 1.20.1 没有 UNBREAKABLE 组件：等价物是物品 NBT 里的 Unbreakable:1b
        // （提示框照样显示"无法破坏"，也能修好此前掉过耐久的旧剑）。
        if (!stack.hasTag() || !stack.getTag().getBoolean("Unbreakable")) {
            stack.getOrCreateTag().putBoolean("Unbreakable", true);
        }
        if (level.isClientSide()) {
            return;
        }
        if (stack.getDamageValue() > 0) {
            stack.setDamageValue(0);
        }
        settlePendingCraft(stack, entity);
    }

    /**
     * 结算"未验证的成品"。
     * <p>
     * 自定义配方产出的剑带 {@code kunjinkao:pending_admin_sword_craft} 标记，正常情况下由
     * {@code AdminSwordCraftingHandler} 在 {@code ItemCraftedEvent} 里处理。但 1.21 的 Crafter
     * 方块走 {@code RecipeManager.getRecipeFor} 合成，不会触发该事件，未授权玩家因此可以绕过授权拿到剑。
     * 这里在剑进入任何生物/玩家背包后逐 tick 兜底：授权玩家放行并清标记，未授权玩家的剑直接销毁。
     */
    private static void settlePendingCraft(ItemStack stack, net.minecraft.world.entity.Entity holder) {
        if (!AdminSwordRecipe.isPendingAdminSword(stack)) {
            return;
        }
        if (holder instanceof Player owner && AdminToolConfig.isAuthorized(owner.getUUID())) {
            AdminSwordRecipe.clearPendingCraftTag(stack);
            return;
        }
        stack.setCount(0);
    }

    /**
     * 在受害者身上记下击杀者。
     * <p>
     * 必须在这里记：剑的秒杀为了绕开"先造伤害再判定"的一整套保护流程，
     * 是在 AttackEntityEvent 里直接取消攻击并调用 target.kill() 的 ——
     * 那一步走的是 genericKill，既没有来源实体，也从未让受害者受过伤，
     * 所以 victim 上的 lastHurtByPlayer / lastHurtByMob 永远是 null，
     * 死亡时再怎么查都查不出是谁动的手（斩首与刷怪蛋掉落就卡在这里）。
     * KunJinKaoDeathEventHandler.KILLER_UUID_KEY 这个键原本就为此声明，只是从来没被写过。
     */
    public static void recordKiller(LivingEntity target, @Nullable Player attacker) {
        if (attacker == null) {
            return;
        }
        target.getPersistentData().putUUID(KunJinKaoDeathEventHandler.KILLER_UUID_KEY, attacker.getUUID());
        // 补上"最后伤害来源玩家"——这一步比上面那行 UUID 更关键。
        // 原版 dropFromLootTable 只在这个字段非空时，才会把 LAST_DAMAGE_PLAYER 放进战利品上下文：
        //     if (hitByPlayer && this.lastHurtByPlayer != null) { builder.withParameter(LAST_DAMAGE_PLAYER, ...) }
        // 于是战利品表里 killed_by_player 这类条件才会成立。
        // 烈焰人掉烈焰棒、凋灵骷髅掉头颅、僵尸掉铁锭全靠它。
        // 剑的秒杀从不造成真实伤害，这个字段一直是空的，所以这些条件从来没满足过 ——
        // 表现就是"打死了却几乎不掉东西"（只剩那些无条件掷骰的，比如萤石粉 0-2）。
        target.setLastHurtByPlayer(attacker);
    }

    public static void applyKunJinKaoMark(LivingEntity target, ItemStack stack) {
        CompoundTag data = target.getPersistentData();
        data.putBoolean(KunJinKaoDeathEventHandler.MARK_KEY, true);
        data.putInt(KunJinKaoDeathEventHandler.LOOTING_MODE_ENTITY_KEY, getLootingLevelSetting(stack));
        // 同时记下写入时刻：这次伤害可能被无敌帧整段丢弃，Entity 会带着标记继续活着，
        // 死亡处理器只认可 MARK_VALID_TICKS 内的标记，这样之后的无关死亡不会也按同样等级复制掉落。
        data.putLong(KunJinKaoDeathEventHandler.MARK_TICK_KEY, target.level().getGameTime());
        data.putBoolean(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY, true);
        data.putBoolean(ULTIMATE_DEATH_MARK, isUltimateDeathEnabled(stack));
        data.putBoolean(QUIT_STRIKE_MARK, isQuitStrikeEnabled(stack));
    }

    /**
     * 处决标记：只写"这次击杀已获授权"与两个处决开关，不写掉落增强标记。
     * <p>
     * 玩家目标必须用这个而不是 {@link #applyKunJinKaoMark}：
     * 后者会带上抢夺等级，而掉落增强是按该等级把掉落物复制对应份数，
     * 用在玩家身上等于把对方整个背包复制几十份。
     */
    public static void applyExecutionMark(LivingEntity target, ItemStack stack) {
        CompoundTag data = target.getPersistentData();
        data.putBoolean(KunJinKaoProtectionHandler.KILL_BY_OVERWRITE_KEY, true);
        // 处决标记同样带时效：写标记与 target.kill() 之间可能因为无敌帧或保护逻辑没死成，
        // 过期后必须失效，否则残留的开关标记会让之后的死亡被误当作处决。
        data.putLong(KunJinKaoDeathEventHandler.MARK_TICK_KEY, target.level().getGameTime());
        data.putBoolean(ULTIMATE_DEATH_MARK, isUltimateDeathEnabled(stack));
        data.putBoolean(QUIT_STRIKE_MARK, isQuitStrikeEnabled(stack));
    }

    /**
     * 把目标杀掉，并且处理"杀不动"的情况。
     * <p>
     * {@code LivingEntity#kill()} 本身只是 {@code hurt(genericKill, MAX)}：它能过掉原版的
     * 无敌判定（genericKill 在 BYPASSES_INVULNERABILITY 里），但过不掉两种很常见的 BOSS 写法：
     * <ul>
     *   <li>{@code hurt()} 里直接 {@code return false} —— 阶段无敌、锁血、剧情演出；</li>
     *   <li>{@code hurt()} 里把伤害截断成"每次最多 N 点" —— 这种会返回 true，
     *       但血只掉一小截，目标照样站着，表现就是"伤害打不破它的防御"。</li>
     * </ul>
     * 所以分两步：先走原版 kill（走得通就保留正常的掉落、死亡事件与击杀归属），
     * 再检查目标是否还活着；还活着就直接把血清零并手动进死亡流程。
     * <p>
     * 用 {@code die()} 而不是 {@code remove()}：掉落、死亡消息、战斗记录、死亡事件都在
     * {@code die()} 里，掉落增强与斩首那些功能依赖它们；{@code die()} 内部会判
     * {@code isRemoved() || dead}，重复调用是安全的。
     * <p>
     * 最后那道 {@code remove()} 只兜"连 die() 都被模组改写"的极端情况 ——
     * 到这一步已经不可能再靠原版接口杀掉它了，宁可不要掉落也不能让它继续挡路。
     */
    public static void killIgnoringImmunity(LivingEntity target, DamageSource source) {
        if (target.isRemoved() || !target.isAlive()) {
            return;
        }
        target.kill();
        if (!target.isAlive() || target.isRemoved()) {
            return;
        }
        LOGGER.debug("[KILL] kill() 被拦下，改为直接结算死亡 target={} health={}",
                target.getType(), target.getHealth());
        target.setHealth(0.0F);
        target.die(source);
        if (!target.isRemoved() && target.isAlive()) {
            target.remove(Entity.RemovalReason.KILLED);
        }
    }

    /**
     * 秒杀用的伤害来源：带上攻击者，死亡消息与击杀归属才是对的。
     * <p>
     * 原版 {@code kill()} 走的是没有来源实体的 genericKill，死亡信息里写不出凶手；
     * 既然这第二步是我们自己结算死亡，就顺手把来源补上。
     */
    public static DamageSource swordKillSource(LivingEntity attacker) {
        return attacker instanceof Player player
                ? player.damageSources().playerAttack(player)
                : attacker.damageSources().mobAttack(attacker);
    }

    public enum AreaClearTargetMode {
        HOSTILE(0, "screen.kunjinkao.area_clear_hostile") {
            @Override
            boolean matches(Mob mob) { return mob instanceof Enemy; }
        },
        FRIENDLY(1, "screen.kunjinkao.area_clear_friendly") {
            @Override
            boolean matches(Mob mob) { return !(mob instanceof Enemy); }
        },
        ALL(2, "screen.kunjinkao.area_clear_all") {
            @Override
            boolean matches(Mob mob) { return true; }
        },
        PLAYERS(3, "screen.kunjinkao.area_clear_players") {
            @Override
            boolean matches(Mob mob) { return false; }
            @Override
            boolean targetsPlayers() { return true; }
        };

        private final int id;
        private final String translationKey;

        AreaClearTargetMode(int id, String translationKey) {
            this.id = id;
            this.translationKey = translationKey;
        }

        public int id() { return id; }
        public String translationKey() { return translationKey; }
        abstract boolean matches(Mob mob);
        boolean targetsPlayers() { return false; }

        public static AreaClearTargetMode byId(int id) {
            for (AreaClearTargetMode mode : values()) {
                if (mode.id == id) return mode;
            }
            return HOSTILE;
        }
    }
}
