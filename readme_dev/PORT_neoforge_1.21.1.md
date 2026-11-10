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
| `context.getHitPos()` | `context.getClickLocation()`（`getHitResult()` 在 1.21.1 是 `protected`，见 §4.4） |
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

**结论：`.\gradlew.bat build` 已成功，产物在 `build/libs/`。**

```
immersive_paintings_enhanced_neoforge-0.2.0+1.21.1.jar          46109 B
immersive_paintings_enhanced_neoforge-0.2.0+1.21.1-sources.jar  32402 B
BUILD SUCCESSFUL, 2 actionable tasks
```

`jar tf` 确认打进包里的东西是对的：3 个游戏 mixin + Create 兼容 mixin、`ipe.mixins.json`、
`ipe.create.mixins.json`、`META-INF/neoforge.mods.toml`、`icon.png`。

走到这一步中间踩了两个坑，都不是「代码写错」，值得记下来（见 §4.3 与 §4.4）：
一是 **Modrinth Maven 按版本号取文件，会把 Fabric 版 jar 塞给 NeoForge 工程**，
二是 `UseOnContext#getHitResult()` 在 1.21.1 是 `protected`。

**仍然没有跑过游戏**。构建成功只证明「能编译、能打包」，不证明 mixin 在运行期能应用。
为了让这次移植不至于「写完就扔」，除构建外还做了四项可离线完成的核对：

1. **官方映射表核对**：下载 `1.21.1` 的 Mojang `client.txt`，逐个确认本移植用到的成员名与描述符
   （`calculateBoundingBox`、`AABB.ofSize`、`Vec3.x/y/z`、`absRotateTo`、`EntityHitResult(Entity, Vec3)`、
   `ProjectileUtil.getEntityHitResult` 两个重载、`Direction.get2DDataValue/getStepX/getStepZ` 等）。
2. **jar 反编译核对**：对 `immersive_paintings-neoforge-1.21.1-0.8.0.jar` 跑 `javap -p -c`，确认
   * `ImmersivePaintingEntity` 的字段/方法名与访问级别（`private int rotation`、
     `protected AABB calculateBoundingBox(BlockPos, Direction)`、`public void setDirection(Direction,int)`、
     `public InteractionResult useOn(UseOnContext)`）；
   * `useOn` 里确实以 `invokevirtual ImmersivePaintingEntity.setDirection:(Direction;I)V` 形式调用
     （`@ModifyArg` 的注入点因此成立，偏移 134），且 `getEntityType()` 是多态的，发光/涂鸦变体共用同一个 `useOn`。
3. **NeoForge 反编译源码核对**：直接读 ModDevGradle 生成的
   `build/moddev/artifacts/neoforge-21.1.154-sources.jar`，确认
   * `HangingEntity.recalculateBoundingBox()` 真的是「`calculateBoundingBox` → `getCenter()` →
     `setPosRaw` → `setBoundingBox`」，`calculateBoundingBox` 在 `HangingEntity` 里是 `abstract protected`，
     但 `ImmersivePaintingEntity` 有具体实现，所以注入点是实方法；
   * `ProjectileUtil` 的两个重载签名逐字符对得上（见 §3.3）；
   * `UseOnContext#getHitResult()` 是 `protected`，`getClickLocation()` 才是 `public`（这是 §4.4 的由来）。
4. **语法核对**：用 JDK 21 的 `javac` 对全部源码做无 classpath 编译，除「找不到符号」外没有其它错误。

> 换句话说：**编译、打包、模组加载与 mixin 应用都是实测过的**（`runServer`，见 §8）；
> 仍未实测的只有客户端侧的渲染与交互。§4.1 是 `runClient` 的检查清单。

### 4.1 首次运行要重点确认

