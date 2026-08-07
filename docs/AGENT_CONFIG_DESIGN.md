# 可配置 Agent 设计（管理页可配：简介 / 提示词 / 工具 / MCP / 子 Agent）

> 文档版本：v1.3  
> 日期：2026-08-07  
> 依据：TECHNICAL_DESIGN v2.0、SUPERVISOR_SUBAGENT_DESIGN、API_DESIGN  
> 目标：将固定编码的 Supervisor / 子 Agent，演进为**可配置、可版本化、可在管理页维护**的 Agent 体系  
> **实现状态：未实现**（设计稿；现行运行时仍为固定五子 Agent，见 SUPERVISOR_SUBAGENT_DESIGN）  
> **落地实现细节（含 DDL 权威）**：[ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md)（后端已落地）  
> **管理前端 UI**：[ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md)（待实现）  
> **阅读顺序**：后端实现读 ADMIN；前端实现读 ADMIN_UI；本文提供领域模型与管理页字段字典。

---

## 1. 为什么要可配置

固定 `KnowledgeAgent` / `OrderAgent` 类适合 Demo，但企业场景常要：

- 运营改提示词，不发版  
- 按业务线增减专责 Agent（会员、发票、跨境…）  
- 按 Agent 挂不同 Tool / MCP，而不是改 Java  
- 主 Agent 动态选择子 Agent（关联关系可配）

核心思想：

```text
AgentDefinition（配置） + AgentRuntime（引擎） = 可运行 Agent
```

Java 里只保留**通用运行时**（ChatClient 装配、Tool 注册表、MCP 桥、路由执行），业务差异尽量进配置。

---

## 2. 总体架构

```text
┌─────────────────────────── Admin UI ───────────────────────────┐
│  Agent 列表 / 编辑：简介、Prompt、Tools、MCP、子Agent、路由       │
└─────────────────────────────┬──────────────────────────────────┘
                              │ REST /api/v1/admin/agents
┌─────────────────────────────▼──────────────────────────────────┐
│                     Config Service                              │
│  校验 → 版本化存储 → 发布(publish) → 通知 Runtime 刷新            │
└─────────────────────────────┬──────────────────────────────────┘
                              │
┌─────────────────────────────▼──────────────────────────────────┐
│                     Agent Runtime                               │
│  DefinitionRegistry（内存缓存当前已发布版本）                      │
│  ToolRegistry / McpRegistry（系统能力目录）                       │
│  Orchestrator：按定义执行 Supervisor 或 Worker                     │
└────────────────────────────────────────────────────────────────┘
```

角色拆分：

| 角色 | 说明 |
|------|------|
| **AgentDefinition** | 配置实体（存库） |
| **Capability Catalog** | 系统已实现的 Tool / MCP Server 目录（代码注册，页面只勾选） |
| **AgentRuntime** | 按定义创建临时 ChatClient / 绑定 tools / 调子 Agent |
| **Publisher** | draft → published，支持回滚 |

---

## 3. 领域模型

### 3.1 AgentDefinition（核心）

```text
AgentDefinition
├── id                    # agt_xxx
├── code                  # 唯一编码：supervisor / knowledge / order_custom ...
├── name                  # 展示名
├── description           # 简介（给运营看 + 可注入路由上下文）
├── type                  # SUPERVISOR | WORKER（本期不做 ROUTER_ONLY）
├── status                # DRAFT | PUBLISHED（生命周期；禁用用 enabled，见下）
├── enabled               # false=Runtime 不可调用（可与 PUBLISHED 并存：已发布但临时下线）
├── version               # draft 修订号 / 或头表版本
├── publishedVersion      # 当前线上版本；未发布为 null
├── modelConfig
│   ├── chatModel         # 可选覆盖全局模型
│   ├── temperature
│   ├── maxTokens
│   └── enableVision      # 是否允许多模态输入
├── prompts
│   ├── systemPrompt
│   ├── userPromptTemplate  # 可选，带占位符
│   └── outputMode        # TEXT | JSON_SCHEMA
├── outputSchema          # JSON Schema（Supervisor / 结构化节点用）
├── tools[]               # 勾选的本地 Tool code 列表
├── mcp
│   ├── enabled
│   ├── serverIds[]
│   └── toolAllowlist[]
├── capabilities              # 非 Tool 能力开关
│   └── enableRag             # true=走 KnowledgeService 检索注入（见 ADMIN §6.3）
├── children[]                # 关联子 Agent（见下）
├── routing               # 仅 SUPERVISOR / 含路由能力时
│   ├── strategy          # LLM_JSON | RULES | HYBRID
│   ├── confidenceThreshold
│   └── fallbackAgentCode
├── policies
│   ├── allowWriteTools   # 是否允许写工具（默认 false；Supervisor 必须 false）
│   ├── requireConfirmFor[]  # 哪些 tool 需二次确认
│   ├── maxToolRounds
│   └── maxChildHops      # 子 Agent 二次路由最大跳数（默认 1）
├── memory
│   ├── injectSummary
│   ├── windowSize        # 可覆盖全局
│   └── injectDescriptionToSupervisor  # 简介是否暴露给主路由
├── ui
│   ├── icon
│   ├── tags[]
│   └── sortOrder
├── createdAt / updatedAt / updatedBy
└── remark
```

