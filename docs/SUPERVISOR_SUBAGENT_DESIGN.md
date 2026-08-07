# Supervisor / 子 Agent 接口与 Prompt 详细设计

> 文档版本：v1.2  
> 日期：2026-08-07  
> 依据：[TECHNICAL_DESIGN.md](../TECHNICAL_DESIGN.md) v2.0  
> 范围：纯 Spring AI · 主 Agent 路由 + 多子 Agent 专责  
> **实现状态：已实现**（固定五子 Agent + ConfirmResume；代码在 `apps/java-gateway`）。  
> 可配置演进：[AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md)（领域）· [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md)（落地，**未实现**）。

---

## 1. 设计目标

1. 明确 **Orchestrator ↔ Supervisor ↔ SubAgent** 的输入/输出契约。  
2. 固定各 Agent 的 **System Prompt**、结构化输出 schema、工具边界。  
3. 保证：**主 Agent 不写业务、子 Agent 不跨域调工具、Vision 最多二次路由一次**。  
4. Prompt 可版本化落盘，便于评测与热更新。

---

## 2. 组件与调用关系

```text
Chat API
  └─ AgentOrchestrator.handle(TurnRequest)
        ├─ (短路) ConfirmResumeExecutor          # PENDING_CONFIRM
        ├─ SupervisorAgent.route(SupervisorInput) → RouteDecision
        ├─ if needClarify → Clarifier（直接回复，不调子 Agent）
        ├─ SubAgentRegistry.get(target).handle(SubAgentRequest) → SubAgentResult
        │     ├─ KnowledgeAgent
        │     ├─ OrderAgent
        │     ├─ TicketAgent
        │     ├─ VisionAgent ──suggestedIntent──► Orchestrator 二次路由（≤1 次）
        │     └─ ChitchatAgent
        └─ PostProcessor → TurnResponse / SSE events
```

### 2.1 Java 接口规划（概念，非代码）

```text
interface SupervisorAgent {
  RouteDecision route(SupervisorInput input);
}

interface SubAgent {
  String name();                      # knowledge|order|ticket|vision|chitchat
  SubAgentResult handle(SubAgentRequest request);
}

interface AgentOrchestrator {
  TurnResponse chat(TurnRequest request);
  Flux<SseEvent> chatStream(TurnRequest request);
}
```

---

## 3. 公共 DTO 契约

### 3.1 TurnRequest（编排入口）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sessionId | string | 是* | 空则创建新会话 |
| userId | string | 是 | 业务用户 ID |
| text | string | 条件 | 无附件时必填 |
| attachmentIds | string[] | 否 | 已上传附件 |
| confirm | boolean | 否 | 是否确认待执行写操作 |
| confirmPayload | object | 否 | 可覆盖会话内 payload |
| locale | string | 否 | 默认 `zh-CN` |
| options | object | 否 | `enableRag` / `enableMcp` / `stream` |

### 3.2 MessageView（注入记忆用）

```json
{
  "role": "user|assistant|system|tool",
  "content": "……",
  "agentName": "order",
  "createdAt": "2026-07-30T04:00:00Z"
}
```

### 3.3 AttachmentView

```json
{
  "id": "att_xxx",
  "contentType": "image/jpeg",
  "path": "/abs/or/relative/path",
  "publicUrl": "/files/att_xxx.jpg"
}
```

### 3.4 Citation

```json
{
  "docId": "doc_xxx",
  "title": "退换货政策",
  "content": "片段原文……",
  "score": 0.82,
  "source": "vector|mcp",
  "metadata": {}
}
```

### 3.5 ToolCallRecord

```json
{
  "name": "query_order",
  "arguments": {"orderId": "ORD20260730001", "userId": "u_001"},
  "result": "{\"ok\":true,...}",
  "source": "local|mcp",
  "latencyMs": 120,
  "success": true
}
```

