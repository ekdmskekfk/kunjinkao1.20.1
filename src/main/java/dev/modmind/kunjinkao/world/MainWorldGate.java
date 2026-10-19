package dev.modmind.kunjinkao.world;

import dev.modmind.kunjinkao.KunJinKaoEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 「主世界之门」的传送逻辑。
 * <p>
 * 目标维度 {@code kunjinkao:main_world} 是本模组用数据包单独添加的主世界
 * （{@code data/kunjinkao/dimension/main_world.json}），与 {@code minecraft:overworld} 无关。
 * <p>
 * 规则：
 * <ul>
 *   <li>在任意非目标维度右键门方块 → 传送到自建主世界的固定落点，并记住当前坐标；</li>
 *   <li>在自建主世界里右键门方块 → 传送回进入前的位置**与维度**（记录缺失或落点已不安全则退回原版主世界出生点）。</li>
 * </ul>
 * 落点处理：只有落点无法安全站立时才动世界，且只替换"可替换"方块（不推平玩家建筑）；
 * 返回时会先校验落点是否仍能站人，不安全就不硬传送。
 * <p>
 * 1.20.1 移植差异：只有一处 API 改名 —— {@code ResourceLocation.fromNamespaceAndPath(...)}
 * 换成 {@code new ResourceLocation(...)}。其余签名（{@code teleportTo}/{@code getPersistentData}/
 * {@code getMinBuildHeight}/{@code Component.translatable}）在 1.20.1 完全一致。
 */
public final class MainWorldGate {

