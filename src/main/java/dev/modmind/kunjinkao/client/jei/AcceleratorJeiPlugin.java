package dev.modmind.kunjinkao.client.jei;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import dev.modmind.kunjinkao.block.entity.AcceleratorBlockEntity;
import dev.modmind.kunjinkao.client.gui.AcceleratorScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * JEI 插件：让加速方块 GUI 的 9 个过滤格位接受**从 JEI 拖入的方块**。
 * <p>
 * 只有方块物品会被接受（与服务端 {@code AcceleratorBlockEntity.applyFilter} 的校验一致），
 * 否则过滤列表里会出现无法与方块比对的东西。
 * <p>
 * 类加载约定：本类与内部的处理器都直接引用了 JEI 的类型，因此**只能由 JEI 自己的注解扫描加载**；
 * 模组其它地方一律不得引用它们，否则没装 JEI 的客户端会 NoClassDefFoundError。
 * （JEI 用的是 compileOnly 依赖，运行时由玩家自备。）
 */
@JeiPlugin
public final class AcceleratorJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(AcceleratorScreen.class, new FilterGhostHandler());
    }

    /** 把 JEI 拖拽的落点映射到过滤格位。 */
    private static final class FilterGhostHandler implements IGhostIngredientHandler<AcceleratorScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(AcceleratorScreen screen, ITypedIngredient<I> ingredient,
                                                   boolean doStart) {
            Optional<ItemStack> maybeStack = ingredient.getItemStack();
            if (maybeStack.isEmpty() || !(maybeStack.get().getItem() instanceof BlockItem)) {
                return List.of();
            }
            // 拖拽开始时就固定住要写入的物品，避免拖到一半源物品变化
            ItemStack dragged = maybeStack.get().copy();
            List<Target<I>> targets = new ArrayList<>(AcceleratorBlockEntity.FILTER_SLOTS);
            for (int slot = 0; slot < AcceleratorBlockEntity.FILTER_SLOTS; slot++) {
                Rect2i area = screen.filterSlotArea(slot);
                int index = slot;
                targets.add(new Target<I>() {
                    @Override
                    public Rect2i getArea() {
                        return area;
                    }

                    @Override
                    public void accept(I value) {
                        screen.acceptFilterDrop(index, dragged);
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
            // 每次拖拽结束时 JEI 都会回调；写入已经在 accept 里完成并立刻发给服务端，这里无事可做
        }

        @Override
        public boolean shouldHighlightTargets() {
            return true;
        }
    }
}