### 3.2 子 Agent 关联（children）

不是简单 ID 列表，建议带**路由元数据**，供 Supervisor 选择：

```json
{
  "agentCode": "knowledge",
  "alias": "知识问答",
  "whenToUse": "政策、FAQ、退换货规则、运费说明",
  "priority": 10,
  "enabled": true,
  "slotHints": ["none"]
}
```

| 字段 | 作用 |
|------|------|
| agentCode | 指向另一个 AgentDefinition.code |
| whenToUse | **写给 Supervisor 看的路由说明**（比硬编码 intent 枚举更灵活） |
| priority | 冲突时排序 |
| enabled | 可临时下线子 Agent 而不删配置 |

Supervisor 的动态 Prompt 片段示例：

```text
你可路由到的子 Agent：
1. knowledge — 政策、FAQ、退换货规则……
2. order — 查单、物流、取消……
请输出 targetAgent（必须是上表 code 之一，或 none 表示仅澄清）……
```

这样**新增子 Agent 不必改枚举代码**，只需配置 children + whenToUse。

### 3.2.1 与现行固定路由的字段映射

现行 P0（固定 Agent）使用 `RouteDecision.targetAgent` + `intent` 枚举。  
可配置演进后：

| 现行字段 | 可配置字段 | 说明 |
|----------|------------|------|
| `targetAgent` | 仍用 **`targetAgent`**（取值 = children[].agentCode 或 `none`） | **不引入** `targetAgentCode`，避免双命名 |
| `intent` | 可选保留为业务标签；路由以 `targetAgent` 为准 | seed 阶段可继续写 knowledge/order/… |
| 固定枚举 | Supervisor.children 的 code 集合 | 发布时校验输出 schema enum |

结构化输出建议字段：

```json
{
  "targetAgent": "order",
  "intent": "order",
  "confidence": 0.9,
  "reason": "...",
  "slots": {},
  "needClarify": false,
  "clarifyQuestion": null
}
```

### 3.3 Agent 类型

| type | 行为 |
|------|------|
| `SUPERVISOR` | 结构化路由；通常无写工具；children 必填 |
| `WORKER` | 执行问答/工具；一般不再挂 children（或仅允许受限交接） |

> ~~`ROUTER_ONLY`~~：本期不做；若仅需路由不说话，用 `SUPERVISOR` + 澄清话术策略即可。

约束：

- **禁止环**：A→B→A（发布时做图检测）。  
- **SUPERVISOR 不得** `allowWriteTools=true`。  
- WORKER 默认 `maxChildHops=0`；Vision 类可 `=1`。

### 3.3.1 `status` 与 `enabled`（避免双重语义）

| 字段 | 含义 | 谁改 |
|------|------|------|
| `status` | 配置生命周期：`DRAFT`（仅草稿）/ `PUBLISHED`（至少发布过一次） | 创建 → DRAFT；成功 publish → PUBLISHED |
| `enabled` | Runtime 是否可被调用 | 运营开关；**默认 true** |

规则：

1. 未发布（仅 DRAFT、无 publishedVersion）→ Runtime **不可**调用，与 `enabled` 无关。  
2. 已发布且 `enabled=false` → 配置仍保留，但 Registry 不暴露；Supervisor children 中对应项视为下线。  
3. **不再使用** `status=DISABLED`；禁用一律用 `enabled=false`。

### 3.4 版本与发布

```text
agent_definition          # 当前编辑态（或头表）
agent_definition_version  # 历史快照（每次 publish 存全量 JSON）
agent_publish_record      # 谁在何时把 vN 推上线
```

