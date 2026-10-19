package dev.modmind.kunjinkao.world;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Optional;

/**
 * 从 <b>ProjectE 的 EMC</b> 里取材。
 * <p>
 * 对应参考实现的 HandlerProjectE：它并不去"翻容器"，而是用玩家攒下的 EMC
 * <b>直接换出</b>方块 —— 前提是玩家已经"认识"过那件物品（知识库里有它）。
 * <p>
 * 全部走反射，不引入对 ProjectE 的编译期依赖；没装 ProjectE 时整条路径静默关闭。
 * <p>
 * <b>1.20.1 的差异（能力系统的代际差，只有取值那一步变了）：</b>
 * 1.21.1 走 NeoForge 的 {@code EntityCapability} +
 * {@code Player#getCapability(EntityCapability)}；1.20.1 的能力是 Forge 的
 * {@code Capability<T>}，取值方法是 {@code ICapabilityProvider#getCapability(Capability)}
 * （返回 {@code LazyOptional}）。所以这里：
 * <ul>
 *   <li>能力字段的类型类换成 {@code net.minecraftforge.common.capabilities.Capability}；</li>
 *   <li>取到的 {@code LazyOptional} 先 {@code resolve()} 成 {@code Optional}，再 {@code orElse(null)}。</li>
 * </ul>
 * ProjectE 那边的 API（{@code PECapabilities} / {@code IEMCProxy} / {@code ItemInfo} /
 * {@code IKnowledgeProvider}）在 1.20.1 与 1.21.1 同名同签名，反射字符串无需改动。
 */
public final class EmcMaterials {

    private static final String CAPABILITIES = "moze_intel.projecte.api.capabilities.PECapabilities";
    private static final String EMC_PROXY = "moze_intel.projecte.api.proxy.IEMCProxy";
    private static final String ITEM_INFO = "moze_intel.projecte.api.ItemInfo";
    private static final String KNOWLEDGE = "moze_intel.projecte.api.capabilities.IKnowledgeProvider";
    /** 1.21.1 这里是 NeoForge 的 EntityCapability；1.20.1 是 Forge 的 Capability。 */
    private static final String CAPABILITY_TYPE = "net.minecraftforge.common.capabilities.Capability";
    /** 1.20.1 的能力取值返回 LazyOptional，需要再 resolve 一次才拿得到 Optional。 */
    private static final String LAZY_OPTIONAL = "net.minecraftforge.common.capabilities.LazyOptional";

    /** 解析结果：只解析一次，失败就永久关闭这条路径。 */
    private static boolean resolved;
    private static Object knowledgeCapability;
    private static Method getCapability;
    private static Method lazyResolve;
    private static Method fromStack;
    private static Object emcProxy;
    private static Method proxyGetPersistentInfo;
    private static Method proxyGetValue;
    private static Method hasKnowledge;
    private static Method getEmc;
    private static Method setEmc;
    private static Method syncEmc;

    private EmcMaterials() {
    }

    private static synchronized boolean ensureResolved() {
        if (resolved) {
            return knowledgeCapability != null;
        }
        resolved = true;
        try {
            Class<?> capabilities = Class.forName(CAPABILITIES);
            knowledgeCapability = capabilities.getField("KNOWLEDGE_CAPABILITY").get(null);
            Class<?> capabilityType = Class.forName(CAPABILITY_TYPE);
            getCapability = Player.class.getMethod("getCapability", capabilityType);
            lazyResolve = Class.forName(LAZY_OPTIONAL).getMethod("resolve");

            Class<?> itemInfo = Class.forName(ITEM_INFO);
            fromStack = itemInfo.getMethod("fromStack", ItemStack.class);

            Class<?> proxy = Class.forName(EMC_PROXY);
            emcProxy = proxy.getField("INSTANCE").get(null);
            proxyGetPersistentInfo = proxy.getMethod("getPersistentInfo", itemInfo);
            proxyGetValue = proxy.getMethod("getValue", itemInfo);

            Class<?> knowledge = Class.forName(KNOWLEDGE);
            hasKnowledge = knowledge.getMethod("hasKnowledge", itemInfo);
            getEmc = knowledge.getMethod("getEmc");
            setEmc = knowledge.getMethod("setEmc", BigInteger.class);
            syncEmc = knowledge.getMethod("syncEmc", ServerPlayer.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            knowledgeCapability = null;
        }
        return knowledgeCapability != null;
    }

    /** 玩家能否用 EMC 换出这一种方块（装了 ProjectE、且知识库里有它、EMC 足够）。 */
    public static boolean canSupply(Player player, Item item) {
        return availableEmcCount(player, item) > 0;
    }

    /** 玩家的 EMC 按该物品单价折算，最多能换多少个。 */
    public static long availableEmcCount(Player player, Item item) {
        if (!ensureResolved()) {
            return 0L;
        }
        try {
            Object knowledge = knowledge(player);
            if (knowledge == null) {
                return 0L;
            }
            Object info = persistentInfo(fromStack.invoke(null, new ItemStack(item)));
            if (info == null || !(Boolean) hasKnowledge.invoke(knowledge, info)) {
                return 0L;
            }
            long value = (Long) proxyGetValue.invoke(emcProxy, info);
            if (value <= 0L) {
                return 0L;
            }
            BigInteger emc = (BigInteger) getEmc.invoke(knowledge);
            return emc.divide(BigInteger.valueOf(value)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0L;
        }
    }

    /**
     * 用 EMC 换出 {@code amount} 个该方块：按单价从玩家 EMC 里扣，并同步给客户端。
     *
     * @return 真的扣成功了才返回 true
     */
    public static boolean spend(Player player, Item item, int amount) {
        if (!ensureResolved() || !(player instanceof ServerPlayer serverPlayer) || amount <= 0) {
            return false;
        }
        try {
            Object knowledge = knowledge(player);
            if (knowledge == null) {
                return false;
            }
            Object info = persistentInfo(fromStack.invoke(null, new ItemStack(item)));
            if (info == null || !(Boolean) hasKnowledge.invoke(knowledge, info)) {
                return false;
            }
            long value = (Long) proxyGetValue.invoke(emcProxy, info);
            if (value <= 0L) {
                return false;
            }
            BigInteger cost = BigInteger.valueOf(value).multiply(BigInteger.valueOf(amount));
            BigInteger emc = (BigInteger) getEmc.invoke(knowledge);
            if (emc.compareTo(cost) < 0) {
                return false;
            }
            setEmc.invoke(knowledge, emc.subtract(cost));
            syncEmc.invoke(knowledge, serverPlayer);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 1.20.1：能力取值是 LazyOptional → resolve() 成 Optional → orElse(null)。 */
    private static Object knowledge(Player player) throws ReflectiveOperationException {
        Object lazy = getCapability.invoke(player, knowledgeCapability);
        if (lazy == null) {
            return null;
        }
        Object optional = lazyResolve.invoke(lazy);
        return ((Optional<?>) optional).orElse(null);
    }

    private static Object persistentInfo(Object info) throws ReflectiveOperationException {
        return proxyGetPersistentInfo.invoke(emcProxy, info);
    }
}