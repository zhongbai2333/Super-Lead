# Super Lead 架构说明

本文档为AI整理，用于确认整体流程是否符合预期，宏观确认模组所有功能无逻辑冲突

相关文档：

- `docs/design.md`：早期功能设计和方向。
- `docs/algorithm_split.md`：物理算法拆分说明。
- `docs/physics_algorithm_technical.md`：绳索物理算法细节。

## 1. 模块地图

主包是 `com.zhongbai233.super_lead`。目前项目可以按下面几块理解：

| 模块 | 主要路径 | 职责 |
| --- | --- | --- |
| Mod 入口与配置 | `Super_lead.java`, `Config.java` | NeoForge 入口、物品/网络/配置注册，公共配置缓存 |
| 牵绳核心 | `lead/` | 连接数据模型、存档、交互事件、网络同步、服务端传输逻辑 |
| 客户端绳索 | `lead/client/` | 渲染提交、客户端物理、LOD、静态区块网格、调试覆盖层 |
| 物理模拟 | `lead/client/sim/` | RopeSimulation 分层实现、XPBD/碰撞/缓存/调参快照 |
| 渲染 | `lead/client/render/`, `lead/client/chunk/` | 动态几何、可见性剔除、静态绳区块烘焙 |
| 调参 | `tuning/` | 本地客户端调参键、GUI、客户端命令、配置持久化 |
| 预设与区域 | `preset/` | OP 预设包、物理区域、服务器推送/同步、区域选择 |
| 服务端配置 GUI | `serverconfig/`, `preset/client/ServerConfigScreen.java` | OP 在线修改 common 配置并同步快照 |
| Mixin | `mixin/SignalGetterMixin.java` | 把红石牵绳信号接入原版 `SignalGetter#getSignal` |
| 资源 | `src/main/resources/` | 语言、配方、物品模型、Mixin 配置 |

## 2. Mod 启动与注册流程

入口类是 `Super_lead`：

1. NeoForge 通过 `@Mod(Super_lead.MODID)` 加载，`MODID = "super_lead"`。
2. 构造函数注册物品：`SuperLeadItems.register(modEventBus)`。
3. 注册网络 payload：`modEventBus.addListener(SuperLeadPayloads::register)`。
4. 注册 common 配置加载/重载监听：`Config::onLoad`、`Config::onReload`。
5. 客户端环境下注册调试覆盖层：`RopeDebugOverlay.register(modEventBus)`。
6. 注册 common 配置规格：`modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC)`。

大量功能通过 `@EventBusSubscriber(modid = Super_lead.MODID)` 自动挂到 NeoForge 事件总线。例如：

- `lead/SuperLeadEvents.java`：玩家交互、世界 tick、登录/换维度同步。
- `preset/PresetServerEvents.java`：预设区域登录/退出/换维度和 server tick。
- `tuning/ClientTuning.java`：客户端命令注册。
- `tuning/gui/SuperLeadKeybindings.java`：客户端按键注册。
- `tuning/cmd/*`, `preset/cmd/*`, `lead/debug/*`：服务端/客户端命令。

资源侧 `super_lead.mixins.json` 注册 `SignalGetterMixin`，当前只有红石信号注入。

## 3. 核心数据模型

### 3.1 LeadAnchor

`LeadAnchor` 是一个连接端点：

- `BlockPos pos`：挂点方块位置。
- `Direction face`：挂在哪个面。

`attachmentPoint(Level)` 根据方块形状计算世界坐标：

- 栅栏固定为方块中心上方 `0.5, 0.75, 0.5`。
- 其他方块取碰撞/轮廓 shape 的包围盒中心，再沿选中面向外挤出一点。

### 3.2 LeadConnection

`LeadConnection` 是一根绳子的服务端权威数据：

- `UUID id`：连接唯一 ID。
- `LeadAnchor from`, `LeadAnchor to`：两端挂点。
- `LeadKind kind`：功能类型。
- `int power`：红石/能量相关强度，范围钳制到 `0..15`。
- `int tier`：能量/物品/流体升级档位，最低为 `0`。
- `int extractAnchor`：物品/流体抽取端，`0` 关闭，`1` from -> to，`2` to -> from。
- `List<RopeAttachment> attachments`：绳上装饰/挂件。

`LeadKind` 当前有：

- `NORMAL`
- `REDSTONE`
- `ENERGY`
- `ITEM`
- `FLUID`

`LeadConnection` 是 immutable record，修改都通过 `withKind`、`withPower`、`withTier`、`withExtractAnchor`、`withAttachments`、`addAttachment`、`removeAttachment` 等方法返回新对象。

### 3.3 RopeAttachment

`RopeAttachment` 表示绳上的挂件：

- `UUID id`
- `double t`：沿绳 0..1 的归一化位置，构造时钳制到 `0.02..0.98`。
- `ItemStack stack`：只保存 1 个物品。
- `boolean displayAsBlock`：方块物品是否按方块形态显示。

