package dev.modmind.kunjinkao.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/**
 * 从 <b>AE2 的无线终端 / 便携元件</b>里取材。
 * <p>
 * 对应参考实现的 HandlerWirelessTerminal 与 HandlerPortableCell：
 * 它们把网络存储当作"容器"，从里面抽取方块。
 * <pre>
 *   MEStorage storage = host.getInventory();
 *   AEItemKey key = AEItemKey.of(itemStack);
 *   storage.extract(key, amount, Actionable.MODULATE, IActionSource.ofPlayer(player));
 * </pre>
 * 这里走更短的一条路：AE2 的物品只要实现 {@code IMenuItem}，就能
 * {@code getMenuHost(player, slot, stack, pos)} 拿到 Host，再从 Host 取 {@code MEStorage}。
 * <p>
 * 全部走反射，没装 AE2 时整条路径静默关闭。
 * <p>
 * <b>1.20.1 的关键差异（AE2 的 API 换代了，这条必须按本机 jar 实测）：</b>
 * 1.21.1 侧的 AE2 是 {@code getMenuHost(Player, ItemMenuHostLocator, BlockHitResult)}
 * 且 locator 由 {@code MenuLocators.forStack(stack)} 构造；而 <b>AE2 15.4.11（1.20.1）</b>是
 * <pre>
 *   ItemMenuHost getMenuHost(Player player, int inventorySlot, ItemStack stack, BlockPos pos)
 * </pre>
 * 没有 {@code forStack}，索引就是玩家背包槽位号。所以这里改成：
 * 先在背包里找到这一叠所在的槽位，再把槽位号传进去；第 4 个参数（方块位置）传 null。
 * {@code getInventory()} 仍然只在 {@code WirelessTerminalMenuHost} /
 * {@code PortableCellMenuHost} 这些子类上，所以还是到运行时类上去找。
 */
public final class Ae2Materials {

    private static final String MENU_ITEM = "appeng.api.implementations.menuobjects.IMenuItem";
    private static final String AE_ITEM_KEY = "appeng.api.stacks.AEItemKey";
    private static final String ACTION_SOURCE = "appeng.api.networking.security.IActionSource";
    private static final String ACTIONABLE = "appeng.api.config.Actionable";

    private static boolean resolved;
    private static Method getMenuHost;

    private static Method aeItemKeyOf;
    private static Method extract;
    private static Method actionSourceOfPlayer;
    private static Object actionableModulate;
    private static Object actionableSimulate;

    private Ae2Materials() {
    }

    private static synchronized boolean ensureResolved() {
        if (resolved) {
            return extract != null;
        }
        resolved = true;
        try {
            Class<?> menuItem = Class.forName(MENU_ITEM);
            // AE2 15.4.11：getMenuHost(Player, int inventorySlot, ItemStack, BlockPos)
            getMenuHost = menuItem.getMethod("getMenuHost", Player.class, int.class,
                    ItemStack.class, BlockPos.class);
            // getInventory 不在基类 ItemMenuHost 上，而在
            // WirelessTerminalMenuHost / PortableCellMenuHost 这些子类上 ——
            // 所以只能到运行时类上去找，不能在这里预先解析。
            aeItemKeyOf = Class.forName(AE_ITEM_KEY).getMethod("of", ItemStack.class);
            Class<?> storage = Class.forName("appeng.api.storage.MEStorage");
            Class<?> actionable = Class.forName(ACTIONABLE);
            extract = storage.getMethod("extract", Class.forName("appeng.api.stacks.AEKey"), long.class,
                    actionable, Class.forName(ACTION_SOURCE));
            actionSourceOfPlayer = Class.forName(ACTION_SOURCE).getMethod("ofPlayer", Player.class);
            actionableModulate = enumConstant(actionable, "MODULATE");
            actionableSimulate = enumConstant(actionable, "SIMULATE");
        } catch (ReflectiveOperationException | LinkageError e) {
            extract = null;
        }
        return extract != null;
    }

    private static Object enumConstant(Class<?> enumType, String name) {
        for (Object constant : enumType.getEnumConstants()) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return null;
    }

    /** 这一叠物品是不是 AE2 的"终端类"（实现了 IMenuItem，能拿出网络存储）。 */
    public static boolean isTerminal(ItemStack stack) {
        if (!ensureResolved() || stack.isEmpty()) {
            return false;
        }
        try {
            return Class.forName(MENU_ITEM).isInstance(stack.getItem());
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 从这一叠终端里试着抽出 {@code amount} 个该物品。
     *
     * @return 实际抽到的数量（0 表示这个终端里没有或抽不动）
     */
    public static long extract(Player player, ItemStack terminal, Item item, long amount) {
        return transfer(player, terminal, item, amount, actionableModulate);
    }

    /** 非破坏性计数：用 SIMULATE 干跑一次，看这个终端里有多少。 */
    public static long count(Player player, ItemStack terminal, Item item, long amount) {
        return transfer(player, terminal, item, amount, actionableSimulate);
    }

    private static long transfer(Player player, ItemStack terminal, Item item, long amount, Object actionable) {
        if (!ensureResolved() || amount <= 0 || actionable == null || terminal.isEmpty()) {
            return 0L;
        }
        try {
            // AE2 15.4.11 要的是槽位号：先在背包里把这一叠找出来。
            int slot = findSlot(player, terminal);
            if (slot < 0) {
                return 0L;
            }
            Object host = getMenuHost.invoke(terminal.getItem(), player, slot, terminal, null);
            if (host == null) {
                return 0L;
            }
            Object storage = invokeGetInventory(host);
            if (storage == null) {
                return 0L;
            }
            Object key = aeItemKeyOf.invoke(null, new ItemStack(item));
            Object source = actionSourceOfPlayer.invoke(null, player);
            Object taken = extract.invoke(storage, key, amount, actionable, source);
            return taken instanceof Long value ? value : 0L;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0L;
        }
    }

    /** 在玩家背包（含副手）里定位这一叠的身份；找不到返回 -1。 */
    private static int findSlot(Player player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i) == stack) {
                return i;
            }
        }
        return -1;
    }

    /** 到运行时类（含父类）上找 getInventory()：基类 ItemMenuHost 并没有这个方法。 */
    private static Object invokeGetInventory(Object host) throws ReflectiveOperationException {
        for (Class<?> type = host.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getMethod("getInventory");
                return method.invoke(host);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找。
            }
        }
        return null;
    }
}