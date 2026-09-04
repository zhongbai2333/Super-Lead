# SuperLead Bench 场景路线图

三层护栏，由内向外：

1. **JUnit 测功台**（`src/test` 下的 `Rope*Test`）：纯求解器与调度 helper，
  无世界数据，秒级反馈。已覆盖拓扑、距离约束、绳绳接触、地形/实体几何、
  邻居图和活动调度。
2. **ModBench 客户端场景**（`runBenchClient`，完整驱动栈：调度器 / 异步 /
  mesh / 渲染）：已上线空吊静止、长跨度、全绳种矩阵、多挂件、物品工作、
  十字堆叠、堆叠创建顺序、玩家碰撞、松紧调整、三层堆叠，以及 53 连接
  建筑服规模的动画 cadence 场景。`rope-mesh-churn-budget-{02,04,08,12}`
  使用 1536 根常驻静态绳和 12 个同时变化的 section，对比不同提交预算下的
  registry 更新时间、mesh 接受延迟、frame interval 与独立 JFR。
  接触场景会导出逐 tick CSV，视觉场景会保留截图。
3. **ModBench 服务端场景**（`runBenchServer`）：`super_lead.server-load` smoke。
  - `super_lead.item-same-face-fanout`：一个原版木桶同一面连接 8 根 ITEM 绳，验证多目标公平轮转、资源守恒和服务端 tick 分布。
  - `super_lead.item-unloaded-source-index`：向 SavedData 直接写入分布在 64 个远端未加载源区块的 4096 根 ITEM 绳（每区块 64 个不同源点），持续采样 600 tick。该场景不放置或访问容器，用于在 JFR 中单独观察源索引构建、按区块过滤和 `getChunkNow` 成本。
  - `super_lead.redstone-network-load`：16 个独立 8 路 REDSTONE 组件周期翻转输入，持续触发真实红石脏更新并记录 tick 分布；验证全部 128 根连接都经历完整 ON/OFF 传播。
  - `super_lead.redstone-vanilla-control-before` / `super_lead.redstone-vanilla-control-after`：与 REDSTONE 网络场景完全相同的 16×8 方块布局、240 tick 和 4 tick 翻转 cadence，但不创建绳。按 before → network → after 顺序在同一 JVM 运行，用两个 control 的均值抵消预热漂移。
  - `super_lead.energy-mekanism-fanout`：一个真实 Mekanism Basic Energy Cube 从同一可抽取面连接 8 根 ENERGY 绳到 8 个目标方块，验证 FE 守恒、目标覆盖和稳定 cadence 性能。
  - Mekanism 已显式加入 `benchRuntimeMod`；后续 ENERGY/FLUID/PRESSURIZED/THERMAL 场景必须在 setup 中断言 `mekanism` 已加载及目标 capability 可用，禁止无 workload 空跑。
4. **ModBench paired 场景**（`runBenchPaired`）：独立 dedicated server + 独立 remote client，
  使用真实 loopback TCP 连接验证启动、登录、世界就绪和客户端渲染采样。

Super Lead 默认从 JitPack 使用固定的 BenchMod `0.1.3-beta` tag；普通构建和 IDE
同步都会应用插件。直接运行 `gradlew runBenchClient` 或 `gradlew runBenchServer`
即可执行场景。只有联调 BenchMod 未发布源码时，才需要先在相邻 `BenchMod` 仓库
执行 `gradlew publishToMavenLocal`，再给 Super Lead 命令添加
`-PmodBenchLocal=true`。

Super Lead 的 ModBench 配置默认启用低开销 JFR。BenchMod 会为每个实际执行的
scenario 单独录制，并写入
`build/modBench/raw-results/default/<run-type>/artifacts/jfr/<scenario-id>.jfr`；
跳过的场景不产生录制。每份 JFR 都独立登记进 `summary.json` 和 bundle，因此夹心
对照可以分别查看 control-before、network 和 control-after 的 CPU/allocation/GC，
不会再把三个阶段混进单一的 run-level `recording.jfr`。

### ITEM 未加载源索引 JFR

运行隔离场景：

```text
./gradlew runBenchServer -PmodBench.scenarios=super_lead.item-unloaded-source-index
```

场景会断言 4096 根连接在整个 600 tick 测量窗口中仍然存在，并且 64 个源区块始终
没有被加载。JFR 中可沿 `LeadTransferService.tickTransfer` 查看
`isChunkAvailableNow/getChunkNow` 的采样；因为没有真实容器或第三方 capability，结果不包含
容器模组的 handler 查询及读写耗时。录制文件为
`build/modBench/raw-results/default/server/artifacts/jfr/super_lead.item-unloaded-source-index.jfr`。

### Mesh churn section 预算矩阵

