package dev.modmind.kunjinkao.wand;

import javax.annotation.Nullable;

import java.util.ArrayList;

/**
 * 按加入顺序挑：一次 {@link #reset()} 之后从头到尾走一遍，走完返回 null。
 * 对齐 Construction Wand KOTS 的 OrderedPool。
 * <p>
 * 顺序有意义：副手指定的那一种排在最前，于是"能放副手那种就先用副手那种"，
 * 只有当它放不下去（比如朝向不合法）时才会退到后面的同类方块。
 */
public final class OrderedPool<T> implements IPool<T> {

    private final ArrayList<T> elements = new ArrayList<>();
    private int index;

    @Override
    public void add(T element) {
        elements.add(element);
    }

    @Override
    public void remove(T element) {
        elements.remove(element);
    }

    @Nullable
    @Override
    public T draw() {
        if (index >= elements.size()) {
            return null;
        }
        T element = elements.get(index);
        index++;
        return element;
    }

    @Override
    public void reset() {
        index = 0;
    }
}