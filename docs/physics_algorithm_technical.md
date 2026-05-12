# Super Lead 物理算法技术文档

本文档集中说明当前项目中“绳子物理模拟 / 碰撞 / 接触回报 / 服务端推挤”的算法结构、文件路径、路径功能以及关键算法位置。

## 模块总览

Super Lead 的绳子物理主要分为两条链路：

1. **客户端视觉物理**：负责本地绳子形状、地形碰撞、绳绳排斥、实体推挤、渲染插值与拾取接触采样。
2. **服务端接触校验与推挤**：接收/校验客户端绳子接触回报，必要时对玩家做单向推挤，并广播视觉接触脉冲。

核心公开入口仍是：

- `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulation.java`

实际算法按职责拆分到同包的抽象父类中，外部调用方不需要知道继承链细节。

## 客户端物理模拟路径

### 文件路径与功能

| 项目路径 | 功能 |
|---|---|
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulation.java` | 公开门面类；保留构造器、`visualLeash`、并行阶段入口和玩家接触采样。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulationCore.java` | 共享状态层；保存节点坐标、速度、约束缓存、边界框、静止/唤醒状态、基础校正方法。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulationRenderCache.java` | 渲染缓存层；负责 partial tick 插值节点、分段可见性掩码、帧 scratch、静态烘焙顶点缓存。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulationVisualState.java` | 视觉状态层；处理无物理垂链、外部接触弯曲和外部冲量。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulationTerrainConstraints.java` | 地形约束层；负责绳子节点/线段与方块 AABB 的碰撞推出、扫掠防穿透、锚点附近避让。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulationContactConstraints.java` | 接触约束层；实现 XPBD 距离约束、绳绳排斥、实体 AABB 单向推绳。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeSimulationStepper.java` | 步进调度层；负责每 tick 预处理、唤醒判定、子步进、统一约束迭代。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/BlockCollisionCache.java` | 方块碰撞缓存；缓存本步进中访问过的方块碰撞盒，避免反复查询世界状态。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeTuning.java` | 单根绳子的物理调参快照；从本地调参和物理区域预设解析覆盖值。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeForceField.java` | 外力场接口；支持调用方给绳子节点叠加自定义加速度。 |
| `src/main/java/com/zhongbai233/super_lead/lead/client/geom/RopeMath.java` | 几何工具；提供线段最近点、线段-AABB 相交、稳定随机方向等纯数学方法。 |

### 继承链

`RopeSimulation` 继承链如下：

```text
RopeSimulation
  -> RopeSimulationStepper
  -> RopeSimulationContactConstraints
  -> RopeSimulationTerrainConstraints
  -> RopeSimulationVisualState
  -> RopeSimulationRenderCache
  -> RopeSimulationCore
```

这样做的目的：

- 保持 `RopeSimulation` 对外 API 不变。
- 将大型算法文件按状态、渲染、视觉、地形、接触、调度、玩家采样拆分。
- 同包 `protected` 状态可继续共享，避免为了拆文件引入大量 getter/setter。

## 客户端物理算法流程

### 1. 创建绳子拓扑

位置：`RopeSimulationCore.java:14` 附近。

创建时根据端点距离和 `RopeTuning.segmentLength()` 决定段数：

- 最少段数：`MIN_SEGMENTS = 4`
- 最大段数：`RopeTuning.segmentMax()`
- 节点数：`segments + 1`

节点初始化为端点连线上的均匀采样点，并给中间节点一个很小的稳定侧向速度，避免完美直线导致碰撞法线退化。

### 2. Tick 步进入口

关键位置：

- `RopeSimulationStepper.java:8`：步进调度类。
- `RopeSimulationStepper.java:17`：`stepUpTo(...)` 单绳兼容入口。
- `RopeSimulationStepper.java:67`：`step(...)` 完整入口。

每 tick 的主要逻辑：

1. 记录 `lastTouchTick`。
2. 计算距离上一次步进的 tick 差，最大截断为 2。
3. 判断端点是否移动、地形是否变化、邻居绳是否唤醒、实体是否靠近、外部接触是否存在。
4. 如果绳子已静止且没有唤醒原因，则清零速度并跳过求解。
5. 若需要求解，则进入子步进。

