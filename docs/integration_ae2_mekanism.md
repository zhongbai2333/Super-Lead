# AE2 / Mekanism 联动绳索设计备忘

本文记录 Super Lead 与 Applied Energistics 2、Mekanism / Mekanism: MoreMachine 的联动边界与测试依赖配置。

## 目标

新增可选联动绳索，而不是替换原模组自带管道：

- AE 网络绳：用绳索连接 AE2 网络节点，服务端通过 AE2 官方 grid API 维护连接。
- Mek 加压管道绳：气体与化学品二合一，走 Mekanism 统一 `IChemicalHandler`。
- Mek 热导管道绳：走 Mekanism `IHeatHandler` 做热量均衡。

这些联动应保持可选：未安装 AE2 / Mekanism 时 Super Lead 仍可独立加载。

## 依赖与运行时测试

Gradle 中采用两层依赖：

- `compileOnly`：只用于编译联动代码，不让 AE2 / Mekanism 成为硬依赖。
- `localRuntimeOnly`：只进入本地开发运行时，方便 `runClient` / `runServer` 测试，不发布到 Super Lead 的 Maven POM。

当前版本来源：

| Mod | 坐标 | 用途 |
| --- | --- | --- |
| Applied Energistics 2 | 编译期 API `org.appliedenergistics:appliedenergistics2:26.1.8-alpha:api` + 完整 compileOnly `org.appliedenergistics:appliedenergistics2:26.1.8-alpha`；运行期完整 `org.appliedenergistics:appliedenergistics2:26.1.8-alpha` | AE 网络绳 API、本地运行时测试，以及绳挂终端打开 AE2 原生菜单 |
| Mekanism | `com.github.QiuYe-123:Mekanism:26.1-SNAPSHOT` | Mek 气体/化学品 capability API 与本地运行时测试；该 26.1 线的 mod version 当前是 `10.8.0` |
| Mekanism: MoreMachine | `./libs/Mekanism-MoreMachine-*.jar` 或 `com.github.lostmyself8:Mekanism-MoreMachine:v1.2.0-1.21.1` | 可选机器测试目标，默认未启用 |

MoreMachine 的 `26.1` 分支目前没有稳定可解析的 JitPack 坐标；JitPack 上能解析的公开 tag 是 `v1.2.0-1.21.1`，但它不是 26.1 线。为了不让本项目的默认测试流因为上游分支发布状态而失败，当前策略是：

- AE2 与 Mekanism 默认进入 `localRuntimeOnly`。
- MoreMachine 26.1 推荐把本地构建出的 jar 放到 `./libs/`，文件名匹配 `Mekanism-MoreMachine-*.jar` 或 `mekmm-*.jar` 即会自动进入运行时。
- 如果只是临时验证 JitPack 已发布 tag，可用 `-PenableMoreMachineRuntime=true` 打开 Maven 依赖；默认版本是当前可解析的 `v1.2.0-1.21.1`。

注意：`neoforge.mods.toml` 里的 `versionRange` 比较的是目标 mod 自己在 metadata 中声明的 mod version，而不是 Maven artifact version 或 Minecraft/NeoForge 版本线。例如 Mekanism 26.1 分支的 Maven 版本是 `26.1-SNAPSHOT`，但运行时 mod version 是 `10.8.0`，所以 Super Lead 对 `mekanism` 的可选依赖范围应写成 `[10.8.0,)`。

## 可行性判断

### Mekanism 加压管道（气体 / 化学品）

可行性高。Mekanism 26.1 线的气体与化学品已经走统一化学品 API，绳索端点可以沿用 Super Lead 当前物品/流体绳的“抽取端 → 图遍历 → 插入端”思路：

1. 根据 `LeadAnchor` 找到方块和面。
2. 查询对应 Mekanism capability。
3. 按绳索 `tier` 决定每 tick 最大吞吐。
4. 从抽取端模拟抽取，再向插入端模拟插入，最后执行真实转移。

实现上采用一个 `LeadKind.PRESSURIZED`：

- 钢块把普通绳升级为加压管道绳。
- 钢块 Shift+右键端点方块切换抽取端；再次点同一端关闭抽取。
- 强化合金升级传输速度。
- 客户端沿用物品/流体绳的流动脉冲与端点膨胀表现。
- 化学过滤使用 `ChemicalFilter.ANY`，因此气体与非气态化学品都可通过同一种绳传输。

群友提到的“泛型几乎不可读”确实会踩坑，但坑点不在 `IChemicalHandler` 本身。当前 Super Lead 的物品/流体抽象使用 NeoForge `ResourceHandler<R extends Resource>`，而 Mekanism 26.1 的化学品 capability 暴露的是自己的 `IChemicalHandler` / `ChemicalStack`，二者不是同一个传输模型。强行把 Mek 化学品塞进 `tickTransfer<R extends Resource>` 会让泛型签名变得很绕，也会把 Mek API 细节污染到核心网络代码。

解决方案是单独做一层很薄的 Mek 化学品 facade：

- `MekanismChemicalBridge` 只出现在 `integration/mekanism` 包里。
- 对外只暴露 `ChemicalFilter`、`hasHandler` 和按锚点调用的 `transferOne` 这类直白方法。
- 核心绳索逻辑不直接碰 Mek 的 `MultiTypeCapability`、`Action`、`GASEOUS` tag 和化学品 handler 细节。
- 如果未来想重新拆成“只气体/只化学品”两种玩法，可在 `MekanismChemicalBridge` 里改用 `ChemicalFilter.GASEOUS` / `ChemicalFilter.NON_GASEOUS`，不需要污染核心网络逻辑。

