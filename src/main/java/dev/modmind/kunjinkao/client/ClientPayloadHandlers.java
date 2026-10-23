package dev.modmind.kunjinkao.client;

import dev.modmind.kunjinkao.client.gui.AdminPasswordScreen;
import dev.modmind.kunjinkao.client.qol.QolClientState;
import dev.modmind.kunjinkao.client.qol.ZoneClientState;
import dev.modmind.kunjinkao.network.AdminEyeStatePayload;
import dev.modmind.kunjinkao.network.AdminPasswordResultPayload;
import dev.modmind.kunjinkao.network.CosmeticStatePayload;
import dev.modmind.kunjinkao.network.ExcludedPlayersPayload;
import dev.modmind.kunjinkao.network.HudEntityActionResultPayload;
import dev.modmind.kunjinkao.network.HudEntityListPayload;
import dev.modmind.kunjinkao.network.HudShieldHitPayload;
// 4 个 HUD 状态回执包已合并为 HudStatePayload，导入一起收敛。
import dev.modmind.kunjinkao.network.HudStatePayload;
import dev.modmind.kunjinkao.network.HudTrueInvisibilityVisualPayload;
import dev.modmind.kunjinkao.network.InjectionStatePayload;
import dev.modmind.kunjinkao.network.OverwriteEffectPayload;
import dev.modmind.kunjinkao.network.QolStatePayload;
import dev.modmind.kunjinkao.network.SwordDrawAnimationPayload;
import dev.modmind.kunjinkao.network.TimeAccelStatusPayload;
import dev.modmind.kunjinkao.network.WandPreviewResultPayload;
import dev.modmind.kunjinkao.network.ZoneStatusPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
// 1.20.1 没有 CustomPacketPayload/IPayloadContext：payload 一律以 Object 传进来（见 ClientHooks）。
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