1. 日志里 mixin 是否全部应用成功（没出现 `Mixin apply failed`）。
2. 潜行贴墙右键 → 画是否贴着方块竖棱、朝 45°、且不穿模。
3. 斜画正前方的方块能否正常右键/破坏（`ProjectileUtilMixin` 是否生效）。
4. 存档 → 退出 → 重进，斜画角度是否保持（靠上游 `Rotation` NBT 字段）。
5. 装了 Create 时：把画放在铁轨边，让列车通过，画不应被推走/消失。

### 4.2 本机的网络问题（均已解决，留作排查记录）

只是排查记录，换机器可以直接跳过。

#### (1) 已解决：`PKIX path building failed`

现象：`.\gradlew.bat build` 在下载 Gradle 分发包这一步就崩，栈顶是
`sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path`。
开不开代理都一样，因为**这一步根本没走代理**——是 JVM 自己的信任库不认那张证书。

诊断（用同一个 JDK 跑一个 `HttpsURLConnection` 探针）：

| 信任库 | 锚点数量 | 结果 |
| --- | --- | --- |
| JDK 默认 `jdk-21.0.2/lib/security/cacerts` | 106 | `PKIX path building failed` |
| Windows 证书库（`-Djavax.net.ssl.trustStoreType=Windows-ROOT`） | 73 | HTTP 200 |

根因：`services.gradle.org/distributions/...` 会 302 到 **GitHub release assets**
(`release-assets.githubusercontent.com`)，这条链的根证书在 Windows 库里有、但**不在这台机器
JDK 的 cacerts 里**（cacerts 与系统证书库不一致，通常是 JDK 被解压/patch 过或 cacerts 被动过）。

修复分两步，都不改 JDK 安装目录：

```powershell
# A. 让 wrapper 不再联网下载：把发行包手动装进 wrapper 缓存
$dir = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.12-bin\cetblhg4pflnnks72fxwobvgv"
curl.exe -L -o "$dir\gradle-8.12-bin.zip" https://mirrors.cloud.tencent.com/gradle/gradle-8.12-bin.zip
tar.exe -xf "$dir\gradle-8.12-bin.zip" -C $dir
New-Item -ItemType File -Force "$dir\gradle-8.12-bin.zip.ok" | Out-Null
```

```properties
# B. 让 Gradle（含 daemon）信任 Windows 证书库
#    文件：%USERPROFILE%\.gradle\gradle.properties （用户级，不进仓库）
org.gradle.jvmargs=-Xmx3G -Djavax.net.ssl.trustStoreType=Windows-ROOT
```

注意：

* `Expand-Archive` 对这个 zip 会抛「构造函数调用异常」，用 `tar.exe` 解压即可。
* 腾讯镜像（`mirrors.cloud.tencent.com/gradle/`）实测 15.8 MB/s，130 MB 八秒下完；
  华为 `mirrors.huaweicloud.com/gradle/` 同样可用，阿里云没有这个目录。
* `Windows-ROOT` 只有 Windows 有。换 Linux/macOS 或修好 JDK 的 cacerts 后要删掉这一行，
  否则第一次 HTTPS 会报 keystore 类型不存在。

修好后：

```
.\gradlew.bat --version   ->  Gradle 8.12
.\gradlew.bat help        ->  BUILD SUCCESSFUL
```

`help` 能成功说明 Gradle 8.12 + ModDevGradle 2.0.90 插件、`settings.gradle`、`build.gradle` 都没问题。

#### (2) 已解决：`maven.neoforged.net` 曾经不可达

（换网络 + 挂代理后已恢复，下面的封锁分析保留下来备查。）

* `Resolve-DnsName` 返回 `103.252.114.11`；AliDNS 返回 `157.240.17.41`（Facebook 网段，明显投毒）；
  DoH.pub 才给出真实 CNAME `1755624453.rsc.cdn77.org` → `95.173.197.105 / 156.146.44.90 / 84.17.57.26`。
  用 `curl --resolve` 直连这三个 IP 全部 reset / 超时，`Test-NetConnection -Port 443` 也是 `False`。
