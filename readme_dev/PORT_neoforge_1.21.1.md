# 移植日志：NeoForge 1.21.1

本文件记录 `Immersive Paintings: Enhanced` 从 **Fabric 1.18.2** 移植到 **NeoForge 1.21.1** 的全过程，
以及从这次移植开始采用的多版本 / 多加载器仓库结构。

* 起始版本：`0.2.0`（Fabric 1.18.2，见 `readme_dev/README_dev.md`）
* 目标版本：`0.2.0+1.21.1`（NeoForge 21.1.154 / Minecraft 1.21.1 / Java 21）
* 上游依赖：[ImmersivePaintings 0.8.0+1.21.1](https://github.com/Luke100000/ImmersivePaintings)（1.21.1 分支）

---

## 1. 仓库结构：为什么这样做

多加载器 / 多版本的模组（包括 Immersive Paintings 上游自己）实际有两种主流做法：

| 做法 | 说明 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **一个 git 分支 = 一个目标** | `main` 发 1.18.2-Fabric，`neoforge/1.21.1` 发 1.21.1-NeoForge | 历史干净、CI 简单、发布互不干扰 | 同一时刻磁盘上只有一份代码，无法对照 |
| **一个仓库多模块** | `common/` + `fabric/` + `neoforge/`，根 `settings.gradle` 统一 include | 共享代码、一次改多处 | 需要先把代码抽到 `common`，改动量与风险大 |

对「已有 Fabric 版本、现在补一个加载器」这种情形，社区普遍先用**分支 + 版本子目录**过渡，
等两边都稳定了再考虑抽 `common`。本次就是照这个思路做的：

```
immersive_paintings_angled_1.18.2/          <- 仓库根（目录名保留，避免破坏现有 IDE 工程）
├── build.gradle / settings.gradle / src/   <- 原 Fabric 1.18.2 工程，一行未改
├── readme_dev/                            <- 开发日志（本文件）
└── neoforge-1.21.1/                       <- 新增：1.21.1 NeoForge 工程（自带 gradlew，独立构建）
    ├── build.gradle, settings.gradle, gradle.properties, gradlew(.bat)
    └── src/main/{java,resources}
```

要点：

* `neoforge-1.21.1/` 是**独立 Gradle 构建**（自己有 `settings.gradle`），根目录的 `settings.gradle`
  没有 `include` 它，所以两边互不干扰：在根目录跑 `.\gradlew.bat build` 仍然只构建 1.18.2。
* 构建 NeoForge 版时进入子目录：`cd neoforge-1.21.1; .\gradlew.bat build`。
* git 分支：本次所有改动都提交在 **`neoforge/1.21.1`** 分支上，`main` 保持为「纯 Fabric 1.18.2 发布线」。
  常用操作：

```powershell
git branch                      # 查看分支
git checkout main               # 回到 Fabric 1.18.2 发布线
git checkout neoforge/1.21.1    # 回到 NeoForge 移植线
```

* 之所以在 `neoforge/1.21.1` 分支上**保留**了 1.18.2 的源码而不是删掉，是为了让两条线的差异可以
  `git diff` 对照。等两边都定型后，可以再把 `src/` 迁进 `fabric-1.18.2/` 做成真正的多模块布局。

---

## 2. 上游 API 发生了什么变化

1.18.2 时 Immersive Paintings 用 Yarn 名（`net.minecraft.util.math.Box` 等），实体继承自
`AbstractImmersiveDecorationEntity`。1.21.1 版本做了两件事：

1. 换到 **official mapping（Mojang 名）+ NeoForge**，因此类型从 `net.minecraft.util.math.*`
   变成 `net.minecraft.core.*` / `net.minecraft.world.phys.*`；
2. 实体结构重写为**原版 `HangingEntity`**：`AbstractImmersiveDecorationEntity` 被删除，
   `rotation` 字段、`updateAttachmentPosition` 等都不存在了。

对照表（本移植实际用到的部分）：

| 1.18.2 (Yarn) | 1.21.1 (official) |
| --- | --- |
| `AbstractImmersiveDecorationEntity` | `ImmersivePaintingEntity extends HangingEntity` |
| `updateAttachmentPosition()` | `calculateBoundingBox(BlockPos, Direction)`（`HangingEntity#recalculateBoundingBox` 内部调用） |
| `BlockPos` / `Box` / `Direction` / `Vec3d` | `BlockPos` / `AABB` / `Direction` / `Vec3` |
| `facing.getOffsetX()/getOffsetZ()` | `facing.getStepX()/getStepZ()` |
| `facing.getHorizontal()` | `facing.get2DDataValue()` |
| `self.setPos(x,y,z)` + `setBoundingBox(box)` | 只返回 `AABB`，位置由 `recalculateBoundingBox` 用盒子中心设置 |
| `self.setYaw()` / `setPitch()` | `absRotateTo(yaw, pitch)`（`setYRot/setXRot` 也行，但不会同步旧值） |
| `Item.useOnBlock(ItemUsageContext)` | `Item.useOn(UseOnContext)` |
| `newPainting(World, BlockPos, Direction, int)` | 不存在了，改为内联 `setPos(BlockPos)` + `setDirection(Direction, int)` |
| `player.isSneaking()` | `player.isShiftKeyDown()` |
| `context.getHitPos()` | `context.getHitResult().getLocation()` |
| `ProjectileUtil.raycast(...)` + `getEntityCollision(...)` | 合并为两个 `ProjectileUtil.getEntityHitResult(...)` 重载 |
| `EntityHitResult` 在 `net.minecraft.util.hit` | `net.minecraft.world.phys.EntityHitResult` |
| Create `Entity#collidesWith` / `method_30949` | `Entity#canCollideWith`（1.20.5 改名） |

### 2.1 关键简化：位置不再需要手动设置

`HangingEntity#recalculateBoundingBox()`（1.21.1）的实现是：

```java
AABB box = calculateBoundingBox(pos, direction);
Vec3 center = box.getCenter();
setPosRaw(center.x, center.y, center.z);
setBoundingBox(box);
```

也就是说**只要 `calculateBoundingBox` 返回一个以目标点为圆心的盒子，位置、包围盒、追踪范围就全对了**。
1.18.2 版里那套「自己算位置 + 自己 `setPos` + 自己 `setBoundingBox`」的代码因此全部删掉，
`ImmersivePaintingEntityMixin` 比 Fabric 版短了三分之一，行为等价。

### 2.2 尺寸单位变了

1.18.2 的 `getWidthPixels()` 返回 **像素**（1 格 = 16 像素），1.21.1 的 `getPaintingWidth()`
返回 **格数**。所以换算由 `/ 32.0` 变成 `/ 2.0`：

```java
double halfWidth  = self.getPaintingWidth()  / 2.0;
double halfHeight = self.getPaintingHeight() / 2.0;
```

「偶数格高要抬高半格」的判断也从 `heightPixels % 32 == 0` 变成 `getPaintingHeight() % 2 == 0`。

---

## 3. 逐文件说明

```
neoforge-1.21.1/src/main/
├── java/com/ipe/
│   ├── IpeMod.java                          @Mod("ipe") 入口，读配置、探测 Create
│   ├── config/IpeConfig.java                JSON 配置（路径 FMLPaths.CONFIGDIR，格式与 1.18.2 完全相同）
│   ├── angled/DiagonalGeometry.java         45° 数学，零 Minecraft 副作用
│   ├── angled/mixin/
│   │   ├── ImmersivePaintingEntityMixin.java 对角包围盒 + 对角 yaw + 免支撑
│   │   ├── ImmersivePaintingItemMixin.java   潜行放置 45°
│   │   └── ProjectileUtilMixin.java          精确准星 / 弹射物命中
│   └── create/mixin/
│       ├── ContraptionCollisionMixin.java    可选：让 Create 无视画
│       └── CreateMixinPlugin.java            仅当 Create 存在时才应用上面那条
└── resources/
    ├── META-INF/neoforge.mods.toml           模组元数据 + 依赖声明 + mixin 配置注册
    ├── ipe.mixins.json                       核心 mixin（required=true）
    ├── ipe.create.mixins.json                可选 mixin（required=false + plugin）
    └── icon.png                              模组图标
```

### 3.1 `ImmersivePaintingEntityMixin`

* 注入 `calculateBoundingBox(BlockPos, Direction)` 的 `HEAD`：
  * `rotation % 90 != 0` 时选一条竖棱（`DiagonalGeometry.corner`），把画布中心放在棱上再沿法线推出半厚度；
  * 用 `absRotateTo(yaw, 0)` 把实体转到对角朝向——渲染器是 `rotationDegrees(-yaw)`，所以只改 yaw 就够了；
  * 返回一个「绕对角矩形」的轴对齐盒子。
* 注入 `survives()` 的 `HEAD`：`angledIgnoresSupport` 打开时直接返回 `true`（悬空、不掉落、放置永不因互相重叠失败）。
* 仍然**不碰** `canBeCollidedWith`：客户端拿它当准星拾取的谓词，返回 `false` 会让斜画无法编辑/破坏。
* `rotation` 在 0.8.0 里是 `private`，用 `@Shadow` 直接读。

### 3.2 `ImmersivePaintingItemMixin`

用 `ThreadLocal` 记住 `useOn` 的 `UseOnContext`，再用 `@ModifyArg` 把
`ImmersivePaintingEntity#setDirection(Direction, int)` 的 **第 2 个参数（index = 1）** 换成 `±45`。
权限检查、发光/涂鸦变体、音效、物品消耗、客户端-服务端握手全部沿用上游逻辑。

### 3.3 `ProjectileUtilMixin`

对角画的轴对齐盒子比画面大约 41%，空角落会「吃掉」点击。这里把两个
`getEntityHitResult` 重载的返回值重新对**真实倾斜画布**做一次射线测试：

* 只擦到空角落 → 丢掉这个画布，用 `predicate.and(c -> c != target)` 重新搜索，让后面的方块/实体现形；
* 真的打到画布 → 把命中点修正到画布上（原版要拿它和方块命中点比距离）。

判定「是不是斜画」用的是 `yaw`（`isDiagonalYaw`）：因为 `rotation` 私有，而斜画的 yaw 必然是
`k*90 ± 45`，正好可以用 `floorMod(round(yaw), 90) != 0` 稳定区分。

### 3.4 Create 兼容

Create 6.x（1.21.1）把整条实体碰撞流程收敛到一个谓词上：

```java
level.getEntitiesOfClass(Entity.class, box.inflate(2).expandTowards(0, 32, 0), contraption::canCollideWith)
```

所以只要让 `AbstractContraptionEntity#canCollideWith` 对画返回 `false`，画就完全不会被考虑——
不会被推、被传送（会重新吸附到方块坐标，把画甩飞）、也不会被 `hurt` 掉成掉落物。
方法名给了 `canCollideWith` / `collidesWith` / `method_30949` 三种拼写且 `require = 0`，
未来 Create 改名时最坏情况只是「功能静默失效」，不会崩游戏。

> 与 1.18.2 的差别：那边打的是 `AbstractContraptionEntity#collidesWith`（Create 自己 override 的），
> 1.21.1 的 Create 6.x 直接继承原版方法，但**方法体仍在 `AbstractContraptionEntity` 上**（`public`），
> 所以 mixin 目标类不变，只是方法名换成 `canCollideWith`。

### 3.5 没有移植的部分

| 1.18.2 功能 | 1.21.1 状态 | 原因 |
| --- | --- | --- |
| `Immersive Painting Blocks`（`imb`）兼容 mixin | **未移植** | 该模组没有 1.21.1 版本，且它在 1.21 的实体交互签名（`interact`）与 1.18 不同，写了也无法验证 |
| `ipe.imb.mixins.json` | 删除 | 同上 |

如果 `imb` 将来发布 1.21.1，按 1.18.2 的思路补一个 `required=false` + 插件的 mixin 配置即可，
不需要改动现有代码。

---

## 4. 验证进行到哪一步

这台机器**无法完整构建**，原因在环境而不在代码：

* `maven.neoforged.net` 连接超时（25 s 无响应），而 ModDevGradle 必须从它下载
  `net.neoforged:neoform:<ver>:zip` 与 `net.neoforged:neoforge:<ver>`；
* `gradle-8.12` 的 wrapper 分发包下载时 JDK 报 `PKIX path building failed`
  （本机 HTTPS 被中间人代理，JDK 21 的 cacerts 里没有对应根证书），
  本地缓存里只有 `gradle-8.12-bin.zip.part`（未完成）与 `gradle-9.3.0`。

因此**编译 / 启动游戏都没有跑过**。为了避免「写完就扔」，改用可离线完成的三种核对：

1. **官方映射表核对**：下载 `1.21.1` 的 Mojang `client.txt`，逐个确认本移植用到的成员名与描述符
   （`calculateBoundingBox`、`AABB.ofSize`、`Vec3.x/y/z`、`absRotateTo`、`EntityHitResult(Entity, Vec3)`、
   `ProjectileUtil.getEntityHitResult` 两个重载、`Direction.get2DDataValue/getStepX/getStepZ` 等）。
2. **jar 反编译核对**：对 `immersive_paintings-neoforge-1.21.1-0.8.0.jar` 跑 `javap -p -c`，确认
   * `ImmersivePaintingEntity` 的字段/方法名与访问级别（`private int rotation`、
     `protected AABB calculateBoundingBox(BlockPos, Direction)`、`public void setDirection(Direction,int)`、
     `public InteractionResult useOn(UseOnContext)`）；
   * `useOn` 里确实以 `invokevirtual ImmersivePaintingEntity.setDirection:(Direction;I)V` 形式调用
     （`@ModifyArg` 的注入点因此成立），且 `getEntityType()` 是多态的，发光/涂鸦变体共用同一个 `useOn`。
3. **原版字节码核对**：对 `1.21.1` 客户端 jar 里的 `HangingEntity` 跑 `javap -p -c`，确认
   `recalculateBoundingBox()` 真的是「`calculateBoundingBox` → `getCenter()` → `setPosRaw` → `setBoundingBox`」，
   这是 §2.1 那个简化的前提。
4. **语法核对**：用 JDK 21 的 `javac` 对全部源码做无 classpath 编译，除「找不到符号」外没有其它错误。

> 换句话说：**API 形状是核对过的，运行时行为没有实测过**。第一次真正跑 `runClient` 时，
> 请重点看下面这几件事。

### 4.1 首次运行要重点确认

1. 日志里 mixin 是否全部应用成功（没出现 `Mixin apply failed`）。
2. 潜行贴墙右键 → 画是否贴着方块竖棱、朝 45°、且不穿模。
3. 斜画正前方的方块能否正常右键/破坏（`ProjectileUtilMixin` 是否生效）。
4. 存档 → 退出 → 重进，斜画角度是否保持（靠上游 `Rotation` NBT 字段）。
5. 装了 Create 时：把画放在铁轨边，让列车通过，画不应被推走/消失。

---

## 5. 已知限制（与 1.18.2 一致）

* 包围盒仍是轴对齐的（Minecraft 只存一个 `AABB`），所以 F3+B 看到的还是那个大立方体；
  它不再影响交互，但仍用于实体追踪。
* 只支持墙面（水平朝向）。地板/天花板保持原版行为。
* 画固定吸附在方块竖棱上，不可自由摆放；多格画从棱对称展开。
* `/tp` 等传送命令会让画按「包围盒中心取整」重新吸附，斜画可能偏一格；放置、编辑、重载不受影响。
* Create 的修复依赖 `canCollideWith`；若未来 Create 改用别的谓词，功能会静默失效（日志里会写是否应用成功）。

---

## 6. 构建说明（给下一位维护者）

```powershell
cd "D:\codename_EndDavid\hello java\immersive_paintings_angled_1.18.2\neoforge-1.21.1"
$env:JAVA_HOME = "D:\codename_EndDavid\hello java\jdk-21.0.2"
.\gradlew.bat build        # 产物在 build/libs/
.\gradlew.bat runClient    # 需要能下载 NeoForge 与 fzzy_config
```

* **JDK**：Gradle 与编译都用 21（`gradle.properties` 里已用 `org.gradle.java.home` 钉死）。
* **Gradle**：wrapper 钉在 `8.12`，与上游 Immersive Paintings 一致（ModDevGradle 2.0.90 的稳定组合）。
  如果想用 9.x，需要同时把 `net.neoforged.moddev` 升到支持 Gradle 9 的版本。
* **网络**：`settings.gradle` 里除了官方 `maven.neoforged.net` 之外，挂了一个
  `bmclapi2.bangbang93.com/maven` 作为 fallback（本机上官方域名不可达时的备用源；
  该镜像的 `neoform` 制品不一定全，但 `neoforge` 的 pom / userdev jar 是齐的）。
  若官方源可用，镜像不会被访问。
* **依赖**：`immersive_paintings 0.8.0+1.21.1` 与 `fzzy_config 0.7.7+1.21+neoforge`
  都从 Modrinth maven 取，二者都在 `neoforge.mods.toml` 里声明了依赖关系。
* **测试 Create**：Create 不是编译期依赖，把 `create-1.21.1-*.jar` 丢进 `neoforge-1.21.1/run/mods` 即可。

---

## 7. TODO

- [ ] 在能联网的机器上跑一次 `.\gradlew.bat build` 与 `runClient`，补全 §4.1 的验证记录。
- [ ] 实测 `ImmersivePaintingEntityMixin` 的对角包围盒在「2 格高画」上是否与原版同样抬高半格。
- [ ] 确认 `ProjectileUtilMixin` 在 1.21.1 的准星拾取（`GameRenderer` → `getEntityHitResult`）确实走该重载。
- [ ] 若 `imb`（Immersive Painting Blocks）发布 1.21.1，按 §3.5 补回兼容 mixin。
- [ ] 两边稳定后，考虑把共用代码抽成 `common/` 多模块布局（届时可以把 1.18.2 的 `src/` 迁入 `fabric-1.18.2/`）。

