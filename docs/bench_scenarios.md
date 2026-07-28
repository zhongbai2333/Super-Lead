# SuperLead Bench 场景路线图

三层护栏，由内向外：

1. **JUnit 测功台**（`src/test` 下的 `Rope*Test`）：纯求解器与调度 helper，
  无世界数据，秒级反馈。已覆盖拓扑、距离约束、绳绳接触、地形/实体几何、
  邻居图和活动调度。
2. **ModBench 客户端场景**（`runBenchClient`，完整驱动栈：调度器 / 异步 /
  mesh / 渲染）：已上线空吊静止、长跨度、全绳种矩阵、多挂件、物品工作、
  十字堆叠、堆叠创建顺序、玩家碰撞、松紧调整和三层堆叠场景。
  接触场景会导出逐 tick CSV，视觉场景会保留截图。
3. **ModBench 服务端场景**（`runBenchServer`）：`super_lead.server-load` smoke。

## 已规划场景（优先级降序）

| 场景 | 守护对象 | 关键断言 | 备注 |
|---|---|---|---|
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