* 缓存镜像 `bmclapi2.bangbang93.com/maven`（后端是 USTC 的 bmclapi）：
  * `net/neoforged/neoforge/21.1.154/neoforge-21.1.154.pom` → 200
  * `net/neoforged/neoforge/21.1.154/neoforge-21.1.154-userdev.jar` → 200（3.9 MB）
  * `net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip` → **403**
    （USTC WAF 拦 `.zip`，加 `UA`、加 `Range` 都一样）
* ModDevGradle 必须要那个 `neoform` zip：它是「原版 jar + 映射 + NeoForge patch」的原料，
  在本地跑 NeoForm 才能生成可编译的 Minecraft，缺了它就没有编译期 classpath。

镜像探测汇总（`neoform-1.21.1-20240808.144430.zip` / 同目录 `.pom`）：

| 源 | `.pom` | `.zip` |
| --- | --- | --- |
| `bmclapi2.bangbang93.com/maven`（→ USTC bmclapi） | 200 | **403** |
| `mirrors.ustc.edu.cn/bmclapi/maven` | — | 403 |
| `mirrors.tuna.tsinghua.edu.cn/bmclapi/maven` | — | 403 |
| `mirrors.bfsu.edu.cn/bmclapi/maven` | — | 403 |
| `mirror.nju.edu.cn/bmclapi/maven` | — | 404 |
| `mirrors.cloud.tencent.com/nexus/.../maven-public` | 404 | 404 |
| `maven.aliyun.com/repository/public` | 404 | 404 |

规律很清楚：**所有 bmclapi 系镜像的 `.pom` 都能取，只有 `.zip` 被 WAF 拦**。
换 UA、加 `Range` 头、先 `HEAD` 再 `GET` 都不行，不是防盗链而是按扩展名拦截。

顺带记录：Gradle 的 `--no-daemon` 在这台机器上执行 `createMinecraftArtifacts` 时会安静地卡住
（CPU 不动、日志不增、java 进程还在），只能 `taskkill /T` 收场。

**试过但已回退**的改动：把 `settings.gradle` 改成 `dependencyResolutionManagement` +
`RepositoriesMode.PREFER_SETTINGS`，想让镜像也覆盖 ModDevGradle 内部创建的 configuration。
原因是 MDG 的 NeoForge 依赖走的是它自己建的 detached configuration，普通 `repositories {}`
改不到。但这个模式会**连 MDG 自带的仓库一起屏蔽掉**，实测让 `.\gradlew.bat help` 从「29 s 成功」
变成「卡在 Starting a Gradle daemon 数分钟」。所以它被回退了，只在注释里留了说明。
结论：仓库列表交给 MDG 自己维护，我们只保证 `pluginManagement` 能取到 MDG 插件本体。

保留的改动（都是低风险的）：

* `gradle.properties` 加 `connectionTimeout=10000` / `socketTimeout=20000`，
  不可达的仓库在 10 s 内失败并报错，而不是挂十几分钟。
* `settings.gradle` 写明镜像的定位（`pom` / userdev jar 的 fallback，取不到 `neoform` zip）。

#### (3) 已解决：Java / Gradle 默认不读 Windows 系统代理

换到可用网络并挂上代理后，浏览器能开、`Invoke-WebRequest` 也能 200，但 `gradlew` 依然报
`Plugin [id: 'net.neoforged.moddev', version: '2.0.90'] was not found`。

原因：Windows 的代理设置存在注册表
`HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`（本例 `ProxyEnable=1`、
`ProxyServer=127.0.0.1:7897`），只有 **WinINET**（IE/Edge/`Invoke-WebRequest`）会读它；
`netsh winhttp show proxy` 显示「直接访问」，**JVM 完全不认识这个设置**。
于是 PowerShell 拿到的是 200，Gradle 直连真实 IP 失败——看起来像「仓库没这个包」。

诊断方法（不改任何东西就能看出来）：

```powershell
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' |
  Select-Object ProxyEnable, ProxyServer, AutoConfigURL
netsh winhttp show proxy
```

