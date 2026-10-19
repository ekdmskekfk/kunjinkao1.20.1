# 移植手册：1.21.1 NeoForge → 1.20.1 Forge

> 目标：把 `F:\mcmodli\tang\neoforge1.21.1` 全量移植到本工程。
> 本文档记录**已验证的规律**，避免重复踩坑。最后更新：2026-10-18

## 1. 状态

**已移植并编译通过：23 个类**

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | 13 个零依赖 COM 类（Theme/Palette/HudFunction/QolSetting/IPool…） | ✅ |
| 2 | 3 个 SavedData + `AdminToolConfig`(416行) + `QolSettings` | ✅ |
| A | `KunJinKaoEntry` + `SwordRegistry`(最小版) + `KunJinKaoEntities` + `KunJinKaoHandEntity` + `KunJinKaoSounds` | ✅ |
| 3 | **剑**：`KunJinKaoSwordItem`(1092行，11 处 DataComponent→NBT) + 创意标签页 | 待做 |
| 4 | `world`(12) + `block`(2) + `block.entity`(1) + `compat`(3, AE2) | 待做 |
| 5 | `wand`(20) | 待做 |
| 6 | `event`(26) + `network`(37) —— **必须一起做**，双向 26 处引用 | 待做 |
| 7 | `client`(44) 渲染/HUD/GUI | 待做 |
| 8 | mixin 基础设施（1.20.1 要 MixinGradle，NeoForge 是内置的） | 待做 |
| 9 | 移植件 `cyou.rimuru.*`(115) + `data/` 按 1.20.1 格式改造 | 待做 |

## 2. 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.10.7-hotspot'   # 必须 JDK 17
$g = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat"
cmd /c "`"$g`" -p F:\mcmodli\tang\forge1.20.1 compileJava --offline --console=plain > $env:TEMP\build.log 2>&1"
```

- **用外部 Gradle 8.14.3，不要用 `gradlew.bat`**：本机 wrapper 要 8.12.1，发行版已不在缓存里，会报
  `gradle-instrumentation-agent-8.12.1.jar` 缺失。
- **`--offline` 就够了**：工具链已全量缓存，单次编译 **16–20 秒**。
- **不要用 `build` 任务**：会跑 `reobfJar` + 部署逻辑，慢且可能有副作用。`compileJava` 足够验证。

## 3. 工具链是怎么来的（关键，换机器要重做）

`maven.minecraftforge.net`（Cloudflare）在本机 TLS 握手会卡死，`maven.neoforged.net` 不可达。
解决方案两层，缺一不可：

### (a) 本地代理
Clash 等客户端开在 `127.0.0.1:7890`。已写进本工程 `gradle.properties`：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

> 离线构建不依赖它。换代理端口就改这几行；不需要了直接删掉。

### (b) 绕过插件 marker
`net.minecraftforge.gradle:6.0.x` 的插件 **marker 只有 forge maven 有**（插件门户只有 7.x，BMCLAPI 镜像不全）。
但**实现包** `net.minecraftforge.gradle:ForgeGradle:6.0.54` 在 Gradle 缓存里有。
init 脚本用 `resolutionStrategy.useModule` 直接指向它：

```groovy
beforeSettings { s ->
    s.pluginManagement {
        resolutionStrategy {
            eachPlugin {
                if (requested.id.id == 'net.minecraftforge.gradle') {
                    useModule('net.minecraftforge.gradle:ForgeGradle:6.0.54')
                }
            }
        }
    }
}
```

## 4. 查 1.20.1 真实 API 的手段（**本次最有价值的发现**）

别猜签名。反编译好的 MC+Forge 就在缓存里，直接 `javap`：

```powershell
$jar = "$env:USERPROFILE\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.4.0_mapped_official_1.20.1\forge-1.20.1-47.4.0_mapped_official_1.20.1.jar"
& "C:\Program Files\Microsoft\jdk-17.0.10.7-hotspot\bin\javap.exe" -classpath $jar 'net.minecraft.world.entity.EntityType$Builder'
```

## 5. 已验证的转换规律

| 1.21.1 (NeoForge) | 1.20.1 (Forge) | 备注 |
|---|---|---|
| `ModConfigSpec` / `.Builder` / `.IntValue` | `ForgeConfigSpec` / … | **纯改名**，`comment`/`define`/`defineInRange`/`push`/`pop`/`get`/`set` 签名完全一致 |
| `net.neoforged.neoforge.server.ServerLifecycleHooks` | `net.minecraftforge.server.ServerLifecycleHooks` | 换包 |
| `SavedData.load/save(CompoundTag, HolderLookup.Provider)` | `(CompoundTag)` | 少一个参数；工厂用 `computeIfAbsent(load, ctor, name)` |
| `DeferredHolder<A,B>` / `DeferredItem<T>` / `DeferredBlock<T>` | `RegistryObject<T>` | Forge 没有分型 API |
| `DeferredRegister.createItems(MOD_ID)` | `DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)` | |
| `DeferredRegister.create(BuiltInRegistries.X, MOD_ID)` | `DeferredRegister.create(Registries.X, MOD_ID)` | **只有** `(IForgeRegistry,String)`、`(ResourceKey,String)`、`(ResourceLocation,String)` 三个重载 |
| `net.neoforged.bus.api.IEventBus` | `net.minecraftforge.eventbus.api.IEventBus` | 注意**全限定**写法也要换 |
| `net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent` | `net.minecraftforge.event.entity.EntityAttributeCreationEvent` | |
| `EntityType.Builder#eyeHeight(float)` | **不存在** | 眼高由实体 `getEyeHeight` 决定 |
| `Attributes.STEP_HEIGHT` | **不存在**（1.20.5 才有） | 用默认 0.6，或实体里 `setMaxUpStep` |
| `dropCustomDeathLoot(ServerLevel, DamageSource, boolean)` | `(DamageSource, int looting, boolean)` | **没有 ServerLevel，多了 looting** |
| `CustomPacketPayload` + `StreamCodec` + `Type<>` | `SimpleChannel` + 整数判别 id + `encode/decode/handle` | **必须重写**，线格式可保持逐字节一致 |
| `PacketDistributor.sendToXxx` | `SimpleChannel.send(PacketDistributor.XXX, payload)` | |
| NeoForge `NeoForge.EVENT_BUS` | `MinecraftForge.EVENT_BUS` | |

## 6. 踩过的坑（别再踩）

1. **PowerShell 5.1 用 GBK 读 UTF-8 会把中文显示成乱码，并让行看起来"粘在一起"** —— 那是显示假象，文件没坏。判断文件真容用 `read` 工具，别信 `Get-Content`。
2. **写文件必须 UTF-8 无 BOM**：`[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))`。`Set-Content -Encoding UTF8` 会带 BOM。
3. **`gradle.properties` 是 ISO-8859-1**：中文要写成 `\uXXXX` 转义，否则 mod_name 会乱码。
4. **正则换泛型时 `DeferredHolder<EntityType<?>, EntityType<X>>` 会漏** —— 第一段自带 `<`，`[^,<>]+` 匹配不上。
5. **`KunJinKaoEntry` 是枢纽**，每个包都引用它，而它注册一切 → 每批要跟着加注册，不能一次写完。

## 7. 依赖

`libs/`（不进仓库）：Modrinth 下载的 1.20.1 版
- `appliedenergistics2-forge-15.4.11.jar`
- `neoecoae-20.4.2.jar`

两只都是 `compileOnly`。**注意 Modrinth 上 AE2 有 forge/fabric 两条同号版本，要按文件名筛 `*forge*`。**
---

## 8. 追加：剑与网络层的实测结论（2026-10-18 晚）

### 剑比预想的好得多

`KunJinKaoSwordItem` 共 **1223 行**，但 `DataComponent` 只出现 **11 次**，且全是
`DataComponents.CUSTOM_DATA` 容器本身。类里**早就有 NBT 抽象层**：

```java
public static CompoundTag dataTag(ItemStack stack)          // 读
public static void writeDataTag(ItemStack stack, CompoundTag tag)  // 写
```

40+ 个字段键（`LootingLevel` / `AttackDamageLimit` / …）全部操作 `CompoundTag`，
**与版本无关**。所以 1.20.1 的改法只是把这两个方法体换成：

```java
stack.getOrCreateTag()   // 代替 stack.get(DataComponents.CUSTOM_DATA).copyTag()
stack.setTag(tag)        // 代替 stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
```

**不是数据模型重写。** 另有一处 `CustomModelData`（模型选择），1.20.1 用
`stack.getOrCreateTag().getInt("CustomModelData")` 或 `ItemModelProperties` 对应。

唯一真正删掉的是：

```java
@Override public boolean canPerformAction(ItemStack stack, ItemAbility ability)
```

`ItemAbility` 是 NeoForge 概念，1.20.1 无对应物 → **整段删除**。影响：1.20.1 上
"扳手能力"只能靠 `c:tools/wrench` 标签被识别，看 ItemAbility 的模组（如 Mekanism）不会认。

### world 包的耦合比想象的小

12 个文件里**只有 2 个**引用包外类：
- `SwordAreaFields` → `AcceleratorBlockEntity`
- `SwordTimeAcceleration` → `NetworkHandler` + `TimeAccelStatusPayload` + `AcceleratorBlockEntity`

所以 **world 不需要先完成整个网络层**，只需要传输骨架 + 一条 payload。

### 网络传输骨架已完成（可编译）

`network/NetworkHandler.java` 已落地并通过编译。要点：
- `SimpleChannel` + `NetworkRegistry.newSimpleChannel`
- 判别 id 用 `nextMessageId` 递增，**只增不改**
- 发送辅助参数类型写成 `Object` → 1.21.1 的调用点零改动
- `readEnumSafe` 原样保留（防改造客户端越界序号踢连接）

**加 payload 的模板**（每移植一个就加一条）：

```java
CHANNEL.messageBuilder(X.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
        .encoder(X::encode)
        .decoder(X::decode)
        .consumerMainThread(X::handle)
        .add();
```

每个 payload 从 1.21.1 的 `StreamCodec.of(写lambda, 读lambda)` 机械拆成：

```java
public static void encode(X p, FriendlyByteBuf buf) { /* 写 lambda 的体 */ }
public static X decode(FriendlyByteBuf buf) { /* 读 lambda 的体 */ }
public static void handle(X p, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() -> { /* 原 handle 的体 */ });
    ctx.get().setPacketHandled(true);
}
```

