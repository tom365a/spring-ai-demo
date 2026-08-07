# Admin / 可配置 Agent — 详细技术设计

> 文档版本：v1.1  
> 日期：2026-08-07  
> 状态：**A0–A4 已落地代码**（`apps/java-gateway`；默认 `app.agent-config.enabled=false`）  
> 前置阅读（建议顺序）：① 本文（落地）← 实现以本为准 · ② [AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md)（领域/管理页）· ③ [API_DESIGN.md](./API_DESIGN.md) §11（HTTP）· ④ [SUPERVISOR_SUBAGENT_DESIGN.md](./SUPERVISOR_SUBAGENT_DESIGN.md)（Seed 行为真源）  
> 目标读者：实现 Admin API + Runtime 热加载 + 管理页（可选）的研发

---

## 1. 文档目的与范围

### 1.1 要解决什么问题

当前 Demo 的 Supervisor / 五子 Agent 为**固定 Java 类 + classpath Prompt**：

| 痛点 | 表现 |
|------|------|
| 改 Prompt 要发版 | 改 `resources/prompts/*.md` 后重启 |
| 增业务线要加类 | 新意图需新 `*Agent` + 改 Orchestrator / 枚举 |
| 工具绑定粗粒度 | `OrderAgent`/`TicketAgent` 挂整个 `OrderTicketTools`，未按名隔离 |
| 无版本/回滚 | 线上话术改坏只能靠 git revert |

**Admin 落地** = 提供配置 CRUD / 发布 / 试运行，并让对话 Runtime **优先读已发布配置**。

### 1.2 范围内

1. 数据表与 JPA 实体（`agt_*`）——**DDL 以实现本节 §4 为准**  
2. Admin REST API（与 API_DESIGN §11 对齐）  
3. Capability Catalog（本地 Tool + MCP Server）  
4. `DefinitionRegistry` + `ConfigurableAgentInvoker`  
5. 与现有 `AgentOrchestrator` 的双轨接入（配置优先，缺省回落固定 Agent）  
6. Seed 数据、校验门禁、审计字段  
7. Demo 级权限（`X-Admin-Role`）与简易管理 UI（可选，可后置）

### 1.3 非范围（本期不做）

- 多租户 / 多业务线选 Supervisor  
- 运营自建任意 HTTP Tool / 上传可执行代码  
- 完整 RBAC / SSO（仅 Demo Header 角色）  
- `ROUTER_ONLY` 类型  
- 独立 Admin 微服务拆分  
- 物理删除已发布 Agent（仅 `enabled=false`，见 §7.6）

### 1.4 成功标准

**阶段 A（Admin 落地，A0–A2；默认 `agent-config.enabled` 可仍为 false）**

1. 管理端可创建/编辑 draft → validate → publish / rollback；Registry 热更新。  
2. Seed 后库内有 6 个 Agent；`enabled=true` 时改 Prompt 即时影响对话。  
3. `fallback-to-legacy=true` 且缺配置时行为与现网一致。  
4. SUPERVISOR 无法挂 WRITE；未知 tool code 发布失败。

**阶段 B（配置主导，可选后置）**

5. 试运行返回 `routeTrace` / `promptsRendered`。  
6. 关闭固定 Prompt 路径（无业务对 `PromptLoader` 依赖），演示剧本仍通过。

---

## 2. 与现有代码的关系

### 2.1 现状（已实现）

```text
ApiController (/api/v1/chat ...)
  └─ AgentOrchestrator
        ├─ ConfirmResume → OrderTicketTools.cancelOrder
        ├─ SupervisorAgent（固定 Prompt + 固定 intent 枚举）
        └─ Map<code, SubAgent>：knowledge|order|ticket|vision|chitchat
              └─ 各自 ChatClient + PromptLoader +（部分）tools(OrderTicketTools 整实例)
```

关键路径：

