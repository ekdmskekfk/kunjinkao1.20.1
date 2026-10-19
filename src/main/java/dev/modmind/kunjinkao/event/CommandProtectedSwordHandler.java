package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Preserves administrator swords across destructive server-command execution.
 *
 * <p>Vanilla's {@code /clear}, {@code /item} and {@code /data} commands edit
 * player inventories directly, so an item class cannot veto the removal.  We
 * therefore take a server-side copy immediately before such a command runs and
 * restore any sword that really disappeared at the end of that server tick.</p>
 *
 * <p>历史实现有两个缺陷，这里一并修掉：
 * <ol>
 *   <li>对**每一条**命令（含 {@code /msg}、命令方块、告示牌点击、RCON）都对全体在线玩家扫一遍背包，
 *       开销是 O(命令数 × 玩家数 × 42 格)；</li>
 *   <li>结算时只数背包/盔甲/副手，于是"剑被放进箱子、合成格或光标"会被当成丢失而**凭空补发一把**，
 *       而且按列表下标恢复还可能还回不同 NBT 的剑。现在改为：只对可能动物品的命令做快照，
 *       结算时把打开容器/光标/附近掉落物也算作"还在"，并按物品身份逐个抵消而不是按下标补发。</li>
 * </ol>
 */
public final class CommandProtectedSwordHandler {
    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");

    /** 只有这些命令会直接改写玩家背包；用词边界匹配以兼容 {@code /execute ... run clear}。 */
    private static final Pattern DESTRUCTIVE_COMMAND =
            Pattern.compile("(^|\\s)([a-z0-9_.-]+:)?(clear|item|data)(\\s|$)");

    private static final Map<ServerPlayer, List<ItemStack>> PENDING_RESTORES = new IdentityHashMap<>();

    private CommandProtectedSwordHandler() {
    }

    @SubscribeEvent
    public static void beforeCommand(CommandEvent event) {
        MinecraftServer server = event.getParseResults().getContext().getSource().getServer();
        if (server == null) {
            return;
        }
        String input = event.getParseResults().getReader().getString();
        if (!DESTRUCTIVE_COMMAND.matcher(input).find()) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Several commands may run in one tick. Keep the first, pre-command
            // snapshot so a preceding /clear cannot erase the backup itself.
            PENDING_RESTORES.computeIfAbsent(player, CommandProtectedSwordHandler::copySwords);
        }
        PENDING_RESTORES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @SubscribeEvent
    public static void afterCommands(TickEvent.ServerTickEvent event) {
        // 1.20.1：tick 事件没有 .Post 子类，统一是 TickEvent + Phase，只认 END。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (PENDING_RESTORES.isEmpty()) {
            return;
        }

