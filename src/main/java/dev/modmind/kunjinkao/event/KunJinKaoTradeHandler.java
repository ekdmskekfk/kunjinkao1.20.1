package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * 村民交易的两项功能（94 无限交易 / 95 村民大师）。
 * <p>
 * 两项放在同一个处理器里，因为它们挂在同一个事件上、都要先判定"这次交易的是谁"。
 * 但它们<b>刻意是两个独立开关</b>：94 只把这次交易的使用次数清零，不改村民任何数据；
 * 95 会把村民永久推到大师级。合并成一个开关的话，只想要"不售罄"的玩家
 * 会被迫接受"所有村民瞬间满级"这种大得多的改动。
 */
public final class KunJinKaoTradeHandler {

    private static final Logger LOGGER = LogManager.getLogger("KunJinKao");
    /** 原版村民等级上限（学徒 1 → 大师 5）。 */
    private static final int VILLAGER_MAX_LEVEL = 5;
    /**
     * 推满级时一并写入的村民经验。
     * <p>
     * 只改等级不写经验会留下"大师级但经验为 0"的矛盾状态；反之写一个足够大的值也不会让它继续升级 ——
     * 原版的 shouldIncreaseLevel 会先检查能否升级，5 级已经到顶了。
     */
    private static final int VILLAGER_MASTER_XP = 1_000_000;

    /** 反射查到的 {@code updateTrades}，查不到就一直是 null，不必重复失败。 */
    private static Method updateTrades;
    private static boolean updateTradesResolved;

    @SubscribeEvent
    public void onTradeWithVillager(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MerchantOffer offer = event.getMerchantOffer();
        if (offer != null && offer.getUses() > 0
                && KunJinKaoQolHandler.isOn(player, QolSetting.UNLIMITED_TRADES)) {
            offer.resetUses();
        }

        if (KunJinKaoQolHandler.isOn(player, QolSetting.VILLAGER_MASTER)
                && event.getAbstractVillager() instanceof Villager villager) {
            promoteToMaster(villager);
        }
    }

    /**
     * 把村民推到满级并刷新交易表。
     * <p>
     * <b>为什么这里用了反射。</b> 1.21.1 里"直接满级且货架跟着更新"没有公开路径：
     * <ul>
     *   <li>{@code setVillagerData} 与 {@code setVillagerXp} 是公开的，但只改数据，
     *       不会重新生成 offers；而 {@code getOffers()} 只在 offers 为空时才去调 updateTrades，
     *       所以光改等级会得到"界面写着大师、卖的还是一级的货"这种半截状态。</li>
     *   <li>{@code increaseMerchantCareer()} 与 {@code updateTrades()} 在 1.21.1 分别是
     *       private 与 protected，外部拿不到。</li>
     * </ul>
     * 所以只能用反射兜底。取不到方法时<b>不假装成功</b>：只把等级推上去并记一条警告，
     * 让"货架没更新"这件事在日志里留痕，而不是静默地做成半个功能。
     */
    private static void promoteToMaster(Villager villager) {
        if (villager.getVillagerData().getLevel() >= VILLAGER_MAX_LEVEL) {
            return;
        }
        villager.setVillagerData(villager.getVillagerData().setLevel(VILLAGER_MAX_LEVEL));
        villager.setVillagerXp(VILLAGER_MASTER_XP);

        Method method = updateTradesMethod();
        if (method == null) {
            LOGGER.warn("[Kunjinkao] 村民大师：等级已推到 {}，但拿不到 updateTrades，货架不会被刷新", VILLAGER_MAX_LEVEL);
            return;
        }
        try {
            method.invoke(villager);
        } catch (ReflectiveOperationException failed) {
            LOGGER.warn("[Kunjinkao] 村民大师：刷新交易表失败：{}", failed.toString());
        }
    }

    private static Method updateTradesMethod() {
        if (updateTradesResolved) {
            return updateTrades;
        }
        updateTradesResolved = true;
        try {
            // 在 Villager 本类里找，不是 AbstractVillager：1.21.1 中它是 Villager 覆盖的 protected 方法。
            Method found = Villager.class.getDeclaredMethod("updateTrades");
            found.setAccessible(true);
            updateTrades = found;
        } catch (NoSuchMethodException | RuntimeException failed) {
            // 别的模组或以后的原版把方法名/可见性改了都会走到这里。记一次就够，不必每次交易都刷屏。
            LOGGER.warn("[Kunjinkao] 村民大师：找不到 Villager#updateTrades（{}），该功能会退化成只改等级",
                    failed.toString());
        }
        return updateTrades;
    }
}