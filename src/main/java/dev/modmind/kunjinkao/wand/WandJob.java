package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.world.PlacementUndoHistory;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次手杖作业，对齐 Construction Wand KOTS 的 WandJob。
 * <p>
 * 它是整个手杖功能的中枢：构造时决定用哪个核心、哪套取材规则；
 * {@link #compute()} 算出这次会动哪些格子；{@link #execute()} 真正执行并扣材料。
 * <b>预览与右键走的是同一个对象、同一份结果</b> —— 这是"预览不骗人"的根本保证。
 * <p>
 * 与参考实现的两处差异（都是本模组结构决定的）：
 * <ul>
 *   <li>没有"手杖耐久"这一层，上限直接取服务器配置（参考实现要跟手杖耐久取 min）；</li>
 *   <li>执行结果转成 {@link PlacementUndoHistory.Change} 交给既有的撤销栈，
 *       而不是参考实现自己那套 UndoHistory。</li>
 * </ul>
 */
public final class WandJob {

    private final Player player;
    private final Level level;
    private final BlockHitResult hit;
    private final ItemStack wand;
    private final WandCore core;
    private final IWandAction action;
    private final IWandSupplier supplier;
    private List<ISnapshot> snapshots = new ArrayList<>();

    public WandJob(Player player, Level level, BlockHitResult hit, ItemStack wand) {
        this.player = player;
        this.level = level;
        this.hit = hit;
        this.wand = wand;
        this.core = WandCore.active(wand);
        if (core == null) {
            this.action = null;
            this.supplier = null;
            return;
        }
        this.action = core.action();
        this.supplier = WandOptions.random(wand)
                ? new SupplierRandom(player, wand)
                : new SupplierInventory(player, wand);
        this.supplier.getSupply(targetItem(level, hit));
    }

    /** 剑上三个手杖开关都关着时为 false —— 调用方据此把这次右键交回原版。 */
    public boolean isActive() {
        return action != null;
    }

    /** 命中处方块对应的物品；不是方块物品时返回 null。 */
    @Nullable
    private static BlockItem targetItem(Level level, BlockHitResult hit) {
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        Item item = level.getBlockState(hit.getBlockPos()).getBlock().asItem();
        return item instanceof BlockItem blockItem ? blockItem : null;
    }

    /** 算出这次会动哪些格子。预览与执行都调它。 */
    public void compute() {
        snapshots = new ArrayList<>();
        if (action == null || supplier == null) {
            return;
        }
        int limit = core.limit(player, wand);
        if (limit <= 0) {
            return;
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            snapshots = action.snapshots(level, player, hit, wand, supplier, limit);
        } else {
            snapshots = action.snapshotsFromAir(level, player, hit, wand, supplier, limit);
        }
    }

    public List<ISnapshot> snapshots() {
        return snapshots;
    }

    /** 预览只发坐标，所以这里给出去重的坐标集合。 */
    public Set<BlockPos> blockPositions() {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (ISnapshot snapshot : snapshots) {
            positions.add(snapshot.getPos());
        }
        return positions;
    }

    public int blockCount() {
        return snapshots.size();
    }

    /**
     * 执行这次作业。
     *
     * @return 这一步的撤销记录；空列表表示一格都没动成
     */
    public List<PlacementUndoHistory.Change> execute() {
        List<PlacementUndoHistory.Change> changes = new ArrayList<>();
        BlockState soundSource = null;
        for (ISnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            BlockState before = level.getBlockState(pos);
            if (!snapshot.execute(level, player, hit)) {
                continue;
            }
            ItemStack required = snapshot.getRequiredItems();
            if (!required.isEmpty()
                    && WandUtil.takeItems(player, required.getItem(), required.getCount()) != 0L) {
                // 材料没扣成：把刚放下去的那一格撤回去，不能白送方块。
                snapshot.forceRestore(level);
                continue;
            }
            changes.add(new PlacementUndoHistory.Change(pos, before,
                    required.isEmpty() ? ItemStack.EMPTY : required.copyWithCount(1)));
            if (soundSource == null) {
                soundSource = snapshot.getBlockState();
            }
        }
        if (!changes.isEmpty() && soundSource != null) {
            // 整批只播一次：逐格播会吵成一片（与参考实现一致，位置取玩家脚下）。
            SoundType sound = soundSource.getSoundType();
            level.playSound(null, player.blockPosition(), sound.getPlaceSound(), SoundSource.BLOCKS,
                    sound.volume, sound.pitch);
        }
        return changes;
    }
}