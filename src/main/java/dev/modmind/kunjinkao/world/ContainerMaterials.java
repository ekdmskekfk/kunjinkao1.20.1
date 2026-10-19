package dev.modmind.kunjinkao.world;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 从<b>容器</b>里取材。
 * <p>
 * 对应建筑手杖的 containers 包（IContainerHandler / ContainerManager / HandlerShulkerbox）：
 * 那边把"容器"抽象成一个接口，凡是能装东西的物品都能注册进去（潜影盒、收纳袋，
 * 以及 AE2 无线终端、植物魔法、ProjectE 等等模组兼容）。
 * 这里只实现<b>原版</b>的两种 —— 潜影盒与收纳袋，其余模组兼容不做。
 * <p>
 * 关键点是：光"找到"不够，还得能<b>取走</b>。背包里的那一叠可以直接 shrink，
 * 装进潜影盒里的不行 —— 必须把容器内容重新写回去。
 * <p>
 * <b>1.20.1 的写法差异（数据模型换了，语义没变）：</b>
 * 1.21.1 用 {@code DataComponents.CONTAINER}（{@code ItemContainerContents}）与
 * {@code DataComponents.BUNDLE_CONTENTS}（{@code BundleContents}）；1.20.1 两者都是原始 NBT：
 * <ul>
 *   <li>潜影盒：物品栈的 {@code BlockEntityTag.Items} 列表；</li>
 *   <li>收纳袋：物品栈根上的 {@code Items} 列表。</li>
 * </ul>
 * 两者的列表元素都是 {@code ItemStack.save} 出来的复合标签（{@code id} / {@code Count} / 可选
 * {@code tag} / 潜影盒还多一个 {@code Slot}），所以这里用同一套"读列表 → 解析成 ItemStack →
 * 改数量 → 写回"的流程覆盖两种容器。改数量时直接改那个复合标签的 {@code Count}，
 * 而不是整体重编码：这样 {@code Slot} 之类的附加字段原样保留，不会把潜影盒的槽位排布打乱。
 */
public final class ContainerMaterials {

    /** 潜影盒内容所在的子标签名（方块实体存档）。 */
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    /** 物品列表名：潜影盒与收纳袋都用 {@code Items}。 */
    private static final String ITEMS_KEY = "Items";

    private ContainerMaterials() {
    }

    /** 玩家身上（背包 + 副手 + 潜影盒 + 收纳袋）共有多少个这一种可放置方块。 */
    public static int count(Player player, Item item) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            total += countIn(inventory.getItem(i), item);
        }
        for (ItemStack offhand : player.getInventory().offhand) {
            total += countIn(offhand, item);
        }
        return total;
    }

    /** 某一个物品里有多少个目标方块：它自己算一份，容器再往里翻一层。 */
    private static int countIn(ItemStack stack, Item item) {
        if (stack.isEmpty()) {
            return 0;
        }
        int total = stack.getItem() == item ? stack.getCount() : 0;
        for (ItemStack inner : contentsOf(stack)) {
            if (inner.getItem() == item) {
                total += inner.getCount();
            }
        }
        return total;
    }

    /** 上述任意位置是否存在这一种方块。 */
    public static boolean has(Player player, Item item) {
        return count(player, item) > 0;
    }

    /**
     * 取走一个目标方块：先背包、再副手、最后容器。
     *
     * @return 真取到了才返回 true
     */
    public static boolean consumeOne(Player player, Item item) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (shrinkDirect(inventory.getItem(i), item)) {
                return true;
            }
        }
        for (ItemStack offhand : player.getInventory().offhand) {
            if (shrinkDirect(offhand, item)) {
                return true;
            }
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (takeFromContainer(inventory.getItem(i), item)) {
                return true;
            }
        }
        for (ItemStack offhand : player.getInventory().offhand) {
            if (takeFromContainer(offhand, item)) {
                return true;
            }
        }
        return false;
    }

    /** 这一叠本身就是目标方块时直接减一。 */
    private static boolean shrinkDirect(ItemStack stack, Item item) {
        if (!stack.isEmpty() && stack.getItem() == item) {
            stack.shrink(1);
            return true;
        }
        return false;
    }

    /** 从这一叠所代表的容器里取一个；取到了容器内容就已经就地改好了。 */
    private static boolean takeFromContainer(ItemStack container, Item item) {
        if (container.isEmpty()) {
            return false;
        }
        ListTag items = itemList(container);
        if (items == null) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            ItemStack inner = ItemStack.of(entry);
            if (inner.isEmpty() || inner.getItem() != item) {
                continue;
            }
            int count = inner.getCount();
            if (count > 1) {
                // 只改数量，其余字段（潜影盒的 Slot、自定义 tag）原样保留。
                entry.putByte("Count", (byte) (count - 1));
            } else {
                items.remove(i);
            }
            return true;
        }
        return false;
    }

    /** 取这一叠容器里所有能看到的物品（只用于计数）。 */
    private static Iterable<ItemStack> contentsOf(ItemStack stack) {
        ListTag items = itemList(stack);
        if (items == null) {
            return List.of();
        }
        List<ItemStack> list = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ItemStack inner = ItemStack.of(items.getCompound(i));
            if (!inner.isEmpty()) {
                list.add(inner);
            }
        }
        return list;
    }

    /**
     * 这一叠所代表的容器的物品列表：潜影盒取 {@code BlockEntityTag.Items}，
     * 收纳袋取根上的 {@code Items}；都不是容器时返回 null。
     */
    private static ListTag itemList(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        CompoundTag blockEntityTag = BlockItem.getBlockEntityData(stack);
        if (blockEntityTag != null && blockEntityTag.contains(ITEMS_KEY, Tag.TAG_LIST)) {
            return blockEntityTag.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BundleItem && stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains(ITEMS_KEY, Tag.TAG_LIST)) {
                return tag.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
            }
        }
        return null;
    }

    /**
     * 在玩家身上的容器（潜影盒 / 收纳袋）里找一叠可用的方块。
     *
     * @param required 只接受这一种物品；为 null 表示任意可放置的方块
     * @return 找到的那一叠（<b>容器内</b>的栈，只用于读出物品与数量，不要直接 shrink）
     */
    public static ItemStack findInContainers(Player player, Item required) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack found = findIn(inventory.getItem(i), required);
            if (!found.isEmpty()) {
                return found;
            }
        }
        for (ItemStack offhand : player.getInventory().offhand) {
            ItemStack found = findIn(offhand, required);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findIn(ItemStack container, Item required) {
        if (container.isEmpty() || !isContainer(container)) {
            return ItemStack.EMPTY;
        }
        for (ItemStack inner : contentsOf(container)) {
            if (inner.isEmpty()) {
                continue;
            }
            boolean placeable = inner.getItem() instanceof BlockItem;
            if (placeable && (required == null || inner.getItem() == required)) {
                return inner;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 这一叠是不是"能让建筑手杖取材"的容器（潜影盒或收纳袋）。 */
    public static boolean isContainer(ItemStack stack) {
        return itemList(stack) != null;
    }

    /** 供外部判断：这一叠里是否存在可放置的方块（任意一种）。 */
    public static boolean hasAnyPlaceable(Player player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (containsPlaceable(inventory.getItem(i))) {
                return true;
            }
        }
        for (ItemStack offhand : player.getInventory().offhand) {
            if (containsPlaceable(offhand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPlaceable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BlockItem) {
            return true;
        }
        for (ItemStack inner : contentsOf(stack)) {
            if (inner.getItem() instanceof BlockItem) {
                return true;
            }
        }
        return false;
    }
}