四档场景使用完全相同的固定坐标夹具：24 个 section、每 section 64 根绳，共 1536 根；
测量阶段重复 6 轮，每轮从其中 12 个 section 各移除并恢复一根绳。只改变每 tick 可提交
的紧急 dirty section 数量：

```text
./gradlew runBenchClient -PmodBench.scenarios=super_lead.rope-mesh-churn-budget-02,super_lead.rope-mesh-churn-budget-04,super_lead.rope-mesh-churn-budget-08,super_lead.rope-mesh-churn-budget-12
```

重点比较 `client.frame.interval` 的 p95/p99/max 与超 16.67 ms 帧数，同时查看：

- `super_lead.mesh_churn.registry_mutation`：增量增删连接的主线程耗时；
- `super_lead.mesh_churn.drain_ticks`：dirty 队列排空 tick；
- `super_lead.mesh_churn.accept_ticks`：全部目标 section 的新 generation 被观察到所需 tick；
- `peak_dirty_queue/peak_dirty_flush`：队列和单 tick 实际提交峰值。

2026-09-04 本机固定坐标结果（Apple M4、Java 25.0.2、无遮挡 flat world、1536 根绳）：

| section/tick | 排空/接受 tick | frame p95 | frame p99 | frame max | >16.67 ms |
|---:|---:|---:|---:|---:|---:|
| 2 | 6 | 4.146 ms | 5.393 ms | 18.204 ms | 1 |
| 4 | 3 | 4.731 ms | 6.205 ms | 13.974 ms | 0 |
| 8 | 2 | 3.560 ms | 4.808 ms | 7.723 ms | 0 |
| 12 | 1 | 4.574 ms | 6.085 ms | 15.851 ms | 0 |

增量 registry mutation 的各档均值约 0.82–0.96 ms、最大值约 1.19 ms。1536 绳矩阵中
预算 8 的最大帧最低且只需要 2 tick；576 绳校准的多轮重复中，预算 12 持续出现
20.9–22.5 ms 最大帧，而预算 8 为 11.2–17.2 ms。预算 4/2 将 handoff 延迟增加到
3/6 tick，尾帧没有稳定胜过 8。因此当前默认紧急预算选择 8，新 mesh 预算仍为 2。
该数值是此夹具和机器上的经验结果；大型整合包升级渲染 Mod 后应重跑矩阵。

### REDSTONE 夹心对照

推荐筛选顺序：

```text
super_lead.redstone-vanilla-control-before,super_lead.redstone-network-load,super_lead.redstone-vanilla-control-after
```

执行时通过 `-PmodBench.scenarios=<上述筛选值>` 传入顺序；对应 JFR 文件名分别为
`super_lead.redstone-vanilla-control-before.jfr`、
`super_lead.redstone-network-load.jfr` 和
`super_lead.redstone-vanilla-control-after.jfr`。

2026-08-07 本机单次结果（Windows 11、Java 25.0.1、32 logical processors）：control-before
mean 0.622 ms，network mean 2.597 ms，control-after mean 0.284 ms。夹心基线
$B=(0.622+0.284)/2=0.453$ ms，128 绳网络相对该世界 workload 的增量约
$2.597-B=2.144$ ms/tick，即 50 ms tick 预算的约 4.29%。这不是 JVM profiler 的
Super Lead self-time：它还包含绳输出触发的原版/NeoForge 邻居更新及其延迟世界工作。
诊断运行确认真正执行 REDSTONE 传播的 tick 平均约 4.23 ms 增量，另一个约 3.99 ms
的重相位没有再次执行 REDSTONE 服务；周期绳有效性校验在该相位平均仅约 0.012 ms。
因此不能通过删减第二相位来宣称低于 1%，除非改变红石输出通知语义或由 profiler
进一步证明可安全合并原版邻居工作。

> 当前 `runBenchPaired` 是 passthrough MVP：已支持 dedicated server + separate client
> 双 JVM 和真实 TCP 连接，但还没有 phase barrier、nonce handshake 或网络延迟/丢包模拟。
> 已有大部分 rope 物理场景仍在 client scenario 内创建 rig，并要求 integrated server；
> 但 53 绳 cadence 已提供 paired vertical slice：权威 rig 在 dedicated server 创建，
> remote client 通过同步缓存观测物理发布与渲染采样。

### Paired smoke

SuperLead 的 `build.gradle` 已配置：

- server：`super_lead.paired-server-cadence`
- remote client：`super_lead.paired-remote-cadence`

最近一次通过结果：服务端创建 53 条绳，remote client 记录 21,973 个渲染采样和
3,261 次物理发布；两端 report 与 paired summary 均为 `PASSED`。

使用默认 JitPack 版本运行：

```text
gradlew prepareBenchRemoteClientOptions runBenchPaired
```