### 3. 并行物理准备

位置：`RopeSimulationStepper.java:29`。

`preparePhysicsParallel(...)` 在主线程读取世界数据并预填缓存：

- 更新自身边界和分段 AABB。
- 保存 tick 起点快照 `snapX/snapY/snapZ`。
- 检查附近地形。
- 计算方块状态 hash 是否变化。
- 预取较大范围内的方块碰撞盒。

进入并行阶段后，worker 线程只读预取缓存，不再直接调用 `Level#getBlockState`。

### 4. 子步进与积分

关键位置：

- `RopeSimulationStepper.java:154`：`substep(...)`
- `RopeSimulationStepper.java:236`：`chooseSubsteps(...)`

子步数量由端点速度和绳子内部最大速度决定：

- 低速：1 子步
- 中速：2 或 3 子步
- 高速：最多 `MAX_SUBSTEPS = 5`

每个子步：

1. 保存 `xPrev/yPrev/zPrev`。
2. 对非 pinned 节点应用阻尼。
3. 叠加重力。
4. 叠加外力场采样结果。
5. 显式积分更新位置。
6. 固定两个端点。

### 5. XPBD 距离约束

关键位置：

- `RopeSimulationContactConstraints.java:16`：`solveDistanceConstraints(...)`
- `RopeSimulationContactConstraints.java:27`：单段距离约束求解。
- `RopeSimulationStepper.java:214`：统一迭代中调用距离约束。

目标长度：

$$
L_{target} = \frac{\|b-a\| \cdot slackFactor(a,b)}{segments}
$$

XPBD 约束函数：

$$
C = \|x_j - x_i\| - L_{target}
$$

使用 compliance 后的拉格朗日乘子增量：

$$
\Delta\lambda = \frac{-C - \alpha \lambda}{w_i + w_j + \alpha}
$$

其中：

$$
\alpha = \frac{compliance}{h^2}
$$

然后沿当前段方向对两端节点应用校正。端点 pinned 时 inverse mass 为 0。

为了避免长绳在空气中约束传播不足，迭代次数至少为：

$$
\left\lceil \frac{segments}{2} \right\rceil
$$

### 6. 地形碰撞约束

关键位置：

- `RopeSimulationTerrainConstraints.java:44`：`solveTerrainConstraints(...)`
- `RopeSimulationTerrainConstraints.java:76`：线段胶囊 vs 方块 AABB。
- `RopeSimulationTerrainConstraints.java:174`：节点 vs 方块 AABB。
- `RopeSimulationTerrainConstraints.java:291`：线段扫掠防穿透。
- `RopeSimulationTerrainConstraints.java:308`：调用 `RopeMath.intersectSegmentAabb(...)`。

地形碰撞分三层：

1. **节点球体推出**：把 rope node 看成半径为 `TERRAIN_RADIUS + COLLISION_EPS` 的球体，从方块 AABB 表面推出。
2. **线段胶囊推出**：处理两个节点都不在方块内，但连接线穿过方块边缘的情况。
3. **线段扫掠防穿透**：当节点移动过快时，用线段-AABB 扫掠找最早命中并推出。

锚点所在方块列会被特殊避让，避免端点固定在方块内时把整根绳子错误推出。

### 7. 绳绳排斥与稳定分层

关键位置：

- `RopeSimulationContactConstraints.java:48`：`solveRopeRopeConstraints(...)`
- `RopeSimulationContactConstraints.java:243`：调用线段最近点。
- `RopeMath.java:12`：`closestSegmentPoints(...)`

流程：

1. 为当前绳子刷新每段 AABB。
2. 用整绳边界和分段 AABB 做 broad phase 剔除。
3. 对可能接触的两段调用 `RopeMath.closestSegmentPoints(...)`。
4. 若最近距离小于 `ROPE_REPEL_DISTANCE`，计算穿透深度。
5. 使用当前/快照高度判断上下层关系。
6. 将法线朝 Y 轴混合，减少多层绳堆叠时的横向抖动。
7. 只移动当前绳子，把邻居绳作为静态目标，避免顺序步进造成抖动。