    /** 本模组自带的主世界维度。 */
    public static final ResourceKey<Level> MAIN_WORLD = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(KunJinKaoEntry.MOD_ID, "main_world"));

    /** 自建主世界中的固定落点水平坐标。 */
    private static final int SPAWN_X = 0;
    private static final int SPAWN_Z = 0;

    /** 安全平台半径：2 表示 5x5。 */
    private static final int PLATFORM_RADIUS = 2;

    /** 平台净空高度（脚下 + 头顶）。 */
    private static final int HEAD_ROOM = 2;

    private static final String RETURN_X = "KunJinKaoGateReturnX";
    private static final String RETURN_Y = "KunJinKaoGateReturnY";
    private static final String RETURN_Z = "KunJinKaoGateReturnZ";
    private static final String RETURN_YAW = "KunJinKaoGateReturnYaw";
    private static final String RETURN_PITCH = "KunJinKaoGateReturnPitch";
    /** 进入前所在的维度：原实现不记它，返回时恒定送往 overworld。 */
    private static final String RETURN_DIM = "KunJinKaoGateReturnDim";

    private MainWorldGate() {
    }

    /** 由门方块右键调用；只应在服务端执行。 */
    public static void use(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (player.level().dimension().equals(MAIN_WORLD)) {
            leave(player, server);
        } else {
            enter(player, server);
        }
    }

    private static void enter(ServerPlayer player, MinecraftServer server) {
        ServerLevel destination = server.getLevel(MAIN_WORLD);
        if (destination == null) {
            // 维度 JSON 未加载（数据包被禁用或加载失败）时明确告知，不静默失败。
            player.displayClientMessage(Component.translatable("message.kunjinkao.world_gate_missing"), true);
            return;
        }
        rememberReturnPosition(player);
        BlockPos landing = prepareLanding(destination, SPAWN_X, SPAWN_Z);
        player.teleportTo(destination,
                landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        player.displayClientMessage(Component.translatable("message.kunjinkao.world_gate_entered"), true);
    }

    private static void leave(ServerPlayer player, MinecraftServer server) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(RETURN_X) || !data.contains(RETURN_Y) || !data.contains(RETURN_Z)) {
            fallbackToOverworldSpawn(player, server);
            return;
        }
        // 回到"进入前所在的维度"：恒定传送到 overworld 会让从下界/末地进来的玩家
        // 被扔到主世界的下界坐标（8 倍比例错位），可能卡进山体或悬空。
        ServerLevel target = resolveReturnLevel(server, data);
        double x = data.getDouble(RETURN_X);
        double y = data.getDouble(RETURN_Y);
        double z = data.getDouble(RETURN_Z);
        float yaw = data.getFloat(RETURN_YAW);
        float pitch = data.getFloat(RETURN_PITCH);
        Vec3 spot = findSafeSpot(target, x, y, z);
        if (spot == null) {
            // 落点已经站不住人（回去时那里被填满/挖空）：不硬传送，退回主世界出生点。
            fallbackToOverworldSpawn(player, server);
            return;
        }
        // 传送成功后再消费记录：原来先清记录再传送，传送失败就永久丢失返回点。
        clearReturnPosition(data);
        player.teleportTo(target, spot.x, spot.y, spot.z, yaw, pitch);
        player.displayClientMessage(Component.translatable("message.kunjinkao.world_gate_returned"), true);
    }

    private static void fallbackToOverworldSpawn(ServerPlayer player, MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        player.displayClientMessage(Component.translatable("message.kunjinkao.world_gate_returned"), true);
    }

    /** 解析记录里的来源维度；缺失、解析失败或维度不存在时回落到原版主世界。 */
    private static ServerLevel resolveReturnLevel(MinecraftServer server, CompoundTag data) {
        String stored = data.getString(RETURN_DIM);
        ResourceLocation id = stored.isEmpty() ? null : ResourceLocation.tryParse(stored);
        if (id == null) {
            return server.overworld();
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        return level != null ? level : server.overworld();
    }

    /**
     * 在记录的坐标上下各 3 格内找一个"脚下实、身上空"的位置。
     *
     * @return 可站立坐标；找不到返回 null
     */
    private static Vec3 findSafeSpot(ServerLevel level, double x, double y, double z) {
        level.getChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        for (int offset = 0; offset <= 3; offset++) {
            Vec3 above = trySpot(level, x, y + offset, z);
            if (above != null) {
                return above;
            }
            if (offset > 0) {
                Vec3 below = trySpot(level, x, y - offset, z);
                if (below != null) {
                    return below;
                }
            }
        }
        return null;
    }

    private static Vec3 trySpot(ServerLevel level, double x, double y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() + HEAD_ROOM >= level.getMaxBuildHeight()) {
            return null;
        }
        BlockPos head = feet.above();
        BlockPos below = feet.below();
        boolean feetFree = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headFree = level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        boolean hasGround = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
        return feetFree && headFree && hasGround ? new Vec3(x, feet.getY(), z) : null;
    }

    private static void rememberReturnPosition(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        data.putDouble(RETURN_X, player.getX());
        data.putDouble(RETURN_Y, player.getY());
        data.putDouble(RETURN_Z, player.getZ());
        data.putFloat(RETURN_YAW, player.getYRot());
        data.putFloat(RETURN_PITCH, player.getXRot());
        // 记下来源维度，返回时才能回到正确的地方。
        data.putString(RETURN_DIM, player.level().dimension().location().toString());
    }

    private static void clearReturnPosition(CompoundTag data) {
        data.remove(RETURN_X);
        data.remove(RETURN_Y);
        data.remove(RETURN_Z);
        data.remove(RETURN_YAW);
        data.remove(RETURN_PITCH);
        data.remove(RETURN_DIM);
    }

    /**
     * 取得落点并保证其安全可站立：先强制加载目标区块，再取地表高度，
     * 然后铺一层 5x5 石台并清出站立空间。幂等——第二次进入时无需再改动任何方块。
     */
    private static BlockPos prepareLanding(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        feetY = Mth.clamp(feetY, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - HEAD_ROOM - 1);
        BlockPos feet = new BlockPos(x, feetY, z);
        // 落点本来就能站人时**不改动世界**：原实现无条件把 5x5x3 清成空气再铺石台，
        // 会把玩家（或其他人）在这块地上造的东西直接推平。
        if (isLandingUsable(level, feet)) {
            return feet;
        }
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                BlockPos floor = feet.offset(dx, -1, dz);
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                    level.setBlockAndUpdate(floor, Blocks.SMOOTH_STONE.defaultBlockState());
                }
                for (int dy = 0; dy <= HEAD_ROOM; dy++) {
                    BlockPos space = feet.offset(dx, dy, dz);
                    // 只清"可替换"方块（树叶、草、雪等），保留玩家放下的实体方块，避免推平建筑。
                    if (level.getBlockState(space).canBeReplaced()) {
                        level.setBlockAndUpdate(space, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return feet;
    }

    /** 落点 3x3 核心区是否已经能安全站立（脚下有碰撞、身上两格无碰撞）。 */
    private static boolean isLandingUsable(ServerLevel level, BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floor = feet.offset(dx, -1, dz);
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
                    return false;
                }
                for (int dy = 0; dy <= HEAD_ROOM; dy++) {
                    BlockPos space = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(space).getCollisionShape(level, space).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}