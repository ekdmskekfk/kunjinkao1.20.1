package dev.modmind.kunjinkao.wand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认取材器，对齐 Construction Wand KOTS 的 SupplierInventory。
 * <p>
 * 规则（与参考实现一致）：
 * <ol>
 *   <li><b>副手优先</b>：副手拿着可放置方块时，这次就只用副手那一种（等于"指定材料"）；</li>
 *   <li>否则用<b>点击的那个方块本身</b>，且在 match 不是 EXACT 时把配置里的同类方块也当成备选；</li>
 *   <li>每一种都要数得出个数；数不出来的根本不进池子；</li>
 *   <li>每挑走一格就把余量减一，减到 0 就移出池子。</li>
 * </ol>
 * 数量口径见 {@link WandUtil#countItem}：背包 + 副手 + 潜影盒/收纳袋 + AE2 终端 + EMC。
 */
public class SupplierInventory implements IWandSupplier {

    protected final Player player;
    protected final ItemStack wand;
    protected final Map<BlockItem, Long> counts = new LinkedHashMap<>();
    protected IPool<BlockItem> pool;

    public SupplierInventory(Player player, ItemStack wand) {
        this.player = player;
        this.wand = wand;
    }

    @Override
    public void getSupply(@Nullable BlockItem target) {
        counts.clear();
        pool = new OrderedPool<>();
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof BlockItem offhandItem) {
            addItem(offhandItem);
        } else if (target != null && target != Items.AIR) {
            addItem(target);
            if (WandOptions.match(wand) != WandOptions.Match.EXACT) {
                for (Item similar : ReplacementRegistry.matchingSet(target)) {
                    if (similar instanceof BlockItem similarBlock) {
                        addItem(similarBlock);
                    }
                }
            }
        }
    }

    protected void addItem(BlockItem item) {
        long count = WandUtil.countItem(player, item);
        if (count > 0L) {
            counts.put(item, count);
            pool.add(item);
        }
    }

    @Nullable
    @Override
    public PlaceSnapshot placeSnapshot(Level level, BlockPos pos, BlockHitResult hit,
                                       @Nullable BlockState supportingBlock) {
        if (!WandUtil.isPositionPlaceable(level, player, pos, WandOptions.replace(wand))) {
            return null;
        }
        pool.reset();
        while (true) {
            BlockItem item = pool.draw();
            if (item == null) {
                return null;
            }
            long available = counts.getOrDefault(item, 0L);
            if (available <= 0L) {
                continue;
            }
            PlaceSnapshot snapshot = PlaceSnapshot.create(level, player, hit, pos, item, supportingBlock, wand);
            if (snapshot == null) {
                continue;
            }
            long left = available - 1L;
            counts.put(item, left);
            if (left <= 0L) {
                pool.remove(item);
            }
            return snapshot;
        }
    }

    @Override
    public long take(ItemStack stack) {
        return WandUtil.takeItems(player, stack.getItem(), stack.getCount());
    }
}