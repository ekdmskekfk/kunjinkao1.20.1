package dev.modmind.kunjinkao.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.modmind.kunjinkao.world.SwordAreaFields;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让暂停场里的<b>方块实体</b>也停下来。
 * <p>
 * 生物与玩家有 {@code EntityTickEvent} 可用，方块实体没有 —— NeoForge 1.21.1
 * 不提供任何方块实体 tick 事件。所以只能动原版的
 * {@code Level.tickBlockEntities}。原方法里这一步是：
 * <pre>
 *   } else if (flag &amp;&amp; this.shouldTickBlocksAt(tickingblockentity.getPos())) {
 *       tickingblockentity.tick();          // ← 拦这里
 *   }
 * </pre>
 * <p>
 * <b>为什么是 {@code @Inject} 而不是 {@code @Redirect}</b>：
 * {@code @Redirect} 更省事（它直接把那个 {@code TickingBlockEntity} 当参数给我，
 * 不用去取循环里的局部变量），但 <b>Mixin 不允许两个 {@code @Redirect} 打同一处调用点</b>，
 * 撞上就只能有一个生效。实测在 infinity3.3.3 里 {@code observable} 那个模组
 * 也 {@code @Redirect} 了这一处、优先级同样是 1000，于是我的被跳过，
 * 叠加 {@code defaultRequire: 1} 直接演变成 <b>启动崩溃</b>。
 * <p>
 * {@code @Inject} 与 {@code @Redirect} 不冲突，代价是要用 MixinExtras 的
 * {@code @Local} 把循环变量取出来 —— 比 {@code @Redirect} 依赖更多，
 * 所以这里同时写了 {@code require = 0}：万一将来某个映射或模组让它取不到，
 * <b>只让暂停场少停机器，绝不让整个游戏起不来</b>。
 * <p>
 * 客户端不参与判定（{@link SwordAreaFields#isPaused(Level, net.minecraft.core.BlockPos)}
 * 在客户端直接返回 false），所以两边不会各停各的。
 */
@Mixin(Level.class)
public abstract class LevelPauseMixin {

    @Inject(
            method = "tickBlockEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"
            ),
            cancellable = true,
            require = 0
    )
    private void kunjinkao$skipPausedBlockEntities(CallbackInfo ci,
                                                   @Local TickingBlockEntity ticker) {
        Level self = (Level) (Object) this;
        if (SwordAreaFields.isPaused(self, ticker.getPos())) {
            // 取消这次 @Inject 就跳过那条 tick() 调用，方块实体这一 tick 不做事。
            ci.cancel();
        }
    }
}