- 编辑只改 DRAFT。  
- **Publish** 才刷新 Runtime 缓存。  
- 回滚 = 指定历史 version 再 publish。

---

## 4. 能力目录（页面只能「选」，不能「发明」）

管理页勾选的 Tool / MCP 必须来自系统注册表，避免配置任意代码执行。

### 4.1 ToolCatalog（本地工具）

由代码 `@Tool` / ToolCallback 启动时注册：

```json
{
  "code": "query_order",
  "name": "查询订单",
  "description": "根据订单号查询当前用户订单",
  "sideEffect": "READ",
  "paramSchema": { "...": "JSON Schema" },
  "ownerDomain": "order"
}
```

| sideEffect | 含义 |
|------------|------|
| READ | 只读 |
| WRITE | 写操作，默认可触发确认 |
| ADMIN | 仅调试 |

页面展示：按 domain 分组勾选；WRITE 工具在 Agent 上勾选时提示「将进入确认策略」。

### 4.2 McpServerCatalog

```json
{
  "id": "mcp_knowledge",
  "name": "知识库 MCP",
  "transport": "sse",
  "endpoint": "http://localhost:3100/sse",
  "status": "UP",
  "tools": [
    { "name": "search_docs", "description": "...", "sideEffect": "READ" }
  ]
}
```

Agent.mcp 配置只引用 `serverId` + allowlist。  
Runtime 在 Agent 调用时，把允许的 MCP tools 转成 Spring AI ToolCallbacks 注入。

---

## 5. 运行时装配

### 5.1 加载

```text
Publish / 启动
  → 读取所有 PUBLISHED AgentDefinition
  → 校验 tools/mcp/children 引用有效
  → 写入 DefinitionRegistry(code → definition)
```

热更新：发布事件 → Registry.replace(code) → 进行中的请求仍用旧引用（请求级快照）。

### 5.2 执行 WORKER

```text
def = registry.get(code)
client = chatClientFactory.build(def.modelConfig, def.prompts.systemPrompt)
tools = toolRegistry.resolve(def.tools) + mcpBridge.resolve(def.mcp)
messages = memoryComposer.compose(def.memory, session, userInput)
result = client.prompt().messages(messages).tools(tools).call()
applyConfirmPolicy(def.policies, result)
```

### 5.3 执行 SUPERVISOR

```text
def = registry.get(supervisorCode)
children = resolveChildren(def.children)  # 带 whenToUse
dynamicSystem = def.prompts.systemPrompt + renderChildrenCatalog(children)
decision = client.structured(outputSchema).call(...)  # targetAgent + confidence...
if decision.confidence < threshold OR needClarify OR targetAgent==none → clarify
else → runtime.invoke(decision.targetAgent, ...)
```

结构化输出见 §3.2.1。相对固定设计：`targetAgent` 的合法取值从写死枚举变为「当前 Supervisor.children 的 code 集合 ∪ {none}」。

---

## 6. 管理页信息架构

### 6.1 页面结构

```text
Agent 管理
├── 列表：名称、code、类型、状态、版本、标签、启用
├── 创建 / 编辑（多 Tab）
│   ├── 基本信息：code、名称、简介、类型、标签、排序
│   ├── 模型：模型名、温度、Vision 开关
│   ├── 提示词：System / UserTemplate；预览「渲染后」
│   ├── 工具：从 ToolCatalog 勾选；标识 READ/WRITE
│   ├── MCP：选 Server + 勾选 tool allowlist；连通性探测
│   ├── 子 Agent：从已发布 WORKER 中多选；编辑 whenToUse / priority
│   ├── 策略：确认工具列表、maxToolRounds、maxChildHops、置信度
│   └── 发布：diff 预览、校验结果、发布 / 回滚
└── 试运行（Playground）：选 Agent + 模拟 session 发一句话看轨迹
```

### 6.2 管理页字段表（表单级）

#### Tab「基本信息」

| 字段 | 控件 | 必填 | 可编辑时机 | 校验 / 说明 |
|------|------|------|------------|-------------|
| code | 文本 | 是 | 仅创建时 | `^[a-z][a-z0-9_]{1,63}$`；唯一 |
| name | 文本 | 是 | 始终（draft） | ≤ 64 字 |
| description | 多行文本 | 否 | draft | ≤ 500 字；简介；可注入路由目录 |
| type | 单选 | 是 | 仅创建时建议锁定 | 仅 `SUPERVISOR` / `WORKER` |
| enabled | 开关 | 是 | draft / 已发布均可 | 临时下线；与 status 关系见 §3.3.1 |
| tags | 标签输入 | 否 | draft | 最多 10 个 |
| sortOrder | 数字 | 否 | draft | 列表排序，默认 100 |
| icon | 选择/文本 | 否 | draft | UI 图标 key |
| remark | 文本 | 否 | draft | 内部备注，不进 Prompt |