挂件会随 `LeadConnection` 一起存档和网络同步。

## 4. 存档结构

### 4.1 绳索连接存档

`SuperLeadSavedData` 是每个维度一份的 `SavedData`：

- ID：`super_lead:lead_connections`
- 字段：`chunks`
- 获取方式：`SuperLeadSavedData.get(ServerLevel level)`
- 底层：`level.getDataStorage().computeIfAbsent(TYPE)`

当前存档已经改成 chunk bucket 结构：

- 每个 bucket 对应一个 `ChunkPos` 打包后的 long key。
- `owned` 保存 owner chunk 内完整的 `LeadConnection` 详情。
- `refs` 保存穿过该 chunk、但详情属于其他 owner chunk 的 rope UUID。
- 服务端运行时会从 buckets 重建 `UUID -> StoredRope` 总索引。

这意味着：

- 绳索连接仍然属于维度，但不再用单个线性列表作为存档形态。
- 一根跨区块绳只在 owner chunk 保存一份详情，其他 covered chunks 只保存引用。
- 客户端只会收到自己正在 watch 的 chunk 相关绳索，不再登录时拿到整个维度的绳索。
- 业务代码仍可通过 `connections()`、`find()`、`update()`、`removeIf()` 使用服务端权威数据。

新增、删除、更新后，`SuperLeadSavedData` 会记录受影响的 dirty chunk keys；`SuperLeadPayloads.sendToDimension` 只重发这些 chunk 给正在监听它们的玩家。

### 4.2 物理区域存档

`PhysicsZoneSavedData` 同样是每维度一份：

- ID：`super_lead:physics_zones`
- 字段：`zones`
- 内容：`PhysicsZone(name, presetName, AABB area)`

区域按 `LinkedHashMap` 保存，查找时 `findContaining` 返回第一个包含玩家坐标的区域。

### 4.3 预设包文件

`RopePresetLibrary` 是 JSON 文件库：

- 目录：`<server directory>/config/super_lead/presets/`
- 文件：`<presetName>.json`
- 名称规则：`^[A-Za-z0-9_\-]{1,32}$`
- 内容：预设名和 `TuningKey id -> string value` 的覆盖表。

预设包不是维度存档的一部分；维度存档只保存区域引用了哪个预设包。

### 4.4 客户端本地调参文件

`TuningStore` 保存玩家本地视觉/物理调参：

- 文件：`<game directory>/config/super_lead-tuning.properties`
- 只保存非默认的本地覆盖值。

这个文件是客户端本地配置，不会自动写到服务器预设包。

## 5. 玩家进入存档/维度时的加载顺序

这里分服务端和客户端两条线看。

### 5.1 服务端登录流程

玩家进入存档后，两个事件类都会响应登录：

1. `SuperLeadEvents.onPlayerLoggedIn`
   - 只处理 `ServerPlayer`。
   - 调用 `SuperLeadPayloads.sendToPlayer(player)`。
   - 这里的 `sendToPlayer` 只发送 `ClearRopeCache` 来清空客户端旧维度缓存，不发送任何绳索详情。

2. `PresetServerEvents.onLogin`
   - 调用 `PresetServerManager.onLogin(player)`。
   - 刷新该维度中已存绳索的 `physicsPreset` 字段。
   - 发送当前维度启用的预设包表：`SyncDimensionPresets`。
   - 如果玩家是 OP，再额外发送区域预览表：`SyncPhysicsZones`。

随后区块同步里：

3. `SuperLeadEvents.onChunkSent`
   - 监听 `ChunkWatchEvent.Sent`。
   - 该事件表示原版 chunk 已经发给客户端，可以安全补发自定义 chunk 数据。
   - 调用 `SuperLeadPayloads.sendChunkToPlayer(level, player, chunk)`。
   - 服务端只读取该 chunk bucket 中的 `owned + refs` 对应的绳索详情，发送 `SyncRopeChunk`。

玩家停止监听区块时：

4. `SuperLeadEvents.onChunkUnwatch`
   - 监听 `ChunkWatchEvent.UnWatch`。
   - 发送 `UnloadRopeChunk`。
   - 客户端减少这个 chunk 对 rope UUID 的引用计数；引用归零才移除对应绳子。

### 5.2 客户端接收连接流程

客户端收到 chunk 绳索表：

1. `SuperLeadPayloads.handleSyncRopeChunk`
2. `SuperLeadNetwork.replaceChunkConnections(level, payload.chunk(), payload.connections())`
3. `StaticRopeChunkRegistry.get().onConnectionsReplaced(level, SuperLeadNetwork.connections(level))`

效果：

- 客户端只替换这个 chunk 的绳索引用。
- 跨区块绳通过 UUID 引用计数保留，直到所有相关 chunk 都卸载。
- 静态绳区块网格注册器按当前已加载连接重建索引。
- 下一次 `SubmitCustomGeometryEvent` 时，动态绳 simulation 和/或静态 chunk mesh 会按距离和调参被创建。

