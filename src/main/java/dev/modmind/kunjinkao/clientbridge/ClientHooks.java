package dev.modmind.kunjinkao.clientbridge;

import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import dev.modmind.kunjinkao.qol.QolSetting;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Common 侧访问客户端行为的网关。
 * <p>
 * 回调只由物理客户端的订阅者装配。这个类刻意<b>不依赖</b> mod 的 client 包、
 * 也不依赖 net.minecraft.client —— 否则专用服务器加载到它就会炸。
 * <p>
 * 1.20.1 移植说明：
 * <ul>
 *   <li>{@code Dist} / {@code FMLEnvironment} 换到 net.minecraftforge 包；</li>
 *   <li>payload 参数类型改成 {@code Object}（Forge 没有 CustomPacketPayload 基类）；</li>
 *   <li>加速器界面回调已补回，签名与 1.21.1 一致（{@code Consumer<AcceleratorBlockEntity>}）。</li>
 * </ul>
 */
public final class ClientHooks {

    @FunctionalInterface
    public interface PayloadHandler {
        void handle(Object payload);
    }

    private static volatile PayloadHandler payloadHandler;
    private static volatile Consumer<AcceleratorBlockEntity> acceleratorScreenOpener;

    /**
     * 客户端本地镜像的"这一项开着吗"查询。
     * <p>
     * 存在的理由是给 mixin 用：{@code Entity#fireImmune} 这类目标在服务端与客户端都会被调到，
     * 而 mixin 类本身是 common 的，不能直接引用 client 包里的类，否则专用服务器一加载就炸。
     */
    private static volatile Predicate<QolSetting> qolStateQuery;

    private ClientHooks() {
    }

    private static boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    public static void registerClientHandlers(PayloadHandler handler,
                                              Consumer<AcceleratorBlockEntity> screenOpener) {
        if (isClient()) {
            payloadHandler = handler;
            acceleratorScreenOpener = screenOpener;
        }
    }

    public static void registerQolStateQuery(Predicate<QolSetting> query) {
        if (isClient()) {
            qolStateQuery = query;
        }
    }

    /**
     * 客户端镜像里这一项是否开着。
     * <p>
     * 专用服务器上恒为 false。客户端的值只是服务端推来的副本，不是权威判定：
     * 它决定的是"火焰动画要不要画"这类纯表现问题。
     */
    public static boolean isQolEnabledOnClient(QolSetting setting) {
        Predicate<QolSetting> query = qolStateQuery;
        return isClient() && query != null && query.test(setting);
    }

    public static void handleClientPayload(Object payload) {
        PayloadHandler handler = payloadHandler;
        if (isClient() && handler != null) {
            handler.handle(payload);
        }
    }

    /** 打开加速器配置界面；专用服务器上是空操作。 */
    public static void openAcceleratorScreen(AcceleratorBlockEntity accelerator) {
        Consumer<AcceleratorBlockEntity> opener = acceleratorScreenOpener;
        if (isClient() && opener != null) {
            opener.accept(accelerator);
        }
    }
}