- `com.demo.cs.application.orchestrator.AgentOrchestrator`
- `com.demo.cs.agent.*` / `agent.supervisor.SupervisorAgent`
- `com.demo.cs.infrastructure.tools.OrderTicketTools`
- `com.demo.cs.config.WebConfig.PromptLoader`
- 表：`cs_*` / `kb_document`（**无** `agt_*`）

### 2.2 目标形态（Admin 落地后）

```text
┌─ Admin UI / curl ─┐
│  /api/v1/admin/*  │
└────────┬──────────┘
         │
┌────────▼──────────────────┐     publish / rollback
│ AgentDefinitionService    │────────────────────────┐
│ AgentPublishService       │                        │
│ AgentDefinitionValidator  │                        ▼
└───────────────────────────┘              DefinitionRegistry
                                           (code → PublishedAgent)
                                                   │
Chat API ──► AgentOrchestrator ────────────────────┤
                                                   ▼
                                      ConfigurableAgentInvoker
                                      ├─ SUPERVISOR：结构化路由（children 动态）
                                      └─ WORKER：按 definition 装 Prompt/Tools/MCP
                                                   │
                                      缺配置或 feature flag 关闭 ──► 现有固定 Agent（回落）
```

### 2.3 设计原则

| 原则 | 说明 |
|------|------|
| 配置优先、代码兜底 | `app.agent-config.enabled=true` 且 Registry 有 published → 走配置；否则走现有类 |
| Catalog 只选不发明 | Tool / MCP 必须来自代码注册表 |
| 请求级快照 | 进入 turn 时拷贝 definition 引用；中途 publish 不影响进行中请求 |
| 领域 JSON 演进 | `draft_json` / `snapshot_json` 存全量；列上冗余 `code/type/enabled/...` 便于列表查询 |
| API 契约已定 | HTTP 形状以 [API_DESIGN.md](./API_DESIGN.md) §11 为准；本文补实现细节 |

---

## 3. 逻辑架构与包规划

建议在 `apps/java-gateway` 内新增包（不拆应用）：

```text
com.demo.cs
├── api
│   └── admin
│       ├── AdminAgentController          # /api/v1/admin/agents/**
│       └── AdminCatalogController        # /api/v1/admin/catalog/**
├── application
│   └── agentconfig
│       ├── AgentDefinitionService        # CRUD draft
│       ├── AgentPublishService           # validate + publish + rollback
│       ├── AgentTrialService             # playground
│       ├── AgentSeedService              # 启动 seed
│       └── AgentDefinitionValidator
├── agent
│   └── runtime
│       ├── DefinitionRegistry            # 内存已发布缓存
│       ├── ConfigurableAgentInvoker      # 按 definition 执行
│       ├── PromptTemplateRenderer        # {{var}} 白名单渲染
│       ├── ToolBindingFactory            # code[] → ToolCallback[]
│       └── ChildrenCatalogRenderer       # whenToUse → 路由目录文案
├── domain
│   ├── AgtAgent
│   ├── AgtAgentVersion
│   ├── AgtMcpServer                      # 可选：首期 MCP 仍用 yml 单 server
│   └── AgtTrialRun                       # 可选
├── infrastructure
│   ├── catalog
│   │   ├── LocalToolCatalog              # 扫描/声明式工具元数据
│   │   └── McpServerCatalog              # 包装现有 McpBridgeClient
│   └── persistence
│       ├── AgtAgentRepository
│       └── AgtAgentVersionRepository
└── config
    └── AgentConfigProperties             # app.agent-config.*
```

**保留不动（阶段 A）**：现有五个 `*Agent`、`SupervisorAgent`、`OrderTicketTools` 实现体；仅改变「谁组装 Prompt/Tools」。

---

## 4. 领域模型与持久化

### 4.1 逻辑模型

与 [AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md) §3.1 一致。实现时拆两层：