/** Handles S2C payloads after client-only dispatch. */
@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {
    }

    public static void handle(Object payload, net.minecraftforge.network.NetworkEvent.Context context) {
        // ClientHooks 是在已经切回主线程之后才调进来的，传进来的 context 就是 null。
        // 早期这里直接 context.enqueueWork(...)，对 null 调方法抛 NPE，
        // 异常被主线程任务执行器吞掉 —— 表现就是"输入密码后毫无反应"（所有 S2C 包都受影响）。
        if (context == null) {
            dispatch(payload);
            return;
        }
        context.enqueueWork(() -> dispatch(payload));
    }

    private static void dispatch(Object payload) {
        {
            if (payload instanceof OverwriteEffectPayload effect) {
                switch (effect.phase()) {
                    case OverwriteEffectPayload.PHASE_START -> KunJinKaoClientOverwriteEffects.start(effect.entityId(), effect.remainingTicks(), effect.phaseDetail());
                    case OverwriteEffectPayload.PHASE_PROGRESS -> KunJinKaoClientOverwriteEffects.update(effect.entityId(), effect.remainingTicks());
                    case OverwriteEffectPayload.PHASE_END -> KunJinKaoClientOverwriteEffects.endFlash(effect.entityId(), effect.position());
                    case OverwriteEffectPayload.PHASE_CANCEL -> KunJinKaoClientOverwriteEffects.cancel(effect.entityId());
                    case OverwriteEffectPayload.PHASE_DECISION -> KunJinKaoClientOverwriteEffects.startDecision(effect.entityId(), effect.remainingTicks());
                    default -> {
                    }
                }
            } else if (payload instanceof HudStatePayload state) {
                // 合并后的 HUD 状态包：按 stateId 分派到原来那四个方法，applyXxx 的逻辑一行未改。
                switch (state.stateId()) {
                    case HudStatePayload.TACTICAL_HUD -> TacticalHudClientPacketHandler.apply(state.enabled(), state.authorized());
                    case HudStatePayload.NIGHT_VISION -> TacticalHudClientPacketHandler.applyNightVision(state.enabled(), state.authorized());
                    case HudStatePayload.TRUE_INVISIBILITY -> TacticalHudClientPacketHandler.applyTrueInvisibility(state.enabled(), state.authorized());
                    case HudStatePayload.MAGNET -> TacticalHudClientPacketHandler.applyMagnet(state.enabled(), state.authorized());
                    default -> {
                        // 未知 stateId：与原来"收到认不出的包"一样，静默忽略。
                    }
                }
            } else if (payload instanceof HudEntityListPayload list) {
                TacticalHudClientPacketHandler.openEntityList(list.entities(), list.authorized());
            } else if (payload instanceof HudEntityActionResultPayload result) {
                TacticalHudClientPacketHandler.applyEntityAction(result.action(), result.entityUuid(), result.success(), result.authorized());
            } else if (payload instanceof HudTrueInvisibilityVisualPayload state) {
                TacticalHudInvisibilityVisualState.setTrueInvisible(state.playerUuid(), state.enabled());
            } else if (payload instanceof HudShieldHitPayload hit) {
                ShieldHitVisualState.trigger(hit.playerUuid(), hit.relativeImpactYaw(), hit.impactHeight());
            } else if (payload instanceof WandPreviewResultPayload preview) {
                WandPreviewRenderer.accept(preview.seq(), preview.positions());
            } else if (payload instanceof AdminEyeStatePayload eye) {
                AdminEyeVisualState.setEnabled(eye.playerUuid(), eye.enabled());
                // 顺带把它当作「我自己是否已授权」的权威镜像：登录时服务端会广播自己的那一条。
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null && minecraft.player.getUUID().equals(eye.playerUuid())) {
                    AdminAuthClientState.setAuthorized(eye.enabled());
                }
            } else if (payload instanceof SwordDrawAnimationPayload animation) {
                RemoteSwordDrawVisualState.trigger(animation.playerUuid(), animation.hand());
            } else if (payload instanceof ExcludedPlayersPayload list) {
                TacticalHudClientPacketHandler.applyExcludedList(list.players(), list.authorized());
            } else if (payload instanceof AdminPasswordResultPayload result) {
                handleAdminPasswordResult(result.authorized());
            } else if (payload instanceof TimeAccelStatusPayload status) {
                // 服务端心跳式推来的加速场快照：存下来，由 TimeAccelOverlayRenderer 画悬浮提示。
                TimeAccelClientState.accept(status.entries());
            } else if (payload instanceof QolStatePayload state) {
                // 功能面板的一行状态：存进客户端镜像，打开着的面板每帧自己读。
                QolClientState.accept(state);
            } else if (payload instanceof ZoneStatusPayload status) {
                // 选区挖掘（127）的选区与进度：存进客户端镜像，由 ZoneSelectionRenderer 每帧自己读。
                ZoneClientState.accept(status);
            } else if (payload instanceof CosmeticStatePayload cosmetics) {
                // 装饰（201–203）：整表快照，存进客户端镜像后由 CosmeticRenderer 用本地玩家位置画出来。
                CosmeticClientState.accept(cosmetics.entries());
            } else if (payload instanceof InjectionStatePayload injection) {
                // 注入：只更新进度条的表现状态，成败判定全在服务端。
                applyInjectionState(injection);
            }
        }
    }

    /** 注入进度：START 开条，ABORT 收条，DONE 收条并闪一下成功提示。 */
    private static void applyInjectionState(InjectionStatePayload payload) {
        switch (payload.state()) {
            case InjectionStatePayload.START -> InjectionClientState.start(
                    payload.durationTicks(), handFromOrdinal(payload.hand()));
            case InjectionStatePayload.DONE -> InjectionClientState.complete();
            case InjectionStatePayload.ABORT -> InjectionClientState.abort();
            default -> {
                // 认不出的状态：静默忽略，与本模组其它包的处理一致。
            }
        }
    }

    /** 手枚举按序号还原，越界回落到主手（与网络层的 readEnumSafe 同一套保守策略）。 */
    private static net.minecraft.world.InteractionHand handFromOrdinal(int ordinal) {
        net.minecraft.world.InteractionHand[] values = net.minecraft.world.InteractionHand.values();
        return ordinal >= 0 && ordinal < values.length
                ? values[ordinal]
                : net.minecraft.world.InteractionHand.MAIN_HAND;
    }

    private static void handleAdminPasswordResult(boolean authorized) {
        Minecraft minecraft = Minecraft.getInstance();
        // 中途验证成功/失败都要立刻更新：成功后应当马上能开控制台，不必重登。
        AdminAuthClientState.setAuthorized(authorized);
        if (minecraft.screen instanceof AdminPasswordScreen) {
            minecraft.setScreen(null);
        }
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(authorized
                    ? "§a管理员密码验证成功。"
                    : "§c密码错误，已关闭验证窗口。"), true);
        }
    }
}