这意味着问题可以解决：不要复用现有 `ResourceHandler<R>` 泛型路径，而是给 Mek chemical 做专用 adapter，再让绳索图遍历逻辑复用同一套“从 A 到 B”的高层流程。

### Mekanism 热导管道

热导管道同样可行，但不应该套用物品/流体/加压管道的抽取端模型：

- `LeadKind.THERMAL` 使用铜块升级。
- 强化合金升级热量均衡速度。
- 不保存 `extractAnchor`，没有主从端点，也不渲染端点膨胀或流动脉冲。
- 服务端按同一热导绳网络组件收集端点热能力，并调用 `MekanismHeatBridge.balance` 在端点之间做热量均衡。

这样更接近热导管道的“网络内温度趋于均衡”语义，也避免把不适用的抽取/插入 UI 套到热量系统上。

### 挂件白名单过滤

所有种类绳子都允许挂物品。对传输型绳子，挂件同时作为无 GUI 的默认白名单过滤器：

- 物品绳：任意挂件都是物品样本；路径上的每根物品绳若挂了物品，只允许匹配该挂件物品与组件的物品通过。
- 流体绳：只把能读出流体内容的挂件计入过滤，例如水桶、岩浆桶或其他带 NeoForge fluid item capability 的容器；非流体装饰不会锁死流体。
- 加压管道绳：只把能读出 Mekanism 化学品内容的挂件计入过滤，例如装有氧气/氢气等化学品的 Mek 容器；非化学品装饰不会锁死管道。
- 一条路径上每根绳子的过滤都要通过，因此可以在分支绳上挂不同样本来做无 GUI 路由。
- 没有可识别样本挂件时，该类型传输保持不过滤。

### AE2 ME 网络

可行但不能按普通“抽取/插入”管道处理。AE2 的 ME 线缆本质是网格图：

- 需要创建/维护 AE2 grid node。
- 需要把两端节点作为一条连接加入 AE2 网络。
- 需要处理连接生命周期：绳子创建、断裂、区块卸载、维度卸载、存档加载。

推荐把 AE 网络绳作为一个独立联动层实现：

1. Super Lead 存档仍只保存绳索端点和类型。
2. 当 AE2 已加载且目标区块有效时，为每条 AE 网络绳创建临时 managed grid node。
3. 使用 `GridHelper.getExposedNode(level, pos, side)` 找到两端 AE2 暴露节点，再用 `GridHelper.createConnection(a, b)` 建立连接。
4. 绳子断开或端点失效时销毁连接。

AE2 通道平衡默认保守：按官方 cable 节点行为做，不绕过 AE2 自身频道/供能规则。官方 API 公开的容量开关是普通 cable（8）与 `GridFlags.DENSE_CAPACITY`（32，受 AE2 全局 channel mode 影响）；没有公开的“任意设置为 256 通道”的 per-node API。因此 Super Lead 的 AE 网络绳采用 8 → 32 的官方容量模型，避免 UI 显示 256 但实际 AE2 不承认。

当前交互与容量规则：

- `LeadKind.AE_NETWORK` 使用福鲁伊克斯水晶块（`ae2:fluix_block`）升级。
- 默认频道容量为 8。
- 使用 16³ 空间组件（`ae2:spatial_cell_component_16`）升级为 AE2 官方致密容量。
- 频道容量按 AE2 官方 API 显示为 8 → 32。

### AE2 绳挂终端

第一版已支持把 `ae2:terminal` 作为挂件挂在 AE 网络绳上，然后空手右键该挂件打开 AE2 原生 ME Terminal 菜单。实现方式不是客户端伪造 GUI，而是复用告示牌挂件的“客户端命中挂件 → 发包给服务端”入口，并在服务端通过 AE2 菜单系统打开：

1. 客户端选中绳子挂件并发送 `OpenRopeAeTerminal`。
2. 服务端校验目标绳子为 `AE_NETWORK`、挂件为 `ae2:terminal`、玩家仍在可触达范围内。
3. `AE2NetworkBridge` 注册自定义 `MenuHostLocator`，让 AE2 菜单能在服务端与客户端都定位到同一个“绳挂终端 Host”。
4. Host 实现 `ITerminalHost` / `IActionHost`，存储视图来自该 AE 绳当前接入的 AE grid。

当前版本刻意只开放普通 ME Terminal，暂不开放合成终端、样板终端或样板访问终端。原因是这些终端需要额外持久化合成格、样板槽或供应器视图，应该单独做持久化设计。

注意：该第一版 Host 复用 AE 绳自身维护的 bridge node 作为 action host，因此主要用于验证“绳上挂终端并打开 AE2 原生菜单”的可行性。若要完全贴近 AE2 Part 行为，后续应为每个终端挂件维护独立 `REQUIRE_CHANNEL` 节点，让终端本身也消耗频道。

## 后续实现拆分

1. 已新增联动 `LeadKind`：`PRESSURIZED`、`THERMAL`、`AE_NETWORK`。
2. 已补物品显示名、语言、颜色映射、服务端配置项与运行时配置入口。
3. 已新增 `integration/mekanism` 桥接层，并在 tick 入口用 `ModList` 判断 Mekanism 是否加载。
4. 已落地 PRESSURIZED chemical capability 传输 tick 与 THERMAL heat balance tick。
5. AE2 已接入材料、官方 grid node 连接维护、频道容量和视觉/提示层。
6. 后续分别增加 gametest 或最小 dev-world 测试步骤。