#### Tab「模型」

| 字段 | 控件 | 必填 | 说明 |
|------|------|------|------|
| chatModel | 下拉（可空） | 否 | 空=用全局默认模型 |
| temperature | 滑块 0～1 | 否 | 默认 0.2 |
| maxTokens | 数字 | 否 | 默认 2048 |
| enableVision | 开关 | 否 | WORKER 如 vision 类打开 |

#### Tab「提示词」

| 字段 | 控件 | 必填 | 说明 |
|------|------|------|------|
| systemPrompt | 大文本 | 是 | 主系统提示词 |
| userPromptTemplate | 大文本 | 否 | 支持白名单变量 |
| outputMode | 单选 | 是 | `TEXT` / `JSON_SCHEMA` |
| outputSchema | JSON 编辑器 | 条件 | outputMode=JSON_SCHEMA 时必填 |
| 试渲染 | 按钮 | — | 用样例变量预览最终 system |

占位符芯片展示：`{{userId}}` `{{summary}}` `{{recentMessages}}` `{{childrenCatalog}}` `{{agentDescription}}` `{{slots}}` `{{locale}}` `{{visionSummary}}`

#### Tab「工具」

| 字段 | 控件 | 说明 |
|------|------|------|
| tools[] | 分组多选（来自 catalog） | 展示 name/sideEffect/domain |
| WRITE 标记 | 只读徽章 | 勾选 WRITE 时提示加入确认策略 |

SUPERVISOR：禁止勾选 `sideEffect=WRITE`（前端禁用 + 后端校验）。

#### Tab「MCP」

| 字段 | 控件 | 说明 |
|------|------|------|
| mcp.enabled | 开关 | |
| mcp.serverIds[] | 多选 Server | 显示 UP/DOWN |
| mcp.toolAllowlist[] | 按 Server 展开勾选 | 空=默认只读工具全开（需产品确认）；建议强制显式勾选 |
| 刷新 | 按钮 | 调 refresh 接口 |

#### Tab「子 Agent」（仅 SUPERVISOR）

| 字段 | 控件 | 必填 | 说明 |
|------|------|------|------|
| children[].agentCode | 下拉（已发布 WORKER） | 是 | |
| children[].alias | 文本 | 否 | 默认用子 Agent name |
| children[].whenToUse | 多行文本 | 是 | 写给主路由的使用说明 |
| children[].priority | 数字 | 否 | 默认 10 |
| children[].enabled | 开关 | 否 | 默认 true |

列表支持上移下移；保存/发布做环检测。

#### Tab「策略」

| 字段 | 控件 | 适用 | 说明 |
|------|------|------|------|
| allowWriteTools | 开关 | WORKER | SUPERVISOR 固定 false 且禁用 |
| requireConfirmFor[] | 多选（已选 WRITE tools） | WORKER | 默认全选 WRITE |
| maxToolRounds | 数字 | WORKER | 默认 3 |
| maxChildHops | 数字 | 含交接能力 | Demo 建议 ≤1 |
| routing.strategy | 单选 | SUPERVISOR | `LLM_JSON` / `HYBRID` |
| routing.confidenceThreshold | 数字 | SUPERVISOR | 默认 0.55 |
| routing.fallbackAgentCode | 下拉 | SUPERVISOR | 可选 chitchat |
| memory.injectSummary | 开关 | 全部 | 默认 true |
| memory.windowSize | 数字 | 全部 | 空=全局 |
| memory.injectDescriptionToSupervisor | 开关 | WORKER | 简介是否进主路由目录 |
| capabilities.enableRag | 开关 | WORKER | 默认 false；knowledge seed 为 true |

#### Tab「发布」

| 元素 | 说明 |
|------|------|
| dirty 标识 | draft 相对 published 是否有变更 |
| Diff 预览 | systemPrompt / tools / children 等关键字段对比 |
| 校验结果 | 展示 validate errors/warnings |
| 发布备注 | remark |
| 发布按钮 | publisher 角色 |
| 版本列表 | 支持查看 snapshot / 回滚 |

#### Playground（试运行）