客户端收到 chunk unload：

1. `SuperLeadPayloads.handleUnloadRopeChunk`
2. `SuperLeadNetwork.unloadChunkConnections(level, payload.chunk())`
3. 引用计数归零的绳子会从客户端连接缓存移除。

旧的全维度 `SyncConnections` / `SyncConnectionChanges` 已删除；当前正常 chunk 同步使用 `ClearRopeCache`、`SyncRopeChunk` 和 `UnloadRopeChunk`。

### 5.3 换维度流程

玩家换维度时：

1. `SuperLeadEvents.onPlayerChangedDimension`
   - 调用 `SuperLeadPayloads.sendToPlayer(player)` 清空客户端旧 chunk rope 缓存。
   - 新维度的绳索详情等待 `ChunkWatchEvent.Sent` 按 chunk 发送。

2. `PresetServerEvents.onChangedDimension`
   - 清除区域选择状态：`PhysicsZoneSelectionManager.clearAndSync(player)`。
   - 刷新新维度中已存绳索的 `physicsPreset` 字段。
   - 发送新维度 `SyncDimensionPresets`。
   - 如果玩家是 OP，再额外发送新维度 `SyncPhysicsZones` 供区域预览。

因为连接和物理区域都是每维度数据，换维度时要清空旧缓存，并由新区块 watch 流程重新填充绳索。

## 6. 创建/修改绳索的流程

### 6.1 新建连接

主入口在 `SuperLeadEvents.handleLeadUse`：

1. 玩家右键可挂载方块/栅栏结点。
2. 第一次点击：
   - 从手中物品读取 `LeadKind`。
   - `SuperLeadNetwork.setPendingAnchor(player, anchor, kind)`。
3. 第二次点击：
   - 读取 pending anchor。
   - 检查是否同一个点、距离是否超过 `SuperLeadNetwork.MAX_LEASH_DISTANCE`。
   - 服务端调用 `SuperLeadNetwork.connect(level, first, anchor, kind)`。
   - 清除 pending。
   - 非创造模式消耗一个牵绳物品。

`SuperLeadNetwork.connect`：

- 服务端：
  - 创建 `LeadConnection`。
  - 写入 `SuperLeadSavedData`。
  - 调用 `SuperLeadPayloads.sendToDimension(serverLevel)`。
- 客户端：
  - 只更新本地缓存，通常用于预测/预览，不是权威存档。

### 6.2 升级、剪断和切换抽取端

玩家交互主要在 `SuperLeadEvents.onRightClickBlock`：

- Shift + 剪刀：剪断绳或进入物理区域选择流程。
- Shift + 对连接使用升级材料：通过 `LeadConnectionAction` 尝试升级类型/档位。
- Shift + 漏斗：切换物品绳抽取端。
- Shift + 炼药锅：切换流体绳抽取端。
- 空手/其他物品：可能添加/移除挂件。

客户端选中绳后发送：

- `UseConnectionAction(connectionId, actionOrdinal, useOffhand)`
- `AddRopeAttachment(connectionId, t, stack)`
- `RemoveRopeAttachment(connectionId, attachmentId)`
- `ToggleRopeAttachmentForm(connectionId, attachmentId)`

服务端处理时会重新校验：

- 连接是否存在。
- 玩家是否真的能触达这根绳。
- 客户端选中的连接是否和服务端近似 sag 曲线匹配。
- 使用的物品/动作是否合法。

通过校验后才修改 `SuperLeadSavedData`，再向维度广播同步。

## 7. 绳索数据同步

网络注册集中在 `SuperLeadPayloads.register`，协议版本字符串为 `"1"`。

### 7.1 连接同步

当前正常同步已经改为 chunk-scoped：

- `SyncRopeChunk`：同步一个 chunk 当前应让客户端知道的绳索详情。
- `UnloadRopeChunk`：通知客户端停止引用一个 chunk 的绳索。
- `ClearRopeCache`：登录和换维度时清掉客户端旧缓存。
- `SyncDimensionPresets`：同步当前维度启用的预设包内容，不包含物理区域坐标。

`SuperLeadPayloads.sendToDimension(ServerLevel)` 的语义也变了：

1. 从 `SuperLeadSavedData.consumeDirtyChunkKeys()` 取出受影响 chunk。
2. 如果没有 dirty chunk，则退回重发当前所有有绳索 bucket 的 chunk。
3. 对每个 chunk 调 `PacketDistributor.sendToPlayersTrackingChunk`。
4. 每个玩家只收到自己正在 watch 的 chunk 的 `SyncRopeChunk`。

客户端处理结果只写本地 chunk 引用缓存：

- `SuperLeadNetwork.replaceChunkConnections`
- `SuperLeadNetwork.unloadChunkConnections`

客户端内部维护：

- `chunkKey -> rope UUID set`
- `rope UUID -> LeadConnection`
- `rope UUID -> ref count`