### 修正后的批次顺序

```
网络骨架 ✅ → payload 按依赖顺序补 → world(12) → block.entity + compat → 剑 → wand → event → client → mixin → 移植件
```

原因：`world.SwordTimeAcceleration` 需要 `NetworkHandler` + `TimeAccelStatusPayload`，
所以网络要**提前**，不能等批次 6。
---

## 9. 下一轮开工包（照单走即可，不用重新摸索）

### 当前进度基线

```
java 源文件 26 / 294     已编译 class 34     资源 43
已移植：client(5) network(5) config(4) qol(2) wand(2) entity(2) sound(1) clientbridge(1) 根包(4)
```

最近一次编译：**BUILD SUCCESSFUL，18 秒**。开工第一件事是确认这个基线还成立。

### 第一步：批量转 14 个「易转」payload

`HudShieldHitPayload` 已完成，当样板。剩下 14 个**只需改 4 处**（模板见第 8 节）：

```powershell
# 环境
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.10.7-hotspot'
$g = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat"
cmd /c "`"$g`" -p F:\mcmodli\tang\forge1.20.1 compileJava --offline --console=plain > $env:TEMP\b.log 2>&1"
```

名单（全部只需 `handle` 转发 `ClientHooks.handleClientPayload`）：

```
AdminEyeStatePayload          AdminPasswordResultPayload    CosmeticStatePayload
ExcludedPlayersPayload        HudEntityActionResultPayload  HudEntityListPayload
HudStatePayload               HudTrueInvisibilityVisualPayload
OverwriteEffectPayload        QolStatePayload               SwordDrawAnimationPayload
TimeAccelStatusPayload        WandPreviewResultPayload      ZoneStatusPayload
```

每个的改法（**4 处**）：
1. 删 `implements CustomPacketPayload`
2. 删 `TYPE` 字段和 `type()` 方法（判别 id 改由 `NetworkHandler` 的 `nextMessageId` 给）
3. `STREAM_CODEC = StreamCodec.of(写, 读)` → 拆成 `encode(X, FriendlyByteBuf)` / `decode(FriendlyByteBuf)`
4. `handle(X, IPayloadContext)` → `handle(X, Supplier<NetworkEvent.Context>)`，体内改成
   `ctx.get().enqueueWork(() -> ClientHooks.handleClientPayload(payload)); ctx.get().setPacketHandled(true);`

然后在 `NetworkHandler.register()` 里按 `nextMessageId++` 逐条 `messageBuilder(...).add()`。
**注意判方向**：这 14 个里 `QolStatePayload` / `TimeAccelStatusPayload` / `ZoneStatusPayload`
等是 S2C（PLAY_TO_CLIENT），其余按 1.21.1 的 `reg.playToClient` / `playToServer` 原样照抄。

### 第二步：world 包 12 个类

网络骨架已有，`SwordTimeAcceleration` 的依赖（`NetworkHandler` + `TimeAccelStatusPayload`）在前一步就满足了。
注意 `SwordAreaFields` / `SwordTimeAcceleration` 会引用 `AcceleratorBlockEntity`，
所以 **`block.entity.AcceleratorBlockEntity` + `compat`(3, AE2) 可能要先做**。
先 `javap` 查 AE2 15.4.11 的 API，别猜。

### 第三步及以后

按第 8 节末尾的顺序：`block.entity + compat` → **剑** → `wand` → `event`(26) + 剩下 18 个 payload → `client`(44) → `mixin` → 移植件。

### 18 个「有自己逻辑」的 payload（留到 event 批次）

```
AcceleratorConfigPayload   AcceleratorFilterPayload   AcceleratorShowRangePayload
CollectBlockPayload        ManageExcludedPlayerPayload ManageHudEntityPayload
PlacementSyncPayload       QolSettingPayload          RemovePlacementPayload
SimpleActionPayload        SubmitAdminPasswordPayload SwordSettingPayload
ToggleDisguisePayload      ToggleOverwritePayload     ToggleTacticalHudPayload
ToggleThemePayload         WandPreviewRequestPayload  ZoneAdjustPayload
```

它们的 `handle` 要调 `event` 包的处理器（`KunJinKaoQolHandler`(920行)、`ZoneSelectionHandler` 等），
**必须在 event 批次里和处理器一起做**，否则只能写空壳。

### 每步的验收标准

1. `compileJava` 退出码 0
2. `Get-ChildItem build\classes\java\main -Recurse -Filter *.class | Measure-Object` 数量只增不减
3. 新写的文件里 `Select-String -Pattern 'neoforged'` 必须为空
4. **不要跑 `build` 任务**（会触发 reobfJar + 部署），`compileJava` 足够
---

## 10. 追加：payload 批量 + 加速器 + world 半边（2026-10-18 深夜）

### 进度基线（更新）

```
java 源文件 53 / 294     已编译 class 68
network 19（骨架 + 15 条注册）  block 1 + block.entity 1 + compat 1
world 8 / 12              config 4  qol 2  wand 2  entity 2  sound 1  client 5  clientbridge 1  根包 5
```

**最新一次编译：BUILD SUCCESSFUL，18 秒，68 个 class。**

### 已完成

- **14 个「易转」payload**：`AdminEyeState` / `AdminPasswordResult` / `CosmeticState` /
  `ExcludedPlayers` / `HudEntityActionResult` / `HudEntityList` / `HudState` /
  `HudTrueInvisibilityVisual` / `OverwriteEffect` / `QolState` / `SwordDrawAnimation` /
  `TimeAccelStatus` / `WandPreviewResult` / `ZoneStatus`。全部 S2C，
  `handle` 只转发 `ClientHooks.handleClientPayload(payload)`。
  `NetworkHandler` 注册表 1 → 15 条，`PROTOCOL_VERSION` 1 → 2。
- **`AcceleratorRegistry`**（`RegistryObject` + `ForgeRegistries`）、**`AcceleratorBlock`**
  （`useWithoutItem` → `use`）、**`AcceleratorBlockEntity`**（NBT 版）、
  **`compat.Ae2GridAcceleration`**（纯反射，逐字复制即可）。
- **world 8 个**：`PlacementUndoHistory`(逐字) `ZoneSelection`(逐字) `SwordAreaFields`(逐字，
  改 1 处) `TimeAccelSavedData` `SwordPlacementRegistry` `MainWorldGate` `ContainerMaterials`
  （NBT 重写）`EmcMaterials`（能力 API 换 Forge）。
- `ClientHooks` 补回 `Consumer<AcceleratorBlockEntity>` 界面回调，签名与 1.21.1 一致。

### 新踩的坑（**优先看这几条**）

1. **`F:` 盘是 exFAT，`write`/`edit` 工具的原子写入会失败**：
   `EISDIR: illegal operation on a directory, link ...`（exFAT 不支持硬链接）。
   绕法：`[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))`，
   中文完全正常。**并行发多个 `write` 调用还会互相踩 tmpdir**，要一个一个来。
2. **Gradle 在文件沙箱下起不来**：报 `Failed to load native library 'native-platform.dll'`。
   原因是它要写 `~/.gradle/native` 下的 `.lock`。编译目标在 `forge1.20.1`、会话工作区在
   `neoforge1.21.1` 时，`workspace-write` 不够，必须 `danger-full-access`。
3. **`MinecraftServer#getLevel` 在 1.20.1 已经返回 `ServerLevel`**（1.21 才放宽成 `Level`）。
   `if (!(server.getLevel(key) instanceof ServerLevel lv))` 会编译报错
   「模式类型 ServerLevel 是表达式类型 ServerLevel 的子类型」→ 直接赋给局部变量判 null。
4. **`SavedData.Factory` 在 1.20.1 不存在**，只有
   `computeIfAbsent(Function<CompoundTag,T>, Supplier<T>, String)`；
   `save/load` 都不带 `HolderLookup.Provider`。
5. **`DataComponents` 相关的两个类要整段重写**（数据模型换了，语义没变）：
   - `ContainerMaterials`：`DataComponents.CONTAINER` / `BUNDLE_CONTENTS` →
     潜影盒 `BlockEntityTag.Items`、收纳袋根上 `Items`，两者都是 `ItemStack.save` 的复合标签列表。
   - `AcceleratorBlockEntity`：`DataComponents.BLOCK_ENTITY_DATA` →
     `BlockItem.getBlockEntityData(stack)`；`ItemStack#save(registries)` → `save(new CompoundTag())`；
     `parseOptional(registries, tag)` → `ItemStack.of(tag)`；`saveWithoutMetadata(registryAccess())` →
     `saveWithoutMetadata()`。
6. **`net.neoforged.neoforge.common.ItemAbilities` → `net.minecraftforge.common.ToolActions`**，
   且 `getToolModifiedState(UseOnContext, ToolAction, boolean)` 的签名两边一致（不必改调用点）。
   另外 `BlockState#shouldSpawnTerrainParticles()` 在 1.20.1 叫 `shouldSpawnParticlesOnBreak()`。
7. **`Player#blockInteractionRange()` 不存在**：用
   `player.getAttributeValue(ForgeMod.BLOCK_REACH.get())`。
8. **`compat.WrenchAbilities` 无法移植**：它整体建立在 NeoForge 的 `ItemAbility` 上，
   1.20.1 没有对应物（与剑里的 `canPerformAction` 同源）→ **整类不移植**，在 PORTING.md 里留档即可。
9. **AE2 的 API 换代了，必须按本机 jar 实测**（`libs/appliedenergistics2-forge-15.4.11.jar`）：
   - `IMenuItem#getMenuHost` 在 15.4.11 是
     `(Player, int inventorySlot, ItemStack, BlockPos)`，**没有** `ItemMenuHostLocator`，
     也没有 `MenuLocators.forStack` → 先在背包里定位槽位号再传进去；
   - `getInventory()` 只在 `WirelessTerminalMenuHost` / `PortableCellMenuHost` 上，仍要运行时反射；
   - `GridHelper.getNodeHost(Level, BlockPos)` 签名与 1.21.1 一致，`Ae2GridAcceleration` 可逐字用。
10. **ProjectE 的能力取值换了代**：1.21.1 是
    `getCapability(EntityCapability)`；1.20.1 是 `getCapability(Capability)` 返回 `LazyOptional`，
    要再 `resolve()` 成 `Optional` 再 `orElse(null)`。

### 下一步（照这个顺序）

