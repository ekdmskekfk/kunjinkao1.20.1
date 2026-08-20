package dev.modmind.my_mod;

import dev.modmind.my_mod.block.AcceleratorBlock;
import dev.modmind.my_mod.block.entity.AcceleratorBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 加速方块相关注册：方块、方块实体以及方块物品。
 */
public class AcceleratorRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ModMindEntry.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ModMindEntry.MOD_ID);

    public static final RegistryObject<AcceleratorBlock> ACCELERATOR_BLOCK =
            BLOCKS.register("accelerator", AcceleratorBlock::new);

    public static final RegistryObject<Item> ACCELERATOR_ITEM =
            SwordRegistry.ITEMS.register("accelerator",
                    () -> new BlockItem(ACCELERATOR_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<AcceleratorBlockEntity>> ACCELERATOR_BE =
            BLOCK_ENTITY_TYPES.register("accelerator",
                    () -> BlockEntityType.Builder.of(AcceleratorBlockEntity::new, ACCELERATOR_BLOCK.get()).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}