跨区块绳可能被多个 chunk 同时引用，只有 ref count 归零才会从客户端消失。

### 7.2 物品/流体/接触脉冲

这些不是连接权威数据，而是表现或物理反馈：

- `ItemPulse`：客户端 `ItemFlowAnimator.queue`，用于显示物品沿绳流动。
- `RopeContactPulse`：客户端 `RopeContactsClient.apply`，用于把服务端接触/反推状态喂给本地 rope sim。
- `ClientRopeContactReport`：客户端检测玩家触绳后上报服务端，服务端 `RopeContactTracker` 决定推力和广播。

### 7.3 接触脉冲现状

`RopeNodesPulse` / 服务端 Verlet 绳形同步已经移除。当前真实架构是：

- 绳子形状由客户端本地 `RopeSimulation` 决定。
- 玩家触绳碰撞由客户端用实际渲染/物理绳折线检测，并通过 `ClientRopeContactReport` 上报。
- 服务端只校验上报是否落在锚点生成的合理包络内，并按限制后的法线/深度处理玩家速度。
- `SuperLeadEvents.onLevelTick` 每个服务端 tick 调用 `RopeContactTracker.tickRopeContacts`，把已接受接触广播给本维度客户端。
- 反推速度每个 server tick、每根绳、每个玩家最多应用一次；同 tick 的重复上报只刷新视觉接触。
- 速度修正是软约束：浅接触不处理，压力由接触深度和绳子相对静止形状的偏移量共同决定。
- 推力行程会按锚点距离缩放：长绳有更长的受力行程和更低的阻力，短绳更硬，避免所有长度的绳子都像同一堵墙。
- 玩家继续向绳内移动时逐渐削掉内向速度；玩家沿法线反向退出时会读取服务端收到的移动输入，按“输入方向和绳子外法线的点积”线性补回 `Attributes.MOVEMENT_SPEED` 对应速度，再叠加额外退出助推。因此朝绳子发力不补速，侧向部分补速，完全背离绳子发力时完整补速，速度药水/装备属性也不会被吃掉。
- 服务端接受后广播 `RopeContactPulse`；LOD 无物理绳和静态区块绳收到接触脉冲后会退出静态网格并叠加外部接触偏移。
- `RopeContactPulse` 还携带服务端实际施加到本地玩家的水平 `dV`，F3 覆盖层显示 `push dV`、方向和接触数。

## 8. 服务端 tick 系统

`SuperLeadEvents.onLevelTick(LevelTickEvent.Post)` 是服务端绳索逻辑总调度。

每 tick：

- 服务端：
  - `tickStuckBreaks`
  - `tickRedstone`
  - `tickEnergy`
  - `tickItem`
  - `tickFluid`
  - `RopeContactTracker.tickRopeContacts`

每 20 tick：

- 服务端调用一次 `pruneInvalid(serverLevel)`，清理端点无效或方块状态不再可挂的连接。

预设系统另有 `PresetServerEvents.onServerTick`：

- 每 server tick 调 `PresetServerManager.tickPlayerZones(server)`。
- 当前是保留入口；普通客户端不再按玩家位置接收区域预设，绳索预设在创建/区域变化/预设变化时刷新。

### 8.1 红石绳

`tickRedstone` 更新连接 power。`SignalGetterMixin` 在原版 `SignalGetter#getSignal` 返回后注入：

```text
原版信号 = getSignal(...)
绳索信号 = SuperLeadNetwork.leadSignal(getter, pos, direction)
最终信号 = max(原版信号, 绳索信号)
```

所以红石输出不是方块实体，而是通过 mixin 查询附近/相关连接实现。

### 8.2 能量/物品/流体绳

`SuperLeadNetwork` 内部实现资源网络和路径遍历：

- 能量：扫描能量端点，按 tier 和配置传输。
- 物品/流体：从 `extractAnchor` 指定源端抽取，沿连接图寻找可插入目标。
- 物品和流体传输会用 round-robin 游标避免永远偏向同一路径。

配置值来自 `Config` 的 volatile 缓存，例如最大距离、物品/流体最大 tier、传输间隔、桶容量等。

## 9. 客户端渲染与物理生命周期

主循环在 `SuperLeadClientEvents.onSubmitCustomGeometry(SubmitCustomGeometryEvent)`。

一次渲染提交大致流程：

1. 没有 level 时清空客户端状态：
   - `SIMS`
   - 预览/hover
   - 物品流动动画