| 字段 | 说明 |
|------|------|
| useDraft | 是否用草稿 |
| text / attachments | 模拟用户输入 |
| 结果区 | answer、routeTrace、toolCalls、promptsRendered |

### 6.3 关键交互

1. **简介 `description`**  
   - 列表与详情展示  
   - 可选注入 Supervisor 的 children 目录（与 whenToUse 互补）

2. **提示词**  
   - 大文本编辑器 + 变量提示  
   - 「试渲染」：用样例会话变量预览最终 system

3. **工具**  
   - 穿梭框 / 分组 checkbox  
   - WRITE 工具强制出现在「需确认」策略默认列表（可取消，但不建议）

4. **MCP**  
   - 先选 Server（显示 UP/DOWN）  
   - 再拉 tools/list 勾选  
   - 保存前校验 allowlist ⊆ server.tools

5. **关联子 Agent**  
   - 仅 SUPERVISOR 可配  
   - 子 Agent 必须是 PUBLISHED 的 WORKER  
   - 可视化：简单树 / 列表，发布时环检测

### 6.4 权限

| 角色 | 能力 |
|------|------|
| viewer | 只读 |
| editor | 改 draft、试运行 |
| publisher | 发布 / 回滚 |
| admin | 管理 Tool/MCP 目录元数据（仍不能动态上传任意代码） |

---

## 7. 管理端 API（草案）

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v1/admin/agents` | 列表 |
| POST | `/api/v1/admin/agents` | 创建 draft |
| GET | `/api/v1/admin/agents/{code}` | 详情（含 draft） |
| PUT | `/api/v1/admin/agents/{code}` | 更新 draft |
| POST | `/api/v1/admin/agents/{code}/publish` | 发布 |
| POST | `/api/v1/admin/agents/{code}/rollback` | body:`{version}` |
| GET | `/api/v1/admin/agents/{code}/versions` | 版本列表 |
| POST | `/api/v1/admin/agents/{code}/validate` | 只校验不发布 |
| POST | `/api/v1/admin/agents/{code}/trial` | 试运行 |
| GET | `/api/v1/admin/catalog/tools` | 本地工具目录 |
| GET | `/api/v1/admin/catalog/mcp-servers` | MCP 目录 |
| POST | `/api/v1/admin/catalog/mcp-servers/{id}/refresh` | 刷新 tools/list |

试运行响应建议带回：`routeTrace`、`toolCalls`、`promptsRendered`（脱敏）。

---

## 8. 校验规则（发布门禁）

1. `code` 唯一，匹配 `^[a-z][a-z0-9_]{1,63}$`。  
2. `systemPrompt` 非空，长度上限（如 20k）。  
3. `tools[]` ⊆ ToolCatalog；未知 code 拒绝。  
4. MCP server 存在且 allowlist 合法；DOWN 时可警告或阻断（可配）。  
5. SUPERVISOR：`children` 至少 1 个；child 均 PUBLISHED。  
6. 无环；`maxChildHops` ≤ 2（Demo 建议 ≤ 1）。  
7. SUPERVISOR：`allowWriteTools` 必须 false；tools 中不得含 WRITE。  
8. `outputMode=JSON_SCHEMA` 时 schema 可解析。  
9. 模板占位符只允许白名单变量。

---

## 9. 与现有「固定五子 Agent」如何平滑演进

### 阶段 A（兼容）

内置 seed 配置，发布为：

- `supervisor`  
- `knowledge` / `order` / `ticket` / `vision` / `chitchat`  

代码里保留同名 Runtime 行为；配置与代码双轨，**配置优先**。

### 阶段 B（配置主导）

删除硬编码 Prompt/工具绑定；全部走 Definition。  
仅保留 Tool 实现类与通用 Orchestrator。

### 阶段 C（多租户/多业务线，可选）

`AgentDefinition` 增加 `tenantId` / `bizLine`；会话带业务线选不同 Supervisor。

---

## 10. 数据表草案（摘要）

> **实现权威 DDL** 见 [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md) **§4.2**（含完整 SQL、`draft_revision` 等）。  
> 本节仅作领域对照；**与 ADMIN 冲突时以 ADMIN 为准**。会话/消息/知识库表见 TECHNICAL_DESIGN。

```text
agt_agent
  id, code, name, description, type, status,   -- status: DRAFT | PUBLISHED
  draft_json, published_version, enabled, draft_revision, sort_order, ...

