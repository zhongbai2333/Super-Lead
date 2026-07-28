# Super Lead 物理算法技术文档

> **⚠️ 回滚说明（2026-07-27）**：求解器已整体回滚到 `v0.3.11-beta` 基线（上一个发布 tag 的物理行为，外加回滚前就存在的未提交绳绳接触几何改动）。本次会话中重新设计的求解器（LRA 链距约束、悬链线包络钳制、仅张力单边距离约束、静止迟滞 `restExitRatio`、`h≡1` 调度不变量等）**已全部撤出代码**，本文档中描述这些机制的章节对应的是被归档的重设计补丁，而不是当前代码。完整重设计 diff 归档于本机：`C:\Users\15044\.claude\projects\D--UserFile-Documents-GitHub-Super-Lead\memory\rope-redesign-full-2026-07-27.patch`（4026 行，约 205 KB）。保留下来的部分：bench 场景与探针（`probeSimForBench` + Core 上的兼容 shim）、拴绳结实体排除、静态绳 chunk-mesh 注册表重写、动态光源按方块亮度量化、BenchMod 集成。后续修复策略：在基线之上小步修改，每步用 bench 场景把关。

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

- `RopeSimulationStepper.java`：`substep(...)`
- `RopeSimulationStepper.java`：`chooseSubsteps(...)` / `boundSubstepSpan(...)`

**模拟时间跨度**：调度器通过 `prepareSingleScheduledStep(tick, interval)` 声明本次
求解覆盖多少 tick 的模拟时间（`scheduledTickSpan`）。稀疏调度的绳子（interval
2/4/8）一次求解推进整个间隔的时间，因此仍以 1 倍速运动；旧实现每次只推进
1 tick，远处绳子会以 1/interval 的速度慢动作运动，玩家靠近升档时又突然加速。

**子步长 h ≡ 1 tick 是不变量**（`MAX_SUBSTEP_TICK_SPAN = 1`）：迭代欠收敛的
PBD 平衡垂度正比于每子步注入的重力位移（g·h²）——实测默认调参下 6 格跨度的
绳在 h=2 时比 h=1 深 0.18 格。若稀疏档允许更长的子步，每次换档都会移动平衡点，
移动又抬高活动度把档位翻回去，形成调度层的自激泵（游戏内表现为悬空绳规律性
弹跳）。稀疏求解因此执行 interval 个 h=1 子步；省的是每次求解的固定开销
（准备、风缓存、地形检测、调度），不是积分次数。回归护栏：
`RopeSchedulerEquilibriumTest`（平衡点跨节奏一致 + 档位翻转不抽水）。
`maxNodeMotionSqr()` 按跨度归一化，稀疏求解不会被误判为高速。

子步数量由端点速度和绳子内部最大速度决定，并受时间跨度下限约束：

- 低速：1 子步
- 中速：2 或 3 子步
- 高速：最多 `maxSubsteps`（默认 5）
- 跨度下限：至少 `ceil(tickSpan / MAX_SUBSTEP_TICK_SPAN)`

每个子步：

1. 保存 `xPrev/yPrev/zPrev`，用于子步末尾速度重建。
2. 对非 pinned 节点应用阻尼。
3. 叠加重力。
4. 叠加外力场采样结果。
5. 显式积分更新位置。
6. 固定两个端点。

子步末尾的速度重建对接触节点执行**接触速度投影**（`projectContactVelocity`）：
沿本子步累计的接触法线消除仍指向表面内部的法向速度（恢复系数 0），切向分量
按 `contactNodeDamping`（幂到子步长，作为滑动摩擦）保留。

推出与速度的关系按接触对象区分（`RopeSimulationCore` 三个校正原语）：

- **地形 / 绳绳**（`applyTerrainCorrection`）：静态或对称求解的对象，推出量
  同步移动 `xPrev`，不产生速度——绳子搁在方块上不再每子步弹跳。
- **实体推挤**（`applyEntityCorrection`）：实体是主动做功的移动物体，推出量
  **不**同步 `xPrev`，速度重建将其转化为甩开速度——被玩家/生物撞开的绳子
  会摆动而不是贴着碰撞箱静态滑动。接触法线仍然累计，摩擦和防重入照常生效
  （投影只消除指向内部的法向速度，不吃甩开分量）。
- **长度/张力**（`applyCorrection`）：允许产生速度，这是张力沿链传播的方式。

这替代了旧的"接触节点三分量全阻尼"，绳子贴表面时的切向摆动不再被杀死。

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

