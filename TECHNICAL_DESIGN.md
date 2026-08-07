# Spring AI 智能客服 Demo — 技术设计文档

> 文档版本：v2.0.1  
> 更新日期：2026-08-07  
> 状态：设计稿 + **P0 已落地**（`apps/java-gateway` 固定多 Agent）；可配置 Admin 见 AGENT_CONFIG（未实现）  
> 选型结论：**纯 Spring AI 单栈**（不再以 LangChain / LangGraph 为主路径）

---

## 1. 文档目标与范围

### 1.1 目标

设计一套可落地的**智能客服 Demo**，基于 **Spring Boot + Spring AI**，验证以下能力在同一业务闭环中的协同：

| 能力 | 说明 |
|------|------|
| 主 Agent + 多子 Agent | 主 Agent 识别意图并路由；子 Agent 专责具体业务 |
| RAG | 基于企业知识库的检索增强生成 |
| Function Call | 大模型调用业务工具（查订单、取消、物流、开工单等） |
| MCP | 通过 Model Context Protocol 接入外部工具/资源 |
| 多模态 | 文本 + 图片（凭证、截图、破损图）理解与回复 |
| 多轮会话 | 会话记忆、上下文压缩、意图连续性 |
| 向量库 | 知识切片嵌入、相似度检索、元数据过滤 |
| 轻量编排 | Java 侧路由 / 确认状态机（非 LangGraph） |

### 1.2 非目标（本期 Demo 不做）

- 引入 Python LangChain / LangGraph 运行时
- 生产级高可用多活、灰度发布、全链路合规审计
- 完整 CRM/工单系统替换（仅 Mock / 轻量适配）
- 语音实时通话、视频客服
- 自主训练/微调基座模型
- 深度嵌套多 Agent 谈判、跨会话长任务官方 Checkpoint 运行时（可作为后续演进）

### 1.3 成功标准

1. 用户可通过 Web/API 发起多轮对话，并获得流式回复。
2. 主 Agent 能稳定输出意图，并路由到正确子 Agent（可观测）。
3. 知识库问答命中率可观测（检索 Top-K、引用片段可回显）。
4. 订单类意图能触发 Function Call，返回结构化业务结果。
5. 至少接入 1 个 MCP Server（工具或资源）。
6. 上传图片后，模型能结合图文完成理解与回答。
7. 会话可跨请求保持上下文，支持清空/切换会话；写操作支持二次确认。

### 1.4 为何改为纯 Spring AI

| 考量 | 结论 |
|------|------|
| 能力重叠 | Spring AI 已覆盖 Chat / RAG / Tool / MCP / Memory / Vision，与 LangChain 高度重叠 |
| 团队与落地 | 智能客服常嵌在 Java 业务系统中，单栈运维、鉴权、事务更简单 |
| 多 Agent 需求 | 「主路由 + 多专责子 Agent」可用多个 `ChatClient` + Java 路由器实现，不必上 LangGraph |
| LangGraph 保留场景 | 仅当需要官方级断点恢复、深图嵌套、复杂 HITL 运行时时再评估引入 |

---

## 2. 总体架构

### 2.1 技术选型原则

本 Demo 采用 **「Spring Boot 3 + Spring AI 单应用」** 架构：

| 层次 | 技术 | 职责 |
|------|------|------|
| API / 会话 / 附件 / 知识入库 | Spring Boot Web + JPA | REST/SSE、鉴权、持久化、上传 |
| Agent 编排 | **Spring AI ChatClient × N** | 主 Agent 路由 + 子 Agent 专责执行 |
| 工具 | Spring AI `@Tool` / ToolCallbacks | Function Call；按子 Agent 隔离工具集 |
| MCP | Spring AI MCP Client | 连接外部 MCP Server |
| 向量检索 | Spring AI VectorStore（默认 PGVector） | Embedding 存储与相似度检索 |
| LLM | OpenAI 兼容 API | Chat / Vision / Embedding / Tool Calling |

### 2.2 逻辑架构图