1. **剑 `KunJinKaoSwordItem`（批次 3）** —— 它现在是枢纽：`world.SwordToolHandler`
   要它的 `getLightningMode` / `LIGHTNING_VISUAL_ONLY` / `LIGHTNING_NATURAL`，
   `world.SwordTimeAcceleration` 要它的 NBT 抽象层。不先做剑，这两个 world 文件编不过。
2. `compat.Ae2ZoneSupply` + `world.ZoneExcavationJob`（照第 9 条 AE2 的实测方法，先 `javap` 再写）。
3. `world.SwordTimeAcceleration`、`world.SwordToolHandler`（收尾 world 12/12）。
4. 之后按第 8 节末尾顺序：`wand` → `event` + 剩下 18 个 payload → `client(44)` → `mixin` → 移植件。
---

## 11. 追加：剑 + wand + 事件三件套（2026-10-18 夜，第二次）

### 进度基线（更新）

```
java 源文件 81 / 294     已编译 class 113     BUILD SUCCESSFUL，18 秒
已移植：根包(8) network(19) world(12) wand(20) block(2) block.entity(1) compat(1)
        event(2) overwrite(2) recipe(1) config(4) qol(2) entity(2) sound(1) client(5) clientbridge(1)
```

world 包 **12/12 收尾**，wand 包 **20/20 全绿**，剑 `KunJinKaoSwordItem` 已落地并注册进创意标签页。

### 本批的核心发现：**事件层是最大的代际差**

1.20.1 没有 `net.minecraftforge.event.tick.*` 这个包，`PlayerTickEvent`/`ServerTickEvent`/
   `EntityTickEvent` 全都要改。对照表：

| 1.21.1 (NeoForge) | 1.20.1 (Forge) | 备注 |
|---|---|---|
| `event.tick.ServerTickEvent.Post` | `TickEvent.ServerTickEvent` + `phase == TickEvent.Phase.END` | 加一句 phase 判断即可 |
| `event.tick.PlayerTickEvent.Post` | `TickEvent.PlayerTickEvent` + phase | `event.player` 也存在，`getEntity()` 可用 |
| `event.tick.EntityTickEvent.Post` | `event.entity.living.LivingEvent.LivingTickEvent` | 只覆盖活体 |
| `entity.living.LivingIncomingDamageEvent` | `entity.living.LivingAttackEvent` | **没有 amount** |
| `entity.living.LivingDamageEvent.Pre` + `setNewDamage` | `entity.living.LivingDamageEvent` + `setAmount` | 1.20.1 的 LivingDamageEvent 是护甲减免**之后**，语义最接近 `.Pre` |
| `PlayerInteractEvent#getEntity()` 返回 `Entity` | 直接返回 `Player` | 不能再写 `instanceof Player p` 模式匹配（会报"表达式类型的子类型"） |
| `@EventBusSubscriber(bus = GAME)` | `@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)` | 也可手动 `MinecraftForge.EVENT_BUS.register(...)` |

### 其余新坑（按类归并，都是机械改动）

1. **剑的构造器**：1.20.1 是 `SwordItem(Tier, int, float, Properties)`；
   `properties.attributes(SwordItem.createAttributes(...))` 是 1.21 的写法。
2. **`Item#appendHoverText`** 第二参在 1.20.1 是 `Level`（1.21 是 `Item.TooltipContext`）；
   **`getUseDuration`** 只有 `(ItemStack)`。
3. `Attributes.BLOCK_INTERACTION_RANGE` 不存在 → `ForgeMod.BLOCK_REACH.get()`。
4. **战利品表**：`EntityType#getDefaultLootTable()` 返回 `ResourceLocation`（不是 `ResourceKey`）；
   取表用 `MinecraftServer#getLootData().getLootTable(id)`；
   `LootContextParams.ATTACKING_ENTITY/DIRECT_ATTACKING_ENTITY` 叫 `KILLER_ENTITY/DIRECT_KILLER_ENTITY`。
5. **药水效果**：`MobEffectInstance#getEffect()` 直接返回 `MobEffect`（1.21 是 `Holder`，多一层 `.value()`）。
6. **属性修饰符**：`AttributeModifier(UUID, String name, double, Operation)`，
   `Operation` 只有 `ADDITION / MULTIPLY_BASE / MULTIPLY_TOTAL`（没有 `ADD_VALUE` / `ADD_MULTIPLIED_BASE`）；
   `getModifier/removeModifier` 收 `UUID`；`LivingEntity#getAttribute` 收 `Attribute`（不是 `Holder<Attribute>`）。
7. **配方 API**：`CraftingContainer` 在 **`net.minecraft.world.inventory`** 包（不是 crafting 包）；
   `CustomRecipe` 构造器要 `(ResourceLocation, CraftingBookCategory)`；
   `assemble(..., RegistryAccess)`；`SimpleCraftingRecipeSerializer` 的工厂是 `(id, category) -> T`。
8. **NBT 工具**：`NbtUtils.readBlockPos(CompoundTag)` 直接返回 `BlockPos`（1.21 才是带 key、返回 `Optional`）；
   `readBlockState(HolderGetter<Block>, tag)` 需要自己给 `BuiltInRegistries.BLOCK.asLookup()`。
9. **`ItemStack.parseOptional(registries, tag)` → `ItemStack.of(tag)`**，`save(registries)` → `save(new CompoundTag())`。

### 一处**功能降级**（不是移植遗漏）

**1.20.1 没有 `TickRateManager`**（`ServerTickRateManager` 是 1.20.3 才加入的），
所以 `SwordTimeAcceleration` 里"对着天空右键 → 拔高全服 tick 率"这条**在 1.20.1 上不可用**。
处理方式：`SwordTimeAcceleration.TICKRATE_SUPPORTED = false`，`applyTickrate` 是空操作，
局部加速（shift+右键方块）完全正常。将来若要补，只能自己做 tick 循环外挂，风险远大于收益。

### 写法上的两个教训

1. **源文件是 LF 换行**，`$t.Replace("...`r`n...")` 会**静默不匹配**（改了等于没改）。
   要么用单行 `Replace`，要么按行号 `ReadAllLines/WriteAllLines` 改。
2. **按行号 `RemoveRange` 极易把括号删歪**（本批弄坏过一次 `UndefinedZoneSavedData`，
   靠重写整个方法体修回来）。改完务必再编译一次确认行号没错。

### 下一步

1. **剩下 23 个 event 处理器**（`KunJinKaoQolHandler` 47KB、`KunJinKaoColdDataEffectsHandler` 22KB 是两个大头），
   照第 11 节的事件对照表逐类改签名；同时在 `KunJinKaoEntry` 里照 1.21.1 的顺序补
   `MinecraftForge.EVENT_BUS.register(...)`（已注册 3 个：Death / Protection / Overwrite）。
2. **剩下 18 个「有自己逻辑」的 payload**（见第 9 节名单）—— 它们的 handle 要调上面那些处理器。
3. `client(44)` → `mixin` → 移植件 `cyou.rimuru.*`(115) + `data/`。
---

## 12. 追加：事件层大面积落地（2026-10-18 夜，第三次）

### 进度基线（更新）

```
java 源文件 98 / 294     已编译 class 131     BUILD SUCCESSFUL，18 秒
event 19 个（含死亡/保护/覆写三个老批次）+ 18 个新处理器已注册进 MinecraftForge.EVENT_BUS
```

本批从 24 个待移植处理器里落地 **18 个**，并修掉它们带出来的 142 个编译错误。

### 本批最大的坑：**1.20.1 没有 BlockDropsEvent**

NeoForge 的 `BlockDropsEvent`（能拿到「已经生成好的掉落实体」并就地改写）在 1.20.1 **完全没有对应物**：
`net.minecraftforge.event.level.BlockEvent` 下只有 `BreakEvent` / `EntityPlaceEvent` /
`NeighborNotifyEvent` / `CropGrowEvent` 等，没有 `HarvestDrops` 也没有 `BlockDrops`。

**可用的替代写法（本批已验证）**：在 `BlockEvent.BreakEvent` 里"自己接管这一刀"——
破坏前按战利品表算好掉落，`event.setCanceled(true)`，再 `level.destroyBlock(pos, false, player)`
把方块以"不掉落"方式移除，最后把掉落直接塞背包 / 过熔炼配方。
对玩家表现与原实现一致，代价是这条路径不再派发原版的"破坏后"事件。

已按这个思路重写：`SwordAutoPickupHandler`（自动入包）。
**`KunJinKaoAutoSmeltHandler` 只落了换算半边**（`isActive` / `smeltAll` / `smeltList`），
事件钩子留到下一批和其它 BreakEvent 接管逻辑一起做 —— 否则同一个 BreakEvent 上
会有两处各自 setCanceled + destroyBlock，互相打架。

### 其余新坑

1. **`RecipeManager#getRecipeFor` 在 1.20.1 直接返回 `Optional<T>`**（1.21 是 `Optional<RecipeHolder<T>>`），
   所以 `.map(holder -> holder.value().assemble(...))` 要改成 `.map(recipe -> recipe.assemble(...))`。
2. **没有 `SingleRecipeInput`**（1.20.1 不存在这个类）→ 用 `new SimpleContainer(stack)` 当熔炼输入。
3. **`ForgeHooks.onBlockBreakEvent(Level, GameType, ServerPlayer, BlockPos)`** 返回 `int`
   （-1 = 被保护类模组拒绝），不是 NeoForge 的 `fireBlockBreak(...)->Event`。
4. **`EntityType.BOGGED`** 是 1.21 才加的 → 1.20.1 的头颅掉落表里必须删掉这一行。
5. `LivingEntity` 类事件（`LivingEvent` 子类）的 `getEntity()` 已经返回 `LivingEntity`，
   不能再写 `instanceof LivingEntity victim`（会报"表达式类型的子类型"）。
6. `TickEvent.PlayerTickEvent` 里取玩家用 **`event.player`**，`event.getEntity()` 也可用；
   但在 `LivingAttackEvent` 里**没有** `player` 字段，别照抄。

### PowerShell 教训（这次真被咬到）

`[System.IO.File]::WriteAllText($path, $lines, $enc)` 传 `List[string]`/`string[]` 时，
PowerShell 会把数组**用空格拼成一行**写进文件 —— 整个类变成单行源码（编译直接炸）。
**写多行一律用 `WriteAllLines`**；`WriteAllText` 只接单个字符串。
另外 `$l.RemoveRange(start, n)` 之后再 `InsertRange` 前，务必先把 `start` 打印出来核对，
本批一次删到了错误的方法体上，只能从源文件整体重做。

