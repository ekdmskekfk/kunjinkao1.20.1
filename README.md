# my_mod_forge_1.20.1 — Forge 1.20.1 独立工程

本目录是完整独立的 Forge 1.20.1 模组工程，已从 NeoForge 1.21.1 根工程迁移全部功能，材质保持原版钻石剑（方案 A）。

## 包含内容

- build.gradle / settings.gradle / gradle.properties：ForgeGradle [6.0,6.2) + JDK 17，official 映射，Forge 1.20.1-47.4.0，阿里云镜像仓库
- gradle/wrapper/gradle-wrapper.properties：Gradle 8.8（使用本目录构建时请从官方 Forge 1.20.1 MDK 或仓库根目录复制 gradlew、gradlew.bat、gradle-wrapper.jar）
- src/main/java/dev/modmind/my_mod/：ModMindEntry、SwordRegistry、KunJinKaoSwordItem、DiamondProjectile（entity）、KunJinKaoDeathEventHandler / KunJinKaoProtectionHandler / KunJinKaoTooltipHandler（event）、ClientModEvents / KunJinKaoTooltipColorHandler（client）
- src/main/resources/META-INF/mods.toml：modId=my_mod，displayName=锟斤拷烫烫烫，依赖 forge [47,) 与 minecraft [1.20.1]
- src/main/resources/assets/my_mod/lang/zh_cn.json 与 en_us.json
- src/main/resources/assets/my_mod/models/item/kun_jin_kao.json：直接继承 minecraft:item/diamond_sword，无自定义 PNG

注意：本工程没有 assets/my_mod/items 目录（1.21.1 专属物品模型映射已删除），源码中无任何 net.neoforged / DeferredHolder / DataComponents 引用。

## 部署到 F:\mcmodli\tang\my_mod_forge_1.20.1\

工作台沙箱仅允许写入当前 NeoForge 工程目录，无法直接创建外部顶层目录。请将 docs/forge_1.20.1/ 整体复制为 F:\mcmodli\tang\my_mod_forge_1.20.1\，补上 gradlew、gradlew.bat、gradle/wrapper/gradle-wrapper.jar 后执行：

./gradlew build   （需要 JDK 17，首次构建会自动下载 Forge 1.20.1-47.4.0）
./gradlew runClient

## 已迁移功能

1. Tooltip：∞ 攻击伤害（彩虹流动色）/ -2.4 攻击速度，原版属性行被移除
2. Shift+右键切换抢夺 0/25/50 级，action bar 提示
3. 左键攻击 = 指令杀（立即死亡）
4. 击杀掉落按模式倍增，稀有掉落概率提升
5. 右键生物 = 点燃数秒后处决
6. 右键发射钻石：命中地面 = 落雷 + 3~6 团随机火焰；命中实体 = 立即死亡 + 落雷 + 随机火焰
7. 剑不消耗耐久
8. 背包任意位置持剑 = 创造飞行、免疫全伤害（含虚空）、免疫 /kill
9. 原版钻石剑材质