2. 调用 `SuperLeadNetwork.pruneInvalid(level)`；当前客户端分支不删除连接，只保留入口，避免跨 chunk 绳因端点 chunk 暂未加载而被误清理。
3. 开始本帧可见性序列：`RopeVisibility.beginFrame(cameraPos)`。
4. 读取客户端连接缓存：`SuperLeadNetwork.connections(level)`。
5. 维护静态区块绳：`StaticRopeChunkRegistry.tickMaintain(...)`。
6. 遍历每根 `LeadConnection`：
   - 计算两端 `attachmentPoint`。
   - 计算 rope 到相机最近距离。
   - 超过 `MAX_RENDER_DISTANCE = 96` 直接跳过。
   - 超过 `PHYSICS_LOD_DISTANCE = 48` 且被静态网格 claim 的绳，动态渲染跳过。
   - 解析本绳调参：`RopeTuning.forConnection(connection)`。
   - 获取或创建 `RopeSimulation`。
   - 计算 bounds 和可见性。
   - 生成 `RenderEntry`。
7. 每 tick 对可见/活动 simulation：
   - `applyServerState` 应用接触脉冲缓存。
   - 不启用物理或超出物理 LOD 时：`updateVisualLeash`，只保持视觉 sag。
   - 启用物理且在 LOD 内时：收集邻近绳/实体，执行 `sim.step(...)`。
   - 上报玩家触绳：`maybeReportPlayerContact(...)`。
8. 收集 `RopeJob`。
9. 渲染挂件、粒子、预览、hover。
10. `LeashBuilder.flush(...)` 批量提交动态几何。
11. 删除本帧不再 active 的 simulation。

注意：`ClientTuning.RENDER_MAX_DISTANCE` 存在调参键，但当前主渲染驱动里实际使用的是 `SuperLeadClientEvents` 的静态常量 `MAX_RENDER_DISTANCE = 96`。如果要让 GUI 的最大渲染距离真正生效，需要改这里的读取逻辑。

## 10. RopeSimulation 分层

`RopeSimulation` 使用继承分层拆开职责：

```text
RopeSimulation
  -> RopeSimulationStepper
    -> RopeSimulationContactConstraints
      -> RopeSimulationTerrainConstraints
        -> RopeSimulationVisualState
          -> RopeSimulationRenderCache
            -> RopeSimulationCore
```

职责粗分：

- `RopeSimulationCore`：节点数组、约束数组、调参快照、缓存脏标记、基础工具。
- `RopeSimulationRenderCache`：`prepareRender`、烘焙顶点缓存、段可见性 mask。
- `RopeSimulationVisualState`：静态 sag、非物理 LOD 视觉更新、服务端/接触外部状态混合。
- `RopeSimulationTerrainConstraints`：地形碰撞、锚点附近修正。
- `RopeSimulationContactConstraints`：绳-绳接触约束。
- `RopeSimulationStepper`：XPBD 风格积分、substep、约束迭代、实体/力场/接触处理。

创建 simulation 时，段数由 `RopeSimulationCore.segmentCount(a, b, RopeTuning)` 决定，调参来自 `LeadConnection.physicsPreset` 对应的维度预设包或本地默认。

## 11. LOD、遮挡和静态区块网格

### 11.1 动态 LOD

当前硬编码距离：

- 动态最大渲染距离：`96`。
- 物理 LOD 距离：`48`。

在物理 LOD 外：

- simulation 不再执行完整物理 step。
- 调用 `updateVisualLeash` 保持视觉曲线。
- 如果被静态网格接管，动态路径不渲染。

`LeashBuilder` 内部还有几何 LOD：

- 近处可用 3D prism。
- 远处切 ribbon。
- 根据 `lod.stride2Distance` / `lod.stride4Distance` 合并渲染段。

### 11.2 可见性剔除

`RopeVisibility` 当前策略：

- 先做整根绳 bounds 的 frustum 检查。
- 近距离（小于 `48`）只做 per-segment frustum mask，不做 CPU 方块射线遮挡。这样能避免半遮挡时整段绳直接消失。
- 远距离使用少量采样点做方块遮挡射线，结果会按距离缓存几帧。
- 方块遮挡只把 `canOcclude && isSolidRender` 的方块当遮挡物，玻璃/树叶/半透明类不会遮挡绳子可见性。

如果以后又出现“半根绳被墙挡一下就全没”的问题，优先看 `RopeVisibility.shouldRender`。

### 11.3 静态区块网格

静态绳由 `lead/client/chunk/` 负责：

- `StaticRopeChunkRegistry`：决定哪些连接被静态区块 mesh 接管，维护 section -> snapshot 索引。
- `RopeStaticGeometry`：从 simulation 或 catenary 生成静态几何快照。
- `RopeSectionMeshDriver`：在 `AddSectionGeometryEvent` 给区块 section 注入绳子几何。
- `EmptySectionRescuer`：让原本空 section 在有静态绳后能重新触发几何发布。
- `StaticRopeChunkLifecycle`：客户端退出/卸载/调参变化时清理或重建静态缓存。

`StaticRopeChunkRegistry.tickMaintain` 的核心规则：

- 近相机的绳保持 dynamic。
- 有 simulation 且足够静止后，远处可以被 static claim。
- 没有 simulation 的远处绳，也可以直接按 catenary 生成静态 geometry。
- 连接表变化、调参变化、chunk 加载状态变化都会触发 dirty sections。