### 3.6 TurnResponse（对外）

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | string | 会话 ID |
| answer | string | 对用户最终话术 |
| intent | string | Supervisor 意图 |
| agentName | string | 实际应答子 Agent；澄清时可为 `supervisor` |
| confidence | number | 路由置信度 |
| citations | Citation[] | 知识引用 |
| toolCalls | ToolCallRecord[] | 本轮工具 |
| confirmRequired | boolean | 是否进入待确认 |
| confirmationPayload | object | 待确认载荷 |
| mode | string | 固定 `spring-ai-multi-agent` |

### 3.7 SSE 事件（与总设一致，补充 payload）

| event | data 关键字段 |
|-------|----------------|
| `intent` | `intent`, `targetAgent`, `confidence`, `reason` |
| `agent` | `agentName` |
| `retrieval` | `topK`, `hitCount` |
| `citation` | `citation` |
| `tool_start` | `name`, `arguments` |
| `tool_end` | `name`, `result`, `success` |
| `confirm_required` | `confirmationPayload`, `answer` |
| `token` | `text` |
| `final` | 同 TurnResponse 精简版 |
| `error` | `message`, `code` |

---

## 4. SupervisorAgent 详细设计

### 4.1 职责

- 意图分类、目标子 Agent 选择、槽位初提、是否澄清。  
- **禁止**：调用业务写工具、编造订单/政策事实、直接长篇回答业务问题（澄清问句除外）。

### 4.2 SupervisorInput

| 字段 | 类型 | 说明 |
|------|------|------|
| userId | string | |
| text | string | 本轮用户文本 |
| hasAttachments | boolean | 是否有图 |
| attachmentCount | int | |
| sessionStatus | `active` \| `pending_confirm` \| `closed` | |
| confirmationPayload | object\|null | 待确认内容摘要 |
| summary | string\|null | 会话摘要 |
| recentMessages | MessageView[] | 最近 N 轮（可截断每条长度） |
| lastIntent | string\|null | |
| lastAgent | string\|null | |
| locale | string | |

**注入给模型时的建议截断：**

- `recentMessages`：最多 6～8 条；单条 content ≤ 300 字。  
- `summary`：≤ 120 字。  
- 不向 Supervisor 注入完整图片二进制（只给 `hasAttachments=true`）。

### 4.3 RouteDecision（结构化输出，强制 JSON）

```json
{
  "intent": "knowledge",
  "confidence": 0.91,
  "targetAgent": "knowledge",
  "reason": "询问退货时效，属政策知识",
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

#### 4.3.1 枚举定义

**intent**

| 值 | 含义 |
|----|------|
| `knowledge` | 政策 / FAQ / 规则说明 |
| `order` | 查单、物流、取消、改址咨询（执行类） |
| `ticket` | 投诉、破损少件、要开工单、催人工 |
| `multimodal` | 本轮依赖图片理解 |
| `chitchat` | 寒暄、能力询问、无关闲聊 |
| `unclear` | 无法判断 |
| `confirm_resume` | 用户在确认/拒绝待执行操作（通常由编排器短路，Supervisor 可作兜底） |

**targetAgent**

| 值 | 对应 |
|----|------|
| `knowledge` | KnowledgeAgent |
| `order` | OrderAgent |
| `ticket` | TicketAgent |
| `vision` | VisionAgent |
| `chitchat` | ChitchatAgent |
| `none` | 不调用子 Agent（澄清 / 仅确认话术） |

**intent → targetAgent 默认映射**

| intent | targetAgent |
|--------|-------------|
| knowledge | knowledge |
| order | order |
| ticket | ticket |
| multimodal | vision |
| chitchat | chitchat |
| unclear | none |
| confirm_resume | none（编排器处理） |

#### 4.3.2 slots 约定

| 字段 | 说明 | 提取规则 |
|------|------|----------|
| orderId | 订单号 | 匹配 `ORD` + 数字；未出现则为 null，**禁止编造** |
| trackingNo | 运单号 | 匹配常见快递前缀+字母数字；禁止编造 |
| category | 工单分类建议 | `破损少件` / `物流异常` / `退换货` / `其他` / null |
| amountHint | 金额线索 | 仅当用户明文提到金额时填写字符串，否则 null |

### 4.4 编排器对 RouteDecision 的处理规则

```text
IF sessionStatus == pending_confirm AND (confirm==true OR 用户明确肯定):
    → ConfirmResumeExecutor（不依赖 Supervisor）
