package dev.modmind.kunjinkao.wand;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;

import net.minecraft.util.RandomSource;

/**
 * 随机挑：每次 {@link #reset()} 重新装满一轮，一轮内每种只出一次，走完返回 null。
 * 对齐 Construction Wand KOTS 的 RandomPool（连权重表一起搬过来了）。
 */
public final class RandomPool<T> implements IPool<T> {

    private final RandomSource random;
    private final HashMap<T, Integer> weights = new HashMap<>();
    private HashSet<T> pool = new HashSet<>();

    public RandomPool(RandomSource random) {
        this.random = random;
    }

    @Override
    public void add(T element) {
        addWithWeight(element, 1);
    }

    @Override
    public void remove(T element) {
        weights.remove(element);
        pool.remove(element);
    }

    public void addWithWeight(T element, int weight) {
        if (weight >= 1) {
            weights.merge(element, weight, Integer::sum);
            pool.add(element);
        }
    }

    @Nullable
    @Override
    public T draw() {
        int total = 0;
        for (T element : pool) {
            total += weights.get(element);
        }
        if (total < 1) {
            return null;
        }
        int roll = random.nextInt(total);
        int accumulated = 0;
        for (T element : pool) {
            accumulated += weights.get(element);
            if (roll < accumulated) {
                pool.remove(element);
                return element;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        pool = new HashSet<>(weights.keySet());
    }
}