### 仍未移植（下一批，全部卡在同一条链上）

`Ae2ZoneSupply`(20KB) → `ZoneExcavationJob`(34KB) → 于是这 8 个文件一起等：
`KunJinKaoQolHandler`(47KB) / `KunJinKaoColdDataEffectsHandler`(22KB) /
`ZoneSelectionHandler` / `KunJinKaoStandFirmHandler` / `KunJinKaoTradeHandler` /
`SwordMiningPenaltyHandler` / `SwordAreaFieldHandler`（要 `PlacementSyncPayload`）/
`KunJinKaoAutoSmeltHandler` 的事件钩子。

顺带记一条：`KunJinKaoQolHandler` 被 5 个其它处理器引用（`isOn` / `toggleNightVision` /
`sendState` / `trySetZoneCorner`），**它不能单独推迟**，必须和 `ZoneExcavationJob` 一起进来。
---

## 13. 追加：选区挖掘链打通，event/world/wand 三包收尾（2026-10-18 夜，第四次）

### 进度基线（更新）

```njava 源文件 108 / 294     已编译 class 147     BUILD SUCCESSFUL
event 26/26  world 12/12  wand 20/20  overwrite 2/2
compat 2/3（WrenchAbilities 整类不移植）  network 20/37  client 3/44
``

### 本批做的事

1. `compat.Ae2ZoneSupply`(20KB) 逐字复制 —— 纯反射 + 纯原版 API，一行没改。
2. `world.ZoneExcavationJob`(34KB) 只改 2 处：`ResourceLocation.fromNamespaceAndPath` → `new ResourceLocation`、
   `ItemStack.isSameItemSameComponents` → `isSameItemSameTags`。
   **印证第 8 节的判断：整条选区挖掘链几乎没有版本耦合**，卡住的只是包依赖顺序。
3. 补回上一批推迟的 7 个处理器（QolHandler 47KB / ColdDataEffects 22KB / ZoneSelection / StandFirm /
   Trade / SwordMiningPenalty / SwordAreaField），外加 `PlacementSyncPayload`（第 16 条注册，版本 2 → 3）。

### 新坑

1. `TickEvent.PlayerTickEvent` 上 `event.getEntity()` 部分写法解析不到 → 直接用公有字段 `event.player`。
2. `AttributeModifier` 的 ResourceLocation 家族在 QolHandler 里再出现一次（长臂善舞）：
   `ResourceLocation` → `UUID`、`Operation.ADD_VALUE` → `ADDITION`、`existing.amount()` → `getAmount()`。
3. `Attributes.BLOCK_INTERACTION_RANGE` / `ENTITY_INTERACTION_RANGE` → `ForgeMod.BLOCK_REACH` / `ENTITY_REACH`。
4. `Attributes.SUBMERGED_MINING_SPEED` → `ForgeMod.SWIM_SPEED`（1.20.1 没有前者）。
5. `PlacementSyncPayload.handle` 原文直接引用 client.render / client.gui 的类；1.20.1 侧必须先过 ClientHooks 网关，
   否则专用服务器加载 common 类就炸。现已留 `applyClientCache`（纯数据），界面那半交给 client 批次。

### 下一步

1. 剩下 17 个 payload。SimpleAction / SwordSetting / QolSetting 的 handle 要调 QolHandler 的静态入口 —— 这些入口现已齐。
2. 之后 client(44) → mixin → 移植件 cyou.rimuru.*(115) + data/。### 2026-10-19 追加：网络层 37/37 收尾

```
java 源文件 125 / 294     已编译 class 164     BUILD SUCCESSFUL，21 秒
network 37/37 ✅（全部 payload 落地并注册）
```

本批落地最后 17 个 payload：`ManageExcludedPlayer` / `SubmitAdminPassword` / `ToggleTacticalHud` / `CollectBlock` /
`ToggleDisguise` / `ToggleOverwrite` / `ToggleTheme` / `ManageHudEntity` / `AcceleratorConfig` / `AcceleratorShowRange` /
`AcceleratorFilter` / `RemovePlacement` / `QolSetting` / `ZoneAdjust` / `WandPreviewRequest` / `SimpleAction` / `SwordSetting`。
`NetworkHandler` 注册表从 16 条涨到 **33 条**，`PROTOCOL_VERSION` 3 → 5。

C2S 的统一模板（与 S2C 不同的一点）：

```java
public static void handle(X payload, Supplier<NetworkEvent.Context> contextSupplier) {
    NetworkEvent.Context context = contextSupplier.get();
    ServerPlayer sender = context.getSender();      // 1.21 的 context.player()
    context.enqueueWork(() -> { /* 原来的体内逻辑，context.player() 换成 sender */ });
    context.setPacketHandled(true);
}
```

坑：`AcceleratorFilterPayload` 在 1.21 用的是 `ItemStack.OPTIONAL_STREAM_CODEC` +
`RegistryFriendlyByteBuf`（要注册表上下文），1.20.1 两者都没有 → 用
`FriendlyByteBuf#writeItem / #readItem`，空槽写"数量 0"读回 `ItemStack.EMPTY`，语义等价。

### 环境坑（重要，浪费了十几分钟）

**会话恢复后沙箱会把进程权限收回去**：`compileJava` 报 `spawn EPERM`，
连 `java -version` 都起不来（但 `cmd /c echo` 这类内建命令仍可用）。
绕法：给那一次工具调用显式加 `sandbox_permissions: danger-full-access` 即可恢复。`r`n（2026-10-19 补记：**这不是每轮都要**。同一会话里跑过一次提权之后，后续不加也能通过；`r`n 只有“会话恢复/隔了很久”之后的第一次可能又要一次。）
判断特征：报错是 **spawn EPERM**，不是编译错误 —— 不要以为是代码写坏了。### 2026-10-19 追加：client 包分批启动（14 / 54 落地）

```
java 源文件 134 / 294     已编译 class 178     BUILD SUCCESSFUL，15 秒
client 14（src） + 40（暂存，机械变换已做）
```