修法：**项目级** 的 `systemProp.*`（`neoforge-1.21.1/gradle.properties`，默认注释掉）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.nonProxyHosts=localhost|127.*|[::1]
```

开代理时取消注释，然后 `.\gradlew.bat --stop` 收尾；关代理时务必重新注释掉，否则每次网络请求都会 `Connection refused: connect`。

> **教训（本机实测）**：不要把代理写进 `%USERPROFILE%\.gradle\gradle.properties`。
> 那是**机器级**配置，会让这台机器上所有 Gradle 项目在代理关闭后全部连不上网。
> 一开始就是这么做的，属于「用修好一次换掉以后每次」的错误取舍，已改回项目级。

#### (3a) 三个「以为能用」但实测不行的写法

| 写法 | 结果 |
| --- | --- |
| 用户级 `~/.gradle/gradle.properties` 写 `systemProp.*` | **能通，但污染全局**，代理一关所有项目挂掉 |
| 命令行 `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` | **无效**。HTTP 客户端在 daemon JVM 里往往早于这些属性初始化，插件/依赖解析仍然直连 |
| 同上但先 `--stop` 保证全新 daemon | **仍然无效**（实测两次，均报 `Plugin [...] was not found`） |
| 项目级 `gradle.properties` 写 `systemProp.*` | **有效**（`--refresh-dependencies` 实测 EXIT=0，真实重新解析成功） |

命令行参数里还踩了一个 `.bat` 的坑：

```
-Dhttp.nonProxyHosts="localhost|127.*"
→ '127.*' 不是内部或外部命令
```

`|` 在经 `gradlew.bat`（cmd）传参时会被当成管道，整个构建直接退出（EXIT=255）。
所以那个参数只适合写在 `gradle.properties` 里。

验证记录（2026-11-10）：

```
A  无任何代理配置 + 缓存完整          → BUILD SUCCESSFUL（4 s，全 UP-TO-DATE）
C  --refresh-dependencies + 命令行 -D  → BUILD FAILED（插件解析不出，见上表）
D  同上但先 --stop                    → BUILD FAILED
E  项目级 systemProp + --refresh-dependencies → BUILD SUCCESSFUL（1 m 54 s，真实联网）
```

配好之后 `createMinecraftArtifacts` 一次通过（129 个 artifact，75 s），`build` 随即成功。

### 4.3 踩坑：Modrinth Maven 会把 Fabric jar 塞给 NeoForge 工程

第一次 `build` 失败在 `compileJava`，报的错很吓人：

```
ImmersivePaintingEntityMixin.java:67: 错误: 找不到符号 类 class_1530
ImmersivePaintingEntityMixin.java:71: 错误: 找不到符号 方法 absRotateTo(float,float)
ProjectileUtilMixin.java:42:         错误: 不兼容的类型: Entity 无法转换为 ImmersivePaintingEntity
```

看起来像「移植写错了」，其实是**依赖拿错了**：`class_1530` 是 **intermediary**（Fabric 系）里
`HangingEntity` 的名字。解包看缓存里的 jar 一目了然：

| jar | 元数据 | `ImmersivePaintingEntity` 的父类 |
| --- | --- | --- |
| 实际解析到的 | `fabric.mod.json` | `net.minecraft.class_1530` |
| 应该用的 | `META-INF/neoforge.mods.toml` | `net.minecraft.world.entity.decoration.HangingEntity` |

父类无法解析 → `ImmersivePaintingEntity` 不算 `Entity` 的子类 → 所有继承来的方法
（`absRotateTo` / `getDirection`）都「找不到符号」，强转也「不兼容」。
一个根因连坐出十几个错误。

根因在 Modrinth Maven 的寻址方式：`maven.modrinth:<slug>:<version>` 里的 `<version>`
若填**版本号**，它只是「在项目里找版本号叫这个的文件」，而 `0.8.0+1.21.1` 同时存在两个条目：

```
version_number = 0.8.0+1.21.1   id = pWBBXq0j   loaders = neoforge   immersive_paintings-neoforge-1.21.1-0.8.0.jar
version_number = 0.8.0+1.21.1   id = Wv4us2nU   loaders = fabric     immersive_paintings-fabric-1.21.1-0.8.0.jar
```

于是「先找到哪个就用哪个」，这次命中的是 Fabric 那个。

正确做法是填 **version ID**（唯一对应一个文件）：

```groovy
implementation "maven.modrinth:immersive-paintings:${project.immersive_paintings_version}"   // = pWBBXq0j
```

同样的坑对 `fzzy-config` 也存在（1.21.1 的 `0.7.7+1.21+neoforge` 碰巧只有一个条目，
但没理由赌上游以后不加），所以一并改成 ID `uG7oHgw6`。
`gradle.properties` 里已把「ID → 文件名」的对应关系写进注释。

> 教训：**多加载器项目在 Modrinth 上，`+1.21.1` 这种版本号不是唯一键**。
> 按版本号解析是「今天能编、明天上游多传一个 loader 就编不过」的定时炸弹。

### 4.4 踩坑：`UseOnContext#getHitResult()` 在 1.21.1 是 `protected`