```text
┌─────────────────────────────────────────────────────────────────┐
│                     Client (Web / API)                           │
│           文本对话 │ 图片上传 │ SSE 流式 │ 会话切换               │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS / SSE
┌────────────────────────────▼────────────────────────────────────┐
│              Spring Boot + Spring AI（单进程）                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────────┐ │
│  │ Auth     │ │ Session  │ │ Attachment│ │ Knowledge Ingest    │ │
│  │ 限流     │ │ Memory   │ │ Upload    │ │ Chunk + Embed       │ │
│  └──────────┘ └──────────┘ └──────────┘ └─────────────────────┘ │
│                                                                  │
│  ┌──────────────────── Agent Orchestrator ─────────────────────┐ │
│  │  SupervisorAgent（主 Agent：意图识别 / 路由 / 澄清）         │ │
│  │       │                                                      │ │
│  │       ├─ KnowledgeAgent   RAG +（可选）MCP search             │ │
│  │       ├─ OrderAgent       query/cancel/logistics Tools        │ │
│  │       ├─ TicketAgent      create_ticket / escalate Tools      │ │
│  │       ├─ VisionAgent      多模态理解 → 再路由或开工单         │ │
│  │       └─ ChitchatAgent    闲聊 / 能力引导                     │ │
│  └──────────────────────────────────────────────────────────────┘ │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────────┐ │
│  │ Tools    │ │ MCP Client│ │ Confirm  │ │ Observability       │ │
│  │ @Tool    │ │           │ │ State    │ │ Trace/Log/Metric    │ │
│  └────┬─────┘ └────┬─────┘ └──────────┘ └─────────────────────┘ │
└───────┼────────────┼────────────────────────────────────────────┘
        │            │
        ▼            ▼
┌──────────────┐  ┌────────────────────────┐
│ Business Mock│  │ MCP Servers            │
│ Order/Ticket │  │ knowledge-mcp 等        │
└──────────────┘  └────────────────────────┘
        │
        ▼
┌───────────────────┐  ┌────────────────────────┐
│ Vector Store      │  │ PostgreSQL             │
│ (PGVector 默认)   │  │ 会话/消息/知识元数据    │
└───────────────────┘  └────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│              LLM Provider (OpenAI-Compatible)                    │
│     Chat │ Vision │ Embedding │ Function/Tool Calling            │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 部署拓扑（Demo）

```text
docker-compose（规划）
├── spring-ai-app              :8080   # 唯一应用进程
├── postgres(+pgvector)        :5432
├── mcp-knowledge-server       :3100
└── minio / 本地 uploads（可选）
```

本地开发可单进程启动（`local` profile：H2 + SimpleVectorStore），不强制容器化。

---

## 3. 仓库与模块划分（设计视角）

> 说明：P0 实现已落地于 `apps/java-gateway`（固定多 Agent）；可配置 Admin 见 AGENT_CONFIG_DESIGN，尚未实现。

建议目标结构：

```text
springai/
├── TECHNICAL_DESIGN.md          # 本文档
├── docs/
│   ├── prompts/                 # 主/子 Agent 提示词（设计源）
│   ├── ADMIN_AGENT_TECHNICAL_DESIGN.md
│   └── …
├── docker-compose.yml
├── apps/
│   └── java-gateway/            # Spring Boot + Spring AI（含 static UI）
├── mcp-servers/
│   └── knowledge-mcp/           # Demo MCP Server
└── samples/
    └── knowledge/
