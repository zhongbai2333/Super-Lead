# AE2 / Mekanism 联动绳索设计备忘

本文记录 Super Lead 与 Applied Energistics 2、Mekanism / Mekanism: MoreMachine 的联动边界与测试依赖配置。

## 目标

新增可选联动绳索，而不是替换原模组自带管道：

- AE 网络绳：用绳索视觉连接 AE2 网络节点。
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
| Applied Energistics 2 | `org.appliedenergistics:appliedenergistics2:26.1.8-alpha` | AE 网络绳 API 与本地运行时测试 |
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

- 钢锭把普通绳升级为加压管道绳。
- 钢锭 Shift+右键端点方块切换抽取端；再次点同一端关闭抽取。
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

- `LeadKind.THERMAL` 使用铜锭升级。
- 强化合金升级热量均衡速度。
- 不保存 `extractAnchor`，没有主从端点，也不渲染端点膨胀或流动脉冲。
- 服务端按同一热导绳网络组件收集端点热能力，并调用 `MekanismHeatBridge.balance` 在端点之间做热量均衡。

这样更接近热导管道的“网络内温度趋于均衡”语义，也避免把不适用的抽取/插入 UI 套到热量系统上。

### AE2 ME 网络

可行但不能按普通“抽取/插入”管道处理。AE2 的 ME 线缆本质是网格图：

- 需要创建/维护 AE2 grid node。
- 需要把两端节点作为一条连接加入 AE2 网络。
- 需要处理连接生命周期：绳子创建、断裂、区块卸载、维度卸载、存档加载。

推荐把 AE 网络绳作为一个独立联动层实现：

1. Super Lead 存档仍只保存绳索端点和类型。
2. 当 AE2 已加载且目标区块有效时，为每条 AE 网络绳创建临时 grid host/node。
3. 两端 anchor 对应节点通过 AE2 API 建立网格连接。
4. 绳子断开或端点失效时销毁连接。

AE2 通道平衡建议默认保守：先按普通 cable 连接行为做，不绕过 AE2 自身频道/供能规则；后续再通过配置暴露“无通道展示绳”等实验玩法。

## 后续实现拆分

1. 已新增 Mek 联动 `LeadKind`：`PRESSURIZED`、`THERMAL`。
2. 已补物品显示名、语言、颜色映射、服务端配置项与运行时配置入口。
3. 已新增 `integration/mekanism` 桥接层，并在 tick 入口用 `ModList` 判断 Mekanism 是否加载。
4. 已落地 PRESSURIZED chemical capability 传输 tick 与 THERMAL heat balance tick。
5. AE2 后续再落地 grid node 生命周期管理。
6. 后续分别增加 gametest 或最小 dev-world 测试步骤。