ELSE IF sessionStatus == pending_confirm AND 用户明确否定:
    → 清除 payload，回复「已取消操作」，END
ELSE:
    decision = Supervisor.route(...)
    IF decision.confidence < ROUTE_CONFIDENCE_THRESHOLD OR decision.needClarify:
        → 回复 clarifyQuestion（或默认澄清模板），agentName=supervisor，END
    ELSE IF hasAttachments AND decision.intent != multimodal:
        → 仍优先走 VisionAgent（安全默认），除非用户明确只要纯文本政策且与图无关
    ELSE:
        → 调用 targetAgent
```

默认阈值：`ROUTE_CONFIDENCE_THRESHOLD = 0.55`。

### 4.5 Supervisor System Prompt

**文件：** [`docs/prompts/supervisor_router.md`](./prompts/supervisor_router.md)（运行时：`resources/prompts/supervisor_router.md`）  
**版本：** `supervisor.v1`

完整正文以 Prompt 文件为准（勿在本文重复维护）。摘要：

- 唯一任务：意图 + `targetAgent`；不答业务细节、不编造单号/政策。  
- intent / targetAgent 枚举见 §4.3.1（含 `none`）。  
- 输出单一 JSON：`intent, confidence, targetAgent, reason, slots, needClarify, clarifyQuestion`。

### 4.6 Supervisor User Prompt 模板

**文件：** [`docs/prompts/supervisor_router_user.md`](./prompts/supervisor_router_user.md)

变量：`sessionStatus`、`confirmationPayloadSummary`、`summary`、`recentMessagesFormatted`、`userId`、`hasAttachments`、`attachmentCount`、`text`。
### 4.7 Supervisor 调用参数建议

| 项 | 建议 |
|----|------|
| temperature | 0～0.2 |
| 响应格式 | JSON Schema / `BeanOutputConverter` 强制结构化 |
| 工具 | **无** |
| 超时 | 5～8s；失败则 heuristic 兜底路由（关键词规则） |

### 4.8 Heuristic 兜底（模型失败时）

| 条件 | intent |
|------|--------|
| 有附件 | multimodal |
| 含「退货/退款/政策/运费/包邮」 | knowledge |
| 含「订单/取消/物流/运单/ORD」 | order |
| 含「工单/破损/少件/投诉/人工」 | ticket |
| 文本长度 &lt; 4 且无关键词 | unclear |
| 其他 | chitchat |

---

## 5. SubAgent 公共契约

### 5.1 SubAgentRequest

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | string | |
| userId | string | |
| text | string | 本轮用户文本（Vision 二次路由前可拼接图片理解摘要） |
| summary | string\|null | |
| recentMessages | MessageView[] | |
| attachments | AttachmentView[] | 仅 Vision 需要读文件；其他 Agent 可忽略 |
| route | RouteDecision | Supervisor 决策（含 slots） |
| options | object | enableRag / enableMcp 等 |
| locale | string | |

### 5.2 SubAgentResult

| 字段 | 类型 | 说明 |
|------|------|------|
| agentName | string | |
| answer | string | 对用户可见正文 |
| citations | Citation[] | 默认 [] |
| toolCalls | ToolCallRecord[] | 默认 [] |
| confirmRequired | boolean | |
| confirmationPayload | object\|null | 例：`{action, orderId, reason}` |
| suggestedIntent | string\|null | **仅 Vision** 可填，供二次路由 |
| suggestedSlots | object\|null | Vision 抽取的槽位 |
| meta | object | 模型名、耗时等 |

### 5.3 子 Agent 通用行为约束

1. 使用礼貌、简洁中文；先结论后细节。  
2. 工具失败时说明「暂时查不到」，引导重试或转人工，不编造。  
3. 不向用户暴露 JSON、内部报错栈、工具原始异常。  
4. 可引用 `route.slots`，但执行前仍应校验（如 orderId 格式）。

---

## 6. KnowledgeAgent

### 6.1 职责

政策 / FAQ / 规则类问答；基于检索（+ 可选 MCP）回答并给出 citations。

### 6.2 工具边界

| 工具 | 必选 | 说明 |
|------|------|------|
| VectorStore 检索（Advisor 或显式 retrieve） | 是 | 主路径 |
| MCP `search_docs` | 可选 | `enableMcp=true` |
| MCP `get_doc_chunk` | 可选 | 按需 |
| 任何写操作 / 订单工具 | **禁止** | |

### 6.3 内部步骤

```text
1. retrieve(text, topK, threshold) → chunks
2. 可选 mcp search_docs(text) → mcpHits（标注 source=mcp）
3. 组装上下文 → ChatClient 生成答案
4. 组装 citations 返回
```

低分或无命中：明确「知识库暂未找到依据」，可建议换表述或转人工（不调用 escalate，只文案引导；真转人工走 TicketAgent）。

### 6.4 System Prompt

**文件：** [`docs/prompts/agent_knowledge.md`](./prompts/agent_knowledge.md) · 版本 `knowledge.v1`  
正文以文件为准：仅据检索结果答政策；无依据则明示；不编造订单；输出中文话术。

### 6.5 User Prompt 模板

**文件：** [`docs/prompts/agent_knowledge_user.md`](./prompts/agent_knowledge_user.md)

`retrievedBlocks` 示例格式：

```text
[1] title=退换货政策 score=0.86
国内订单签收后 7 天内可无理由退货……
```

### 6.6 生成参数

| 项 | 建议 |
|----|------|
| temperature | 0.2 |
| tools | 无（检索在模型外完成）；或仅只读 MCP tools |
| 流式 | 支持 token 事件 |

---

## 7. OrderAgent

### 7.1 职责

查订单、查物流、取消订单（需确认）；禁止编造状态。

### 7.2 工具边界（仅此集合）

| Tool | 描述要点 | 关键参数 |
|------|----------|----------|
| `query_order` | 查订单 | `orderId`, `userId` |
| `query_logistics` | 查物流 | `trackingNo` |
| `cancel_order` | 取消未发货订单 | `orderId`, `userId`, `reason` |

**禁止：** `create_ticket`、`escalate_human`、MCP 写工具、知识库写入。

### 7.3 确认协议

当模型或编排逻辑判定需要执行 `cancel_order`：

1. **本轮不直接调用** `cancel_order`（或 Tool 层拦截写操作）。  
2. `SubAgentResult.confirmRequired=true`。  
3. `confirmationPayload`：

```json
{
  "action": "cancel_order",
  "orderId": "ORD20260730001",
  "userId": "u_001",
  "reason": "用户申请取消",
  "agentName": "order"
}
```

4. `answer` 向用户说明将执行的操作，请确认。  
5. 下一轮由 Orchestrator ConfirmResumeExecutor 调用真实 `cancel_order`。

查单 / 查物流：只读，可直接 Tool Calling。

### 7.4 System Prompt

**文件：** [`docs/prompts/agent_order.md`](./prompts/agent_order.md) · 版本 `order.v1`  
正文以文件为准：只读查单/物流；取消走确认；禁止编造状态。

### 7.5 开发者附加上下文（User 模板）

**文件：** [`docs/prompts/agent_order_user.md`](./prompts/agent_order_user.md)  
注入 `userId`、`slotsJson` 及会话上下文。

### 7.6 Tool 描述文案（供模型理解）

| name | description（建议） |
|------|---------------------|
| query_order | 根据订单号查询当前用户的订单状态与基本信息 |
| query_logistics | 根据运单号查询物流轨迹 |
| cancel_order | 取消当前用户名下「待发货」订单；执行前必须已获用户确认 |

### 7.7 生成参数

| 项 | 建议 |
|----|------|
| temperature | 0.1～0.2 |
| tools | 仅订单域 3 个 |
| max tool rounds | 3 |

---

## 8. TicketAgent

### 8.1 职责

创建售后工单、转人工；处理破损/投诉等。

### 8.2 工具边界

| Tool | 说明 |
|------|------|
| `create_ticket` | `userId, category, description, priority` |
| `escalate_human` | `userId, reason` |

**禁止：** 订单取消、物流查询（可文案建议用户去问订单，或依赖历史中已有订单信息写入 description）。

### 8.3 System Prompt

**文件：** [`docs/prompts/agent_ticket.md`](./prompts/agent_ticket.md) · 版本 `ticket.v1`  
正文以文件为准：开工单/转人工；禁止谎称已登记。

User 模板：[`docs/prompts/agent_ticket_user.md`](./prompts/agent_ticket_user.md)。

### 8.4 与 Vision 的衔接

若 `text` 中已包含 `[图片理解]...` 摘要，将其写入 `create_ticket.description`，无需用户重复描述。

---

## 9. VisionAgent

### 9.1 职责

理解图片 + 用户文本，产出场景摘要与 `suggestedIntent`；**不直接执行写工具**（Demo 约定）。

### 9.2 输入

- `attachments` 至少 1 个；读取本地文件或 URL 构建多模态消息。  
- 无附件：返回错误结果，建议 Orchestrator 改走 unclear。

### 9.3 Vision 结构化输出（统一 Schema）

与实现（`VisionAgent` 解析）及 Prompt 一致：**扁平 JSON**，不要嵌套 `visionFacts`。

模型输出形态：

1. 先给用户一段中文说明（`answer`）。  
2. 再跟一个 JSON 块：

```json
{
  "suggestedIntent": "ticket",
  "suggestedSlots": {
    "orderId": null,
    "trackingNo": null,
    "category": "破损少件"
  },
  "scene": "包裹外箱破损，内物疑似耳机"
}
```

| 字段 | 落点 |
|------|------|
| 正文（JSON 前） | `SubAgentResult.answer` |
| `suggestedIntent` | `SubAgentResult.suggestedIntent` |
| `suggestedSlots` | `SubAgentResult.suggestedSlots` |
| `scene` | `SubAgentResult.meta` / visionSummary（二次路由拼接用） |

允许的 `suggestedIntent`：`order` | `ticket` | `knowledge` | `unclear`。

### 9.4 Orchestrator 二次路由规则

```text
VisionAgent 返回后：
  IF suggestedIntent in {order, ticket, knowledge}:
      构造新 SubAgentRequest：
        text = 原 text + "\n[图片理解]" + visionSummary
        route.slots 合并 suggestedSlots
      调用对应 SubAgent（本轮仅允许这一次二次路由）
      最终 answer 以第二次子 Agent 为主；可把视觉一句话并入
  ELSE:
      直接返回 Vision 的 answer / 澄清