`ImmersivePaintingItemMixin` 需要点击位置来算「点中了棱的哪一侧」，1.18.2 用的是
`ItemUsageContext#getHitPos()`。1.21.1 对应的 `getHitResult()` 被降级成 `protected`：

```
ImmersivePaintingItemMixin.java:68: 错误: getHitResult() 在 UseOnContext 中是 protected 访问控制
```

核对 `UseOnContext.java`（来自 `build/moddev/artifacts/neoforge-21.1.154-sources.jar`）：

```java
33:    protected final BlockHitResult getHitResult() { ... }
45:    public Vec3 getClickLocation() { return this.hitResult.getLocation(); }
```

所以改用 `getClickLocation()`——它就是同一个 `hitResult.getLocation()`，只是公开的。
这也是「先读反编译源码、再写 mixin」比「凭记忆写」可靠的例子：这个访问级别在 1.19/1.20 期间变过。

---

## 5. 已知限制（与 1.18.2 一致）

* 包围盒仍是轴对齐的（Minecraft 每个实体只存一个 `AABB`），所以 F3+B 看到的还是那个大立方体；
  它不再影响交互，但仍用于实体追踪。
* 只支持墙面（水平朝向）。地板/天花板保持原版行为。
* 画固定吸附在方块竖棱上，不可自由摆放；多格画从棱对称展开。
* `/tp` 等传送命令会让画按「包围盒中心取整」重新吸附，斜画可能偏一格；放置、编辑、重载不受影响。
* Create 修复依赖 `canCollideWith`；若未来 Create 改用别的谓词，功能会静默失效（日志里会写是否应用成功）。

---

## 6. 构建说明（给下一位维护者）

```powershell
cd "D:\codename_EndDavid\hello java\immersive_paintings_angled_1.18.2\neoforge-1.21.1"
.\gradlew.bat build        # 产物在 build/libs/
.\gradlew.bat runClient    # 启动开发客户端
```

* **JDK**：Gradle 与编译都用 21（`gradle.properties` 里已用 `org.gradle.java.home` 钉死）。
  这台机器 PATH 上的 `java` 是 17，所以只能靠钉住的路径，`JAVA_HOME` 设不设都行。
* **Gradle**：wrapper 钉在 `8.12`，与上游 Immersive Paintings 一致（ModDevGradle 2.0.90 的稳定组合）。
  发行包已手动装进 `~/.gradle/wrapper/dists`（见 §4.2(1)），换机器时要重做这一步或能正常联网。
* **仓库**：`pluginManagement` 里是 **官方 maven.neoforged.net → bmclapi 镜像 → Central → Plugin Portal**，
  `build.gradle` 里只有 Modrinth（用于 `immersive_paintings` / `fzzy-config`）。
  ModDevGradle 解析 NeoForge 制品用的是它**自己建的仓库**，不要试图用
  `dependencyResolutionManagement` 覆盖（原因见 §4.2(2) 的回退记录）。