| 层 | 内容 |
|----|------|
| 头表列 | `id, code, name, description, type, status, enabled, published_version, sort_order, draft_revision, updated_at, updated_by` |
| `draft_json` | 完整 `AgentDefinition`（含 model/prompts/tools/mcp/children/routing/policies/memory/ui） |
| `agt_agent_version` | 每次 publish 的全量 `snapshot_json` + `version` + `remark` |

**状态语义**（已定稿）：

- `status`：`DRAFT` | `PUBLISHED`（生命周期）  
- `enabled`：Runtime 开关；已发布也可 `false` 临时下线  
- **不用** `DISABLED`

### 4.2 DDL（**实现权威**）

> **agt_\*** 表以**本节 SQL 为准**。[AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md) §10 仅为领域摘要，冲突时以本文为准。  
> 会话/消息等仍用现有 `cs_*`。

```sql
-- 头表：当前编辑态 + 线上指针
CREATE TABLE agt_agent (
  id                 VARCHAR(64)  PRIMARY KEY,          -- agt_xxx
  code               VARCHAR(64)  NOT NULL UNIQUE,      -- supervisor / knowledge / ...
  name               VARCHAR(128) NOT NULL,
  description        VARCHAR(1000),
  type               VARCHAR(32)  NOT NULL,             -- SUPERVISOR | WORKER
  status             VARCHAR(32)  NOT NULL,             -- DRAFT | PUBLISHED
  enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
  published_version  INT,                               -- null = 从未发布
  draft_revision     INT          NOT NULL DEFAULT 0,   -- 乐观锁 / 并发编辑
  sort_order         INT          NOT NULL DEFAULT 100,
  draft_json         CLOB         NOT NULL,             -- H2/PG: TEXT/JSONB
  created_at         TIMESTAMP    NOT NULL,
  updated_at         TIMESTAMP    NOT NULL,
  updated_by         VARCHAR(64),
  remark             VARCHAR(500)
);

CREATE INDEX idx_agt_agent_type ON agt_agent(type);
CREATE INDEX idx_agt_agent_status ON agt_agent(status);

-- 发布历史（不可变快照）
CREATE TABLE agt_agent_version (
  id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  agent_code     VARCHAR(64)  NOT NULL,
  version        INT          NOT NULL,
  snapshot_json  CLOB         NOT NULL,
  published_at   TIMESTAMP    NOT NULL,
  published_by   VARCHAR(64),
  remark         VARCHAR(500),
  CONSTRAINT uk_agt_agent_ver UNIQUE (agent_code, version)
);

-- MCP Server 登记（Demo：可先只 seed 一条，与 app.mcp 对齐）
CREATE TABLE agt_mcp_server (
  id            VARCHAR(64)  PRIMARY KEY,               -- mcp_knowledge
  name          VARCHAR(128) NOT NULL,
  transport     VARCHAR(32)  NOT NULL,                  -- http | sse
  endpoint      VARCHAR(512) NOT NULL,
  config_json   CLOB,
  status        VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',-- UP | DOWN | UNKNOWN
  tools_json    CLOB,                                   -- 最近一次 tools/list 缓存
  updated_at    TIMESTAMP    NOT NULL
);

-- 试运行记录（可选，P1）
CREATE TABLE agt_trial_run (
  id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  agent_code     VARCHAR(64)  NOT NULL,
  use_draft      BOOLEAN      NOT NULL,
  mode           VARCHAR(32)  NOT NULL,                 -- single | full_route
  request_json   CLOB,
  response_json  CLOB,
  latency_ms     INT,
  created_at     TIMESTAMP    NOT NULL,
  created_by     VARCHAR(64)
);
```

**本地 Tool Catalog**：首期**不落库**，由 `LocalToolCatalog` 代码声明（与 `OrderTicketTools` 方法一一对应）。

**JSONB**：Postgres 可用 `JSONB`；H2 `local` profile 用 `CLOB` + 应用侧 JSON。实体字段统一 `String` + `ObjectMapper`，或 Hibernate JSON 类型按 profile 区分。

### 4.3 `draft_json` 最小必填示例（WORKER）