```

### 9.5 System Prompt

**文件：** [`docs/prompts/agent_vision.md`](./prompts/agent_vision.md) · 版本 `vision.v1`  
正文以文件为准；JSON 字段必须与 §9.3 一致（`suggestedIntent` / `suggestedSlots` / `scene`）。

> 实现也可「两次调用」更稳：第一次纯视觉描述；第二次结构化路由。Demo 为一次输出后截取 JSON。

### 9.6 生成参数

| 项 | 建议 |
|----|------|
| model | 默认与 `LLM_MODEL` 相同；可选覆盖 `VISION_MODEL`（见配置清单；未设则回落主模型） |
| tools | 无 |
| temperature | 0.2 |

---

## 10. ChitchatAgent

### 10.1 职责

寒暄与能力说明；引导到可办理事项；无工具。

### 10.2 System Prompt

**文件：** [`docs/prompts/agent_chitchat.md`](./prompts/agent_chitchat.md) · 版本 `chitchat.v1`  
正文以文件为准：短寒暄、能力引导、无工具。

---

## 11. ConfirmResumeExecutor（编排短路，非 LLM Agent）

### 11.1 触发条件

仅当 **`session.status == pending_confirm`** 时进入确认短路逻辑：

- `confirm == true`，或用户文本匹配肯定语（「确认」「是的」「同意」等），且未匹配否定语 → 执行 ConfirmResumeExecutor。  
- 匹配否定语 → 不执行工具，清空确认态（§11.3）。  
- **非** `pending_confirm` 时：用户说「取消订单 …」走正常 Supervisor → OrderAgent，**不得**当作放弃确认。

### 11.2 行为

1. 读取 `confirmation_payload`。  
2. `action` 分发：

| action | 调用 |
|--------|------|
| `cancel_order` | OrderTools.cancel_order(...) |

3. 成功/失败生成固定模板中文回复。  
4. 清空 payload，`status=active`。  
5. 写审计日志；`agentName=confirm_executor`。

### 11.3 否定语（仅 pending_confirm）——与现网代码对齐

**当前实现**（`AgentOrchestrator.isNegative`，以代码为准）：

```text
IF pending_confirm:
  IF confirm==true OR 肯定语（确认/是的/同意/好/好的）→ 执行 ConfirmResume
  ELSE IF 否定:
       「不要了」|「算了」|「拒绝」
       OR （含「取消」且 不含「取消订单」且 不含「ORD」）
     → 放弃确认，清空 payload，回复「已取消该操作…」
  ELSE → 不走确认短路，继续正常 Supervisor 路由（可能仍带 pending 状态，实现可保持至下一轮肯定/否定）
