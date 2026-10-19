package dev.modmind.kunjinkao.wand;

import javax.annotation.Nullable;

/**
 * 取材池：决定这次建造按什么顺序从"可选材料"里挑方块。
 * 对齐 Construction Wand KOTS 的 basics.pool.IPool。
 * <p>
 * 约定：{@link #draw()} 走完一轮还挑不出可用材料时返回 null ——
 * 调用方正是靠这个 null 判断"材料挑不出来"，所以实现必须在一轮结束后真的返回 null，
 * 不能无限循环。
 */
public interface IPool<T> {

    void add(T element);

    void remove(T element);

    @Nullable
    T draw();

    void reset();
}