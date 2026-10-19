package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.PasswordAdminSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 管理员身份的运维命令。
 *
 * <p>密码机制只负责"授予"，而授权一旦写进存档就无法通过改密码回收，因此需要一条
 * 只对 OP（权限等级 4）开放的命令来查看/授予/撤销管理员身份。这也是本模组第一处
 * 使用原版权限系统的地方：面向服务器的管理操作走 OP，面向玩法的权限仍走密码。</p>
 */
public final class AdminCommandHandler {
    private static final String ROOT = "kunjinkao-admin";
    private static final int OP_PERMISSION_LEVEL = 4;

    private AdminCommandHandler() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(OP_PERMISSION_LEVEL))
                .then(Commands.literal("list")
                        .executes(context -> listAdmins(context.getSource())))
                .then(Commands.literal("grant")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> grant(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> revoke(context.getSource(),
                                        EntityArgument.getPlayer(context, "player"))))));
    }

    private static int listAdmins(CommandSourceStack source) {
        PasswordAdminSavedData data = data(source);
        if (data == null) {
            return 0;
        }
        var uuids = data.snapshot();
        if (uuids.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[Kunjinkao] 当前没有任何管理员授权"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Kunjinkao] 管理员共 " + uuids.size() + " 人："), false);
        for (UUID uuid : uuids) {
            ServerPlayer online = source.getServer() == null
                    ? null : source.getServer().getPlayerList().getPlayer(uuid);
            String label = online == null
                    ? uuid.toString() : online.getName().getString() + " (" + uuid + ")";
            source.sendSuccess(() -> Component.literal("  - " + label), false);
        }
        return uuids.size();
    }

    private static int grant(CommandSourceStack source, ServerPlayer target) {
        PasswordAdminSavedData data = data(source);
        if (data == null) {
            return 0;
        }
        data.authorize(target.getUUID());
        source.sendSuccess(() -> Component.literal(
                "[Kunjinkao] 已授予 " + target.getName().getString() + " 管理员身份"), true);
        return 1;
    }

    private static int revoke(CommandSourceStack source, ServerPlayer target) {
        PasswordAdminSavedData data = data(source);
        if (data == null) {
            return 0;
        }
        boolean removed = data.revoke(target.getUUID());
        if (removed) {
            // 本模组的设计是"持剑即权限"：只撤销存档里的 UUID 不够，
            // 必须同时把剑收回，否则被撤销者仍保有秒杀、覆写与千倍掉落等全部能力。
            int stripped = stripAdminSwords(target);
            source.sendSuccess(() -> Component.literal(
                    "[Kunjinkao] 已撤销 " + target.getName().getString() + " 的管理员身份"
                            + (stripped > 0 ? "，并收回 " + stripped + " 把管理员剑" : "")), true);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "[Kunjinkao] " + target.getName().getString() + " 本来就不在管理员名单里"), false);
        return 0;
    }

    /**
     * 收回目标身上所有能拿到的管理员剑：背包 / 盔甲 / 副手 / 光标 / 潜影盒等内容物 / 本人丢在地上的。
     * <p>
     * 命令保护那边已经把"容器内容物、附近掉落物"算作仍然持有，所以撤销只清背包槽是不够的 ——
     * 被撤销者靠一个潜影盒或"先把剑丢在地上"就能保住秒杀/覆写/千倍掉落全部能力（审计指出）。
     * 只处理 {@code getOwner()} 指向本人的掉落物，避免误删别人的东西。
     */
    private static int stripAdminSwords(ServerPlayer target) {
        int stripped = 0;
        for (int slot = 0; slot < target.getInventory().getContainerSize(); slot++) {
            ItemStack stack = target.getInventory().getItem(slot);
            if (stack.getItem() instanceof KunJinKaoSwordItem) {
                stack.setCount(0);
                stripped++;
                continue;
            }
            stripped += stripSwordsInsideContainer(stack);
        }
        ItemStack carried = target.containerMenu.getCarried();
        if (carried.getItem() instanceof KunJinKaoSwordItem) {
            target.containerMenu.setCarried(ItemStack.EMPTY);
            stripped++;
        } else {
            stripped += stripSwordsInsideContainer(carried);
        }
        List<ItemEntity> dropped = target.level().getEntitiesOfClass(ItemEntity.class,
                target.getBoundingBox().inflate(8.0D));
        for (ItemEntity entity : dropped) {
            if (target.getUUID().equals(entity.getOwner()) && entity.getItem().getItem() instanceof KunJinKaoSwordItem) {
                entity.discard();
                stripped++;
            }
        }
        target.containerMenu.broadcastChanges();
        return stripped;
    }

    /** 潜影盒 / 收纳袋这类容器物品里塞的剑；就地重建容器内容，返回收回数量。 */
    private static int stripSwordsInsideContainer(ItemStack container) {
        // 1.20.1 没有 CONTAINER 组件：潜影盒在 BlockEntityTag.Items、收纳袋在根上 Items，
        // 两边都是 ItemStack.save 出来的复合标签列表，直接从列表里删条目即可。
        net.minecraft.nbt.ListTag items = null;
        net.minecraft.nbt.CompoundTag beTag = net.minecraft.world.item.BlockItem.getBlockEntityData(container);
        if (beTag != null && beTag.contains("Items", net.minecraft.nbt.Tag.TAG_LIST)) {
            items = beTag.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        } else if (container.hasTag() && container.getTag().contains("Items", net.minecraft.nbt.Tag.TAG_LIST)) {
            items = container.getTag().getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        }
        if (items == null) {
            return 0;
        }
        int stripped = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            ItemStack inner = ItemStack.of(items.getCompound(i));
            if (inner.getItem() instanceof KunJinKaoSwordItem) {
                items.remove(i);
                stripped++;
            }
        }
        return stripped;
    }

    private static PasswordAdminSavedData data(CommandSourceStack source) {
        var server = source.getServer() != null ? source.getServer() : ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            source.sendFailure(Component.literal("[Kunjinkao] 服务器未就绪"));
            return null;
        }
        return PasswordAdminSavedData.get(server);
    }
}