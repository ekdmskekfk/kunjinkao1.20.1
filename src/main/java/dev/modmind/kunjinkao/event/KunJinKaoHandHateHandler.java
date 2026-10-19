package dev.modmind.kunjinkao.event;

import dev.modmind.kunjinkao.entity.KunJinKaoHandEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/**
 * "几乎所有敌对与中立生物都恨君君"的实现。
 * <p>
 * <b>为什么只能这样做：</b>"谁恨谁"写在<b>对方</b>的目标选择器里，而每个生物的目标 goal
 * 是它自己的类里写死的 —— 想让僵尸/狼/铁傀儡/猪灵都去恨一只自定义生物，
 * 要么给每个生物打 mixin（不现实），要么在它们加入世界时<b>往它们的目标选择器里塞一条
 * 指向君君的目标</b>。这里用的是后者。
 * <p>
 * <b>注入谁：</b>只给 {@code targetSelector} 里<b>已经有目标 goal</b> 的生物注入 ——
 * 敌对生物自然全在内；中立但会打架的（狼、铁傀儡、猪灵、蜜蜂、北极熊……）也在内，
 * 因为它们都有目标 goal。而牛羊鸡这类连目标选择器都是空的生物不注入：
 * 它们连"攻击"这个动作的 goal 都没有，硬塞进去也只会每 tick 空转。
 * <p>
 * <b>优先级取 3：</b>低于绝大多数生物自己的主目标（僵尸优先打玩家）——
 * 场上没有它本来要打的对象时，君君就是下一个。这是"仇恨"，不是"最高执念"。
 * <p>
 * 用 persistent data 打标记：实体从存档重载时这个事件会再次触发，
 * 不打标记会把同一个 goal 越塞越多（每次重载 +1 条，掉 TPS 还会挤掉别的目标）。
 */
public final class KunJinKaoHandHateHandler {

    private static final String FLAG = "KunJinKaoHandHate";

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (mob instanceof KunJinKaoHandEntity) {
            return;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(FLAG)) {
            return;
        }
        // 先打标记再判断"有没有目标 goal"：本来就不会打的生物永远不用注入，
        // 这个结论对它一辈子有效，不必每次加入世界都重查一遍。
        data.putBoolean(FLAG, true);
        if (mob.targetSelector.getAvailableGoals().isEmpty()) {
            return;
        }
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, KunJinKaoHandEntity.class, false));
    }
}