package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 在原版矿石掉落完成后补充额外掉落，使管理员剑的矿石掉落达到设置的倍数。
 * 不取消原有破坏流程，因此经验、进度、保护插件和其他方块逻辑保持原样。
 */
public final class KunJinKaoOreDropHandler {

    /** 临时诊断日志：定位"倍率不生效"卡在哪个分支，定位完删除。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("KunjinKaoOreDropDiag");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        LOGGER.info("[OreDropDiag] onBlockBreak 触发 state={} canceled={} level={} player={}",
                event.getState(), event.isCanceled(), event.getLevel().getClass().getSimpleName(),
                event.getPlayer() == null ? "null" : event.getPlayer().getClass().getSimpleName());
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack tool = player.getMainHandItem();
        if (!(KunJinKaoSwordItem.isSwordLike(tool))) {
            LOGGER.info("[OreDropDiag] 手里不是注入物品/剑: {}", tool);
            return;
        }

        BlockState state = event.getState();
        if (!isOre(state)) {
            LOGGER.info("[OreDropDiag] 不是矿石: {}", state);
            return;
        }

        int multiplier = KunJinKaoSwordItem.getOreDropMultiplier(tool);
        if (multiplier <= 1) {
            LOGGER.info("[OreDropDiag] 倍率<=1: {}", multiplier);
            return;
        }

        BlockPos pos = event.getPos().immutable();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> rawDrops = Block.getDrops(state, level, pos, blockEntity, player, tool);
        if (rawDrops.isEmpty()) {
            LOGGER.info("[OreDropDiag] 原版掉落为空: {}", state);
            return;
        }
        // 开了挖掘熔炼时，补发的这一份也必须是熔炼产物。
        // 正常那一路（BlockDropsEvent）已经烧过了，这里不跟着烧的话，
        // 同一个开关会挖出"一半是锭、一半是原矿"，看起来像随机 bug。
        final List<ItemStack> baseDrops = KunJinKaoAutoSmeltHandler.smeltList(level, tool, rawDrops);

        // BreakEvent 在原版放出掉落之前触发。这里直接补发 multiplier - 1 份。
        //
        // 1.20.1 上不能再用「排进任务队列、等方块变成空气再补」那套：
        // MinecraftServer#execute 在服务端线程上是**立即执行**的，而 BreakEvent 又排在
        // 方块被移除之前 —— 那一刻 isAir() 还是 false，补发永远被跳过，
        // 表现为「矿石倍率怎么调都没效果」。（1.21.1 的时序不同，所以那边没暴露。）
        //
        // 安全性：本处理器是 LOWEST 优先级、且前面已判过 isCanceled，走到这里说明
        // 这一下确实被允许破坏；即使后续有别的 mod 又取消了破坏，最坏结果是多掉几份，
        // 不会导致复制物品。
        LOGGER.info("[OreDropDiag] 补发 multiplier={} baseDrops={}", multiplier, baseDrops.size());
        // 1.20.1 上 BreakEvent 路径不可靠（方块尚未移除、execute 又是立即执行），
        // 改由 BlockPlayerDestroyMixin 在 playerDestroy 之后接管，这里不再补发。
        for (ItemStack baseDrop : baseDrops) {
            spawnExtraStacks(level, pos, baseDrop, multiplier - 1);
        }
    }

    private static void spawnExtraStacks(ServerLevel level, BlockPos pos, ItemStack baseDrop, int copies) {
        int totalCount = baseDrop.getCount() * copies;
        while (totalCount > 0) {
            int count = Math.min(totalCount, baseDrop.getMaxStackSize());
            ItemStack extra = baseDrop.copy();
            extra.setCount(count);
            Block.popResource(level, pos, extra);
            totalCount -= count;
        }
    }

    /** 方块 id 里"独立的 ore 词"。用词边界，避免 core / store / shore 这类被误判成矿石。 */
    private static final Pattern ORE_IN_ID = Pattern.compile("(^|_)ore(_|$)");

    /**
     * 这一块算不算矿石。
     * <p>
     * <b>不能只看 {@code c:ores}。</b> 相当一部分模组只把矿石挂在<b>子标签</b>上：
     * AllTheModium 3.2.1 就是典型 —— jar 里有
     * {@code data/c/tags/block/ores/allthemodium.json} 与同级的 vibranium / unobtainium，
     * 却<b>没有</b> {@code data/c/tags/block/ores.json}，所以 {@code c:ores} 里查不到它的矿石，
     * 表现就是"这个模组的矿石无法被翻倍"。
     * <p>
     * 判定顺序（从最正典到最兜底）：
     * <ol>
     *   <li>{@code c:ores}：NeoForge 约定的正典标签；</li>
     *   <li>任何一个像矿石的方块标签：{@code c:ores/<材料>}、{@code c:ores_in_ground/<岩石>}、
     *       原版的 {@code minecraft:*_ores} 都落在这里；</li>
     *   <li>方块 id 里带独立的 ore 词：给完全不挂标签的模组兜底
     *       （{@code allthemodium:vibranium_ore}、{@code mod:deepslate_x_ore} 这类）。</li>
     * </ol>
     * 第三条是启发式，会有误报的可能（例如某个叫 {@code ore_crusher} 的机器方块）；
     * 但它的代价只是"多掉几份"，而漏判的代价是"这个功能对这个模组完全没用"，
     * 两边权衡下来宁可宽一点。
     */
    public static boolean isOreBlock(BlockState state) {
        return isOre(state);
    }

    private static boolean isOre(BlockState state) {
        if (state.is(Tags.Blocks.ORES)) {
            return true;
        }
        if (state.getTags().anyMatch(tag -> looksLikeOreTag(tag.location()))) {
            return true;
        }
        return ORE_IN_ID.matcher(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()).find();
    }

    /**
     * 标签路径像不像"矿石标签"。
     * <p>
     * 覆盖到的形态（都是实机里见过的）：{@code ores}、{@code ores/allthemodium}、
     * {@code ores_in_ground/deepslate}、{@code iron_ores}（原版那批）、{@code foo/ores/bar}。
     */
    private static boolean looksLikeOreTag(ResourceLocation tag) {
        String path = tag.getPath();
        return path.equals("ores")
                || path.startsWith("ores/")
                || path.startsWith("ores_in_ground/")
                || path.endsWith("_ores")
                || path.contains("/ores/");
    }
}