```

### 3.1 应用包结构（规划）

```text
com.demo.cs
├── api                 # Controller：chat、session、knowledge、upload、debug
├── domain              # Session、Message、Citation、ToolCall、AgentRoute
├── application
│   ├── orchestrator    # AgentOrchestrator（总控）
│   ├── session         # 会话与记忆
│   ├── knowledge       # 入库 / 检索
│   └── attachment      # 多模态附件
├── agent
│   ├── supervisor      # 主 Agent：意图识别
│   ├── knowledge       # 知识问答子 Agent
│   ├── order           # 订单履约子 Agent
│   ├── ticket          # 售后工单子 Agent
│   ├── vision          # 多模态子 Agent
│   └── chitchat        # 闲聊子 Agent
├── infrastructure
│   ├── vector          # VectorStore（含 local Simple）
│   ├── mcp             # MCP Client 桥
│   ├── tools           # @Tool 实现（OrderTicketTools 等）
│   └── persistence     # JPA Repository
└── config              # AppProperties、AiConfig、WebConfig / PromptLoader
```

### 3.2 职责边界

| 能力 | 主责 | 说明 |
|------|------|------|
| HTTP API、鉴权、会话 | Spring Boot | 唯一对外入口 |
| 意图识别与路由 | SupervisorAgent | 输出结构化 Intent |
| 政策 / FAQ | KnowledgeAgent | RAG Advisor + 可选 MCP |
| 订单 / 物流 / 取消 | OrderAgent | 仅挂载订单域 Tools |
| 工单 / 转人工 | TicketAgent | 仅挂载工单域 Tools |
| 图片理解 | VisionAgent | Vision 模型；可回抛二次路由 |
| 知识入库与向量 | KnowledgeService + VectorStore | 统一数据面，避免双写 |
| MCP | Spring AI MCP Client | Host 在本应用内 |
| 写操作确认 | ConfirmState（会话字段） | 非图 Checkpoint，业务状态机 |

---

## 4. 主 Agent + 多子 Agent 设计（核心）

### 4.1 角色定义

| Agent | 类型 | 输入 | 输出 | 可用能力 |
|-------|------|------|------|----------|
| **SupervisorAgent** | 主 Agent | 用户话术 + 摘要 + 是否有附件 | 结构化 `RouteDecision` | 一般**不**直接调业务写工具 |
| KnowledgeAgent | 子 Agent | 用户问题 + 历史 | 带 citations 的回答 | VectorStore 检索、MCP `search_docs`（只读） |
| OrderAgent | 子 Agent | 订单相关诉求 | 工具结果转述 | `query_order` / `query_logistics` / `cancel_order` |
| TicketAgent | 子 Agent | 售后诉求 | 工单结果 | `create_ticket` / `escalate_human` |
| VisionAgent | 子 Agent | 文本 + 图片 | 场景摘要 + 建议 intent | Vision；可建议转 Order/Ticket |
| ChitchatAgent | 子 Agent | 闲聊 | 简短引导 | 无业务写工具 |

### 4.2 路由决策结构（Supervisor 输出）

```json
{
  "intent": "knowledge | order | ticket | multimodal | chitchat | unclear | confirm_resume",
  "confidence": 0.0,
  "targetAgent": "knowledge | order | ticket | vision | chitchat | none",
  "reason": "用户在询问退货时效",
  "slots": {
    "orderId": null,
    "trackingNo": null,
    "category": null,
    "amountHint": null
  },
  "needClarify": false,
  "clarifyQuestion": null
}
```

约束：

1. Supervisor **只做分类与槽位初提**，不编造订单状态。  
2. `confidence < 阈值` 或 `needClarify=true` 或 `targetAgent=none` → 直接澄清，不进入子 Agent。  
3. 若会话处于 `PENDING_CONFIRM`，优先确认短路（肯定执行 / 否定放弃）；详见 SUPERVISOR_SUBAGENT_DESIGN §11。  
4. 有附件时默认优先 `multimodal` → VisionAgent，再由编排器二次路由。

### 4.3 编排时序

```text
请求进入 AgentOrchestrator
  1) 加载 Session + 窗口记忆 + summary
  2) 若 PENDING_CONFIRM 且用户确认 → 执行确认载荷（工具）→ 回复 → END
  3) SupervisorAgent.route(...) → RouteDecision
  4) 按 targetAgent 调用对应 SubAgent
  5) SubAgent 内部：
       - Knowledge: retrieve → generate（可带 MCP）
       - Order/Ticket: ChatClient + 域内 Tools（模型 Function Call）
       - Vision: 理解图片 → 返回建议 intent → Orchestrator 可再调 Order/Ticket
  6) post_process：引用整理、审计字段、落库、可选滚动摘要
  7) SSE 推送事件后结束