```json
{
  "code": "order",
  "name": "订单履约",
  "description": "查单、物流、取消确认",
  "type": "WORKER",
  "modelConfig": {
    "chatModel": null,
    "temperature": 0.2,
    "maxTokens": 2048,
    "enableVision": false
  },
  "prompts": {
    "systemPrompt": "……",
    "userPromptTemplate": "当前 userId={{userId}}\n路由槽位={{slots}}\n\n{{text}}",
    "outputMode": "TEXT"
  },
  "outputSchema": null,
  "tools": ["query_order", "query_logistics", "cancel_order"],
  "mcp": { "enabled": false, "serverIds": [], "toolAllowlist": [] },
  "capabilities": {
    "enableRag": false
  },
  "children": [],
  "routing": null,
  "policies": {
    "allowWriteTools": true,
    "requireConfirmFor": ["cancel_order"],
    "maxToolRounds": 3,
    "maxChildHops": 0
  },
  "memory": {
    "injectSummary": true,
    "windowSize": null,
    "injectDescriptionToSupervisor": true
  },
  "ui": { "icon": "order", "tags": ["履约"], "sortOrder": 20 }
}
```

Supervisor 差异：`type=SUPERVISOR`，`children` 非空，`routing` 有值，`tools` 无 WRITE，`allowWriteTools=false`，`outputMode=JSON_SCHEMA`，`outputSchema` 对齐 `RouteDecision`（字段名 **`targetAgent`**，合法 enum = children codes ∪ `none`）。

---

## 5. Capability Catalog

### 5.1 LocalToolCatalog

启动时注册元数据（建议显式列表，避免反射脆）：

| code | sideEffect | ownerDomain | 实现 |
|------|------------|-------------|------|
| `query_order` | READ | order | `OrderTicketTools.queryOrder` |
| `query_logistics` | READ | order | `OrderTicketTools.queryLogistics` |
| `cancel_order` | WRITE | order | `OrderTicketTools.cancelOrder` |
| `create_ticket` | WRITE | ticket | `OrderTicketTools.createTicket` |
| `escalate_human` | WRITE | ticket | `OrderTicketTools.escalateHuman` |

`ToolBindingFactory.resolve(List<String> codes)`：

1. 校验 ⊆ catalog  
2. 按 code 过滤，生成仅含所选方法的 `MethodToolCallback` / 包装 ToolCallbacks（**禁止**再把整个 `OrderTicketTools` 无筛选交给 WORKER）  
3. WRITE 且在 `requireConfirmFor`：Runtime 拦截——模型若发起调用，转为 `confirmRequired`（与现 OrderAgent 短路语义一致）

### 5.2 McpServerCatalog

- 首期：seed `mcp_knowledge`，`endpoint` = `app.mcp.http-base-url`  
- `refresh`：调现有 `McpBridgeClient` 拉 tools，写 `tools_json` + `status`  
- Agent.mcp：`serverIds` + `toolAllowlist`；空 allowlist = **仅 READ 工具**（与 AGENT_CONFIG 建议一致；发布时 warning 若为空）  
- 阶段 A：Knowledge 的 MCP 仍可走现有 `searchDocs` 路径；配置 `mcp.enabled` 控制是否注入

---

## 6. Runtime 详细设计

### 6.1 DefinitionRegistry

```text
interface DefinitionRegistry {
  Optional<PublishedAgent> get(String code);     // enabled && published
  void replace(String code, PublishedAgent snap); // publish 后
  void remove(String code);                      // enabled=false 或删除
  Map<String, PublishedAgent> allEnabled();
}

record PublishedAgent(String code, int version, AgentDefinition definition) {}
```

- 启动：`AgentSeedService`（若库空）→ 读全部 `PUBLISHED && enabled` → `replace`  
- Publish：DB 事务提交后 `replace`  
- Rollback：新 version = max+1，snapshot=历史内容，再 `replace`  
- **不在**请求路径读库（除 trial 强制 useDraft）