若要联调相邻 BenchMod 源码，先在 BenchMod 执行 `gradlew publishToMavenLocal`，
再给上面的 Super Lead 命令添加 `-PmodBenchLocal=true`。

结果位于 `build/modBench/paired/default/summary.json`，两端报告分别位于
`build/modBench/raw-results/default/paired-server/summary.json` 和
`build/modBench/raw-results/default/remote-client/summary.json`。

## 已规划场景（优先级降序）

| 场景 | 守护对象 | 关键断言 | 备注 |
|---|---|---|---|
| rope-animation-cadence | 53 连接背景下物理发布到逐帧渲染的连续性 | 动态阶段有物理解发布和逐帧位移；记录最大发布间隔、连续静止帧和单帧跳变量 | integrated 版本：`super_lead.rope-animation-cadence`；paired 版本：`super_lead.paired-server-cadence` + `super_lead.paired-remote-cadence` |
| rope-long-span | 多长度单位、跨区块同步和长绳物理 | 跨度超过单单位上限；连接全程存在；拓扑节点充分；尾振幅收敛 | 已上线：`super_lead.rope-long-span` |
| rope-kind-matrix | 全部 8 种 `LeadKind` 的同步、物理和渲染 | kind 不串型；模拟坐标有限；所有绳均静止 | 已上线：`super_lead.rope-kind-matrix` |
| rope-attachments | 多种方块/物品挂件同步和挂绳静止 | 灯笼、灵魂灯笼、悬挂告示牌、铁锭顺序与类型正确；挂绳静止 | 已上线：`super_lead.rope-attachments` |
| rope-item-work | 原版容器间真实物品运输 | ITEM 绳方向同步；木桶库存守恒且目标增加；客户端收到正向物品脉冲 | 已上线：`super_lead.rope-item-work` |
| rope-stack-contact | 绳绳分离、静止与接触摩擦 | 十字绳保持分离并按时静止；尾窗腹点相对切向滑移的峰值、RMS 和累计值受限 | 已上线：`super_lead.rope-stack-contact`；导出 `rope-stack-trace.csv` |
| rope-stack-order | 绳绳接触的创建/遍历顺序敏感性 | X→Z 与 Z→X 两套十字 rig 都分离、静止，尾振幅和收敛时间相近 | 已上线：`super_lead.rope-stack-order` |
| rope-mesh-handoff | chunk mesh 进出对称性（C1/C2 修复） | 静止绳 N tick 内 meshAccepted=true；扰动→恢复循环 ≤1 次拆装；无 accepted 抖动 | 需关风或出风区（风绳按设计永不烘焙）；probe 已带 meshAccepted |
| rope-lod-ladder | 调度档位切换（h≡1 平衡点） | 相机沿关键 LOD 距离阶梯移动，每档静止后 bellyY 漂移 < 0.01 | 用 BenchCameraPath；守护调度层泵 |
| rope-attachment-swing | 挂件摆动 + 光照反馈环（C3 修复） | 带发光挂件的绳静止后：光照重烘焙计数为 0、mesh 不拆装、挂件摆动收敛 | 基础多挂件场景已上线；仍需暴露 light-rebake 计数探针 |
| rope-wind-envelope | 风系统 | 风区内绳保持动态（不烘焙）、摆动幅度在包络内、无 NaN；出风区 + 冷却后正常烘焙 | 用固定种子风场；断言用分位数而非确切值 |
| rope-transparency | 全透明绳的 mesh 跳过路径 | 透明绳不进 chunk mesh（skipStaticMeshForTransparency）、拾取仍有效 | |
| rope-entity-push | 实体推绳反馈（B2 修正后的速度语义） | 生物穿过绳：最大偏移 ≥ 阈值（反馈存在）、离开后 N tick 静止（无残留振荡） | 需要 spawn 一只生物并脚本移动 |
| rope-drape-terrain | 真实 drape（interiorTerrainContact 路径） | 绳搭在方块上：静止、不被拉离方块、无穿透 | 守护 LRA 的 drape 门 |

## 约定

- 每个场景必须：固定坐标系（从 player pose 派生）、记录创建的全部 ID、
  teardown 按 ID 精确清理、失败信息里带完整状态字符串。
- 物理断言一律走 `SuperLeadClientEvents.probeSimForBench`（只读探针），
  不得触碰模拟内部状态。
- 波形疑难：参照 stack 场景把逐 tick 序列写进 `resultDirectory`，
  用频率/相位判定能量源（反相=接触泵、同相=共同驱动、锯齿=约束打架）。
- 窗口失焦会把整轮判成 INCONCLUSIVE（帧指标不可比），物理断言仍执行；
  盯着窗口跑或用 `-DmodBench.client.requireWindowFocus=false`（如 runtime 支持）。