```

### 4.4 子 Agent 隔离原则（重要）

1. **工具隔离**：OrderAgent 看不到 `create_ticket` 以外的无关工具（按域注册），降低误调。  
2. **提示词隔离**：每个子 Agent 独立 system prompt。  
3. **记忆共享**：同一 `session_id` 的短期消息共享；子 Agent 不维护独立长期记忆（Demo）。  
4. **一次主路由**：默认每轮 Supervisor 路由一次；Vision 允许 **一次** 二次路由。避免子 Agent 无限互调。  
5. **失败降级**：子 Agent 失败 → 礼貌致歉 + 可选 `escalate_human`。

### 4.5 与 LangGraph 多 Agent 的对应关系（概念映射）

| LangGraph 概念 | Spring AI 设计对应 |
|----------------|-------------------|
| Supervisor 节点 | SupervisorAgent（ChatClient + 结构化输出） |
| 子图 / 子 Agent | 独立 ChatClient Bean + 专属 Tools |
| 条件边 | Java `switch(intent)` / Strategy |
| interrupt / resume | `session.status=PENDING_CONFIRM` + `confirmation_payload` |
| checkpoint | 会话表 + 消息表（业务级，非图运行时） |
| 流式事件 | SSE event：intent / token / tool_* / citation / final |

---

## 5. 核心能力设计

### 5.1 多轮会话（Conversation Memory）

#### 5.1.1 会话模型

```text
Session
├── session_id
├── user_id
├── channel
├── status            # active | pending_confirm | closed
├── summary
├── last_intent
├── last_agent
├── confirmation_payload   # JSON，待确认写操作
├── created_at / updated_at
└── messages[]
    ├── role / content
    ├── citations[] / tool_calls[] / attachments[]
    └── agent_name           # 本轮实际应答的子 Agent
```

#### 5.1.2 记忆策略（三层）

1. **短期窗口**：最近 N 轮（默认 8～12），注入各 Agent。  
2. **会话摘要**：超窗后由小模型/主模型生成 `summary`。  
3. **长期画像（可选）**：独立 profile，不与知识库向量混检。

#### 5.1.3 确认状态机（替代 Graph interrupt）

```text
ACTIVE
  --(OrderAgent 判定需取消/退款等)--> PENDING_CONFIRM
  --(用户 confirm=true 或口令确认)--> 执行工具 --> ACTIVE
  --(用户取消确认)--> ACTIVE（不执行）
```

#### 5.1.4 会话 API（草案）

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/sessions` | 创建会话 |
| GET | `/api/v1/sessions/{id}` | 会话详情 + 消息 |
| DELETE | `/api/v1/sessions/{id}` | 关闭/清空 |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat/stream` | SSE 流式对话 |

---

### 5.2 RAG

#### 5.2.1 知识处理流水线

```text
文档上传 → 解析 → 清洗 → 分块 → Embedding → VectorStore + 元数据落库
```

- 分块：`chunk_size≈500~800 tokens`，`overlap≈80~120`。  
- 元数据：`doc_id`、`title`、`source`、`section`、`locale`、`updated_at`。

#### 5.2.2 检索与回答

- Top-K + 相似度阈值；可选元数据过滤。  
- KnowledgeAgent Prompt：**仅依据检索片段**；不足则明确无依据。  
- 响应返回 `citations`。  
- 实现上优先 Spring AI `QuestionAnswerAdvisor` / 等价检索 Advisor。

#### 5.2.3 在多 Agent 中的位置

```text
Supervisor → intent=knowledge → KnowledgeAgent
                              → retrieve → answer_with_citations
```

---

### 5.3 Function Call

#### 5.3.1 工具清单与归属

| Tool | 归属子 Agent | 说明 |
|------|--------------|------|
| `query_order` | OrderAgent | 查订单 |
| `cancel_order` | OrderAgent | 取消（需确认） |
| `query_logistics` | OrderAgent | 物流轨迹 |
| `create_ticket` | TicketAgent | 创建工单 |
| `escalate_human` | TicketAgent | 转人工 |
| `search_knowledge` | KnowledgeAgent（可选） | 显式检索 |
| MCP `search_docs` | KnowledgeAgent | 协议化知识工具 |

#### 5.3.2 安全规则

1. 写操作进入 `PENDING_CONFIRM`。  
2. 工具必须带 `user_id`，禁止越权。  
3. 每次调用审计落库。  
4. 单工具超时降级。

#### 5.3.3 实现方式（Spring AI）

- 域内 `@Tool` 方法 + 对应子 Agent 的 `ChatClient.prompt().tools(...)`。  
- 不在 Supervisor 上挂载写工具，避免主路由阶段误执行。

---

### 5.4 MCP

#### 5.4.1 角色

```text
MCP Host/Client ← 本应用 Spring AI MCP Client
MCP Server      ← knowledge-mcp（独立进程）
```

#### 5.4.2 Demo Server

1. **knowledge-mcp**：`search_docs`、`get_doc_chunk`  
2. 可选 filesystem-mcp（只读样例目录）

#### 5.4.3 集成

1. 启动连接 MCP（SSE / stdio）。  
2. tools/list → 转为 Spring AI ToolCallbacks。  
3. **仅注入 KnowledgeAgent**（默认只读）。  
4. UI/日志标注来源：`local` / `mcp`。

#### 5.4.4 配置概念

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        servers:
          knowledge:
            transport: sse
            url: http://localhost:3100/sse
```