**距离约束是单边的（只拉不推）**：段长 ≤ 目标长时跳过。绳索物理上不承受压缩；
双边等式形式会把"被压短"的段往外推——贴地绳带着松弛盈余（scale 0.6 下约 5.4%
额外长度）时，盈余无法向下走，推挤只能横向屈曲，表现为躺平的绳自己扭成蛇形并
持续蠕动。单边化后盈余安静地躺着；空中悬垂不受影响（重力使悬链线的每一段
天然处于张力状态）。刚性 finalize 复用同一实现，同样只收缩不推开。

**LRA（Long Range Attachment）拉伸安全网 —— 仅限纯空中**：两端都 pinned 的
链上，节点 `i` 距锚点 A 不可能超过 `i * L_target`，距锚点 B 不可能超过
`(n-1-i) * L_target`。每轮迭代做一次 O(n) 的双向球面回拉
（`solveLongRangeAttachments`），限制全链拉伸；Gauss-Seidel 距离扫掠负责局部
形状。空中绳的迭代数因此直接使用配置值 `iterAir`（下限
`MIN_SOLVER_ITERATIONS = 2`），不再随段数增长。

LRA 的两个关键限定（否则绳子会"没有物理"）：

1. **按"长度完整性合法"启用，而不是按邻近**。判据 `lengthIntegrity`：
   无真实 drape、无实际实体接触、无外力场、无外部接触——**邻居堆叠允许**
   （包络只限制到锚距离，不会压平分层鼓包，堆叠正是靠它守住长度预算）。
   "真实 drape" 由 `interiorTerrainContact` 判定：上一子步地形求解器确实移动
   过**绳身节点**（排除两端各 `ANCHOR_FRINGE_NODES=2` 个锚缘节点——栅栏顶
   锚点位于栅栏 1.5 高碰撞柱内部，锚缘擦碰每子步必然发生；若按原始邻近判定，
   游戏里所有拴在方块上的绳都永远丧失长度完整性，实测十字堆叠因此拉伸 25%
   并永久哼鸣）。实体接触同理按"实际施加"判定（`entityContactApplied`，由
   `applyEntityCorrection` 置位）：驱动层的实体列表只是包围盒邻近候选（玩家
   站在 1 格内即非空），按候选关包络的症状是"人一靠近绳就松、一离开就紧"。
   真实 drape / 实际实体接触下路径合法地长于包络，钳制必须关闭；此时距离
   扫掠是唯一长度载体，迭代数按链长缩放但有界：
   `max(配置值, min(ceil(segments/2), CONTACT_LENGTH_PASSES_MAX = 16))`
   （旧实现无上界，最坏 `4×segments = 128`）。刚性 finalize 双扫掠（产生
   速度）比 LRA 更进一步，只允许在完全自由空中运行——对任何接触鼓包重复
   刚性压平都会形成永久振荡（游戏内堆叠 bench 抓到的 ±0.15 永动即此）。
2. **网面 = 垂度模型包络，硬贴（`LRA_RELAX = 1.0`），零余量**。每个节点的
   半径预算取理想 catenary 上对应点到两锚的欧氏距离——躺在网上就是躺在模型
   曲线里。三个被实测否决的替代设计，不要回退：链距预算（`i·L`）的贴网形状
   是尖底 V（腹深比 catenary 深 15%，即"中间不自然下垂"）；随速度/状态开合
   的余量是弛豫振荡器（垂入→减速→收窄→回拉→重开→再垂入，表现为持续弹跳
   呼吸）；欠松弛（0.5/0.8）等于放行拉伸，静止深度超模型 30-50%。双端固定链
   的均匀拉伸对 GS 距离扫掠不可见（切向修正沿链望远镜式抵消，四倍迭代仅改善
   6%），所以网定义的曲面就是静止形状——它必须是模型曲面。

子步尾的 taut projection（slack < 0.3 的数值张力混合）**保留**：它同时承担
张力接触的整跨 V 形响应（`RopeContactResponseModel` 的 tension 路径），
`preserveContactNodes` 保证不覆盖地形/绳绳接触节点。

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

1. 为当前绳子刷新每段当前几何 AABB。
2. 用整绳边界和分段 AABB 做 broad phase 剔除。帧级空间桶按每根
  绳子的实际接触 reach 与受限运动距离膨胀，而不是使用固定距离。