```

说明：

- **非** `pending_confirm` 时，用户说「取消订单 ORD…」走 OrderAgent，不会被当成放弃确认。  
- 「取消」单独出现在待确认态 → 放弃；「取消订单」/含 `ORD` → **不**当作否定（避免与业务取消意图混淆）。  
- ~~更复杂的「含订单则先澄清」~~ 不作本期目标；若需增强，另开版本，勿与现网行为 silently 偏离。
---

## 12. Prompt 资产目录与版本管理

```text
docs/prompts/                          # 设计源（与 resources 双份同步，见 docs/README.md）
├── supervisor_router.md               # supervisor.v1 system
├── supervisor_router_user.md          # supervisor user 模板
├── agent_knowledge.md                 # knowledge.v1
├── agent_knowledge_user.md
├── agent_order.md                     # order.v1
├── agent_order_user.md
├── agent_ticket.md                    # ticket.v1
├── agent_ticket_user.md
├── agent_vision.md                    # vision.v1
├── agent_chitchat.md                  # chitchat.v1
└── summarize_session.md               # summary.v1
```

运行时加载路径：`apps/java-gateway/src/main/resources/prompts/`（**以能被应用加载的副本为准**）。

- **System / summarize**：已被 `PromptLoader` 加载。  
- **`*_user.md`**：仅 Seed/设计用，**当前代码未加载**；Admin 落地后写入 `userPromptTemplate` 再生效。详见 [docs/README.md](./README.md)。

### 12.1 会话摘要 Prompt

**文件：** [`docs/prompts/summarize_session.md`](./prompts/summarize_session.md)

正文以文件为准：≤120 字中文摘要；保留诉求/单号/待确认/未决；不发明事实。

### 12.2 版本策略

- 每个文件首行注释：`<!-- prompt-id: xxx version: v1 -->`  
- 配置项：`app.prompts.version=v1`（可选）  
- 变更走评测集回归（尤其 `route_cases.jsonl`）  
- **禁止**仅在本文内联粘贴全文；改 Prompt 只改 `docs/prompts` + `resources/prompts`。

---

## 13. 工具 Schema 汇总（Function Call）

### 13.1 query_order

```json
{
  "name": "query_order",
  "parameters": {
    "type": "object",
    "properties": {
      "orderId": {"type": "string", "description": "订单号，如 ORD20260730001"},
      "userId": {"type": "string", "description": "当前用户 ID"}
    },
    "required": ["orderId", "userId"]
  }
}
```

### 13.2 cancel_order

```json
{
  "name": "cancel_order",
  "parameters": {
    "type": "object",
    "properties": {
      "orderId": {"type": "string"},
      "userId": {"type": "string"},
      "reason": {"type": "string"}
    },
    "required": ["orderId", "userId", "reason"]
  }
}
```

### 13.3 query_logistics

```json
{
  "name": "query_logistics",
  "parameters": {
    "type": "object",
    "properties": {
      "trackingNo": {"type": "string"}
    },
    "required": ["trackingNo"]
  }
}
```

### 13.4 create_ticket

```json
{
  "name": "create_ticket",
  "parameters": {
    "type": "object",
    "properties": {
      "userId": {"type": "string"},
      "category": {"type": "string"},
      "description": {"type": "string"},
      "priority": {"type": "string", "enum": ["P1", "P2", "P3"]}
    },
    "required": ["userId", "category", "description"]
  }
}
```

### 13.5 escalate_human

```json
{
  "name": "escalate_human",
  "parameters": {
    "type": "object",
    "properties": {
      "userId": {"type": "string"},
      "reason": {"type": "string"}
    },
    "required": ["userId", "reason"]
  }
}
```

---

## 14. 端到端示例（契约级）

### 例 A：知识问答

1. User:「国内订单退货几天？」  
2. Supervisor → `{intent:knowledge, targetAgent:knowledge, confidence:0.93}`  
3. KnowledgeAgent 检索 + 回答 + citations  
4. SSE: intent → agent → citation* → token* → final  

### 例 B：取消订单确认

1. User:「取消 ORD20260730001」  
2. Supervisor → order  
3. OrderAgent → `confirmRequired=true`, payload.action=cancel_order  
4. User:「确认」  
5. ConfirmResumeExecutor 调 cancel_order → 成功话术  

### 例 C：破损图售后

1. User 上传图 +「帮我售后」  
2. Supervisor → multimodal / vision  
3. Vision → suggestedIntent=ticket, category=破损少件  
4. TicketAgent.create_ticket → 返回 ticketId  

---

## 15. 测试要点（接口/Prompt 验收）

| 编号 | 场景 | 期望 |
|------|------|------|
| T1 | 纯政策问句 | 路由 knowledge；有 citation 或明确无依据 |
| T2 | 无单号查单 | order 追问单号，不编造 |
| T3 | 取消未确认 | 不落库已取消；pending_confirm |
| T4 | 确认后取消 | 工具成功；状态回 active |
| T5 | Knowledge 无订单工具 | 工具审计中不出现 query_order |
| T6 | 有图破损 | vision → ticket；description 含图片理解 |
| T7 | Supervisor JSON 损坏 | heuristic 兜底，不 500 |
| T8 | 低置信度 | needClarify，不进子 Agent |

---

## 16. 与总设文档关系

| 本文 | TECHNICAL_DESIGN v2.0 |
|------|------------------------|
| 细化 Agent 契约与 Prompt | §4 主/子 Agent、§7 Prompt |
| 工具 schema | §5.3 Function Call |
| 确认执行器 | §5.1.3 确认状态机 |
| SSE | §6.2 |

**下一步可选：** 可配置 Agent（[AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md)）或实现映射清单。

---

### 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-30 | 首版：Supervisor/子 Agent 接口与 Prompt 详细设计 |
| v1.1 | 2026-08-07 | 标明已实现；Prompt 改引用不内联；Vision schema 统一；确认否定规则；User 模板落盘 |
| v1.2 | 2026-08-07 | 确认否定与代码对齐；链 ADMIN 落地文 |