---

### 5.5 多模态

#### 5.5.1 范围

- 输入：文本 + 图片（JPG/PNG/WebP，≤5MB）。  
- 输出：文本（一期不做图生图）。

#### 5.5.2 流程

```text
上传 → attachment_id
  → Supervisor 发现附件 → VisionAgent
  → 产出 {scene, entities, suggestedIntent}
  → Orchestrator 二次路由到 Order/Ticket/Knowledge
```

#### 5.5.3 安全

- 隐私脱敏；Demo 附件保留期 7 天。

---

### 5.6 向量库

| 方案 | 说明 |
|------|------|
| **PGVector（默认）** | 与 Postgres 一体，Spring AI 支持好 |
| SimpleVectorStore | `local` profile 无 Docker 时使用 |
| Milvus | 数据量上来后再评估 |

- Embedding 模型锁定；维度与 `dimensions` 配置一致。  
- 文档更新：元数据事务 + 向量 upsert；删除级联 chunk。

---

## 6. Orchestrator 状态与事件

### 6.1 运行态（每轮请求内存对象）

```text
TurnContext
├── session_id / user_id
├── input_text / attachments
├── route: RouteDecision
├── agent_name
├── retrieved_chunks[] / citations[]
├── tool_results[]
├── needs_confirmation / confirmation_payload
├── final_answer
└── error
```

### 6.2 SSE 事件

| event | 含义 |
|-------|------|
| `intent` | Supervisor 路由结果 |
| `agent` | 当前子 Agent 名称 |
| `retrieval` | 检索摘要 |
| `tool_start` / `tool_end` | 工具调用 |
| `citation` | 知识引用 |
| `confirm_required` | 需要用户确认 |
| `token` | 增量文本 |
| `final` | 结束包 |
| `error` | 错误 |

---

## 7. 模型与 Prompt 设计

### 7.1 模型分工

| 用途 | 建议 |
|------|------|
| Supervisor 意图 | 小/中模型 + **强制 JSON/结构化输出** |
| 子 Agent 对话 / Tool | 强 Tool Calling 模型 |
| Vision | 多模态模型 |
| Embedding | 独立 Embedding 模型（维度固定） |
| 会话摘要 | 可用较小模型 |

### 7.2 Prompt 资产

```text
docs/prompts/
├── supervisor_router.md
├── agent_knowledge.md
├── agent_order.md
├── agent_ticket.md
├── agent_vision.md
├── agent_chitchat.md
└── summarize_session.md
```

原则：专责、禁编造、槽位复用、工具优先于臆测。

---

## 8. 数据存储设计

### 8.1 业务表（草案）

- `cs_session`（含 `status`、`confirmation_payload`、`last_intent`、`last_agent`）
- `cs_message`（含 `agent_name`）
- `cs_attachment`
- `cs_tool_invocation`
- `cs_agent_route_log`（可选：记录每轮 Supervisor 决策，便于评测）
- `kb_document` / `kb_chunk_meta` / `kb_ingest_job`

### 8.2 缓存（可选 Redis）

- `cs:session:{id}:lock` — 同会话串行  
- `cs:rate:{user}` — 限流  
- `cs:mcp:tools:cache` — MCP 工具列表缓存  

Demo 可不引入 Redis，用 DB + 单实例即可。

---

## 9. API 设计总览

### 9.1 对外 API