并行阶段下，邻居坐标来自 tick 起点快照，属于 Jacobi 风格耦合；因此使用 `ROPE_ROPE_PARALLEL_RELAX` 做欠松弛，降低三层堆叠过冲。

### 8. 实体对绳子的单向推挤

关键位置：

- `RopeSimulationContactConstraints.java:101`：`solveEntityConstraints(...)`
- `RopeSimulationContactConstraints.java:117`：线段 vs 实体 AABB 推出。

实体碰撞是单向的：实体不会被客户端物理直接推动，只有绳子被实体 AABB 推开。

算法把每段绳子当作胶囊，计算线段到实体 AABB 的最近点。如果距离小于半径，则根据接触参数把校正量分配到两个端点。

此外还存在“滑过实体上下边缘”的预算逻辑：当绳子在实体侧面靠近顶部/底部，并且已经累计足够水平推挤量时，会临时忽略该窄带接触，让绳子更自然地从实体上下滑过。

### 9. 玩家接触采样与客户端回报

关键位置：

- `RopeSimulation.java`：`findPlayerContact(...)`
- `SuperLeadClientEvents.java:567`：客户端调用 `sim.findPlayerContact(...)`

客户端会在当前渲染/物理绳折线上寻找距离玩家 AABB 最近的接触点，输出：

- 世界坐标 `x/y/z`
- 绳长参数 `t`
- 水平法线 `normalX/normalZ`
- 接触深度 `depth`

该结果用于构造 `ClientRopeContactReport` 发给服务端做权威校验与玩家推挤。

## 服务端接触与推挤路径

### 文件路径与功能

| 项目路径 | 功能 |
|---|---|
| `src/main/java/com/zhongbai233/super_lead/lead/RopeContactTracker.java` | 服务端接触仲裁；接收客户端接触报告、校验合理性、推挤玩家、广播 `RopeContactPulse`。 |
| `src/main/java/com/zhongbai233/super_lead/lead/RopeContactGeometry.java` | 服务端接触几何工具；验证客户端绳形包络、区域相交。 |
| `src/main/java/com/zhongbai233/super_lead/lead/ServerPhysicsTuning.java` | 从服务器预设解析接触校验/推挤参数。 |
| `src/main/java/com/zhongbai233/super_lead/lead/ClientRopeContactReport.java` | 客户端上报的绳子接触数据包。 |
| `src/main/java/com/zhongbai233/super_lead/lead/RopeContactPulse.java` | 服务端广播给客户端的视觉接触脉冲。 |

### 当前服务端模式

服务端不再运行绳子 Verlet 物理，也不再用锚点绳形主动判定玩家碰撞。当前主流程以客户端接触回报为输入，服务端负责校验和推挤玩家：

1. 客户端采样绳子接触并发 `ClientRopeContactReport`。
2. 服务端 `acceptClientContact(...)` 校验数据有限性、绳子 ID、绳子类型、距离、物理区域、预设启用状态。
3. 服务端用 `RopeContactGeometry` 校验接触点是否位于合理的客户端绳形包络内。
4. 计算玩家受力方向和深度增益。
5. 对玩家速度做单向推挤。
6. 缓存短 TTL 的接触脉冲，并广播给同维度客户端；LOD/静态绳收到该脉冲后退出静态网格并显示相同偏移。

### 服务端调参解析

关键位置：

- `ServerPhysicsTuning.java:11`：调参 record。
- `ServerPhysicsTuning.java:40`：`loadServerPhysicsTuning(...)`。

服务端调参来源为物理区域使用的 preset overrides；会解析：

- `mode.physics`
- `gravity`
- `slack.tight`
- `contact.pushback`
- `contact.radius`
- `contact.spring`
- `contact.velocityDamping`
- `contact.maxRecoilPerTick`

服务端 gravity 只用于判断客户端报告是否可能来自下垂绳形；不会驱动服务端绳子模拟。

### 客户端绳形合理性校验

关键位置：

- `RopeContactGeometry.java:18`：`closestPointOnPlausibleClientRope(...)`
- `RopeContactGeometry.java:55`：`closestPointOnPlausibleClientRopeToPlayerAabb(...)`