agt_agent_version
  id, agent_code, version, snapshot_json, published_at, published_by, remark

-- agt_tool_catalog：本期不做（LocalToolCatalog 纯代码）
agt_mcp_server
  id, name, transport, endpoint, config_json, status, tools_json, ...

agt_trial_run      -- 试运行记录（可选，P1）
```

`draft_json` / `snapshot_json` 存 3.1 全量结构（含 `capabilities`）；高频查询字段冗余列存储。  
下线只用 `enabled=false`；**无**物理删除已发布 Agent 的 API（见 ADMIN §7.6）。

---

## 11. Prompt 模板变量（白名单）

| 变量 | 说明 |
|------|------|
| `{{userId}}` | 当前用户 |
| `{{summary}}` | 会话摘要 |
| `{{recentMessages}}` / `{{recentMessagesFormatted}}` | 窗口消息 |
| `{{childrenCatalog}}` | 子 Agent 目录（Supervisor） |
| `{{agentDescription}}` | 本 Agent 简介 |
| `{{slots}}` / `{{slotsJson}}` | 路由槽位 JSON |
| `{{locale}}` | 语言 |
| `{{visionSummary}}` | 二次路由时的视觉摘要 |
| `{{text}}` | 本轮用户文本 |
| `{{retrievedBlocks}}` / `{{mcpBlocks}}` | Knowledge 检索块 |
| `{{sessionStatus}}` / `{{confirmationPayloadSummary}}` | 会话确认态 |
| `{{hasAttachments}}` / `{{attachmentCount}}` | 附件标记 |
| `{{transcript}}` | 摘要用全文 |

未知变量：发布校验失败或运行时原样保留（建议校验失败）。

---

## 12. 安全要点

1. **工具不能配置任意 HTTP**（除非做成受控 HttpTool 且 URL 白名单）。  
2. MCP 只信已登记 Server；生产禁随意填 endpoint（或 admin 审批）。  
3. Prompt 注入：系统模板与用户输入分区；试运行也要隔离生产数据。  
4. 写工具默认确认；审计必记 `agentCode + version`。  
5. 发布权限与变更审计（谁改了 systemPrompt）。

---

## 13. 可观测

每次对话落库：

- `agentCode` + `agentVersion`（发布版本）  
- Supervisor `targetAgent` / confidence  
- tools / mcp 来源  

管理页可按 Agent 看调用量、失败率、确认率。

---

## 14. 推荐落地节奏

| 阶段 | 内容 |
|------|------|
| P0 | 表结构 + Admin CRUD + 发布；Runtime 读 published；seed 五个 Worker + Supervisor |
| P1 | 工具/MCP 勾选生效；Playground 试运行 |
| P2 | 动态 children 路由文案；去掉硬编码 intent 枚举 |
| P3 | 回滚、diff、多业务线、权限 |

---

## 15. 和「写死 Agent」的取舍

| | 固定代码 Agent | 可配置 Agent |
|--|----------------|--------------|
| 改 Prompt | 发版 | 发布配置 |
| 增业务线 | 加类 | 加配置 |
| 安全 | 更可控 | 需目录+校验+权限 |
| 复杂度 | 低 | 中高 |
| 适合 | Demo / 稳定域 | 运营频繁调参、多租户 |

**建议**：Demo 可先 P0 做出「配置能驱动 Prompt+Tools」；children 动态路由作为 P2，避免第一步就做完美编排器。

---

## 16. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-30 | 可配置 Agent：模型、管理页、目录、发布、运行时装配 |
| v1.1 | 2026-07-30 | 增补管理页字段表；Admin API 详见 API_DESIGN v1.1 |
| v1.2 | 2026-08-07 | 统一 `targetAgent` 命名；厘清 status/enabled；去掉 ROUTER_ONLY；标明未实现；DDL 权威说明 |
| v1.3 | 2026-08-07 | DDL 权威移交 ADMIN；增 capabilities.enableRag；tool_catalog 明确不做 |

---

**关联文档**

- [docs/README.md](./README.md) — 文档索引  
- [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md) — **落地详细技术设计**（包、DDL、Runtime、排期）  
- [TECHNICAL_DESIGN.md](../TECHNICAL_DESIGN.md)  
- [SUPERVISOR_SUBAGENT_DESIGN.md](./SUPERVISOR_SUBAGENT_DESIGN.md)  
- [API_DESIGN.md](./API_DESIGN.md) §11 — Admin Agent / Catalog API（设计已写，**未实现**）
