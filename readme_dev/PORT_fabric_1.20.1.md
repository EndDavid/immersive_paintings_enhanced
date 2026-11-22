# 移植日志：Fabric 1.20.1

本文件记录 `Immersive Paintings: Enhanced` 从 **Fabric 1.18.2** 移植到 **Fabric 1.20.1** 的全过程，
以及这次移植采用的仓库 / 分支结构。

* 起始版本：`0.2.0`（Fabric 1.18.2，见 [README_dev.md](README_dev.md)）
* 目标版本：`0.2.0+1.20.1`（Minecraft 1.20.1 / Fabric Loader 0.15.7 / Java 17 字节码）
* 上游依赖：[Immersive Paintings 0.6.13+1.20.1](https://modrinth.com/mod/immersive-paintings)（Fabric）
* git 分支：`fabric/1.20.1`
* 工程目录：`fabric-1.20.1/`

---

## 1. 仓库结构：分支 + 版本子目录

这次沿用 `neoforge/1.21.1` 移植时定下的做法，而不是把所有版本塞进同一个 Gradle 多模块工程：

```
immersive_paintings_angled_1.18.2/          <- 仓库根（目录名保留，避免破坏现有 IDE 工程）
├── build.gradle / settings.gradle / src/   <- 原 Fabric 1.18.2 工程，一行未改
├── readme_dev/                            <- 开发日志（本文件）
└── fabric-1.20.1/                         <- 新增：1.20.1 Fabric 工程（自带 gradlew，独立构建）
    ├── build.gradle, settings.gradle, gradle.properties, gradlew(.bat)
    └── src/main/{java,resources}
```

要点：

* `fabric-1.20.1/` 是**独立 Gradle 构建**（自己有 `settings.gradle`）。根目录的
  `settings.gradle` 没有 `include` 它，所以在根目录执行 `.\gradlew.bat build` 仍然只构建
  1.18.2；构建 1.20.1 要进入子目录。
* git 分支 `fabric/1.20.1` 从 `main` 拉出。`main` 保持为「纯 Fabric 1.18.2 发布线」，
  `neoforge/1.21.1` 保持为 NeoForge 1.21.1 移植线，互不干扰。
* 为了能 `git diff main fabric/1.20.1` 对照，分支上**保留**了根目录的 1.18.2 源码，
  只新增 `fabric-1.20.1/`。等三条线都稳定后，再考虑把公共代码抽成 `common/` 多模块。

常用操作：

```
git branch                      # 查看分支
git checkout main               # Fabric 1.18.2 发布线
git checkout fabric/1.20.1      # 本次 Fabric 1.20.1 移植线
git checkout neoforge/1.21.1    # NeoForge 1.21.1 移植线
```

---

## 2. 上游 1.20.1 API 与 1.18.2 的差异

先确认一个关键事实：**Immersive Paintings 1.20.1 仍然是 1.18.2 那套
`AbstractImmersiveDecorationEntity` 结构**，没有像 1.21.1 那样改写成原版 `HangingEntity`。
1.20.1 分支的 `AbstractImmersiveDecorationEntity` 依然有：

* `protected BlockPos attachmentPos;`
* `protected Direction facing;`
* `protected int rotation;`
* `protected void updateAttachmentPosition()`
* `public boolean canStayAttached()`
* `public abstract int getWidthPixels();` / `getHeightPixels()`
* `public int getRotation();`

`ImmersivePaintingItem#newPainting(World, BlockPos, Direction, int)` 的签名也与 1.18.2 完全一致，
`ImmersivePaintingEntity#isGraffiti()` 仍然存在。因此本工程用到的注入点一个都没变。

逐项核对（依据本机 1.20.1 Yarn `1.20.1+build.10` 映射与 `javap` 反编译的
`immersive_paintings-0.6.13+1.20.1-fabric.jar`）：

| 1.18.2 用法 | 1.20.1 对应物 | 结果 |
| --- | --- | --- |
| `AbstractImmersiveDecorationEntity#updateAttachmentPosition` | 同名同签名 | 不变 |
| `#canStayAttached` | 同名同签名 | 不变 |
| `@Shadow rotation / attachmentPos / facing` | 字段仍在，`rotation` 仍是 `protected` | 不变 |
| `Entity#setPos(double,double,double)` | Yarn 1.20.1 仍有 `setPos(DDD)`（`method_23327`） | 不变 |
| `Entity#setPosition(double,double,double)` | 被上游 override 成「重新吸附到方块」 | 仍然避开它，改用 `setPos` |
| `Entity#setYaw / setPitch` | 1.20.1 Yarn 就是这两个名字 | 不变 |
| `Entity#setBoundingBox(Box)` | 同名 | 不变 |
| `ItemUsageContext#getHitPos()` | 仍然 `public` | 不变 |
| `Item#useOnBlock(ItemUsageContext)` | 同名 | 不变 |
| `ProjectileUtil#raycast(Entity,Vec3d,Vec3d,Box,Predicate,double)` | 同签名（`method_18075`） | 不变 |
| `ProjectileUtil#getEntityCollision(World,Entity,Vec3d,Vec3d,Box,Predicate,float)` | 同签名（`method_37226`） | 不变 |
| `Entity#collidesWith(Entity)` / `method_30949` | 仍然是这个名字 | 不变 |
| `Direction#getOffsetX/getOffsetZ/getHorizontal/getVector` | 都在 | 不变 |
| `ActionResult` / `Hand` / `EntityHitResult` 包名 | 未变 | 不变 |

> 对比一下 NeoForge 1.21.1：那边实体结构被重写、方法名全部换成 Mojang official，
> 所以 `neoforge-1.21.1/` 里要大改；1.20.1 这边则正好落在一个「Yarn 名字基本没动」的区间。

**结论：10 个 Java 源文件从 1.18.2 原样复制到 `fabric-1.20.1/`，一行未改，直接编译通过。**
唯一改动的是构建配置和 `fabric.mod.json` 里的版本范围。

---

## 3. 逐文件说明

```
fabric-1.20.1/src/main/
├── java/com/ipe/
│   ├── IpeMod.java                          Fabric 入口，读配置、探测 Create
│   ├── config/IpeConfig.java                JSON 配置（路径、格式与 1.18.2 完全相同）
│   ├── angled/DiagonalGeometry.java         45° 数学，零 Minecraft 副作用
│   ├── angled/mixin/
│   │   ├── AbstractDecorationMixin.java     对角位置 / 包围盒 / 免支撑
│   │   ├── ImmersivePaintingItemMixin.java  潜行放置 45°
│   │   ├── ProjectileUtilMixin.java         精确准星 / 弹射物命中
│   │   ├── ImbEventsMixin.java              可选：拒绝把 45° 涂鸦转成方块
│   │   └── ImbMixinPlugin.java              仅当 imb 存在时才应用上面那条
│   └── create/mixin/
│       ├── ContraptionCollisionMixin.java   可选：让 Create 无视画
│       └── CreateMixinPlugin.java           仅当 Create 存在时才应用上面那条
└── resources/
    ├── fabric.mod.json                      模组元数据 + 依赖范围 + mixin 配置注册
    ├── ipe.mixins.json                      核心 mixin（required=true）
    ├── ipe.create.mixins.json               可选 mixin（required=false + plugin）
    ├── ipe.imb.mixins.json                  可选 mixin（required=false + plugin）
    └── assets/ipe/icon.png                  模组图标
```

`fabric.mod.json` 与 1.18.2 版的差异只有版本范围：

| 字段 | 1.18.2 | 1.20.1 |
| --- | --- | --- |
| `minecraft` | `~1.18.2` | `~1.20.1` |
| `immersive_paintings` | `>=0.6.7` | `>=0.6.13` |
| `fabricloader` | `>=0.14.0` | `>=0.14.21` |

`id` 仍然是 **`ipe`**，`name` 仍然是 **`Immersive Paintings: Enhanced`**，与 1.18.2 完全一致；
只有产物文件名和 `version` 后缀不同（`immersive_paintings_enhanced_fabric-0.2.0+1.20.1.jar`）。

---

## 4. 构建

### 4.1 工具链

| 项 | 值 | 说明 |
| --- | --- | --- |
| Gradle | 9.3.0 | 与根目录 1.18.2 工程一致，wrapper 已随子目录携带 |
| Fabric Loom | 1.15.5（`fabric-loom-remap`） | 与根工程同一插件线 |
| Gradle 守护进程 JDK | 21（`gradle.properties` 里钉死路径） | Loom 1.15 **要求 JVM ≥ 21** |
| 编译目标 | `--release 17` | 1.20.1 是 Java 17 游戏，产出仍是 Java 17 字节码 |

### 4.2 依赖版本

```properties
minecraft_version=1.20.1
yarn_mappings=1.20.1+build.10
loader_version=0.15.7
fabric_api_version=0.92.2+1.20.1
immersive_paintings_version=TOHB3jQz
```

* `immersive_paintings_version` 填的是 **Modrinth version ID**，不是版本号。
  `0.6.13+1.20.1` 在 Modrinth 上同时存在 Fabric 和 Forge 两个文件，按版本号解析会
  「先找到哪个用哪个」——NeoForge 移植时就因此拿到过 Fabric jar，编译报出一堆
  intermediary 名字错误。`TOHB3jQz` 唯一对应
  `immersive_paintings-0.6.13+1.20.1-fabric.jar`。
* Fabric API 只用于开发运行：Immersive Paintings 的 `fabric.mod.json` 声明了
  `fabric-api-base`，开发环境里没有 Fabric API 就跑不起来。本工程代码本身不 import 它，
  所以正式发布的 jar 不需要把 Fabric API 打进依赖表（用户按 IP 的要求装 Fabric API 即可）。

### 4.3 结果

```
> Task :compileJava
> Task :processResources
> Task :jar
> Task :remapJar
> Task :build

BUILD SUCCESSFUL in 2m 21s
```

产物：

```
build/libs/immersive_paintings_enhanced_fabric-0.2.0+1.20.1.jar          49175 B
build/libs/immersive_paintings_enhanced_fabric-0.2.0+1.20.1-sources.jar  33809 B
```

```
sha256  bd9ac188ca05e710521340e97a8fedab352c65a3a466c9a2b278767609382e35  ...-0.2.0+1.20.1.jar
```

`jar tf` 确认包内有：3 个核心 mixin + Create 兼容 mixin + `imb` 兼容 mixin、三份
`*.mixins.json`、`fabric.mod.json`、`icon.png`、`LICENSE_...`。

另外用 `javap -v` 检查了**发布 jar** 里的注解，确认 Loom 已把 mixin 目标从 Yarn 名重映射成
intermediary 名，例如：

```
@ModifyArg(target = "Limmersive_paintings/item/ImmersivePaintingItem;newPainting(
    Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;I)
    Limmersive_paintings/entity/ImmersivePaintingEntity;")
@Inject(method = "method_18075" ...)   // ProjectileUtil#raycast
```

这很重要：Loom 1.15 默认在构建期直接重映射 mixin 注解（本次产物里没有生成 refmap 文件），
而不是把 Yarn 名字原样留在发布 jar 里等运行时再处理。如果只检查 `build/devlibs/` 里的
dev jar（仍是 Yarn 名）会误以为没处理。

---

## 5. 运行期验证（开发服务器）

构建成功只证明「能编译、能打包」，不证明 mixin 在运行期能应用。因此跑了一次
`runServer`（比客户端轻，不需要图形界面）。

### 5.1 准备

```
fabric-1.20.1/run/eula.txt           -> eula=true
fabric-1.20.1/run/server.properties  -> online-mode=false, server-port=25566, view-distance=2
```

```
cd fabric-1.20.1
.\gradlew.bat runServer --offline
```

### 5.2 结果：通过

```
[main/INFO] (FabricLoader/GameProvider) Loading Minecraft 1.20.1 with Fabric Loader 0.15.7
[main/INFO] (ipe) Immersive Paintings: Enhanced ready - sneak + right click a wall to place a 45 degree painting
[Server thread/INFO] (Minecraft) Starting minecraft server version 1.20.1
[Server thread/INFO] (Minecraft) Done (9.407s)! For help, type "help"
```

加载成功的模组列表里包含：

```
fabricloader 0.15.7
minecraft 1.20.1
immersive_paintings 0.6.13+1.20.1
ipe 0.2.0+1.20.1
```

关键点：

* **没有任何 `Mixin apply failed` / `InvalidInjectionException`**。
  `ipe.mixins.json` 是 `"required": true` + `"defaultRequire": 1`。
* `AbstractDecorationMixin`（注入 `updateAttachmentPosition` / `canStayAttached`）与
  `ImmersivePaintingItemMixin`（注入 `useOnBlock` 的 `@ModifyArg`）的目标类在实体 / 物品
  注册时必然加载，所以这两条注入点已经**实际应用**；如果对不上，服务器会在启动阶段直接崩。
* `ProjectileUtilMixin` 的目标 `ProjectileUtil` 在纯服务端启动路径中不一定会被加载，
  因此「服务器能起来」本身不能单独证明它已应用。不过 Loom 已把注解里的
  `raycast` / `getEntityCollision` 成功重映射为 `method_18075` / `method_37226`，
  描述符也与 1.20.1 映射逐项一致；真正的客户端准星行为仍列入 §7 待实测。
* `CreateMixinPlugin` 因为没装 Create 被正确跳过。
* 上游 IP 自己会打印
  `No data fixer registered for immersive_paintings:painting` 等 4 行 ERROR；
  这是 1.20.1 IP 未注册 data fixer 的正常现象，与本移植无关。

### 5.3 `imb` 可选兼容验证

把本机 `imb-1.0.1+fabric+1.20.1.jar` 放进 `run/mods/` 后再启动：

```
[main/INFO] (imb) Immersive Painting Blocks loaded
[Server thread/INFO] (Minecraft) Done (4.052s)! For help, type "help"
```

没有 `Mixin apply failed`。`ipe.imb.mixins.json` 是 `required=false` + `ImbMixinPlugin`，
插件在 `imb` 存在时启用 `ImbEventsMixin`，注入点是 `com.imb.events.IMBEvents#onUseEntity
(PlayerEntity, World, Hand, Entity, EntityHitResult)`——与 1.18.2 版完全一致。
服务器能起来说明该注入点也被找到了（`defaultRequire=1` 会在找不到时抛异常）。

---

## 6. 踩坑记录

### 6.1 Loom 1.15 要求 Java 21，而 1.20.1 要 Java 17 字节码

根目录 1.18.2 工程在本机直接 `.\gradlew.bat build` 会失败：

```
Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM.
```

因为 PATH 上的 `java` 是 JRE 17，而 Loom 1.15.5 的 jar 是 Java 21 字节码。修法：

```properties
org.gradle.java.home=D:/codename_EndDavid/hello java/jdk-21.0.2
org.gradle.java.installations.paths=D:/codename_EndDavid/hello java/jdk-21.0.2
```

同时 `build.gradle` 里 `options.release = 17`，保证产物仍是 1.20.1 能加载的 Java 17
字节码。**这个 JDK 路径是本机路径，换机器要改或删掉，让 Gradle 用默认 JDK 21。**

### 6.2 Fabric API 0.92.2 要求 Fabric Loader ≥ 0.15.6

第一次 `runServer` 直接拒绝启动：

```
Immediate reason: [HARD_DEP_NO_CANDIDATE fabric-api 0.92.2+1.20.1
    {depends fabricloader @ [>=0.15.6]}, ROOT_FORCELOAD_SINGLE fabric-api 0.92.2+1.20.1]
...
Mod 'Fabric API' (fabric-api) 0.92.2+1.20.1 requires version 0.15.6 or later of mod
'Fabric Loader' (fabricloader), but only the wrong version is present: 0.14.25!
```

原因是 `fabric_api_version=0.92.2+1.20.1` 是 1.20.1 后期版本，而 loader 一开始按上游
`0.14.21` 的思路填了 `0.14.25`。修法：`loader_version=0.15.7`（本机缓存里有，且满足
`fabric.mod.json` 的 `>=0.14.21`）。**这不是模组代码问题，发布 jar 对 loader 的要求仍是
`>=0.14.21`，只有开发运行需要 0.15.7。**

### 6.3 Modrinth 多加载器同版本号

与 NeoForge 移植同一个坑：`0.6.13+1.20.1` 同时有 Fabric / Forge 两个文件。必须用
version ID `TOHB3jQz`。已在 `gradle.properties` 注释里写清对应文件名。

### 6.4 `runServer` 的收尾

开发服务器不会自己退出；验证完 `Done (...)` 后要结束 `java.exe` 进程
（命令行包含 `devlaunchinjector` / `KnotServer`），否则下一次会因 25566 端口被占用而失败。

---

## 7. 还没有验证的部分

开发服务器只跑到「加载 + 生成世界」，摸不到客户端渲染与交互。以下仍需 `runClient` 实测：

1. 潜行贴墙右键是否真的出现 45° 贴棱的画（`@ModifyArg` 那条注入）。
2. `ProjectileUtilMixin` 的准星拾取是否按预期丢弃「空角落」的命中。
3. 画的角度在存档 / 重载 / 其他玩家视角下是否保持（依赖上游 `Rotation` NBT）。
4. 2 格高画的对角包围盒是否与原版一样抬高半格。
5. Create 在场时 `ContraptionCollisionMixin` 是否被应用（日志里会出现
   `Create detected: contraption collision bypass applied to ...`）。
   本机没有 Create 1.20.1 的 Fabric jar，且 Create 的依赖树较复杂，本次未测。
6. `imb` 这边只验证了「加载不崩、注入点存在」；「斜向涂鸦转换被拒绝」的行为要在客户端
   用涂鸦转换器点一下才能确认。

另外，本机开发运行用的是 JDK 21（Loom 要求），而正式玩家多半用 Java 17。mixin 与产物
字节码都是 17，理论上无差异，但严格说「Java 17 实机启动」也属于未实测项。

---

## 8. 构建与测试说明（给下一位维护者）

```powershell
cd "D:\codename_EndDavid\hello java\immersive_paintings_angled_1.18.2\fabric-1.20.1"

.\gradlew.bat build        # 产物在 build/libs/
.\gradlew.bat runServer    # 开发服务器（需要 run/eula.txt）
.\gradlew.bat runClient    # 开发客户端（需要图形界面）
```

* **JDK**：Gradle 守护进程用 21（`gradle.properties` 钉死），编译目标 17。
  换机器时把 `org.gradle.java.home` / `org.gradle.java.installations.paths` 改成本机
  JDK 21 路径，或整行删掉让 Gradle 自己找。
* **Gradle**：wrapper 9.3.0，与根工程一致；本机 wrapper 缓存里已有该发行包。
* **测试 Create**：Create 不是编译期依赖。把 `create-fabric-0.5.1-*-mc1.20.1.jar`
  及其依赖丢进 `fabric-1.20.1/run/mods` 即可；不装也能正常启动。
* **测试 `imb`**：把 `imb-1.0.1+fabric+1.20.1.jar` 放进 `run/mods`。它是可选依赖，
  不装也能启动；装了之后 `ImbMixinPlugin` 会启用 `ipe.imb.mixins.json`。
* **代理**：`gradle.properties` 底部有五条注释掉的 `systemProp.*.proxy*`。
  只有在本机代理开启且需要联网下载时才取消注释，然后 `.\gradlew.bat --stop`；
  用完记得注释回去。
* **不要提交** `build/`、`run/`、`.gradle/`：`fabric-1.20.1/.gitignore` 已处理。

---

## 9. TODO

- [x] 创建 `fabric/1.20.1` 分支与 `fabric-1.20.1/` 独立工程（2026-11-22）。
- [x] `.\gradlew.bat build` 通过，产物约 48 KB。
- [x] `runServer` 通过：模组加载、无 mixin 应用失败。
- [x] `imb` 在场时 `runServer` 通过（可选 mixin 配置能启用）。
- [ ] `runClient` 验证 §7 的 1–4 项（放置角度、准星拾取、持久化、2 格高偏移）。
- [ ] 用 Create Fabric 1.20.1 实测 `ContraptionCollisionMixin`。
- [ ] 在 Java 17 实机（非开发环境）启动一次，确认与 JDK 21 开发运行无差异。
- [ ] 三条线稳定后，考虑抽 `common/` 多模块布局（届时把 1.18.2 的 `src/` 迁入
  `fabric-1.18.2/`，1.20.1 与 1.21.1 共用 `common/`）。

---

## 10. 与 `neoforge/1.21.1` 分支的关系

| 分支 | 目标 | 工程目录 | 上游 IP |
| --- | --- | --- | --- |
| `main` | Fabric 1.18.2 | `.`（根） | 0.6.7+1.18.2 |
| `fabric/1.20.1` | Fabric 1.20.1 | `fabric-1.20.1/` | 0.6.13+1.20.1 |
| `neoforge/1.21.1` | NeoForge 1.21.1 | `neoforge-1.21.1/` | 0.8.0+1.21.1 |

三条线各自独立构建、互不 `include`。1.20.1 这次几乎没有 Java 改动，1.21.1 那次则因为上游
实体结构重写而改写了注入点；两份日志可以对照阅读：

* 本文件：`readme_dev/PORT_fabric_1.20.1.md`
* NeoForge：`neoforge/1.21.1` 分支上的 `readme_dev/PORT_neoforge_1.21.1.md`