### 6.2 编排接入点（改动面最小）

在 `AgentOrchestrator.executeTurn` 中：

```text
IF app.agent-config.enabled:
  publishedSupervisor = registry.get("supervisor")   # 仅 enabled && published
  IF present:
    decision = configurableInvoker.route(supervisorDef, input)
  ELSE:
    decision = supervisorAgent.route(...)            # 回落

  # children 目录渲染时已排除 enabled=false 的子 Agent
  target = decision.targetAgent
  IF target == none OR needClarify OR low confidence:
    clarify; END
  IF registry.get(target).isEmpty:                   # 未发布 / enabled=false / 未知
    IF fallback-to-legacy && agents.containsKey(target):
      result = agents.get(target).handle(request)
    ELSE:
      澄清：「该专责客服暂不可用」; END
  ELSE:
    result = configurableInvoker.handle(workerDef, request)
ELSE:
  // 全走现有逻辑
```

#### `enabled=false` 定稿规则

1. `DefinitionRegistry.get(code)` **不返回** `enabled=false` 的 Agent。  
2. Supervisor 渲染 `childrenCatalog` 时**过滤**掉 `children[].enabled=false` 以及目标 Agent 头表 `enabled=false`。  
3. 若模型仍输出已下线 `targetAgent`：按上表走澄清（或 legacy 回落），**不**调用已下线配置。  
4. 下线不等于删库；历史 `agt_agent_version` 保留，可再 `enabled=true` + 可选 re-publish。

Vision 二次路由、ConfirmResume、附件优先 Vision 等**编排规则保留在 Orchestrator**，不搬进配置。

### 6.2.1 Chat API 的 `intent` 与动态 `targetAgent`

对外 Chat / SSE 仍返回 `intent` 字段（兼容现客户端）。配置化后约定：

| 情况 | `intent` 取值 | `agentName` / 路由 |
|------|---------------|-------------------|
| Seed 五子 + 标准枚举 | 与现行一致：`knowledge`/`order`/…/`multimodal`/… | `targetAgent` 为 vision/knowledge/… |
| 自定义 WORKER code（如 `invoice`） | **`intent` = 该 `targetAgent` code**（字符串透传，不再强制枚举校验） | 同 code |
| 澄清 / none | `unclear` 或 Supervisor 给出的 intent | `agentName=supervisor` |
| 确认短路 | `confirm_resume` | `confirm_executor` |

OpenAPI / 前端：将 `intent` 视为 **string**，Demo UI 不因未知值崩溃。固定枚举仅作文档示例，非硬校验。

### 6.3 ConfigurableAgentInvoker

#### SUPERVISOR

1. `system = render(def.prompts.systemPrompt, {childrenCatalog, ...})`  
2. `childrenCatalog` 由 `ChildrenCatalogRenderer` 根据可用 children 生成（见 §6.2 enabled 规则）  
3. `ChatClient` + `BeanOutputConverter` / JSON Schema → `RouteDecision`  
4. 校验 `targetAgent ∈ 可用 children ∪ {none}`；非法则 heuristic 兜底  
5. 阈值：`def.routing.confidenceThreshold` 优先，否则 `app.route-confidence-threshold`

#### WORKER

1. 组装 messages：summary / window（`memory.*`）+ user 模板  
2. `tools = ToolBindingFactory + McpBridge`  
3. `maxToolRounds` 限制循环  
4. **RAG**：若 `capabilities.enableRag=true`（或 seed 的 `knowledge` 默认 true），先 `KnowledgeService.retrieve`，结果注入 `{{retrievedBlocks}}`；管理页用该开关表达，**不必**把向量检索做成可勾选 Tool  
5. Vision：`enableVision=true` 时挂 Media；解析 JSON 按 SUPERVISOR_SUBAGENT §9.3  

#### Confirm / WRITE