这块和“远处下线、上线后绳不显示/不重新物理化”的问题高度相关。排查时重点看：

- `onChunkSent` 是否对玩家所在 chunk 发送了 `SyncRopeChunk`。
- 客户端 `replaceChunkConnections` 后当前 chunk 是否持有 rope UUID 引用。
- `tickMaintain` 是否把靠近后的连接从 static claim 释放。
- `markSectionsDirty` 和 `EmptySectionRescuer.flushPendingDirtySections` 是否让区块 mesh 重建。
- 动态 `SIMS` 中是否重新创建了对应 `RopeSimulation`。

## 12. 调参与预设系统

### 12.1 TuningKey 和 ClientTuning

所有客户端调参注册在 `ClientTuning`：

- `physics.shape`：`slack.loose`, `slack.tight`, `segment.length`, `segment.max`
- `physics.solver`：`gravity`, `damping`, `iterations.air`, `iterations.contact`, `iterations.rope`, `compliance`
- `physics.contact`：玩家反推相关参数
- `render.mode`：`mode.physics`, `mode.render3d`, `mode.chunkMeshStaticRopes`
- `render.geom`：绳粗细、ribbon 宽度
- `render.lod`：ribbon/stride LOD 距离
- `render.attach`：挂件缩放/下垂
- `misc`：拾取半径、最大渲染距离

`TuningKey` 有三层值：

1. `defaultValue`
2. `localValue`：玩家本地 GUI/命令修改，保存到 `super_lead-tuning.properties`
3. `presetValue`：服务器临时推送的预设覆盖；当前区域同步不再按玩家位置使用它

有效值计算：

```text
effective = presetValue != null ? presetValue : (localValue != null ? localValue : defaultValue)
```

`ClientTuning.fire` 会根据 key 分组增加：

- `physicsEpoch`
- `renderEpoch`

静态网格和 UI 可以用 epoch 判断是否要重建。

### 12.2 每根绳真正使用的物理参数

渲染/物理里不要直接用 `TuningKey.get()` 当作绳参数。当前正确路径是：

```text
RopeTuning.forConnection(connection)
  -> PhysicsZonesClient.overridesForPreset(connection.physicsPreset)
  -> dimension preset overrides 或空表
  -> 缺失项回退 key.getLocalOrDefault()
```

关键点：

- 普通客户端不再接收区域坐标，也不会按玩家当前位置套用区域预设。
- 每根绳自己的物理参数由服务端写入的 `LeadConnection.physicsPreset` 决定。
- 如果绳不在任何物理区域，`physicsPreset` 为空，客户端使用玩家本地值或默认值。
- 这避免了玩家站在 A 区域时，把 A 区域预设误套到远处 B 区域或无区域绳上。

### 12.3 物理区域和预设同步

服务端 `PresetServerManager` 维护区域表，但不把区域表广播给普通客户端：

- `PhysicsZoneSavedData`：每维度 OP 区域表，包含 `name -> presetName -> AABB`。
- `LeadConnection.physicsPreset`：每根绳当前绑定的预设名。
- `SyncDimensionPresets`：当前维度启用的预设包内容，只有 preset 名和覆盖参数，没有区域坐标。
- `SyncPhysicsZones`：OP-only 区域预览表，只响应 OP 的区域列表/预览请求。

创建绳索或区域改变时：

1. 服务端用绳索两端锚点求 midpoint，先查包含 midpoint 的区域。
2. 如果 midpoint 未命中，再用端点线段和区域 AABB 做相交判断。
3. 命中区域后把区域的 `presetName` 写入 `LeadConnection.physicsPreset`。
4. 保存/同步这根绳所在 chunk 的 `SyncRopeChunk`。
5. 发送/刷新 `SyncDimensionPresets`，保证客户端有该 preset 的覆盖参数。

客户端渲染绳索时：

1. 从 `SyncRopeChunk` 读取 `LeadConnection.physicsPreset`。
2. 用 `PhysicsZonesClient.overridesForPreset(physicsPreset)` 查当前维度 preset 覆盖表。
3. `RopeTuning.forConnection(connection)` 合成该绳的实际物理参数。

因此普通玩家只能知道自己已加载 chunk 内的绳子使用了哪个 preset，不能从网络包反推完整区域边界。

### 12.4 预设编辑后的刷新

OP 编辑预设后走 `PresetServerManager.editKey`：

1. 保存 JSON。
2. `refreshPresetUsage(server, presetName)`。
3. 遍历已加载维度，重新计算绳索 `physicsPreset`。
4. 如果绳索字段变化，重发 dirty chunk 的 `SyncRopeChunk`。
5. 重发 `SyncDimensionPresets` 给该维度玩家。
6. 只给 OP 刷新 `SyncPhysicsZones` 区域预览。

所以预设修改要同时影响：

