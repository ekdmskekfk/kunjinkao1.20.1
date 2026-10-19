package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.config.AdminToolConfig;
import dev.modmind.kunjinkao.world.Ae2Materials;
import dev.modmind.kunjinkao.world.ContainerMaterials;
import dev.modmind.kunjinkao.world.EmcMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 手杖的世界操作与取材，对齐 Construction Wand KOTS 的 basics.WandUtil。
 * <p>
 * 与旧实现最大的区别：<b>不再借用原版 BlockItem.place</b>，而是直接
 * {@code setBlockAndUpdate} 自己落方块，然后派发原版的两个事件
 * （{@code EntityPlaceEvent} / {@code BreakEvent}），让保护类模组仍能拦截。
 * 参考实现就是这么做的 —— 它必须这么做，因为一次要放几十上百格，
 * 走原版放置流程既慢又没法逐个控制材料消耗。
 * <p>
 * 材料消耗因此独立出来：{@link #takeItems} 负责真正从背包 / 容器 / AE2 终端 / EMC 里扣。
 */
public final class WandUtil {

    private WandUtil() {
    }

    // ===================== 世界操作 =====================

    /**
     * 放一个方块，并派发原版的放置事件。
     * <p>
     * 顺序与参考实现一致：先落方块，再拿落好的快照发 {@code EntityPlaceEvent}；
     * 被取消就把方块撤掉并返回 false。{@code setPlacedBy} 与统计都在事件通过之后才做。
     *
     * @param item 这次用的是哪一种物品；为 null 表示按方块反推（撤销恢复就走这条路）
     */
    public static boolean placeBlock(Level level, Player player, BlockState state, BlockPos pos,
                                     @Nullable BlockItem item) {
        if (!level.setBlockAndUpdate(pos, state)) {
            return false;
        }
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(snapshot, state, player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            level.removeBlock(pos, false);
            return false;
        }
        ItemStack placedWith;
        if (item == null) {
            placedWith = new ItemStack(state.getBlock().asItem());
        } else {
            placedWith = new ItemStack(item);
            player.awardStat(Stats.ITEM_USED.get(item));
        }
        state.getBlock().setPlacedBy(level, pos, state, player, placedWith);
        return true;
    }

    /**
     * 挖掉一个方块，并派发原版的破坏事件。
     * <p>
     * 生存模式下额外两条限制（参考实现的 removeBlock）：硬度为负（基岩）或带方块实体的方块不碰；
     * {@code expected} 给了的话还要求当前方块与它同类（撤销放置时用得上）。
     * 创造模式下这些限制都不适用。
     */
    public static boolean removeBlock(Level level, Player player, @Nullable BlockState expected, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!level.mayInteract(player, pos)) {
            return false;
        }
        if (!player.isCreative()) {
            if (current.getDestroySpeed(level, pos) <= -1.0F || level.getBlockEntity(pos) != null) {
                return false;
            }
            if (expected != null && !ReplacementRegistry.matchBlocks(current.getBlock(), expected.getBlock())) {
                return false;
            }
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, current, player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return false;
        }
        level.removeBlock(pos, false);
        return true;
    }

    /** 位置是否可被这名玩家改动：世界边界内、有权限、且在最大距离内。 */
    public static boolean isPositionModifiable(Level level, Player player, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) {
            return false;
        }
        if (!level.mayInteract(player, pos)) {
            return false;
        }
        int maxRange = AdminToolConfig.wandMaxRange();
        if (maxRange <= 0) {
            return true;
        }
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= maxRange;
    }

    /**
     * 这一格能不能放：先把"可改动"走一遍，然后要么是空气，要么允许替换且目标方块自己说可以替换。
     * <p>
     * 参考实现传的是一个 {@code Direction.DOWN} 的假命中点 —— 可替换判定对草、雪层这类方块
     * 只关心"是不是同一块"，朝向不影响，照搬即可。
     */
    public static boolean isPositionPlaceable(Level level, Player player, BlockPos pos, boolean replace) {
        if (!isPositionModifiable(level, player, pos)) {
            return false;
        }
        if (level.isEmptyBlock(pos)) {
            return true;
        }
        if (!replace) {
            return false;
        }
        BlockHitResult fakeHit = new BlockHitResult(new Vec3(0.0D, 0.0D, 0.0D), Direction.DOWN, pos, false);
        return level.getBlockState(pos)
                .canBeReplaced(new WandItemUseContext(level, player, fakeHit, pos, (BlockItem) Items.STONE));
    }

    /** 这一格能不能被破坏：创造模式一律可以，生存模式要求硬度不为负且没有方块实体。 */
    public static boolean isBlockRemovable(Level level, Player player, BlockPos pos) {
        if (!isPositionModifiable(level, player, pos)) {
            return false;
        }
        if (player.isCreative()) {
            return true;
        }
        return level.getBlockState(pos).getDestroySpeed(level, pos) > -1.0F && level.getBlockEntity(pos) == null;
    }

    /** 这一格是不是"透的"：空气，或者没有碰撞箱（草、火把这类）。 */
    public static boolean isBlockPermeable(Level level, BlockPos pos) {
        return level.isEmptyBlock(pos) || level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 这个方块的碰撞箱里有没有活着的实体（排除旁观者）—— 有就不放，免得把生物卡进墙里。 */
    public static boolean entitiesCollidingWithBlock(Level level, BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds().move(pos);
        return !level.getEntitiesOfClass(LivingEntity.class, box, Predicate.not(Entity::isSpectator)).isEmpty();
    }

    /**
     * 这个带方块实体的方块允不允许手杖碰 —— 对齐参考实现的 isTEAllowed。
     * <p>
     * 名单里可以写方块 id，也可以写模组 id；{@code teWhitelist} 决定它是白名单还是黑名单。
     * 默认是黑名单，且名单里只有 chiselsandbits（参考实现的默认值）。
     */
    public static boolean isTEAllowed(BlockState state) {
        if (!state.hasBlockEntity()) {
            return true;
        }
        ResourceLocation name = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (name == null) {
            return false;
        }
        List<? extends String> list = AdminToolConfig.wandTeList();
        boolean inList = list.contains(name.toString()) || list.contains(name.getNamespace());
        return AdminToolConfig.wandTeWhitelist() == inList;
    }

    // ===================== 取材 =====================

    /** 这一种方块现在总共还能拿出多少个。创造模式给 Long.MAX_VALUE（用不完）。 */
    public static long countItem(Player player, Item item) {
        if (player.isCreative()) {
            return Long.MAX_VALUE;
        }
        long total = ContainerMaterials.count(player, item);
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (Ae2Materials.isTerminal(stack)) {
                total += Ae2Materials.count(player, stack, item, Integer.MAX_VALUE);
            }
        }
        for (ItemStack offhand : inventory.offhand) {
            if (Ae2Materials.isTerminal(offhand)) {
                total += Ae2Materials.count(player, offhand, item, Integer.MAX_VALUE);
            }
        }
        total += EmcMaterials.availableEmcCount(player, item);
        return total;
    }

    /**
     * 真正取走 want 个。
     *
     * @return <b>还差多少个</b>：返回 0 表示取满了（参考实现正是拿 0 当成功标志）
     */
    public static long takeItems(Player player, Item item, long want) {
        if (want <= 0L) {
            return 0L;
        }
        if (player.isCreative()) {
            return 0L;
        }
        long remaining = want;
        // 先身上（背包 -> 副手 -> 潜影盒 / 收纳袋）。
        while (remaining > 0L && ContainerMaterials.consumeOne(player, item)) {
            remaining--;
        }
        // 再 AE2 终端类物品。
        if (remaining > 0L) {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize() && remaining > 0L; i++) {
                ItemStack stack = inventory.getItem(i);
                if (Ae2Materials.isTerminal(stack)) {
                    remaining -= Ae2Materials.extract(player, stack, item, remaining);
                }
            }
            for (ItemStack offhand : inventory.offhand) {
                if (remaining <= 0L) {
                    break;
                }
                if (Ae2Materials.isTerminal(offhand)) {
                    remaining -= Ae2Materials.extract(player, offhand, item, remaining);
                }
            }
        }
        // 最后 ProjectE 的 EMC。
        if (remaining > 0L) {
            long emc = EmcMaterials.availableEmcCount(player, item);
            if (emc > 0L) {
                long usable = Math.min(emc, remaining);
                if (usable > 0L && EmcMaterials.spend(player, item, (int) Math.min(usable, Integer.MAX_VALUE))) {
                    remaining -= usable;
                }
            }
        }
        return remaining;
    }

    /** 快捷栏 + 副手。随机取材只在这一段里挑，对齐参考实现的 SupplierRandom。 */
    public static List<ItemStack> hotbarWithOffhand(Player player) {
        List<ItemStack> list = new ArrayList<>(player.getInventory().items.subList(0, 9));
        list.addAll(player.getInventory().offhand);
        return list;
    }

    public static boolean isPlaceable(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    // ===================== 几何 =====================

    /** 眼睛高度那个点（参考实现拿它算空中放置的落点）。 */
    public static Vec3 entityPositionVec(Entity entity) {
        return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() / 2.0D, entity.getZ());
    }

    public static Vec3 blockPosVec(BlockPos pos) {
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos posFromVec(Vec3 vec) {
        return new BlockPos((int) Math.round(vec.x), (int) Math.round(vec.y), (int) Math.round(vec.z));
    }

    public static Direction fromVector(Vec3 vector) {
        return Direction.getNearest(vector.x, vector.y, vector.z);
    }
}