- `cancel_order` 等：保持「本轮不真正执行 → confirmRequired → ConfirmResume 直调 Tool」  
- 配置 `requireConfirmFor` 驱动拦截名单；缺省 = 所有 WRITE

### 6.4 Prompt 渲染

`PromptTemplateRenderer`：

- 仅替换白名单：`userId, summary, recentMessages, childrenCatalog, agentDescription, slots, locale, visionSummary, text, retrievedBlocks, mcpBlocks`  
- 未知 `{{x}}`：validate 失败；Runtime 防御性保留原文并打 warn  
- 与现有 `*_user.md` 对齐，Seed 时把文件内容写入 `userPromptTemplate`

### 6.5 模型覆盖

`modelConfig.chatModel` 非空时：用 `ChatClient.Builder` clone + options 覆盖；空则全局 `LLM_MODEL`。  
`enableVision`：与现 VisionAgent 相同多模态消息构建。

---

## 7. Admin API 实现要点

契约全文见 [API_DESIGN.md](./API_DESIGN.md) §11。本节只补实现约定。

### 7.1 鉴权（Demo）

| Header | 规则 |
|--------|------|
| `X-API-Key` | 与现网一致（可选放宽） |
| `X-Admin-Role` | `viewer` / `editor` / `publisher`；缺省 `viewer` |

| 操作 | 最低角色 |
|------|----------|
| GET 列表/详情/versions/catalog | viewer |
| POST/PUT draft、validate、trial | editor |
| publish / rollback、mcp refresh | publisher |

用 `HandlerInterceptor` 或 `@PreAuthorize` 风格的简易切面；**生产需换 JWT**。

### 7.2 并发

- 更新 draft：请求带 `draftRevision`（或 `updatedAt`）；不匹配 → `40901`  
- publish：按 `code` 串行（`SELECT … FOR UPDATE` 或同步锁）

### 7.3 Validate / Publish 流水线

```text
validate(code, useDraft=true):
  load draft_json → AgentDefinition
  run rules (AGENT_CONFIG §8)
  return { ok, errors[], warnings[] }

publish(code, remark):
  v = validate(); if !v.ok → 42201
  nextVer = coalesce(published_version,0)+1
  insert agt_agent_version(snapshot=draft_json, version=nextVer)
  update agt_agent set status=PUBLISHED, published_version=nextVer, ...
  registry.replace(code, ...)
```

### 7.4 Trial

| mode | 行为 |
|------|------|
| `single` | 只跑 `{code}` 对应 Agent（Supervisor 则只产出 RouteDecision 文本化；WORKER 则 handle） |
| `full_route` | 以 `supervisor` 为入口走 Orchestrator 子集（可建隔离 session `trial_*`，不污染用户会话） |

`useDraft=true`：不写 Registry，用内存临时 definition。  
响应含 `promptsRendered.system`（截断 4k）、`routeTrace`、`toolCalls`。

### 7.5 错误码

沿用 API_DESIGN：`40901` 冲突、`42201` 校验失败、`40401` 不存在、`40301` 角色不足。

### 7.6 删除与归档（定稿）

| 操作 | 是否提供 | 说明 |
|------|----------|------|
| `DELETE /admin/agents/{code}` | **本期不提供** | 避免误删审计与 Seed 基准 |
| `enabled=false` | **唯一下线手段** | Runtime 不可见；draft 仍可编辑 |
| 物理删除 | 不做 | 运维若清理需手工 SQL（文档不鼓励） |
| 从未发布的纯 DRAFT | 可选：editor 调 `DELETE` **仅当** `published_version IS NULL`（P2 可加；A0 可不做） |

列表默认可筛 `enabled`；下线 Agent 仍出现在 Admin 列表中。

---

## 8. Seed 与平滑演进

### 8.1 Seed 内容（启动，幂等）

若 `agt_agent` 无 `supervisor` 行，则插入并**自动 publish v1**：