**整包一次吃不下**：54 个文件里大量是"1.21 渲染新 API"，首轮编译 226 个错误。
所以改成**分组落地**，已写好的机械变换结果放在
`F:\mcmodli\tang\forge1.20.1\.porting-staging\client\`（不在 `src` 下，不参与编译），
下一批直接 Move 回来就能继续。

本批落地的 14 个（纯状态 / 无渲染依赖）：
`ClientHudState` `AdminEyeVisualState` `TacticalHudInvisibilityVisualState` `KunJinKaoTooltipColorHandler`
`KunJinKaoClientOverwriteEffects` `RemoteSwordDrawVisualState` `ShieldHitVisualState` `TimeAccelClientState`
`StandFirmClient` `hud/function/*` `qol/QolClientState` `qol/ZoneClientState` `qol/QolPanelConfig`

### client 包的 API 缺口清单（下一批按这个改）

| 1.21.1 | 1.20.1 | 影响文件 |
|---|---|---|
| `net.minecraft.client.resources.PlayerSkin`（record） | **不存在** → `SkinManager` + `AbstractClientPlayer#getSkinTextureLocation()/getModelName()` | 12 处（`HoneycombShieldLayer` 等） |
| `ClientTickEvent.Pre/Post` | `TickEvent.ClientTickEvent` + `Phase` | `KunJinKaoClientEvents` / `StandFirmClient` ✅已改 |
| `RegisterGuiLayersEvent` + `LayeredDraw` | `RegisterGuiOverlaysEvent` + `IGuiOverlay#render(GuiGraphics, float, int, int)` | `EyeHudLayer` / `HudOverlayRenderer` / `TimeAccelOverlayRenderer` |
| `Screen#renderBackground(GuiGraphics,int,int,float)` 新签名 | 1.20.1 是 `renderBackground(GuiGraphics, int, int, float)` 的**旧**重载 | 8 处（各 Screen） |
| `ContainerEventHandler#mouseScrolled(double,double,double,double)` | 1.20.1 是 `(double,double,double)`（3 个 double） | 10 处（各 Screen） |
| `Model#renderToBuffer(PoseStack,VertexConsumer,int,int,int)` | 一致；但 1.21 多出的 `int color` 重载没有 | `KunJinKaoHandModel` |
| `Map<ModelResourceLocation,BakedModel>` | 1.20.1 的 `ModelBakery`/`ModelEvent` 泛型是 `ModelResourceLocation` | `ClientModEvents` |
| `net.minecraft.world.level.block.entity.vault.VaultBlockEntity` / `TrialSpawnerBlockEntity` | **1.21 才有**（试炼厅） | `QolTreasureScanner` |
| `mezz.jei.*` | 本工程 `libs/` **没有 JEI**（只有 AE2 + neoecoae），JEI 只能 compileOnly | `jei/AcceleratorJeiPlugin` |

**结论**：`jei/AcceleratorJeiPlugin` 建议整类不移植（1.20.1 上 JEI 集成是可选的配方预览），
除非往 `libs/` 补一个 1.20.1 的 JEI jar。

### 下一批建议顺序（都在暂存区，按依赖从里到外）

1. `render/SwordWorldEffects` `render/SwordHitFeedback` `render/SwordAmbientParticles` `render/TrueInvisibilityRenderHandler`
   —— 这几只碰粒子/世界效果，不碰 GUI。
2. `ClientPayloadHandlers` + `TacticalHudClientPacketHandler` —— 它们把 payload 分发给上面那些状态类。
3. `hud/*` 与 `render/EyeHudLayer` —— HUD Layer → Overlay 的改造。
4. `gui/*`（4 个 Screen，最大的 `SwordOptionsScreen` 69KB）。
5. `KunJinKaoClientEvents` / `ClientModEvents` —— 最后装配。### 2026-10-19 追加：client 第二批（27 / 54）

```
java 源文件 147 / 294     已编译 class 194     BUILD SUCCESSFUL
client 27（src） + 27（暂存）
```

本批落地：
- **世界/粒子渲染**：`SwordWorldEffects` `SwordAmbientParticles` `SwordHitFeedback` `TrueInvisibilityRenderHandler`
  `ZoneSelectionRenderer` `PlacementPreviewRenderer` `WandPreviewRenderer`
- **Screen**：`AdminPasswordScreen` `HudScreen` `HudEntityScreen` `HudExclusionScreen`
- **HUD 与按键**：`HudOverlayRenderer` `KunJinKaoKeyBindings`

### 本批新坑（都是 1.20.1 的旧签名）

1. **`Screen#renderBackground` 在 1.20.1 只有单参版**：`renderBackground(GuiGraphics)`。
   1.21 的四参重载 `(GuiGraphics, int, int, float)` 不存在 → 覆写方法签名和 `super.` 调用都要去掉后三个参数。
2. **`mouseScrolled` 在 1.20.1 是 3 个 double**：`(double mouseX, double mouseY, double delta)`。
   1.21 多了水平增量 `deltaX` → 覆写签名与 `super.mouseScrolled(...)` 都要少一个参数。
3. `ItemStack.isSameItemSameComponents` → `isSameItemSameTags`（第 N 次出现，**全局搜一遍比等编译报错快**）。

> 小结：这三条已按"src + 暂存区一起改"处理，暂存区里 4 个 Screen 也已修好，
> 后面 Move 回来不会再报同一批错。

### 暂存区还剩 27 个（按难度分组）

| 组 | 文件 |
|---|---|
| 世界渲染（重） | `CosmeticRenderer` `KunJinKaoClientSwordVisuals`(31KB) `SwordSwingTrail` `QolTreasureRenderer` `KunJinKaoGlowModels` `KunJinKaoHandRenderer` `KunJinKaoHandModel` `AcceleratorBlockEntityRenderer` |
| 实体层/手臂 | `HoneycombShieldLayer` `KunJinKaoThirdPersonGrabLayer`（依赖 `PlayerSkin`，要改 `SkinManager`） |
| HUD | `EyeHudLayer` `TimeAccelOverlayRenderer` `KunJinKaoOverwriteHudOverlay`（Layer → Overlay） |
| Screen（大） | `AcceleratorScreen` `SwordOptionsScreen`(69KB) `QolControlScreen`(46KB) |
| 装配/分发 | `ClientPayloadHandlers` `TacticalHudClientPacketHandler` `ClientModEvents` `KunJinKaoClientEvents` `ClientBlockScreens` `QolKeybinds` `QolFogHandler` `CosmeticClientState` |
| 暂不移植 | `QolTreasureScanner`（试炼厅方块 1.21 才有） `AcceleratorJeiPlugin`（缺 JEI 依赖） |### 2026-10-19 追加：client 第三批（31 / 54）

```
java 源文件 151 / 294     已编译 class 199     BUILD SUCCESSFUL
client 31（src） + 23（暂存）
```

新增落地：`CosmeticClientState` `render/CosmeticRenderer` `render/QolTreasureRenderer` `qol/QolTreasureScanner`。

新坑两条：

1. **`RenderLevelStageEvent#getPartialTick()` 在 1.20.1 直接返回 `float`**
   （1.21 是 `DeltaTracker`，要再 `.getGameTimeDeltaPartialTick(false)`）。
2. **`QolTreasureScanner` 里的试炼厅分支必须删**：`TrialSpawnerBlockEntity` / `VaultBlockEntity`
   都是 1.21 才有的类。删掉这两支后 `isTreasure(...)` 以 `return false;` 收尾，
   行为等价于"1.20.1 上没有这两类宝藏容器"。

> 另外更正上一节：**沙箱提权不是每轮都要**。同一会话里成功跑过一次之后，
> 后续 `compileJava` 不加 `sandbox_permissions` 也能通过；只有会话恢复后的第一次可能又需要一次。### 2026-10-19 追加：client 第四批（35 / 54）

```
java 源文件 155 / 294     已编译 class 204     BUILD SUCCESSFUL
client 35（src） + 19（暂存）
```

新增：`render/AcceleratorBlockEntityRenderer` `render/KunJinKaoGlowModels` `render/KunJinKaoHandRenderer`
`model/KunJinKaoHandModel`。

### 新坑（渲染底层 API 换代）

1. **`VertexConsumer`**：1.21 是 `addVertex(pose,x,y,z).setColor(r,g,b,a)`；
   1.20.1 是 `vertex(pose, x, y, z).color(r, g, b, a).endVertex()`。
   （1.20.1 有 `vertex(Matrix4f/ PoseStack.Pose, float,float,float)` 重载可用。）
2. **`ModelResourceLocation.standalone/inventory` 是 1.21 的工厂方法**；1.20.1 用
   `new ModelResourceLocation(id, "standalone")` / `new ModelResourceLocation(id, "inventory")`。
3. **`ModelEvent.ModifyBakingResult#getModels()` 在 1.20.1 返回 `Map<ResourceLocation, BakedModel>`**
   （1.21 才是 `Map<ModelResourceLocation, BakedModel>`）。
4. **`Model#renderToBuffer` 在 1.20.1 是 8 参**：`(PoseStack, VertexConsumer, int light, int overlay, float r, float g, float b, float a)`；
   `ModelPart#render` 只有 4 参 `(PoseStack, VertexConsumer, int, int)`（1.21 多一个 color）。
5. **`getRenderBoundingBox` 的钩子在 1.20.1 属于「方块实体」不属于「渲染器」**：
   `net.minecraftforge.common.extensions.IForgeBlockEntity#getRenderBoundingBox()`
   vs 1.21 的 `BlockEntityRenderer#getRenderBoundingBox(T)` → 方法要从渲染器搬到 BlockEntity。

### 又一次被行号编辑咬到

给 `KunJinKaoHandModel` 改 `renderToBuffer` 时，按行号替换把方法结尾的 `}` 覆盖掉了，
编译报的是「进行语法分析时已到达文件结尾」+ 重复 `@Override`。
**结论（第三次了）：按行号改完必须立刻把改动区域打印出来核对**，别直接进编译。

### 暂存区还剩 19 个

`KunJinKaoClientSwordVisuals`(31KB) `render/SwordSwingTrail` `render/HoneycombShieldLayer`
`render/KunJinKaoThirdPersonGrabLayer` `render/EyeHudLayer` `render/KunJinKaoIdleDataRefreshHandler`
`KunJinKaoOverwriteHudOverlay` `TimeAccelOverlayRenderer` `gui/AcceleratorScreen`
`gui/SwordOptionsScreen` `gui/QolControlScreen` `ClientModEvents` `KunJinKaoClientEvents`
`QolFogHandler` `QolKeybinds` `ClientBlockScreens` `ClientPayloadHandlers`
`TacticalHudClientPacketHandler` `jei/AcceleratorJeiPlugin`### 2026-10-19 追加：client 第五批（42 / 54）

```
java 源文件 162 / 294     已编译 class 216     BUILD SUCCESSFUL
client 42（src） + 12（暂存）
```

新增：`qol/QolKeybinds` `QolFogHandler` `ClientBlockScreens` `render/SwordSwingTrail`
`render/KunJinKaoIdleDataRefreshHandler` `KunJinKaoClientSwordVisuals`(31KB) `gui/AcceleratorScreen`(21KB)。

### 新坑

1. **`CompoundTag#copyTag()` 是 1.21 的名字，1.20.1 叫 `copy()`**。
2. **Screen 的 `renderBackground(GuiGraphics)` 拿不到鼠标坐标**是"真问题"不是"编译噪音"：
   `AcceleratorScreen` 的面板悬停高亮写在 `renderBackground` 里（1.21 那版有四参重载，能拿到 mouseX/Y）。
   1.20.1 的解法：在 `render(graphics, mouseX, mouseY, partialTick)` 里把坐标记进
   `hoverMouseX/hoverMouseY` 两个字段，`renderBackground` 读这份副本（`super.render` 会先调
   `renderBackground`，所以赋值必须放在 `super.render(...)` **之前**）。
   **以后遇到"Screen 里 mouseX 找不到符号"就照这个套路改。**
3. `VertexConsumer` 旧版接口（`vertex/color/endVertex`）在 `SwordSwingTrail` 里也出现了一次 ——
   这条已在第 12 节记过，属重复劳动，说明**同一类改动应该先在暂存区全量搜一遍再编译**。

### 暂存区还剩 12 个

`gui/SwordOptionsScreen`(69KB) `gui/QolControlScreen`(46KB) `render/HoneycombShieldLayer`
`render/KunJinKaoThirdPersonGrabLayer` `render/EyeHudLayer` `KunJinKaoOverwriteHudOverlay`
`TimeAccelOverlayRenderer` `ClientModEvents` `KunJinKaoClientEvents` `ClientPayloadHandlers`
`TacticalHudClientPacketHandler` `jei/AcceleratorJeiPlugin`### 2026-10-19 追加：client 第六批（45 / 54）

```
java 源文件 165 / 294     已编译 class 220     BUILD SUCCESSFUL
client 45（src） + 9（暂存）
```

新增：`render/HoneycombShieldLayer` `render/KunJinKaoThirdPersonGrabLayer` `render/EyeHudLayer`。

### 新坑

1. **`EntityRenderersEvent.AddLayers#getSkin`**：1.21 是 `getSkin(PlayerSkin.Model.WIDE/SLIM)`；
   1.20.1 收 **String** —— `getSkin("default")` / `getSkin("slim")`。
2. **`player.getSkin().texture()`**（1.21）→ **`player.getSkinTextureLocation()`**（1.20.1）。
3. **`VertexConsumer#normal` 收 `Matrix3f`**，不是 `PoseStack.Pose`：
   `.normal(pose, x, y, z)` → `.normal(pose.normal(), x, y, z)`。
4. `GuiGraphics#setColor` 与 `VertexConsumer#color` **同名不同物** —— 见下面的血泪教训。

### 血泪教训：正则会误伤同名 API

我用一条通用正则把 `.setColor(...)` → `.color(...).endVertex()` 一次性刷到 `src` + 暂存区，
结果**把 `GuiGraphics.setColor(...)` 也改了**（那是 GUI 叠加色，不是顶点接口），
还把已经手工改过的 `HoneycombShieldLayer` 刷成了 `.endVertex().endVertex()`。

**结论**：批量替换必须带上"接收者类型"的限定（比如只刷 `consumer.` / `buffer.` 前缀的行），
或者先 `Select-String` 列出命中行、人眼过一遍再替换。**同名 API 的版本迁移不能靠盲刷。**

### 暂存区还剩 9 个（下一批就是它们，之后 client 收尾）

`gui/SwordOptionsScreen`(69KB) `gui/QolControlScreen`(46KB) `KunJinKaoOverwriteHudOverlay`
`TimeAccelOverlayRenderer` `ClientModEvents` `KunJinKaoClientEvents` `ClientPayloadHandlers`
`TacticalHudClientPacketHandler` `jei/AcceleratorJeiPlugin`(建议不移植)

其中 `EyeHudLayer` 已经进来了，HUD 那三个还剩 **Layer → Overlay 的结构改造**没做；
装配类（`ClientModEvents` / `ClientPayloadHandlers` 等）必须等它们齐了再进。### 2026-10-19 追加：client 第七批（47 / 54）

```
java 源文件 167 / 294     已编译 class 222     BUILD SUCCESSFUL
client 47（src） + 7（暂存）
```

新增：`ClientPayloadHandlers` `TacticalHudClientPacketHandler`（两个客户端分发器）。

改法只有一处：`ClientPayloadHandlers.handle(CustomPacketPayload, IPayloadContext)` →
`handle(Object, NetworkEvent.Context)` —— 1.20.1 既没有 `CustomPacketPayload` 也没有
`IPayloadContext`，所以整条链路（`ClientHooks` 网关 → 这里）统一用 `Object` 传 payload。

> 能顺利进来，说明前面几批把 `HudScreen` / `AdminPasswordScreen` / `WandPreviewRenderer` /
> `CosmeticClientState` / `QolClientState` / `ZoneClientState` 都先落地是对的 ——
> **分发器是汇合点，必须最后进**。

### 暂存区还剩 7 个

| 文件 | 卡在哪 |
|---|---|
| `gui/SwordOptionsScreen`(69KB) | 大件 Screen，预计一批 |
| `gui/QolControlScreen`(46KB) | 大件 Screen，预计一批 |
| `KunJinKaoOverwriteHudOverlay`(16KB) | **Layer → Overlay 结构改造** |
| `TimeAccelOverlayRenderer`(7KB) | 同上 |
| `ClientModEvents` | 注册 HUD 层 / 渲染器，要等上面三个 |
| `KunJinKaoClientEvents` | 装配，要等 ClientModEvents |
| `jei/AcceleratorJeiPlugin` | 缺 JEI 依赖，建议不移植 |

### 整体进度快照（本轮结束时）

```
java 源文件 167 / 294（dev 包 167/179）    已编译 class 222
event 26/26 ✅   network 37/37 ✅   world 12/12 ✅   wand 20/20 ✅
overwrite 2/2 ✅  compat 2/3    client 47/54
mixin 0/4        移植件 cyou.rimuru.* 0/115
```### 2026-10-19 追加：client 第八批（49 / 54）

```
java 源文件 169 / 294     已编译 class 224     BUILD SUCCESSFUL
client 49（src） + 5（暂存）
```

新增：`render/TimeAccelOverlayRenderer` `render/KunJinKaoOverwriteHudOverlay`。

**`Layer` 的担忧被证伪了一半**：这两个 Overlay 其实**没有**用 `LayeredDraw` 那套 API
（它们是在 `RenderLevelStageEvent` 上自己画世界空间的东西），所以只改了一处
`getPartialTick().getGameTimeDeltaPartialTick(false)` → `getPartialTick()`。
真正用 `RegisterGuiLayersEvent` 的是装配类 `ClientModEvents`（下一个坑）。

### 装配层试过一次，确认了三个待改点（本轮先退回暂存）

`ClientModEvents` / `KunJinKaoClientEvents` 编译出 20 个错误，归成三条：

1. **`RegisterGuiLayersEvent` → `RegisterGuiOverlaysEvent`**：
   1.20.1 的 HUD 是 `IGuiOverlay#render(GuiGraphics, float partialTick, int width, int height)`
   注册到 `RegisterGuiOverlaysEvent#registerAbove/registerBelow(VanillaGuiOverlay.…)`，
   **不是** 1.21 的 `LayeredDraw` + `registerAbove/layer(...)`。
2. **`InputEvent.MouseScrollingEvent#getScrollDeltaY()` → `getScrollDelta()`**（1.20.1 只有一个轴）。
3. **`ClientHooks.registerClientHandlers` 的方法引用要包一层**：
   `ClientPayloadHandlers::handle` 现在是 `(Object, NetworkEvent.Context)`，
   而网关收的是 `PayloadHandler#handle(Object)` → 需要
   `payload -> ClientPayloadHandlers.handle(payload, null)`。
   另外 `ModelResourceLocation.standalone(...)` 的老问题（见第 12 节）。

### 暂存区还剩 5 个

`gui/SwordOptionsScreen`(69KB) `gui/QolControlScreen`(46KB) `ClientModEvents`
`KunJinKaoClientEvents` `jei/AcceleratorJeiPlugin`(建议不移植)

顺序：两个大 Screen → 装配两个 → client 收尾（54 − 1 JEI = 53/53 计入验收）。### 2026-10-19 追加：client 收尾（53 / 54，只剩 JEI 插件）

```
java 源文件 173 / 294     已编译 class 235     BUILD SUCCESSFUL
client 53（src） + 1（暂存：jei/AcceleratorJeiPlugin，缺 JEI 依赖，确定不移植）
```

新增：`gui/SwordOptionsScreen`(69KB) `gui/QolControlScreen`(46KB) `ClientModEvents` `KunJinKaoClientEvents`。

**client 包从"最不敢动"变成"只剩一个可选集成没做"。**

### 两个大 Screen 出奇地好改

69KB 的 `SwordOptionsScreen` **零错误**直接通过；46KB 的 `QolControlScreen` 只有两类问题：
`mouseScrolled` 四参 → 三参，以及 `renderBackground` 拿不到鼠标坐标（照 `AcceleratorScreen`
那套 `hoverMouseX/hoverMouseY` 字段副本解决）。**说明前面几批把 API 差异摸清之后，
大文件本身不是难点，难点在"没摸清时被第一个报错牵着走"。**

### HUD Layer → Overlay 的真实写法（1.20.1）

```java
@SubscribeEvent
public static void registerOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
    // IGuiOverlay#render 是【5 个参数】：ForgeGui, GuiGraphics, float partialTick, int width, int height
    event.registerAboveAll("kun_jin_kao_overwrite", (gui, guiGraphics, partialTick, w, h) -> { ... });
}
```

对照 1.21.1：`RegisterGuiLayersEvent` + `LayeredDraw` + `(GuiGraphics, DeltaTracker)` 两参 lambda。
**注意 1.20.1 的 lambda 是 5 个参数、第一个是 `ForgeGui`（用不上也得接住）。**

### 剩余大局

```
java 173 / 294
已移植：client 53 ✅  event 26 ✅  network 37 ✅  world 12 ✅  wand 20 ✅  overwrite 2 ✅  compat 2
未移植：mixin 4 个类 + 移植件 cyou.rimuru.*(115) + data/ 资源格式改造
```### 2026-10-19 追加：mixin 基础设施（2 / 4 落地）

```
java 源文件 175 / 294     已编译 class 237     BUILD SUCCESSFUL
mixin 2（src） + 2（暂存：EC 输出路由 / LevelPause）
```

### 1.20.1 装 mixin 的三件事（NeoForge 是内置的，Forge 全要自己做）

1. **`src/main/resources/kunjinkao.mixins.json`**：`compatibilityLevel` 从 `JAVA_21` 改成 **`JAVA_17`**，
   加 `"refmap": "kunjinkao.refmap.json"`，`required` 从 `false` 改成 `true`。
2. **jar 清单必须有 `MixinConfigs` 属性**（在 `build.gradle` 的 `jar.manifest.attributes` 里）：
   ```groovy
   'MixinConfigs': 'kunjinkao.mixins.json',
   ```
   **Forge 不会自动发现配置文件** —— 少这一行，mixin 静默不生效，而且没有任何报错。
3. `org.spongepowered.asm` 由 ForgeGradle 的 userdev 提供，**不用额外加依赖**，直接能编译。

### 两个 mixin 因外部件推迟（不是移植失误）

| 文件 | 卡点 |
|---|---|
| `ECOLargeIntegratedWorkingStationOutputRouterMixin` | 引用 `cn.dancingsnow.neoecoae.api.me.output.ECOCraftingOutputRouter`；`libs/neoecoae-20.4.2.jar`（1.20.1 版）**没有这个类** —— 该模组的 API 换代了，要按 1.20.1 的实际类名重写 |
| `LevelPauseMixin` | 用 `com.llamalad7.mixinextras.sugar.@Local`；NeoForge 内置 MixinExtras，**Forge 1.20.1 不内置** —— 要往 `libs/` 补 `mixinextras-forge-0.3.x.jar` 才能编 |

### 第三个坑：`hurtAndBreak` 签名换代

`ItemStackDurabilityMixin` 的目标方法描述符必须跟着改：

```
1.21:  hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V
1.20.1: hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V
```

`@ModifyVariable` 的处理器形参也要少一个 `ServerLevel` —— 描述符是**字符串**，编译期不校验，
写错了只会在运行时静默 `require = 0` 地不生效。**这类"字符串目标"必须拿 javap 核过再写。**### 2026-10-19 追加：移植件 cyou.rimuru.* 的调研结论（本轮未落地）

```
java 源文件 175 / 294     已编译 class 237     BUILD SUCCESSFUL
cyou 包：已做完全部机械变换，放在 .porting-staging\cyou\（115 个文件，不参与编译）
```

### 这个包是什么

`cyou.rimuru.shootingstardemo.*` **不是本模组自己的代码**，而是一个**捆绑的独立 demo mod**
（自带 `shooting_star_demo.mc1211.mixins.json`），有几个明显特征：

- 类名是 `a` / `b` / `d` / `e` / `g` —— **反编译产物**，不是人写的源码；
- lambda 形如 `arg_0 -> ((RegisterKeyMappingsEvent)x).register(arg_0)` —— 反编译器的典型输出；
- 包名里带 `mc1211`（MC 1.21.1），13 个子包，其中 73 个是客户端渲染 / mixin。

### 关键事实（决定要不要做）

**整个 `dev` 包只有 `KunJinKaoEntry.java` 引用它**（16 处，全是注册调用：
方块/物品/实体/网络/创意标签页）。而我们 1.20.1 的入口是自己重写的，**没有引用 cyou 的任何一个类**。

也就是说：**当前这 175 个文件的移植成果，在功能上不依赖 cyou**；
去掉它 = 去掉那个 demo mod 的内容（一颗流星法术 + 它的特效/网络/渲染），主模组不受影响。

### 三个可选路线（需要人拍板）

| 路线 | 代价 | 结果 |
|---|---|---|
| A. 不移植 | 0 | 主模组完整可用；demo mod 的内容（流星法术等）消失 |
| B. 全量移植 | 约 4–5 轮 | 需逐类改：`RegisterPayloadHandlersEvent`/`PayloadRegistrar` → `SimpleChannel`、`RegisterShadersEvent`、`IClientItemExtensions` 的 Forge 版、13 个 mixin（还要 MixinExtras） |
| C. 只移植非渲染部分 | 约 2 轮 | 法术逻辑在，但没有特效/渲染；意义上不完整，**不推荐** |

**我的建议是 A**：它是反编译的第三方 demo，不是本模组的功能；`PORTING.md` 第 1 节把它列进
"移植件"，但按"主模组可用"这个真正的目标看，它的边际价值最低、成本最高。
如果你要 B，我下一轮就开始，按 `registry → net → star → magic/spell → client/*` 的顺序推。### 2026-10-19 追加：data/ 资源格式改造（1.20.1 布局）

```
java 源文件 175 / 294     已编译 class 237     资源文件 62     BUILD SUCCESSFUL
```

已落地 `data/` 共 17 个文件：

- `kunjinkao/dimension/main_world.json`、`dimension_type/main_world.json`（自建主世界，格式两边一致）
- `kunjinkao/loot_tables/blocks/*` ← 源里的 `loot_table/`（**1.21 单数 → 1.20.1 复数**）
- `kunjinkao/recipes/admin_sword.json` ← 源里的 `recipe/`（同上）
- `kunjinkao/tags/block/zone_excavation_blacklist.json`
- `minecraft/tags/block/*`、`minecraft/tags/damage_type/*`（原版标签扩展，两边通用）
- 新增 `pack.mcmeta`，**`pack_format: 15`**（1.20.1）

### 目录单复数：这是 1.20.1 与 1.21 最容易静默失效的差异

| 1.21.1（单数） | 1.20.1（复数） |
|---|---|
| `data/<ns>/loot_table/` | `data/<ns>/loot_tables/` |
| `data/<ns>/recipe/` | `data/<ns>/recipes/` |
| `data/<ns>/advancement/` | `data/<ns>/advancements/` |

**放错了不会报错，只是那条数据完全不生效。** 源工程里两种目录**同时存在**（当初为兼容两边），
所以拷过来之后必须手动把复数目录留下、单数目录删掉。

### 有意未拷的两块

- `data/shooting_star_demo/*` —— 属于那个捆绑 demo mod，随 `cyou` 包一起待决策（见上一节）。
- `data/c/tags/*` 与 `data/ae2/tags/*` —— `c:` 是 1.21 的公共标签命名空间，**1.20.1 不存在**；
  1.20.1 对应的是 `forge:` 命名空间。`ae2:tags/item/quartz_wrench` 则要按 AE2 15.4.11 的实际标签名核对。
  这两块等 `cyou` 决策一起处理（扳手识别也依赖它们）。### 2026-10-19 追加：选定路线 B —— cyou 包开始移植（基线 262 错）

用户拍板 **B（全量移植）**。本轮完成两件事：

1. 115 个文件从 `.porting-staging\cyou` 移回 `src\main\java\cyou`；
2. 把散落在反编译代码里的**全限定** `net.neoforged.*` 前缀全部换掉（正则替换前面漏掉的 FQN 形式），
   **`neoforged` 残留已归零**。

**当前编译基线：262 个错误**，集中在：
`a.java`(48) `c.java`(42) `d.java`(32) `s.java`(32) `e.java`(30) `b.java`(30)
`GuiMixin`(10) `k.java`(8) `LevelRendererMixin`(8) `x.java`(6) `ShootingStarDemoClient`(4) `GameRendererMixin`(4)

### 下一轮的工作顺序（按错误密度从高到低，等价于按依赖从内到外）

1. `d.java` / `e.java` / `b.java` / `g.java` —— 注册与网络（`RegisterPayloadHandlersEvent`/`PayloadRegistrar`
   → `SimpleChannel`，参考 `dev.modmind.kunjinkao.network.NetworkHandler` 的写法）
2. `a.java` / `c.java` / `s.java` —— 主体逻辑（多半是 `DataComponents` / `Holder` / 事件签名）
3. `client.mixin` 下的 13 个 mixin（`GuiMixin` / `LevelRendererMixin` / `GameRendererMixin` …）
   —— 注意项目自己的 `kunjinkao.mixins.json` 已经占用了 jar 清单的 `MixinConfigs`，
   **要么把 demo 的 mixin 并进来，要么改成逗号分隔的多配置**
4. `client.fx`(24) / `client.render`(11) / `client.magic`(12) / `client.cinematic`(12) —— 渲染层
#### cyou 错误分类（下一轮的施工图）

262 个错误按**消息**聚类后只有 7 类，按可批量程度从高到低：

| 数量 | 类别 | 估计改法 |
|---|---|---|
| 194 | `找不到符号` | 逐文件看（大头，多数是 `DataComponents`/`Holder`/`Level` 事件签名） |
| 44 | `CustomPacketPayload` / `network.protocol.common.custom` / `network.codec` | demo 的网络层是 1.21 payload+StreamCodec，**必须整体重写成 SimpleChannel**（照 `dev.modmind.kunjinkao.network` 那套） |
| 6 | `Matrix4f 无法转换为 Quaternionf` | 1.20.1 的 `PoseStack#mulPose` 要 `Quaternionf`；1.21 某些重载收 `Matrix4f` |
| 6 | `枚举 switch case 标签必须为枚举常量的非限定名称` | 反编译产物把 `Enum.CONST` 写进了 case 标签，去掉前缀即可 |
| 2 | `d 未覆盖 VertexConsumer.unsetDefaultColor()` | 有个类 `implements VertexConsumer`；1.20.1 的接口方法集不同，要么补空实现要么别直接实现接口 |
| 2 | `ResourceLocation 无法转换为 String` | 1.20.1 某 API 收 String（反过来是 1.21 收 ResourceLocation） |
| 2 | `ClientTickEvent` 包不存在 | 同主工程：`TickEvent.ClientTickEvent` + Phase |
| 2 | `net.minecraftforge.network.registration 不存在` | 1.20.1 没有 `PayloadRegistrar`，随网络层重写一起消失 |

**施工顺序建议**：先一次性干掉 44 个网络类错误（它们让 `d/e/b/g` 完全编不过，是最大的单点），
再处理 `ClientTickEvent` 与枚举 case 这类机械项，最后逐文件啃 194 个"找不到符号"。
#### 路线 B 第 1 步：机械项清完（262 → 254）

已修：
- `a.java` 里 6 处 `case cyou.rimuru...star.l.b ->` 去限定名（反编译产物）；
- `ShootingStarDemoClient` 的 `ClientTickEvent.Post` → `TickEvent.ClientTickEvent` + Phase 判断。

**剩下 254 个错误全是硬骨头**：192 个「找不到符号」散在反编译代码里（`a` 42 / `c` 42 / `s` 32 /
`d` 32 / `b` 30 / `e` 30），加 44 个网络层（要整体重写）+ 6 个 JOML 矩阵 + 2 个 VertexConsumer 接口。

**给后续排期的现实估计**：机械项只值 8 个错误；真正的量在网络重写（44）与逐文件读反编译代码
（192）。按"一轮清一个文件"的粒度，`d/e/b/g` 网络 + `a/c/s` 主体大约要 **5–7 轮**，之后
`client.fx/render/magic/cinematic`(59) 与 13 个 mixin 还要 **4–6 轮**。
**即路线 B 的完整落地是 10 轮以上的工作量**，且全程要读反编译产物（可维护性天然差）。
---

## 16. 收尾：现状与缺口清单（2026-10-19）

### 最终验收状态

```
java 源文件 175 / 294     已编译 class 237     资源文件 64
compileJava: BUILD SUCCESSFUL（15 秒，退出码 0）
neoforged 残留: 0
```

### 已完成（主模组全量）

| 包 | 状态 | 说明 |
|---|---|---|
| 根包 | 8/8 | 入口、剑、主题、调色板、三个注册表 |
| network | 37/37 | 33 条注册，`PROTOCOL_VERSION = 5` |
| event | 26/26 | 全部注册进 `MinecraftForge.EVENT_BUS` |
| world | 12/12 | 含选区挖掘链 |
| wand | 20/20 | |
| block / block.entity / compat | 2 / 1 / 2 | `WrenchAbilities` 按决策不移植 |
| overwrite | 2/2 | |
| config / qol / entity / sound / recipe | 4 / 2 / 2 / 1 / 1 | |
| client | 53/54 | 只剩 JEI 插件 |
| mixin | 2/4 | 另 2 个卡外部依赖 |
| data/ | 19 个文件 | 1.20.1 复数目录布局 + `pack_format: 15` |

### 有意不移植的 5 项（都不是遗漏，各有硬约束）

| 项 | 卡点 | 影响 |
|---|---|---|
| `compat/WrenchAbilities` | 建立在 NeoForge 的 `ItemAbility` 上，1.20.1 无对应物 | 看 `ItemAbility` 的模组（如 Mekanism）不把本剑当自家扳手 |
| `client/jei/AcceleratorJeiPlugin` | `libs/` 无 JEI（只有 AE2 + neoecoae），JEI 只能 compileOnly | 1.20.1 上无 JEI 配方预览集成 |
| `mixin/LevelPauseMixin` | 依赖 MixinExtras 的 `@Local`；NeoForge 内置、**Forge 1.20.1 不内置** | 暂停场不冻结方块实体 tick |
| `mixin/ECOLargeIntegratedWorkingStationOutputRouterMixin` | `neoecoae-20.4.2`（1.20.1 版）没有 `ECOCraftingOutputRouter` 类 | 与 neoecoae 的联动缺失 |
| `cyou.rimuru.shootingstardemo.*`(115) | 反编译的第三方 demo mod，只被原入口引用；路线 B 实测需 10+ 轮 | 捆绑 demo 的流星法术等内容缺失。**已完成全量机械变换，存于 `.porting-staging\cyou\`** |

### 若要补上这 5 项，各自的前置条件

1. `WrenchAbilities`：**不可能**（版本能力差），除非 1.20.1 侧改用标签近似并接受行为差异。
2. `AcceleratorJeiPlugin`：往 `libs/` 放一个 1.20.1 的 JEI jar（如 `jei-1.20.1-forge-15.x`），约 1 轮。
3. `LevelPauseMixin`：往 `libs/` 放 `mixinextras-forge-0.3.x`，约 1 轮。
4. `ECOLarge...Mixin`：按 1.20.1 的 neoecoae API 重写，需先反编译该 jar 看实际类名，约 1 轮。
5. `cyou` 包：10+ 轮，且全程读反编译代码。

### 移植方法论的沉淀（本项目最大的可复用资产）

`PORTING.md` 第 10–15 节累计记录了 **40+ 条 1.21.1 → 1.20.1 的 API 差异对照**
（DataComponent→NBT、事件层换代、`RegistryObject`、配方 API、渲染底层的 `VertexConsumer`/`Model`/
`ModelResourceLocation`、Screen 的 `renderBackground`/`mouseScrolled`、属性修饰符 UUID 化、
AE2/ProjectE 反射 API 换代、mixin 装配……），以及 3 条元教训：

- **同名 API 不能盲刷正则**（`GuiGraphics.setColor` vs `VertexConsumer#color`）；
- **按行号改完立刻打印核对**（三次事故：`UndefinedZoneSavedData` / `QolTreasureScanner` / `KunJinKaoHandModel`）；
- **字符串形式的目标（mixin 描述符）编译期不校验**，必须 `javap` 核过。
---

## 17. 补依赖：JEI / MixinExtras 落地（2026-10-19 晚）

按"能用现成 libs 就用、不自己写兼容层"的要求，三个外部件都去拿了真包：

| 依赖 | 来源 | 版本 | 结果 |
|---|---|---|---|
| **JEI** | `maven.blamejared.com/mezz/jei/jei-1.20.1-forge/` | 15.71.0.224（2.28 MB） | ✅ `AcceleratorJeiPlugin` 直接编译通过，**零改动** |
| **MixinExtras** | `repo1.maven.org/.../io/github/llamalad7/mixinextras-forge/` | 0.3.5 | ✅ `LevelPauseMixin` 编译通过 |
| **neoecoae** | 已是 `libs/neoecoae-20.4.2.jar`（Modrinth，2026-08-26） | 20.4.2 | ⏳ 待 API 映射（见下） |

```
java 源文件 177 / 294     已编译 class 241     BUILD SUCCESSFUL
neoforged 残留 0            暂存区 116（cyou 115 + EC mixin 1）
```

### 坑：`mixinextras-forge` 是 jar-in-jar 包装

`mixinextras-forge-0.3.5.jar` 里只有 `MixinExtrasMod.class` + `META-INF/jars/MixinExtras-0.3.5.jar`，
**真正的 `com.llamalad7.mixinextras.*` 在内层 jar 里**。直接把它加 `compileOnly` 会报
「程序包 com.llamalad7.mixinextras.sugar 不存在」。

解法：把内层 jar 抽出来（165 KB）也放进 `libs/`，两个都 `compileOnly`：

```groovy
'libs/mixinextras-forge-0.3.5.jar',   // 运行时靠这个（mod 包装）
'libs/mixinextras-0.3.5.jar')          // 编译期靠这个（真类）
```

### neoecoae 那个 mixin 的真实情况（不是缺 jar，是 API 换代）

`20.4.2`（1.20.1 版）里**没有** `ECOCraftingOutputRouter`。它有的是：

```
cn/dancingsnow/neoecoae/api/me/ECOCraftingCPULogic
cn/dancingsnow/neoecoae/api/me/ECOFinalOutputBuffer
cn/dancingsnow/neoecoae/impl/crafting/fastpath/external/ECOExternalCpuOutputRoutes$Sink
cn/dancingsnow/neoecoae/impl/crafting/fastpath/external/ECOExternalCpuOutputRoutes$Delivery
```

1.21 版（21.2.x）才有 `api.me.output.ECOCraftingOutputRouter`。
所以这条按"用现成 API、不写兼容层"的做法，下一步是**读 20.4.2 的
`ECOCraftingCPULogic` / `ECOExternalCpuOutputRoutes`，看混入点该换成哪个方法**，
而不是再造一个 Router 抽象。**若 20.4.2 根本没有等价的输出路由概念，该 mixin 就不移植**（并说明原因）。
---

## 18. cyou 定性为「不移植」+ neoecoae 那条的真实结论

### cyou：确认不移植

用户决定：**不移植 `cyou.rimuru.shootingstardemo.*`**。115 个文件（已完成全部机械变换）
保留在 `.porting-staging\cyou\`，不进 `src`、不参与编译。`PORTING.md` 第 16 节的缺口表以此为准。

### neoecoae 那条：1.20.1 **不需要 mixin**

读完 `neoecoae-20.4.2`（1.20.1 版）的实际 API，结论比预期好：

| | 1.21.1（21.2.x） | 1.20.1（20.4.2） |
|---|---|---|
| 输出路由接口 | `api.me.output.ECOCraftingOutputRouter`（要 mixin 织入，因为机器没实现它） | **`impl.crafting.fastpath.external.ECOExternalCpuOutputRoutes`** 的注册式 API |
| 扩展点 | 无（只能改字节码） | `ECOExternalCpuOutputRoutes.register(UUID jobId, Sink sink)` / `unregister` / `deliver(UUID, AEKey, long, Actionable) -> Delivery` |
| Sink 契约 | `neoecoae$insertIntoCpuForJob(UUID, AEKey, long, Actionable)` | `boolean neoecoae$ownsJob(UUID)` + `long neoecoae$insertJobOutput(AEKey, long, Actionable)` |
| 工作站类名 | `ECOLargeIntegratedWorkingStationBlockEntity` | `ECOIntegratedWorkingStationBlockEntity`（名字也不同） |

**也就是说 20.4.2 的作者已经把「外部扩展输出路由」做成了公开注册点**（名字里就带 `external`），
根本不需要织字节码 —— 这正是"用现成 API、不写兼容层"的做法。

剩下要确认的只有一件事：**工作站自己是否已经在 `register` 自己**。
- 若已注册 → 这条**完全不需要任何代码**，删掉那个 mixin 即可；
- 若没注册 → 写一个极小的桥（在机器初始化时 `register(jobId, sink)`，`sink` 转发给它的网格合成服务），
  **不是重造 Router 抽象**。

下一步：反编译 `ECOIntegratedWorkingStationBlockEntity`，看它有没有调用 `ECOExternalCpuOutputRoutes.register`。
### 结论：那条 neoecoae mixin **在 1.20.1 上不需要**

两步核实：

1. **谁引用了 `ECOExternalCpuOutputRoutes`**（全 jar 扫常量池）：
   `ECOCraftingCPULogic`、`ECOExternalCpuOutputRoutes` 自己、以及它的两个 forge mixin
   （`AdvCraftingCpuLogicFastPathMixin` / `CraftingCpuLogicFastPathMixin`）。
   **工作站类不在其中** —— 它不注册 sink，路由由「合成 CPU 逻辑」侧登记。

2. **`ECOIntegratedWorkingStationBlockEntity` 的 API 清单**：只有
   `getInputItemHandler` / `getOutputItemHandler` / `getOutput` / `getInputTank` / `getOutputTank` /
   `createUI` 之类。**没有 `craftingJobId`，没有批次投递，也没有任何「输出受阻」判定。**

也就是说：1.21 那条 `if (... && this instanceof ECOCraftingOutputRouter) else return false;` 的代码路径
**在 20.4.2 里根本不存在** —— 那套「大型集成工作站 + 合成任务产物投递」是 1.21 线才有的功能。
既然要补的洞都不存在，补丁自然不需要。

**处置**：`ECOLargeIntegratedWorkingStationOutputRouterMixin` **不移植**（已从暂存区删除，
源文件仍在 1.21.1 工程里可查）。`kunjinkao.mixins.json` 里也不需要加这一项。

**mixin 批次至此收尾：3 / 3**（`ItemStackDurabilityMixin`、`EntityFireImmuneMixin`、`LevelPauseMixin`）。
---

## 19. 终态（2026-10-19）

用户决定 **删除 `cyou` 包**（不移植，也不保留暂存）。`.porting-staging\` 整个目录已移除。

### 最终验收

```
compileJava: BUILD SUCCESSFUL（退出码 0）
java 源文件 177 / 294      已编译 class 241      资源文件 64
neoforged 残留 0
```

294 − 177 = 117 个未移植文件，**全部属于已决定删除的 `cyou.rimuru.shootingstardemo`（115）
与另外 2 个有硬约束的文件**（`compat/WrenchAbilities` 依赖 NeoForge 独有的 `ItemAbility`；
`mixin/ECOLargeIntegratedWorkingStationOutputRouterMixin` 的目标代码在 1.20.1 的 neoecoae 里不存在）。

### 交付内容清单

| 类别 | 内容 |
|---|---|
| 物品与武器 | 锟斤拷之剑（1096 行，全功能）、加速方块、主世界之门 |
| 系统 | 时间加速（局部/整体）、暂停场、范围加速、选区挖掘（含 AE2 供给）、覆写流程 |
| QoL | 功能面板全集、长臂善舞、战术 HUD、真实隐身、磁铁、夜视… |
| 建筑手杖 | 20 个类的完整实现（建筑/天使/破坏三种核心 + 撤销） |
| 装饰 | 称号牌、环绕三剑（含反击）、光环拖尾 |
| 客户端 | 54/54：全部 GUI（剑设置 69KB、QoL 面板 46KB、加速器、密码、HUD 三屏）、世界渲染、HUD Overlay |
| 网络 | 33 条注册（S2C 20 / C2S 13），`PROTOCOL_VERSION = 5` |
| 事件 | 26 个处理器全部挂载 |
| 数据 | 主世界维度、战利品表、配方、标签、`pack_format 15` |
| mixin | 3 个（耐久、火焰免疫、暂停场） |

### 依赖（`libs/`，均 `compileOnly`）

```
appliedenergistics2-forge-15.4.11.jar      AE2 15.4.11
neoecoae-20.4.2.jar                        Neo Eco AE 20.4.2
jei-1.20.1-forge-15.71.0.224.jar           JEI 15.71.0.224
mixinextras-forge-0.3.5.jar                MixinExtras 包装（运行时）
mixinextras-0.3.5.jar                      MixinExtras 内层（编译期，@Local 需要）
```