* **依赖必须用 Modrinth 版本 ID，不能用版本号**——原因见 §4.3：
  * `immersive_paintings` → `pWBBXq0j`（`immersive_paintings-neoforge-1.21.1-0.8.0.jar`）
  * `fzzy_config` → `uG7oHgw6`（`fzzy_config-0.7.7+1.21+neoforge.jar`）
  * `kotlin_for_forge` → `4qCjWixP`（`kotlinforforge-5.9.0-all.jar`，`runtimeOnly`；缺了它
    `fzzy_config` 会拒绝加载，见 §8.2(1)）
* **测试 Create**：Create 不是编译期依赖，把 `create-1.21.1-*.jar` 丢进 `neoforge-1.21.1/run/mods` 即可。
* **需要联网时**（首次构建 / `--refresh-dependencies`）：代理开着的话，取消 `gradle.properties`
  里那五行 `systemProp.*.proxy*` 的注释，再 `.\gradlew.bat --stop`；用完记得注释回去。
  详见 4.2(3) 与 4.2(3a)——**不要**写进用户级 `~/.gradle/gradle.properties`，也别指望命令行 `-D`。
* **别提交** `build/`、`run/`、`.gradle/`：`neoforge-1.21.1/.gitignore` 已处理。

---

## 7. TODO

- [x] 跑通 `.\gradlew.bat build`（2026-11-10，产物 46 KB，2 个 task）。
- [x] 跑通 `runServer`：模组全部加载、无 mixin 应用失败（见 §8）。
- [ ] 跑 `runClient`，补全 §4.1 剩下的客户端侧验证（§8.3 列出的四项）。
- [ ] 实测 `ImmersivePaintingEntityMixin` 的对角包围盒在「2 格高画」上是否与原版同样抬高半格。
- [ ] 确认 `ProjectileUtilMixin` 在 1.21.1 的准星拾取（`GameRenderer` → `getEntityHitResult`）确实走该重载。
- [ ] 若 `imb`（Immersive Painting Blocks）发布 1.21.1，按 §3.5 补回兼容 mixin。
- [ ] 两边稳定后，考虑抽 `common/` 多模块布局（届时把 1.18.2 的 `src/` 迁入 `fabric-1.18.2/`）。
- [ ] 与 §4.2 相关的机器级设置（`~/.gradle/gradle.properties` 里的 `trustStoreType` 一行、
  wrapper 缓存里的 Gradle 8.12 发行包）属于本机环境，**不要**提交进仓库。
  代理配置**不在**机器级文件里，它在 §4.2(3) 说的项目级注释块中。

---

## 8. 运行期验证记录（2026-11-10）

构建通过之后又跑了一次**开发服务器**（`.\gradlew.bat runServer`，比客户端轻，不需要图形与资源文件），
用来验证「mixin 能不能真的应用」和「mod 能不能真的加载」。

### 8.1 结果：通过

```
[modloading-worker-0/INFO] [ipe/]: Immersive Paintings: Enhanced (NeoForge 1.21.1) ready
                          - sneak + right click a wall to place a 45 degree painting
[Server thread/INFO] [net.minecraft.server.dedicated.DedicatedServer/]: Starting minecraft server version 1.21.1
[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: Done (0.604s)! For help, type "help"
```

同一份日志里加载成功的模组列表：

```
Fzzy Config 0.7.7+1.21+neoforge (fzzy_config)
Immersive Paintings 0.8.0 (immersive_paintings)
Immersive Paintings: Enhanced 0.2.0+1.21.1 (ipe)
Kotlin For Forge 5.9.0 (kotlinforforge)
```

关键点：

* **没有任何 `Mixin apply failed` / `InvalidInjectionException`**。`ipe.mixins.json` 是
  `"required": true` + `"defaultRequire": 1`，任何一条注入点找不到都会让游戏直接崩，
  所以「服务器能起来」本身就证明三条核心 mixin 的注入点（方法名 / 描述符）在运行时是存在的。