| code | type | tools / children |
|------|------|------------------|
| supervisor | SUPERVISOR | children: knowledge, order, ticket, vision, chitchat；Prompt ← `supervisor_router.md` |
| knowledge | WORKER | tools=[]；`capabilities.enableRag=true`；mcp 可选；Prompt ← `agent_knowledge.md` + user 模板 |
| order | WORKER | query_order, query_logistics, cancel_order |
| ticket | WORKER | create_ticket, escalate_human |
| vision | WORKER | tools=[]；enableVision=true；maxChildHops=1 |
| chitchat | WORKER | tools=[] |

`whenToUse` 文案从现 SUPERVISOR 路由规则提炼。

配置项：

```yaml
app:
  agent-config:
    enabled: false          # 阶段 A 默认 false，验通后改 true
    seed-on-startup: true
    fallback-to-legacy: true
```

### 8.2 阶段划分（实现排期）

| 阶段 | 内容 | 验收 |
|------|------|------|
| **A0** | 表 + 实体 + Admin CRUD + validate（不接 Runtime） | curl 可改 draft |
| **A1** | publish/rollback + Registry + Seed | Registry 有 6 个 Agent |
| **A2** | ConfigurableInvoker 接 Orchestrator（flag 开关） | flag=true 时改 Prompt 即时生效 |
| **A3** | Tool 按 code 过滤绑定 + confirm 策略配置化 | order 工具集正确隔离 |
| **A4** | trial + catalog API +（可选）简易 Admin HTML | Playground 可用 |
| **B** | 删除/闲置硬编码 Prompt 路径（保留 Tool 实现） | 无 PromptLoader 业务依赖 |
| **C** | 多租户（可选） | — |

建议：**先合 A0–A2** 即可称为「Admin 落地」；A3/A4 紧随。

---

## 9. 管理 UI

> **详细设计已独立成文**：[ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md)（静态 `admin.html`、Tab 编辑器、Playground、flag 联调、阶段 B）。  
> 下文为摘要；冲突以该文为准。

### 9.1 方案选择

| 方案 | 说明 | 建议 |
|------|------|------|
| 静态页挂在 `java-gateway` | `static/admin.html`，Hash 路由调 Admin API | **Demo 采用** |
| 纯 curl / Bruno 集 | 无 UI | 仅调试 |
| 独立前端工程 | 过重 | 本期不做 |

### 9.2 信息架构

列表 → 多 Tab 编辑 → 发布 Diff → Playground；角色 `X-Admin-Role` 控制按钮。  
排期：U0–U3 为管理前端 MVP。

---

## 10. 可观测与审计

每次对话（配置路径）额外落：

| 字段 | 位置 |
|------|------|
| `agentCode` + `agentVersion` | `cs_message` 扩展列或 tool/route meta JSON |
| `targetAgent` / confidence | 现有 `cs_agent_route_log`（可加 `agent_version`） |

Admin 写操作：publish/rollback 已有 `agt_agent_version`；draft 更新靠 `updated_by` + 可选 changelog 表（P2）。

指标（可选 Micrometer）：`agent_config_publish_total`、`agent_runtime_fallback_total`、`agent_trial_total`。

---

## 11. 安全设计（落地约束）

1. Admin API 默认仅 `local` / 内网；生产必须鉴权升级。  
2. `draft_json` 禁止存 API Key。  
3. MCP `endpoint`：仅 publisher 可改；或 Demo 锁死为 seed，禁止 UI 改 endpoint。  
4. Trial 使用独立 `userId` 前缀 `trial_`，Mock 订单仍可用，但日志标记 `trial=true`。  
5. Prompt 与用户输入分区：user 内容永不写入 system 槽位变量以外的拼接（渲染器只做占位替换）。

---

## 12. 测试计划

