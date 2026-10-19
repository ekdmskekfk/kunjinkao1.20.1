package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SIMILAR 口径下的"同类方块组"，对齐 Construction Wand KOTS 的 ReplacementRegistry。
 * <p>
 * 组由配置 {@code [wand] similarBlocks} 定义：一组一行，组内用分号隔开。
 * 默认只有泥土家族一组（与参考实现一致）。
 * <p>
 * 配置重载后列表会换成新实例，于是这里自动重建 —— 改完配置不用重启游戏。
 */
public final class ReplacementRegistry {

    private static final List<Set<Item>> GROUPS = new ArrayList<>();
    private static List<? extends String> builtFrom;

    private ReplacementRegistry() {
    }

    private static void ensureBuilt() {
        List<? extends String> current = AdminToolConfig.wandSimilarBlocks();
        if (current == builtFrom) {
            return;
        }
        builtFrom = current;
        GROUPS.clear();
        for (String entry : current) {
            if (entry == null) {
                continue;
            }
            Set<Item> group = new HashSet<>();
            for (String id : entry.split(";")) {
                ResourceLocation key = ResourceLocation.tryParse(id.trim());
                if (key == null) {
                    continue;
                }
                Item item = BuiltInRegistries.ITEM.get(key);
                if (item != Items.AIR) {
                    group.add(item);
                }
            }
            if (!group.isEmpty()) {
                GROUPS.add(group);
            }
        }
    }

    /** 与这一种同组的其它物品（不含它自己）。取材池用它把同类方块算成备选。 */
    public static Set<Item> matchingSet(Item item) {
        ensureBuilt();
        Set<Item> result = new HashSet<>();
        for (Set<Item> group : GROUPS) {
            if (group.contains(item)) {
                result.addAll(group);
            }
        }
        result.remove(item);
        return result;
    }

    /** 两个方块是不是同一组。同一个方块永远算同组。 */
    public static boolean matchBlocks(Block first, Block second) {
        if (first == second) {
            return true;
        }
        if (first == Blocks.AIR || second == Blocks.AIR) {
            return false;
        }
        ensureBuilt();
        Item firstItem = first.asItem();
        Item secondItem = second.asItem();
        for (Set<Item> group : GROUPS) {
            if (group.contains(firstItem) && group.contains(secondItem)) {
                return true;
            }
        }
        return false;
    }
}