package dev.modmind.kunjinkao.wand;

import dev.modmind.kunjinkao.KunJinKaoSwordItem;
import dev.modmind.kunjinkao.config.AdminToolConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 三种放置类核心，对齐参考实现的 items.core（CoreDefault / ItemCoreAngel / ItemCoreDestruction）。
 * <p>
 * 参考实现是三个核心<b>物品</b>各带一个动作，本模组把核心做成剑上的开关，
 * 所以这里只负责两件事：读出当前该用哪个动作、算出这个动作单次的上限。
 * 优先级与旧实现一致：破坏 &gt; 天使 &gt; 建筑。
 */
public enum WandCore {

    CONSTRUCTION(new ActionConstruction()),
    ANGEL(new ActionAngel()),
    DESTRUCTION(new ActionDestruction());

    private final IWandAction action;

    WandCore(IWandAction action) {
        this.action = action;
    }

    public IWandAction action() {
        return action;
    }

    /** 当前剑上该用哪个核心；三个开关都关着时返回 null。 */
    public static WandCore active(ItemStack stack) {
        if (KunJinKaoSwordItem.isDestructionCoreEnabled(stack)) {
            return DESTRUCTION;
        }
        if (KunJinKaoSwordItem.isAngelCoreEnabled(stack)) {
            return ANGEL;
        }
        if (KunJinKaoSwordItem.isConstructionWandEnabled(stack)) {
            return CONSTRUCTION;
        }
        return null;
    }

    /**
     * 这个核心单次能作用多少格。
     * <p>
     * 参考实现把上限放在"每根手杖的配置"里；本模组只有一把剑，所以放到服务器配置里。
     * 破坏核心返回 0 表示服主关掉了它 —— 调用方看到 0 就不该动手。
     */
    public int limit(Player player, ItemStack stack) {
        return switch (this) {
            case CONSTRUCTION -> player.isCreative()
                    ? AdminToolConfig.wandCreativeLimit()
                    : AdminToolConfig.wandPlaceLimit();
            case ANGEL -> AdminToolConfig.wandAngelDistance();
            case DESTRUCTION -> AdminToolConfig.wandDestroyLimit();
        };
    }
}