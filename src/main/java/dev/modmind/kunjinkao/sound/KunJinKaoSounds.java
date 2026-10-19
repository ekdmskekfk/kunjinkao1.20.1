package dev.modmind.kunjinkao.sound;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/**
 * 剑自己的音效。
 * <p>
 * 在它之前，模组的音效全部借用原版（AMETHYST_CLUSTER_BREAK、SHOVEL_FLATTEN 之类）。
 * 借用的问题是音色和"数据损坏"的主题不搭 —— 紫水晶的清脆听上去像魔法，不像故障。
 * <p>
 * 这五个是脚本合成的（tools/sound/make_sounds.ps1），不是外部素材：
 * <ul>
 *   <li>{@link #SWING} 挥砍破空 —— 噪声叠一段 1400Hz 下滑到 200Hz 的扫频</li>
 *   <li>{@link #HIT} 命中 —— 瞬态咔哒 + 165Hz 冲击 + 2400Hz 金属泛音</li>
 *   <li>{@link #COMPILE_DONE} 编译完成 —— 660Hz 与 990Hz 两段上行</li>
 *   <li>{@link #HUM} 待机嗡鸣 —— 55/110/165/220Hz 整数谐波，2 秒整周期，可无缝循环</li>
 *   <li>{@link #CORRUPT} 数据损坏 —— 降采样方波叠噪声</li>
 * </ul>
 * <p>
 * 顺带记一个坑：Minecraft 的音频只走 {@code JOrbisAudioStream}，<b>只认 Ogg Vorbis</b>，
 * 全 jar 里没有任何 WAV 支持（连一个 Wav 类都没有）。所以合成出来的 PCM 必须
 * 用 ffmpeg 转成 .ogg 才能用，光丢 .wav 进去是静音。
 */
public final class KunJinKaoSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, KunJinKaoEntry.MOD_ID);

    public static final RegistryObject<SoundEvent> SWING = register("swing");
    public static final RegistryObject<SoundEvent> HIT = register("hit");
    public static final RegistryObject<SoundEvent> COMPILE_DONE = register("compile_done");
    public static final RegistryObject<SoundEvent> HUM = register("hum");
    public static final RegistryObject<SoundEvent> CORRUPT = register("corrupt");

    private KunJinKaoSounds() {
    }

    /**
     * 由 {@code KunJinKaoEntry} 调用。
     * <p>
     * 注意别把这个方法和下面私有的 {@code register(String)} 搞混：少了它，
     * {@code KunJinKaoSounds.register(modEventBus)} 会解析到那个接受 String 的私有重载，
     * 编译报"不兼容的类型: IEventBus 无法转换为 String"—— 报错位置在调用方而不是这里，
     * 第一次踩不容易一眼看出来。
     */
    public static void register(net.minecraftforge.eventbus.api.IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }

    /** 可变传播距离：剑的音效不该像原版那样固定 16 格。 */
    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(KunJinKaoEntry.MOD_ID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}