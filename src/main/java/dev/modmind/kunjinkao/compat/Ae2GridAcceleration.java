package dev.modmind.kunjinkao.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * AE2（以及任何实现了同一套 ME 网络接口的模组）的网络机器加速入口。
 * <p>
 * 这类机器既不走原版 {@code BlockEntityTicker}，也没有方块实体自己的 {@code serverTick()}：
 * 它们把 tick 交给 ME 网络 —— 方块实体实现 {@code IInWorldGridNodeHost}，
 * 网络节点上挂一个 {@code IGridTickable} 服务，机器每刻要做的事就写在
 * {@code IGridTickable.tickingRequest(node, ticksSinceLast)} 里。
 * 所以额外调用它若干次就是加速这台机器，与"额外调用原版 ticker"是同一件事，
 * 只是入口换成了 ME 网络的 tick 服务。
 * <p>
 * 全部走反射，不引入对 AE2 的编译期依赖；AE2 不存在时整条路径静默关闭。
 * 调用序列参照无用拉伸器的 {@code tickAeNodes}：
 * 取 node host → 六个方向取节点并按身份去重 → 取 IGridTickable → 反复 tickingRequest，
 * 遇到 SLEEP 就说明这台机器当前没事可做，停止继续调用。
 */
public final class Ae2GridAcceleration {

    private static final String GRID_HELPER = "appeng.api.networking.GridHelper";
    private static final String NODE_HOST = "appeng.api.networking.IInWorldGridNodeHost";
    private static final String GRID_NODE = "appeng.api.networking.IGridNode";
    private static final String GRID_TICKABLE = "appeng.api.networking.ticking.IGridTickable";

    /** 解析结果：只解析一次，失败就永久关闭这条路径，避免每刻反复 ClassNotFound。 */
    private static boolean resolved;
    private static Method getNodeHost;
    private static Method getGridNode;
    private static Method getGrid;
    private static Method isActive;
    private static Method getService;
    private static Method tickingRequest;
    private static Class<?> nodeHostClass;
    private static Class<?> gridNodeClass;
    private static Class<?> gridTickableClass;

    private Ae2GridAcceleration() {
    }

    private static synchronized boolean ensureResolved() {
        if (resolved) {
            return getNodeHost != null;
        }
        resolved = true;
        try {
            Class<?> helper = Class.forName(GRID_HELPER);
            nodeHostClass = Class.forName(NODE_HOST);
            gridNodeClass = Class.forName(GRID_NODE);
            gridTickableClass = Class.forName(GRID_TICKABLE);
            getNodeHost = helper.getMethod("getNodeHost", Level.class, BlockPos.class);
            getGridNode = nodeHostClass.getMethod("getGridNode", Direction.class);
            getGrid = gridNodeClass.getMethod("getGrid");
            isActive = gridNodeClass.getMethod("isActive");
            getService = gridNodeClass.getMethod("getService", Class.class);
            tickingRequest = gridTickableClass.getMethod("tickingRequest", gridNodeClass, int.class);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            // 没装 AE2（或版本对不上）：关掉这条路径即可，不是错误。
            getNodeHost = null;
        }
        return getNodeHost != null;
    }

    /** 该方块实体是否是一台挂得上 ME 网络的机器（用于"能否被加速"的判定）。 */
    public static boolean isGridMachine(@Nullable BlockEntity entity) {
        // 注意要先 ensureResolved：nodeHostClass 是解析之后才有的，
        // 直接判 null 会让第一次调用永远返回 false。
        return entity != null && ensureResolved() && nodeHostClass.isInstance(entity);
    }

    /** 这个位置是否有可 tick 的 ME 机器。 */
    public static boolean hasTickable(Level level, BlockPos pos) {
        return !collect(level, pos).isEmpty();
    }

    /**
     * 额外推进这台 ME 机器。
     *
     * @return 实际执行的 tick 次数，供调用方扣减每刻预算
     */
    public static int tick(Level level, BlockPos pos, int extra, int budget) {
        List<Object> endpoints = collect(level, pos);
        if (endpoints.isEmpty()) {
            return 0;
        }
        int calls = Math.min(extra, budget);
        int done = 0;
        for (Iterator<Object> it = endpoints.iterator(); it.hasNext();) {
            Object[] endpoint = (Object[]) it.next();
            for (int i = 0; i < calls; i++) {
                try {
                    Object node = endpoint[0];
                    if (getGrid.invoke(node) == null || !(Boolean) isActive.invoke(node)) {
                        break;
                    }
                    // 第二个参数是「距上次调用过了多少 tick」，AE2 的机器拿它当本次要推进的量。
                    // 传 0 等于说「一 tick 都没过去」，机器会直接跳过 ——
                    // 表现出来就是「加速场建起来了，但机器不变快」。额外调一次就理解为推进 1 tick。
                    Object modulation = tickingRequest.invoke(endpoint[1], node, 1);
                    done++;
                    // SLEEP 表示这台机器当前没有待办，再调也是空转。
                    if (modulation != null && "SLEEP".equals(String.valueOf(modulation))) {
                        break;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    break;
                }
            }
        }
        return done;
    }

    /** 收集该位置上所有 (IGridNode, IGridTickable) 组合，按节点身份去重。 */
    private static List<Object> collect(Level level, BlockPos pos) {
        if (!ensureResolved()) {
            return List.of();
        }
        Object host;
        try {
            host = getNodeHost.invoke(null, level, pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
        if (host == null) {
            return List.of();
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> endpoints = new ArrayList<>(2);
        for (Direction dir : Direction.values()) {
            try {
                Object node = getGridNode.invoke(host, dir);
                // 同一个节点会在多个面被返回，必须按身份去重，
                // 否则同一台机器会被重复推进好几倍。
                if (node == null || !seen.add(node)) {
                    continue;
                }
                if (getGrid.invoke(node) == null || !(Boolean) isActive.invoke(node)) {
                    continue;
                }
                Object tickable = getService.invoke(node, gridTickableClass);
                if (tickable != null) {
                    endpoints.add(new Object[]{node, tickable});
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 某个面异常不影响其它面。
            }
        }
        return endpoints;
    }
}