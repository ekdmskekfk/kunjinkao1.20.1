package dev.modmind.kunjinkao.overwrite;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「未定义区块」的落盘备份。
 * <p>
 * 屏障本身是普通方块，会跟着区块一起写进存档；而内存里的还原表（ZONES 静态字段）活不过一次进程退出。
 * 所以这里把「还剩多少 tick 到期」和「每个位置原本是什么方块」也写进存档：
 * 服务器在 600 tick 内关闭/崩溃、或者单机退档重进之后，靠这份数据在下次启动时兜底还原，
 * 避免存档里永久留下 3×3×2 的不可破坏屏障（普通玩家根本清不掉）。
 * <p>
 * 存放位置与本模组其它存档数据一致：主世界的 {@code getDataStorage()}。
 */
public final class UndefinedZoneSavedData extends SavedData {

    private static final String DATA_NAME = "kunjinkao_undefined_zones";
    private static final String ZONES_KEY = "Zones";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String REMAINING_KEY = "RemainingTicks";
    private static final String BLOCKS_KEY = "Blocks";
    private static final String POS_KEY = "Pos";
    private static final String STATE_KEY = "State";

    /**
     * 一条待还原的未定义区块记录。
     * <p>
     * 只记「剩余 tick」而不记绝对到期时间：绝对时间属于某个具体 ServerLevel 的 gameTime，
     * 退档重进后游戏刻不一定连续，存剩余时间才能表达「当时还剩多久」这个真实语义。
     */
    public static final class ZoneRecord {
        private final ResourceLocation dimension;
        private final Map<BlockPos, BlockState> originals;
        private long remainingTicks;

        ZoneRecord(ResourceLocation dimension, long remainingTicks, Map<BlockPos, BlockState> originals) {
            this.dimension = dimension;
            this.remainingTicks = remainingTicks;
            this.originals = originals;
        }

        public ResourceLocation dimension() {
            return dimension;
        }

        public long remainingTicks() {
            return remainingTicks;
        }

        public Map<BlockPos, BlockState> originals() {
            return originals;
        }

        void setRemainingTicks(long ticks) {
            this.remainingTicks = ticks;
        }
    }

    private final List<ZoneRecord> zones = new ArrayList<>();

    public static UndefinedZoneSavedData get(MinecraftServer server) {
        // 1.20.1 没有 SavedData.Factory：直接给 load / ctor / 名字。
        return server.overworld().getDataStorage().computeIfAbsent(
                UndefinedZoneSavedData::load, UndefinedZoneSavedData::new, DATA_NAME);
    }

    private static UndefinedZoneSavedData load(CompoundTag tag) {
        UndefinedZoneSavedData data = new UndefinedZoneSavedData();
        // 1.20.1 的存档反序列化拿不到注册表参数，Block 的查询直接从静态注册表要。
        HolderGetter<Block> blockLookup = net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup();
        for (Tag entry : tag.getList(ZONES_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag record = (CompoundTag) entry;
            ResourceLocation dimension = ResourceLocation.tryParse(record.getString(DIMENSION_KEY));
            if (dimension == null) {
                // 条目损坏就直接丢弃：宁可少还原一次，也不能把方块还原到错误的维度
                continue;
            }
            Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
            for (Tag blockEntry : record.getList(BLOCKS_KEY, Tag.TAG_COMPOUND)) {
                CompoundTag blockRecord = (CompoundTag) blockEntry;
                // 1.20.1：readBlockPos 直接收坐标子标签并返回 BlockPos（1.21 才是带 key、返回 Optional）。
                if (!blockRecord.contains(POS_KEY, Tag.TAG_COMPOUND)) {
                    continue;
                }
                BlockPos pos = NbtUtils.readBlockPos(blockRecord.getCompound(POS_KEY));
                originals.put(pos, NbtUtils.readBlockState(blockLookup, blockRecord.getCompound(STATE_KEY)));
            }
            if (!originals.isEmpty()) {
                data.zones.add(new ZoneRecord(dimension, record.getLong(REMAINING_KEY), originals));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ZoneRecord zone : zones) {
            CompoundTag record = new CompoundTag();
            record.putString(DIMENSION_KEY, zone.dimension.toString());
            record.putLong(REMAINING_KEY, zone.remainingTicks);
            ListTag blocks = new ListTag();
            for (Map.Entry<BlockPos, BlockState> entry : zone.originals.entrySet()) {
                CompoundTag blockRecord = new CompoundTag();
                blockRecord.put(POS_KEY, NbtUtils.writeBlockPos(entry.getKey()));
                blockRecord.put(STATE_KEY, NbtUtils.writeBlockState(entry.getValue()));
                blocks.add(blockRecord);
            }
            record.put(BLOCKS_KEY, blocks);
            list.add(record);
        }
        tag.put(ZONES_KEY, list);
        return tag;
    }

    /** 写入一条新记录；返回的对象同时交给内存里的 Zone 持有，到期还原之后据此删除落盘记录。 */
    public ZoneRecord add(ResourceLocation dimension, long remainingTicks, Map<BlockPos, BlockState> originals) {
        ZoneRecord record = new ZoneRecord(dimension, remainingTicks, originals);
        zones.add(record);
        setDirty();
        return record;
    }

    /** 刷新剩余时间。setDirty() 只是置一个标志位，真正的序列化仍由自动保存触发，所以每 tick 调用没有额外开销。 */
    public void updateRemaining(ZoneRecord record, long remainingTicks) {
        if (record.remainingTicks != remainingTicks) {
            record.setRemainingTicks(remainingTicks);
            setDirty();
        }
    }

    public void remove(ZoneRecord record) {
        if (zones.remove(record)) {
            setDirty();
        }
    }

    /** 启动兜底还原用的快照，避免遍历的同时删除集合元素。 */
    public List<ZoneRecord> snapshot() {
        return new ArrayList<>(zones);
    }
}