| 分组 | 路径前缀 | 说明 |
|------|----------|------|
| 会话 | `/api/v1/sessions` | CRUD |
| 对话 | `/api/v1/chat`、`/chat/stream` | sync / SSE |
| 知识库 | `/api/v1/knowledge` | 入库、bootstrap、检索调试 |
| 附件 | `/api/v1/attachments` | 图片上传 |
| MCP | `/api/v1/mcp/tools` | 工具列表调试 |
| 健康 | `/actuator/health` | 探活 |
| 调试 | `/api/v1/debug/*` | 路由日志、工具审计、配置 |

### 9.2 鉴权（Demo）

- `X-API-Key` 或简易 JWT；本地 Web 可放宽。

---

## 10. 可观测性与评测

### 10.1 追踪字段

`trace_id` → Supervisor 决策 → 子 Agent → Tool/MCP → LLM。  
必记：`intent`、`targetAgent`、`confidence`、检索分、工具名、token、时延。

### 10.2 指标

- 路由准确率、子 Agent 调用分布  
- TTFT / E2E latency、Tool 成功率、MCP 成功率  
- RAG hit rate、转人工率、确认转化率  

### 10.3 评测集

```text
samples/eval/
├── route_cases.jsonl        # Supervisor 路由
├── rag_cases.jsonl
├── tool_cases.jsonl
├── multimodal_cases.jsonl
└── multiturn_cases.jsonl
```

---

## 11. 安全与合规（Demo 级）

1. 系统指令与用户内容隔离；工具参数校验。  
2. 秘钥不入库。  
3. PII 日志脱敏。  
4. 写工具白名单 + 确认态。  
5. MCP 默认只读、最小权限。  
6. Supervisor 不直接执行写操作。

---

## 12. 配置清单（概念）

| 变量 | 说明 | 默认建议 |
|------|------|----------|
| `LLM_BASE_URL` / `LLM_API_KEY` | 对话模型 | — |
| `SPRING_AI_OPENAI_API_KEY` / `SPRING_AI_OPENAI_BASE_URL` | Spring AI 绑定（可与 LLM_* 相同） | 回落到 LLM_* |
| `LLM_MODEL` | 主/子 Agent 模型 | `gpt-4o-mini` |
| `SUPERVISOR_MODEL` | 路由模型（可选更小） | 同主模型 |
| `VISION_MODEL` | 多模态（**可选**；未设则用 `LLM_MODEL`） | 同主模型 |
| `EMBEDDING_MODEL` | 嵌入 | `text-embedding-3-small` |
| `VECTOR_BACKEND` | `pgvector` / `simple` | `pgvector` |
| `MCP_ENABLED` | 是否启用 MCP | `true` |
| `SESSION_WINDOW_SIZE` | 短期记忆轮数 | `10` |
| `RAG_TOP_K` | 检索条数 | `5` |
| `RAG_SCORE_THRESHOLD` | 低分阈值 | `0.55` |
| `ROUTE_CONFIDENCE_THRESHOLD` | 低于则澄清 | `0.55` |

---

## 13. 实施里程碑（仅排期，本文不改代码）

### M0 — 设计冻结与工程收敛计划（0.5 天）

- 按 v2.0 确认模块边界；列出与旧混合架构差异清单。

### M1 — 会话 + 单 ChatClient（1 天）

- Session / SSE / 基础 UI。

### M2 — Supervisor 路由（1 天）

- 结构化 Intent；`cs_agent_route_log`；调试面板展示路由。

### M3 — KnowledgeAgent + RAG（1～2 天）

- 入库、检索、citations。

### M4 — OrderAgent / TicketAgent + Tools（1～2 天）

- 工具隔离、审计、确认态。

### M5 — MCP 挂到 KnowledgeAgent（1 天）

### M6 — VisionAgent + 二次路由（1 天）

### M7 — 评测与演示打磨（0.5～1 天）

---

## 14. 演示剧本（验收）

1. **路由**：问退货政策 → `knowledge`；问订单 → `order`；闲聊 → `chitchat`。  
2. **多轮 RAG**：追问「海外呢？」→ 仍走 KnowledgeAgent，上下文正确。  
3. **Tool**：查 `ORD...` → 取消需确认 → 再次发送确认后生效。  
4. **MCP**：知识问题可看到 MCP 工具调用痕迹。  
5. **多模态**：破损图 +「帮我售后」→ Vision → Ticket。  
6. **隔离**：订单问题不应误调无关写工具（抽测）。