3. 对可能接触的两段调用 `RopeMath.closestSegmentPoints(...)`。
4. 对称计算两根绳的中心线接触距离：双方可见半粗细之和与双方物理半径
  之和先乘绳间几何校准系数 `0.80`，再与双方显式
  `ropeRepelDistance` 取最大值。该系数只校准绳-绳表面的视觉识别，地形
  和实体碰撞仍使用各自的完整物理半径；显式排斥距离也是不缩放的硬下限。
  调粗任意一根绳仍会扩大该 pair 的碰撞距离，且 A→B 与 B→A 使用相同目标。
5. 若当前位置发生穿透，沿最近点法线推出；完全重合时使用由两根绳 seed
  共同确定的稳定相反法线。
6. **质量分配 + 实时读取**：顺序驱动读邻居的**实时**位置——本绳已做过的
  修正不会被重复测量，迭代循环内的多趟求解单调收敛于测量穿透量、不会超出
  （这是允许它像其他单边约束一样留在循环内、保持接触强度的前提）。若邻居
  也在当前 tick 窗口内求解（`lastSteppedTick` 距今 ≤1 tick），本绳只承担
  `w_self / (w_self + w_other)` 份额，剩余由邻居自己的求解吸收；休眠/静态
  邻居视为无限质量，本绳全额推出。旧实现每根绳各自吸收 100%，双方合计分离
  2 倍距离——能量源，表现为堆叠永不静止。并行驱动下 worker 只能读 tick
  起点快照（Jacobi），只有这种耦合会重复计数，因此 `ROPE_ROPE_PARALLEL_RELAX`
  **仅**在并行阶段应用；顺序路径加欠松弛只会把接触削弱到看不见。
7. 接触校正走与地形推出相同的非穿透路径：同步移动 `xPrev/yPrev/zPrev` 并
  累计接触法线，穿透修正不会被速度重建读成弹跳速度；顺序不对称残留是纯位置
  性的，会衰减而不是振铃。
8. **稳定分层**：等高十字交叉的接触法线退化（距离≈0 → 种子随机方向）或近乎
  水平，而水平推挤会被绳自身长度约束抵消——测得的失败模式是两根绳互穿冻结
  （JUnit）或打破对称后永久横向抖动（游戏内）。物理上的正解是垂直分层：深接触
  按 `水平度 × 穿透深度比` 把法线混向 ±Y，方向取实测高度差符号，完全等高时用
  种子稳定轴决胜（两根绳必然选相反方向），重力随后稳定层序。实测十字堆叠
  4 tick 静止、分离恰为接触距离。

曾试验过"每子步一趟 × 快照基准 × 全局欠松弛 × 份额减半"的组合：四个因子
叠乘后接触响应只剩约一成，运动中的绳互相穿过、静止绳带着可见重叠入睡，
表现为"绳绳碰撞消失"。接触强度必须由结构（实时读取 + 份额分配）保证，
不能靠一味削弱求解来防过冲。

绳间约束当前只处理当前几何中实际发现的重叠，不使用跨整帧的旧侧投影。
这样可以避免一次离散穿层被错误地投影任意远距离，也避免多个线段、迭代
轮次和双向邻居求解重复注入无界位置修正。高速运动下仍可能存在离散穿层，
后续若需要 CCD，应采用子步级、单次消费且具备 correction budget 的接触
manifold，而不是复用帧级历史快照。

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

服务端也不计算视觉风。`ServerRopeCurve` 是确定性的粗曲线缓存，用于校验、滑索、接触 fallback 和鹦鹉栖息采样；它不得随 game tick 或风场变化而变化。这样可以避免大量绳索时每 tick 失效曲线缓存，并把风的全部成本留在客户端视觉路径中。

### 当前动态绳优化目标

动态绳的性能目标不是让所有绳都永久完整模拟，而是按可见性、距离和交互状态分级：

- 近距离、玩家接触、滑索、高亮和有强视觉变化的绳：允许完整客户端物理和动态几何。
- 中距离动态绳：优先降低物理 step 频率、减少物理节点或复用上一帧 mesh。
- 远距离绳：优先使用 visual sag、ribbon LOD 或静态 chunk mesh，不运行完整 XPBD。
- 稳定绳：在端点不变、速度低、无接触、无有效风时进入休眠；唤醒条件包括端点变化、玩家靠近/接触、服务端接触脉冲、风重新有效或调参变化。
- 极端场景：应通过每帧 physics/mesh budget 控制最坏成本，未获得预算的绳保持上一帧可接受状态，而不是阻塞整帧。