* `CreateMixinPlugin.onLoad` 没抛异常，`ContraptionCollisionMixin` 因为没装 Create 被正确跳过
  （日志里没有出现 `Create detected: ...` 那一行）。
* 唯一残留的警告来自上游：`Reference map 'immersive_paintings.refmap.json' ... could not be read`，
  这是 modrinth 发布的 jar 没带 refmap 的正常现象，开发环境可忽略。

### 8.2 这一步抓到两个「只有真跑才会暴露」的 bug

#### (1) 缺 KotlinForForge，mod 加载直接中止

```
Mod fzzy_config requires kotlinforforge 5.3.0 or above
Currently, kotlinforforge is not installed
...
net.neoforged.fml.ModLoadingException: Loading errors encountered
```

`fzzy_config` 是 Kotlin 写的，它把 KotlinForForge 当硬依赖。上一版 `build.gradle` 只声明了
`fzzy_config`，所以**编译完全没问题、一跑就崩**。修法：

```groovy
implementation "maven.modrinth:fzzy-config:${project.fzzy_config_version}"
runtimeOnly   "maven.modrinth:kotlin-for-forge:${project.kotlinforforge_version}"   // = 4qCjWixP (5.9.0)
```

用 `runtimeOnly` 是因为我们的代码不编译期引用 Kotlin；选 5.9.0 是因为它的
`mods.toml` 声明 `loaderVersion = "[5,)"`、`minecraft versionRange = "[1.20.6,1.22)"`，
覆盖 1.21.1 且不锁死 NeoForge 版本（5.12.0 也可以，但它把 MC 上限写到了 `1.21.9`）。

#### (2) `mandatory = false` 在 NeoForge 21 里**不生效**，Create 变成硬依赖

```
Missing or unsupported mandatory dependencies:
    Mod ID: 'create', Requested by: 'ipe', Expected range: '*', Actual version: '[MISSING]'
...
net.neoforged.fml.ModLoadingException: Mod ipe requires create any
```

`neoforge.mods.toml` 里原来写的是 Forge 1.20.1 时代的布尔写法：

```toml
[[dependencies.ipe]]
modId = "create"
mandatory = false      # NeoForge 21.x 已改用 type = "required" | "optional"
```

NeoForge 21 的依赖声明改成了枚举 `type`，`mandatory` 被忽略，缺省即 `required`。
结果这个「可有可无的兼容模组」把整个模组变成了**必须装 Create 才能进游戏**。
修法是四处依赖全部换成 `type = "required"` / `type = "optional"`：

```toml
[[dependencies.ipe]]
modId = "create"
type = "optional"
versionRange = "*"
```

> 这一条尤其值得记：编译器、`jar tf`、静态核对**全都发现不了**，只有 FML 的依赖解析会拒绝加载。
> 也说明「构建成功」和「能玩」之间还隔着一次真实启动。

### 8.3 仍然没有验证的部分

服务器只跑到「加载 + 生成世界」，摸不到客户端渲染与交互。所以下面这些仍是**待实测**：

1. 潜行贴墙右键是否真的出现 45° 贴棱的画（涉及 `@ModifyArg` 那条注入 → 需要客户端 + 创造模式）。
2. `ProjectileUtilMixin` 的准星拾取是否按预期丢弃「空角落」的命中。
3. 画的角度在存档/重载后是否保持。
4. Create 在场时 `ContraptionCollisionMixin` 是否被应用（日志里会出现
   `Create detected: contraption collision bypass applied to ...`）。

这些需要 `.\gradlew.bat runClient`，属于「有图形界面的人下次开游戏时顺手确认」的事项。

### 8.4 开发服务器留下的东西

* `neoforge-1.21.1/run/server/`（`eula.txt`、`server.properties`、`world/`、`logs/`）已由
  `.gitignore` 排除，不会进仓库。
* 每次跑完记得确认没有残留的 `java.exe`，否则下次 `runServer` 会因端口 25565 被占用而失败。