| 编号 | 场景 | 期望 |
|------|------|------|
| TA1 | seed 后 GET /admin/agents | 6 条，supervisor 为 SUPERVISOR |
| TA2 | 改 order systemPrompt → publish → chat 查单 | 回复风格变化且工具仍可用 |
| TA3 | SUPERVISOR 勾选 cancel_order → validate | errors 非空，无法 publish |
| TA4 | children 环 A→B→A | validate 失败 |
| TA5 | rollback v1 | Registry version 递增但 Prompt 回到 v1 内容 |
| TA6 | enabled=false on knowledge | children 目录无 knowledge；若强行路由则澄清「暂不可用」（fallback 关闭时） |
| TA7 | flag=false | 行为与改 Admin 前一致（回落） |
| TA8 | trial useDraft 未发布文案 | 不影响线上 Registry |
| TA9 | 并发两 editor 同 revision | 后者 40901 |
| TA10 | 演示剧本 1–6（flag=true） | 与 README 剧本通过 |

回归：现有 `route_cases` / 手工剧本在 `fallback` 与 `enabled` 两种模式都跑一遍。

---

## 13. 配置清单增量

```yaml
app:
  agent-config:
    enabled: false
    seed-on-startup: true
    fallback-to-legacy: true
    prompt-max-length: 20000
    trial-session-ttl-minutes: 60
```

环境变量（可选）：`APP_AGENT_CONFIG_ENABLED=true`。

---

## 14. 风险与决策

| 风险 | 应对 |
|------|------|
| 配置 JSON 与代码行为漂移 | Seed 单测快照；演示剧本自动化 |
| Tool 过滤实现复杂 | A2 可先「按 Agent 预设工具集」硬编码映射表，A3 再 Method 级过滤 |
| H2 vs PG JSON | 统一 String 存 JSON 文本 |
| 热更新并发 | 请求级 snapshot；Registry 用 `ConcurrentHashMap` + immutable record |
| Knowledge 检索非 Tool | 用 `capabilities.enableRag` 显式开关 + 模板变量注入 |

**已确认决策（本文）**：

1. 路由字段名继续用 `targetAgent`（不用 `targetAgentCode`）。  
2. 阶段 A 默认 `agent-config.enabled=false`。  
3. Confirm 状态机留在 Orchestrator；否定语规则与现网代码对齐（见 SUPERVISOR §11.3）。  
4. Tool Catalog 首期不落库（无 `agt_tool_catalog` 表）。  
5. DDL 以实现权威在**本文 §4**；AGENT_CONFIG §10 为摘要。  
6. `enabled=false` = Registry 不可见 + children 过滤；无物理删除 API。  
7. 自定义 Agent 时对外 `intent` 透传 `targetAgent` code。  
8. RAG 用 `capabilities.enableRag`，不做成 Catalog Tool。

---

## 15. 文档与代码对照

| 文档 | 职责 |
|------|------|
| **本文** | Admin **如何在本仓库落地**（包、DDL、接入点、排期、测试） |
| AGENT_CONFIG_DESIGN | 产品/领域模型、管理页字段、校验规则 |
| API_DESIGN §11 | HTTP 契约 |
| SUPERVISOR_SUBAGENT_DESIGN | 固定 Agent 行为真源（Seed 应对齐） |

实现完成后：更新 [docs/README.md](./README.md) 实现状态；将本文标为「实现中/已落地」。

---

## 16. 建议开工顺序（任务拆解）

1. DDL + `AgtAgent` / `AgtAgentVersion` + Repository  
2. `AgentDefinition` Java record/DTO + Validator  
3. `AdminAgentController` CRUD + validate  
4. Publish/Rollback + `DefinitionRegistry` + Seed  
5. `ConfigurableAgentInvoker` + Orchestrator 开关接入  
6. `LocalToolCatalog` + 按 code 绑定  
7. Trial + Catalog API  
8. （可选）Admin 静态页  

---

## 17. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-08-07 | 首版：Admin 落地详细技术设计（包结构、DDL、Runtime 接入、排期） |
| v1.1 | 2026-08-07 | 统一服务命名；DDL 权威声明；enabled/删除/intent/RAG 定稿；成功标准分 A/B |