服务端不会完全相信客户端报告的位置，而是根据端点和 preset 生成一个“合理垂度包络”：

- 如果重力约等于 0，则使用端点直线。
- 否则根据绳长和 `slack.tight` 估算 sag。
- 将曲线采样成 8 到 32 段。
- 计算客户端报告点到该包络的最近距离。
- 距离超过 tolerance + deflection allowance 时拒绝。

### 玩家推挤模型

关键位置：

- `RopeContactTracker.java:154`：`applyOneSidedPushback(...)`

推挤是单向的，不会把玩家往绳子里拉回去：

1. 根据接触深度得到 penetration gain。
2. 根据绳子偏移量得到 tension boost。
3. 只阻尼玩家朝向绳子内部的速度分量。
4. 加一个深度相关的小推力，避免玩家静止卡在绳子内。
5. 推挤量被 `maxRecoilPerTick` 限制。

增益采用 smoothstep：

$$
smoothstep(x)=x^3(x(6x-15)+10)
$$

这样浅接触比较柔和，深接触逐渐变硬。

## 几何工具关键位置

| 算法 | 文件位置 | 用途 |
|---|---|---|
| 两线段最近点 | `RopeMath.java:12` | 绳绳排斥、分段接触检测。 |
| 线段-AABB 相交/扫掠 | `RopeMath.java:49` | 地形扫掠防穿透。 |
| 客户端合理绳形最近点 | `RopeContactGeometry.java:18` | 服务端校验客户端接触报告。 |
| 客户端折线 vs 玩家 AABB | `RopeSimulation.java` | 客户端接触采样与上报。 |

## 运行时数据流

### 正常渲染/物理路径

```text
SuperLeadClientEvents
  -> 获取 LeadConnection 端点
  -> 查找/创建 RopeSimulation
  -> RopeSimulation.step(...)
     -> 显式积分
     -> XPBD 距离约束
     -> 地形约束
     -> 绳绳排斥
     -> 实体 AABB 推绳
  -> prepareRender(partialTick)
  -> LeashBuilder / RopeAttachmentRenderer 渲染
```

### 玩家接触回报路径

```text
客户端 RopeSimulation.findPlayerContact(...)
  -> ClientRopeContactReport
  -> 服务端 RopeContactTracker.acceptClientContact(...)
  -> RopeContactGeometry 校验合理性
  -> applyOneSidedPushback(...)
  -> RopeContactPulse 广播视觉脉冲
```

## 物理调参入口

| 参数来源 | 相关文件 | 说明 |
|---|---|---|
| 客户端默认/本地调参 | `src/main/java/com/zhongbai233/super_lead/tuning/ClientTuning.java` | 定义重力、阻尼、迭代次数、碰撞半径、LOD 等调参 key。 |
| 客户端单绳快照 | `src/main/java/com/zhongbai233/super_lead/lead/client/sim/RopeTuning.java` | 为一根绳子解析区域预设和本地默认值。 |
| 服务端接触调参 | `src/main/java/com/zhongbai233/super_lead/lead/ServerPhysicsTuning.java` | 为服务端接触/推挤逻辑解析 physics zone preset。 |
| 物理区域同步 | `src/main/java/com/zhongbai233/super_lead/preset/PhysicsZone*.java` | 决定哪些区域启用物理与预设覆盖。 |

## 注意事项

- `RopeSimulation` 的 public API 应尽量保持稳定，避免影响渲染、拾取、chunk mesh 和调试命令调用方。
- 并行物理阶段禁止 worker 线程直接读世界方块状态，必须依赖 `preparePhysicsParallel(...)` 的预取缓存。
- 绳绳排斥只修正当前绳子，不直接移动邻居，避免顺序步进产生额外抖动。
- 地形碰撞同时包含节点、线段胶囊和扫掠三种路径，删除任意一层都可能重新引入穿模或边角抖动。
- 服务端当前以客户端接触报告为主要输入，但仍保留合理性校验和 fallback 估计，不能直接信任客户端坐标。

## 编译验证

最近一次验证命令：

```powershell
.\gradlew.bat compileJava -q --console=plain
```

结果：编译通过。