- 维度启用预设包缓存。
- 每根绳的 `physicsPreset` 字段和客户端 `RopeTuning.forConnection`。

如果只看到 GUI 变了但绳没变，重点查 `SyncDimensionPresets`、`LeadConnection.physicsPreset` 和 `RopeTuning.forConnection`。

## 13. GUI 和命令入口

### 13.1 客户端本地调参

入口：

- 按键：`SuperLeadKeybindings.OPEN_CONFIG`
- 命令：`/superlead gui`
- 界面：`SuperLeadConfigScreen`
- 预览：`PreviewRope`

命令还支持：

- `/superlead config list`
- `/superlead config get <key>`
- `/superlead config set <key> <value>`
- `/superlead config reset ...`
- `/superlead status`

这些只改客户端本地 `ClientTuning`。

### 13.2 服务端配置界面

入口：

- `SuperLeadConfigScreen` 里的 “Server Config” 按钮
- 界面：`preset/client/ServerConfigScreen`

该界面有三个 tab：

- server：在线修改 `Config` 里的 common 配置。
- presets：管理预设包。
- zones：查看/删除/预览物理区域。

网络：

- 请求快照：`ServerQuery(SERVER_CONFIG)`
- 设置值：`ServerConfigSet`
- 返回快照：`ServerConfigSnapshot`

服务端只允许 OP：`ServerConfigManager.isOp` 使用 `PermissionLevel.GAMEMASTERS`。

### 13.3 预设包命令/GUI

服务端命令：

- `/superlead preset list`
- `/superlead preset show <name>`
- `/superlead preset save <name> from-keys ...`
- `/superlead preset delete <name>`
- `/superlead preset edit <name> <key> <value|reset>`

GUI：

- `PresetEditScreen`：编辑单个预设包的 key/value，带预览和输入控件。
- `ServerConfigScreen` 的 presets tab：列表、新建、删除、打开编辑。

网络：

- `ServerQuery(PRESET_LIST)` / `PresetListResponse`
- `PresetDetailsRequest` / `PresetDetailsResponse`
- `PresetEditKey`

### 13.4 物理区域命令/GUI

命令：

- `/superlead zone list`
- `/superlead zone select`
- `/superlead zone cancel`
- `/superlead zone add <name> <preset>`
- `/superlead zone remove <name>`

GUI/交互：

- `ServerConfigScreen` zones tab 可刷新、预览、删除区域。
- `ZoneCreateScreen` 用两个角和预设名创建区域。
- `PhysicsZoneSelectionManager` 管理剪刀 Shift+右键选择两个角。

网络：

- `ZoneSelectionClick`
- `ZoneSelectionState`
- `OpenZoneCreateScreen`
- `ZoneCreateRequest`
- `ServerQuery(ZONE_LIST)`
- `SyncPhysicsZones`：OP-only 区域预览响应

### 13.5 调试和压测

调试/压测命令：

- 客户端 `/superlead_stress ...`

服务器 Verlet 物理压测和 `server_rope` 调试入口已经删除；服务端不再运行绳子物理模拟。
当前保留的压测入口主要用于压测客户端绳数量和模拟开销。

## 14. 配置体系

### 14.1 Common Config

`Config.java` 使用 `ModConfigSpec` 定义 common 配置，并在 load/reload 时刷新 volatile 缓存。

主要配置：

- 能量：
  - `energy.tier_max_level`
  - `energy.base_transfer_per_tick`
- 网络/连接：
  - `network.max_leash_distance`
  - `network.item_tier_max`
  - `network.fluid_tier_max`
  - `network.item_transfer_interval`
  - `network.fluid_bucket_amount`
  - `network.stuck_break_ticks`
- 预设：
  - `presets.allow_op_visual_presets`

`Config.applyRuntime(key, value)` 支持服务端在线修改，修改后 `refreshAfterRuntimeSet()` 更新缓存。

### 14.2 ServerConfigSnapshot

OP GUI 不直接读服务器文件，而是：

1. 客户端发 `ServerQuery(SERVER_CONFIG)`。
2. 服务端 `ServerConfigManager.sendSnapshot` 返回 `Config.snapshot()`。
3. 客户端 `ServerConfigClient` 缓存最后一份快照并通知 GUI listener。
4. 用户修改后发 `ServerConfigSet(key, value)`。
5. 服务端应用成功后再发新 snapshot。

## 15. 数据权威性和不变量

维护这个项目时建议牢牢记住这些规则：

