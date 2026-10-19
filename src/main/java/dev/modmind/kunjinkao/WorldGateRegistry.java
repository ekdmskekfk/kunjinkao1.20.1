package dev.modmind.kunjinkao;

import dev.modmind.kunjinkao.block.WorldGateBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 主世界之门方块相关注册：方块与方块物品。
 * 物品沿用 {@link SwordRegistry#ITEMS}，方块单独一个 DeferredRegister。
 * <p>
 * 1.20.1 差异：{@code DeferredHolder}/{@code DeferredItem} → {@code RegistryObject}，
 * {@code BuiltInRegistries.BLOCK} → {@code ForgeRegistries.BLOCKS}。
 */
public class WorldGateRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, KunJinKaoEntry.MOD_ID);

    public static final RegistryObject<WorldGateBlock> WORLD_GATE_BLOCK =
            BLOCKS.register("world_gate", WorldGateBlock::new);

    public static final RegistryObject<Item> WORLD_GATE_ITEM =
            SwordRegistry.ITEMS.register("world_gate",
                    () -> new BlockItem(WORLD_GATE_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}