---

## 15. 风险与已确认决策

### 15.1 风险

| 项 | 风险 | 应对 |
|----|------|------|
| 路由不准 | 子 Agent 选错 | 结构化输出 + 阈值澄清 + 路由评测集 |
| 子 Agent 误调工具 | 串域 | 工具集物理隔离 |
| 无官方 Graph Checkpoint | 长流程恢复弱 | 用会话确认态覆盖客服主场景；复杂长任务后续再评估 |
| MCP 实现差异 | 联调成本 | 先 SSE/HTTP 一个 Server |
| 模型差异 | Tool/Vision 不稳 | ModelRouter + 双配置 |

### 15.2 已确认 / 默认决策（v2.0）

| # | 议题 | 决策 |
|---|------|------|
| 1 | 技术栈 | **纯 Spring AI**，不引入 LangChain/LangGraph 主路径 |
| 2 | 编排模型 | **Supervisor + 多子 Agent** |
| 3 | 向量库 | **PGVector**（local 可用 Simple） |
| 4 | MCP | **Java Spring AI Client 为主**，挂 KnowledgeAgent |
| 5 | 前端 | **需要简易 Web UI** |
| 6 | LLM | OpenAI 兼容网关（具体厂商实现时配置） |

### 15.3 后续可选演进

- 若出现强需求：深分支、多轮子 Agent 协作、官方断点恢复 → 再评估 **BFF(Spring AI) + LangGraph** 或引入工作流引擎。  
- 本期不提前支付双运行时成本。

---

## 16. 附录

### A. 术语

| 术语 | 含义 |
|------|------|
| Supervisor / 主 Agent | 负责意图识别与路由的 Agent |
| Sub Agent / 子 Agent | 负责某一业务域的专责 Agent |
| RAG | 检索增强生成 |
| Function Call | 模型按 schema 调用外部函数 |
| MCP | Model Context Protocol |
| Citation | 回答所依据的知识片段引用 |
| PENDING_CONFIRM | 写操作待用户确认的会话状态 |

### B. 参考方向（实现阶段）

- Spring AI：ChatClient、Advisors、Tools、VectorStore、MCP Client、Chat Memory  
- MCP Specification：tools / resources / transports  

### C. 明确不再作为本期主依赖

- LangChain（Python）  
- LangGraph（Python）  
- Java ↔ Python Agent 内部协议（原 `/internal/agent/*`）

### D. 文档修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-30 | 首版：Spring AI + LangChain + LangGraph 混合架构 |
| v2.0 | 2026-07-30 | **改为纯 Spring AI**；主 Agent + 多子 Agent；确认态替代 Graph interrupt；不改代码 |
| v2.0.1 | 2026-08-07 | `targetAgent` 补 `none`；配置项与 docs 索引对齐 |
| v2.0.2 | 2026-08-07 | RouteDecision.slots 对齐 category/amountHint |

---

**配套详细设计**

- [docs/README.md](./docs/README.md) — 文档索引（现行 / 演进）  
- [docs/SUPERVISOR_SUBAGENT_DESIGN.md](./docs/SUPERVISOR_SUBAGENT_DESIGN.md) — Supervisor/子 Agent 接口与 Prompt（已实现）  
- [docs/API_DESIGN.md](./docs/API_DESIGN.md) — API 详细设计（业务 API 已实现；Admin 未实现）  
- [docs/AGENT_CONFIG_DESIGN.md](./docs/AGENT_CONFIG_DESIGN.md) — 可配置 Agent（未实现）  
- [docs/ADMIN_AGENT_TECHNICAL_DESIGN.md](./docs/ADMIN_AGENT_TECHNICAL_DESIGN.md) — Admin 后端落地（A0–A4 已实现）  
- [docs/ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./docs/ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md) — 管理前端 UI + 可配置联调（未实现）  

**下一步**：可选开启 `APP_AGENT_CONFIG_ENABLED=true` 做配置化对话联调；或推进阶段 B（配置主导、关闭 legacy 回落）。