1. 服务端 `SuperLeadSavedData` 是连接权威源。
2. 客户端 `SuperLeadNetwork.CONNECTIONS` 只是当前已加载 chunk 派生出来的缓存，不是完整维度表。
3. 任何连接变更都应通过 `SuperLeadNetwork` 写入 saved data，并调用 `SuperLeadPayloads.sendToDimension` 重发 dirty chunks。
4. 客户端发来的连接 ID、动作、挂件位置都不能信任；服务端必须重新查连接和校验玩家触达。
5. 物理区域是服务端权威；普通客户端只收 `SyncDimensionPresets`，OP 才能通过 `SyncPhysicsZones` 看区域预览。
6. 每根绳的物理参数应走 `LeadConnection.physicsPreset` 和 `RopeTuning.forConnection`。
7. 静态区块绳只负责远处/静态表现，靠近后必须回到动态 simulation。
8. `RopeContactPulse` 只同步已接受的接触偏移；绳形权威仍是每个客户端自己的 `RopeSimulation`。
9. `Config` 是服务器 common 规则；`ClientTuning` 是客户端视觉/模拟调参，两者不是同一层。
10. 连接和物理区域都是每维度数据，换维度必须清空旧客户端缓存，再按新区块 watch 重新同步。

## 16. 常见问题排查路径

### 16.1 进存档后绳不显示

优先看：

- `SuperLeadEvents.onPlayerLoggedIn` 是否发送 `ClearRopeCache` 清掉旧缓存。
- `ChunkWatchEvent.Sent` 是否触发 `SuperLeadEvents.onChunkSent`。
- `SuperLeadPayloads.sendChunkToPlayer` 是否发出 `SyncRopeChunk`。
- 客户端是否进入 `SuperLeadNetwork.replaceChunkConnections`。
- 对跨区块绳，`ropeRefCount` 是否仍大于 0。
- `StaticRopeChunkRegistry.onConnectionsReplaced` 是否用当前已加载连接重建。
- `SuperLeadClientEvents.onSubmitCustomGeometry` 是否因为距离、可见性或 static claim 跳过。

### 16.2 靠近远处绳后没有物理化

优先看：

- `StaticRopeChunkRegistry.tickMaintain` 是否把近处连接加入 `forceDynamic`。
- `StaticRopeChunkRegistry.isClaimed` 对该连接是否仍为 true。
- `SuperLeadClientEvents` 是否创建/保留了对应 `RopeSimulation`。
- `RopeTuning.forConnection` 返回的 `modePhysics` 是否为 true。
- 该绳 `lodDistSqr` 是否仍大于 `PHYSICS_LOD_DISTANCE_SQR`。

### 16.3 物理区域/预设包改了但绳参数没变

优先看：

- 服务端是否允许 `Config.allowOpVisualPresets`。
- `PresetServerManager.editKey` 是否调用 `refreshPresetUsage`。
- 客户端是否收到新的 `SyncDimensionPresets`。
- `PhysicsZonesClient.epoch()` 是否增加。
- `LeadConnection.physicsPreset` 是否是服务端期望的 preset 名。
- `RopeTuning.forConnection` 是否查到了正确 preset。
- simulation 是否调用 `sim.setTuning(tuning)` 并因 physics epoch/render epoch 重建必要缓存。

### 16.4 GUI 显示值变了但服务端规则没变

区分两类 GUI：

- `SuperLeadConfigScreen`：客户端本地调参，只影响本机视觉/模拟。
- `ServerConfigScreen` server tab：OP 服务端配置，会发 `ServerConfigSet` 并改 `Config`。

如果玩家以为在改全局绳物理参数，需要确认他改的是预设/区域，还是本地调参。

### 16.5 红石绳没有输出

优先看：

- 连接 `kind == REDSTONE` 或能提供红石 power 的类型。
- `LeadConnection.power` 是否更新。
- `SuperLeadNetwork.leadSignal` 是否能从位置/方向匹配到连接。
- `super_lead.mixins.json` 是否加载了 `SignalGetterMixin`。

## 17. 新功能开发建议

新增连接字段：

1. 改 `LeadConnection` record。
2. 改 `LeadConnection.CODEC`。
3. 改 `LeadConnectionPayloadCodec.writeConnection/readConnection`。
4. 改所有 `with...` 方法，避免修改时丢字段。
5. `SyncRopeChunk` 复用 `LeadConnectionPayloadCodec.writeConnection/readConnection`，通常不需要单独改。

新增客户端调参：

1. 在 `ClientTuning` 注册 `TuningKey`。
2. 如果影响物理，加入 `RopeTuning` record 和 `fromOverrides`。
3. 如果影响渲染，确认 `ClientTuning.fire` 会增加 render epoch。
4. 加语言 key。
5. 如果预设包也要支持，不需要额外注册，预设用 key id 字符串存储。

新增服务端配置：

1. 在 `Config` 增加 `ModConfigSpec` 项和缓存字段。
2. 加入 `snapshot()` 和 `applyRuntime()`。
3. 加服务端 GUI 字段描述。
4. 如果影响客户端表现，考虑是否需要新 payload 同步。

新增网络包：

1. 定义 record 实现 `CustomPacketPayload`。
2. 定义 `TYPE` 和 `STREAM_CODEC`。
3. 在 `SuperLeadPayloads.register` 里注册方向。
4. handler 内区分 client/server 线程语义，服务端包必须校验玩家权限和距离。
