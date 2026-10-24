package dev.modmind.kunjinkao.network;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

/**
 * 「附魔工坊」的执行包（C2S，1.20.1 版）：把某个附魔按指定等级做成附魔书或写到手持物上。
 *
 * <p>与 1.21.1 的区别只有"怎么找附魔、怎么写上去"：
 * 那边是数据驱动的 {@code Holder} + 组件，这边是 {@link ForgeRegistries#ENCHANTMENTS}
 * 里的普通对象 + {@code EnchantedBookItem}。线格式两端一致（id / 等级 / 模式）。</p>
 *
 * <p>执行必须在服务端：工坊是管理员功能，授权只能在服务端校验；客户端的隐藏按钮不算数。</p>
 */
public record EnchantPayload(String enchantId, int level, int mode) {

    /** 做成附魔书。 */
    public static final int MODE_BOOK = 0;
    /** 直接写到手持的剑/注入物上。 */
    public static final int MODE_ITEM = 1;

    public static void encode(EnchantPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.enchantId());
        buf.writeVarInt(payload.level());
        buf.writeVarInt(payload.mode());
    }

    public static EnchantPayload decode(FriendlyByteBuf buf) {
        return new EnchantPayload(buf.readUtf(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(EnchantPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player != null) {
                apply(player, payload);
            }
        });
        context.setPacketHandled(true);
    }

    private static void apply(ServerPlayer player, EnchantPayload payload) {
        if (!AdminToolConfig.isAuthorized(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.admin_required"), true);
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(payload.enchantId());
        Enchantment enchantment = id == null ? null : ForgeRegistries.ENCHANTMENTS.getValue(id);
        if (enchantment == null) {
            player.displayClientMessage(Component.translatable("message.kunjinkao.enchant_unknown"), true);
            return;
        }
        // 原版上限 255，超出直接夹住而不是拒绝，免得滑条拖过 255 时玩家以为功能坏了。
        int level = Math.max(1, Math.min(255, payload.level()));

        if (payload.mode() == MODE_BOOK) {
            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
            EnchantedBookItem.addEnchantment(book, new EnchantmentInstance(enchantment, level));
            if (!player.getInventory().add(book)) {
                player.drop(book, false);
            }
        } else {
            ItemStack stack = player.getMainHandItem();
            if (!KunJinKaoSwordItem.isSwordLike(stack)) {
                player.displayClientMessage(Component.translatable("message.kunjinkao.enchant_need_sword"), true);
                return;
            }
            stack.enchant(enchantment, level);
            // 改的是服务端这一份栈，必须主动同步，否则客户端要等下一次容器刷新才看得到。
            player.inventoryMenu.broadcastChanges();
        }

        player.displayClientMessage(
                Component.translatable("message.kunjinkao.enchant_applied", enchantment.getFullname(level)), true);
    }
}
