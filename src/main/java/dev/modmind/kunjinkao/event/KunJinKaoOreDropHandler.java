package dev.modmind.kunjinkao.event;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

import java.util.regex.Pattern;

/**
 * 矿石判定的唯一入口，供 {@code BlockPlayerDestroyMixin} 复用。
 * <p>
 * 1.20.1 没有 {@code BlockDropsEvent}；{@code BlockEvent.BreakEvent} 那条路
 * 在本整合包里实测从不触发（而且它在方块被移除之前触发，时序上也不合适）。
 * 所以"挖矿掉落翻倍"改由 {@code Block#playerDestroy} 的 mixin 接管 ——
 * 这里只保留判定：这一块算不算矿石。
 * <p>
 * 判定顺序（从最正典到最兜底）：
 * <ol>
 *   <li>{@code forge:ores}：Forge 1.20.1 的正典标签，即 {@link Tags.Blocks#ORES}；</li>
 *   <li>任何一个像矿石的方块标签：{@code forge:ores/<材料>}、{@code forge:ores_in_ground/<岩石>}、
 *       原版的 {@code minecraft:*_ores} 都落在这里；</li>
 *   <li>方块 id 里带独立的 ore 词：给完全不挂标签的模组兜底
 *       （{@code allthemodium:vibranium_ore}、{@code mod:deepslate_x_ore} 这类）。</li>
 * </ol>
 * 第三条是启发式，会有误报的可能（例如某个叫 {@code ore_crusher} 的机器方块）；
 * 但它的代价只是"多掉几份"，而漏判的代价是"这个功能对这个模组完全没用"，
 * 两边权衡下来宁可宽一点。
 */
public final class KunJinKaoOreDropHandler {

    private KunJinKaoOreDropHandler() {
    }

    /** 方块 id 里"独立的 ore 词"。用词边界，避免 core / store / shore 这类被误判成矿石。 */
    private static final Pattern ORE_IN_ID = Pattern.compile("(^|_)ore(_|$)");

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