        for (Map.Entry<ServerPlayer, List<ItemStack>> entry : PENDING_RESTORES.entrySet()) {
            ServerPlayer player = entry.getKey();
            if (!player.isRemoved()) {
                restoreMissingSwords(player, entry.getValue());
            }
        }
        PENDING_RESTORES.clear();
    }

    /** 掉线玩家不再持有快照，避免 IdentityHashMap 长期引用已退出的 ServerPlayer。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING_RESTORES.remove(event.getEntity());
    }

    private static List<ItemStack> copySwords(ServerPlayer player) {
        List<ItemStack> copies = new ArrayList<>();
        collectSwords(player, copies, true);
        return copies;
    }

    /**
     * 统计玩家"仍然拥有"的管理员剑。
     *
     * @param copy true 表示加入副本（快照用），false 表示加入实际物品栈（结算比对用）
     */
    private static void collectSwords(ServerPlayer player, List<ItemStack> target, boolean copy) {
        // 背包三块与打开的容器菜单会重叠（InventoryMenu 本身就包含背包槽），用身份集合去重。
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        addIfSword(player.getInventory().items, target, seen, copy);
        addIfSword(player.getInventory().armor, target, seen, copy);
        addIfSword(player.getInventory().offhand, target, seen, copy);
        // 潜影盒 / 收纳袋这类"容器物品"里装的剑也算"仍然拥有"：
        // 复核指出把剑塞进潜影盒会被当成丢失而凭空补发一把。
        for (ItemStack stack : player.getInventory().items) {
            addSwordsInsideContainers(stack, target, seen, copy);
        }
        if (player.containerMenu != null) {
            for (Slot slot : player.containerMenu.slots) {
                ItemStack stack = slot.getItem();
                if (stack.getItem() instanceof KunJinKaoSwordItem && seen.add(stack)) {
                    target.add(copy ? stack.copy() : stack);
                }
            }
            ItemStack carried = player.containerMenu.getCarried();
            if (carried.getItem() instanceof KunJinKaoSwordItem && seen.add(carried)) {
                target.add(copy ? carried.copy() : carried);
            }
        }
        // 丢在地上的剑同样算"还在"，否则玩家自己丢剑会让保护逻辑补发一把。
        AABB area = player.getBoundingBox().inflate(8.0D);
        for (ItemEntity entity : player.level().getEntitiesOfClass(ItemEntity.class, area)) {
            ItemStack stack = entity.getItem();
            if (stack.getItem() instanceof KunJinKaoSwordItem && seen.add(stack)) {
                target.add(copy ? stack.copy() : stack);
            }
        }
    }

    /** 扫描容器物品（潜影盒等）内部装着的管理员剑。 */
    private static void addSwordsInsideContainers(ItemStack stack, List<ItemStack> target, Set<ItemStack> seen, boolean copy) {
        // 1.20.1 没有 CONTAINER 组件：潜影盒在 BlockEntityTag.Items、收纳袋在根上 Items，
        // 元素都是 ItemStack.save 的复合标签，解析出来逐个看即可。
        java.util.List<ItemStack> inners = new java.util.ArrayList<>();
        net.minecraft.nbt.CompoundTag beTag = net.minecraft.world.item.BlockItem.getBlockEntityData(stack);
        net.minecraft.nbt.ListTag items = null;
        if (beTag != null && beTag.contains("Items", net.minecraft.nbt.Tag.TAG_LIST)) {
            items = beTag.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        } else if (stack.hasTag() && stack.getTag().contains("Items", net.minecraft.nbt.Tag.TAG_LIST)) {
            items = stack.getTag().getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        }
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                inners.add(ItemStack.of(items.getCompound(i)));
            }
        }
        for (ItemStack inner : inners) {
            if (inner.getItem() instanceof KunJinKaoSwordItem && seen.add(inner)) {
                target.add(copy ? inner.copy() : inner);
            }
        }
    }

    private static void addIfSword(Iterable<ItemStack> source, List<ItemStack> target, Set<ItemStack> seen, boolean copy) {
        for (ItemStack stack : source) {
            if (stack.getItem() instanceof KunJinKaoSwordItem && seen.add(stack)) {
                target.add(copy ? stack.copy() : stack);
            }
        }
    }

    private static void restoreMissingSwords(ServerPlayer player, List<ItemStack> originals) {
        // 现存剑按身份逐个抵消快照里的原件，剩下的才是真的没了 —— 不按下标补发，避免还回不同 NBT 的剑。
        List<ItemStack> present = new ArrayList<>();
        collectSwords(player, present, false);

        List<ItemStack> missing = new ArrayList<>();
        for (ItemStack original : originals) {
            int index = indexOfMatch(present, original);
            if (index >= 0) {
                present.remove(index);
            } else {
                missing.add(original);
            }
        }

        for (ItemStack stack : missing) {
            ItemStack restored = stack.copy();
            if (!player.getInventory().add(restored)) {
                player.drop(restored, false);
            }
        }
        if (!missing.isEmpty()) {
            LOGGER.warn("[Kunjinkao] 玩家 {} 的管理员剑被命令移除，已补发 {} 把",
                    player.getName().getString(), missing.size());
        }
    }

    private static int indexOfMatch(List<ItemStack> candidates, ItemStack target) {
        for (int index = 0; index < candidates.size(); index++) {
            if (ItemStack.matches(candidates.get(index), target)) {
                return index;
            }
        }
        return -1;
    }
}