**统一静止判据**：`RopeSimulation.isVisuallyAtRest()` 是"这根绳视觉上静止"的
唯一权威。物理休眠、chunk mesh 进出（`StaticRopeChunkRegistry.isMeshEligible`）、
邻居唤醒（`isDisturbingNeighbors`）都读取该状态，不再各自维护阈值——旧实现中
求解器（5.0e-4）、mesh 进入（4.0e-5）、mesh 退出（5.0e-4）、挂件摆动（2.25e-6）
四套阈值互相矛盾，绳子可以同时"静到可以休眠"又"动到必须拆 mesh"，在网格内外
反复横跳。状态机为双阈值迟滞：进入静止需要 `settleThresholdTicks` 次连续低于
`settleMotionSqr` 的求解；退出需要超过 `settleMotionSqr × restExitRatio`（默认
8 倍）的真实扰动。运动量按求解覆盖的模拟时间跨度归一化，稀疏 LOD 求解不会被
误判为高能量。mesh 注册表的高 LOD 进入 debounce 与硬退出阈值机制随之删除。

**挂件光照反馈环切断**：动态光源的 chunk 重烘焙脏标记量化到"光源方块变化或
发光等级变化"（`RopeDynamicLights.apply`）；亚方块的摆动位移只更新连续采样
位置（逐帧 boost 仍然平滑），不再触发半径 8 格的 `requestLightRebuildNear`。
旧的 0.25 格移动触发让"挂件摆动 → 光源微移 → 邻居绳重烘焙 → 网格拆装 →
绳形微扰 → 挂件摆动"闭合成正反馈，这是有挂件的绳更频繁进出 mesh 的直接原因。
同理 `invalidateNearBlock` 的方块变化影响半径从 2.0 收紧到 1.05（方块自身 +
直接邻接连接形变的碰撞可达范围），普通建造不再把 5×5×5 范围内的网格全部拆掉。

这些目标只作用于客户端视觉物理。服务端继续只做接触报告裁决、资源/红石/滑索等玩法权威逻辑。

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

preset 中仍可能包含风相关键，但这些键只影响客户端视觉风。服务端解析层可以保留字段兼容旧预设，但服务端接触校验和 `ServerRopeCurve` 不应使用这些字段生成随时间变化的绳形。

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
     -> 绳绳对称接触（每子步一趟，基于邻居快照）
     -> 统一迭代:
        -> XPBD 距离约束（接触场景迭代数按链长缩放，有界）
        -> LRA 拉伸安全网（仅纯空中，带瞬态余量）
        -> 实体 AABB 推绳（速度注入）
        -> 地形约束（最终话语权，无速度注入）
     -> 速度重建 + 接触速度投影
     -> taut projection（slack < 0.3，含张力接触 V 形）
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
- 绳绳排斥只写当前绳子，从不直接移动邻居；份额分配通过双方各自的求解配合实现。
  顺序驱动必须读邻居实时位置（快照基准会让循环内的重复求解过冲）；并行驱动
  必须读快照且必须欠松弛。两条规则不可交换。
- LRA 与绳绳/地形/实体推出同属单边约束，修正必须走接触路径（同步 `xPrev`），
  走张力路径（`applyCorrection`）会把回拉变成速度、在约束边界形成蹦床。
- 地形碰撞同时包含节点、线段胶囊和扫掠三种路径，删除任意一层都可能重新引入穿模或边角抖动。
- 静态几何的穿透推出必须走 `applyTerrainCorrection`（同步 `xPrev` + 累计接触
  法线），否则速度重建把推出量变成弹跳速度；实体推挤必须走
  `applyEntityCorrection`（不同步 `xPrev`），否则绳子失去被撞开的甩动反馈。
- LRA 只允许在自由空中运行；任何接触场景下把节点往锚点球面拽都会把绳子
  从方块/实体/邻居上拉开，表现为"绳子没有物理"。
- 静止判断只能读 `isVisuallyAtRest()` / `isDisturbingNeighbors()`，不要在调用方
  重新发明运动阈值。
- 服务端当前以客户端接触报告为主要输入，但仍保留合理性校验和 fallback 估计，不能直接信任客户端坐标。

## 编译验证

最近一次验证命令：

```powershell
.\gradlew.bat compileJava compileTestJava test --console=plain
```

结果：编译通过，275 个测试全部通过（含新增 `RopeRestStateTest` 与绳绳对称分配测试）。
