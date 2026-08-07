# API 详细设计

> 文档版本：v1.3  
> 日期：2026-08-07  
> 依据：[TECHNICAL_DESIGN.md](../TECHNICAL_DESIGN.md) v2.0、[SUPERVISOR_SUBAGENT_DESIGN.md](./SUPERVISOR_SUBAGENT_DESIGN.md)、[AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md)、[ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md)  
> 范围：对外 HTTP/SSE API + Admin 可配置 Agent API（纯 Spring AI）  
>
> **实现状态**  
> | 分组 | 状态 |  
> |------|------|  
> | 会话 / 对话 / SSE / 附件 / 知识库 / MCP 调试 / 调试 API / 健康检查 | **已实现**（`apps/java-gateway`） |  
> | Admin Agent / Catalog（§11） | **已实现**（见 [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md)；默认 `app.agent-config.enabled=false`） |

---

## 1. 总则

### 1.1 Base URL

| 环境 | Base URL |
|------|----------|
| 本地 | `http://localhost:8080` |
| Docker Demo | `http://localhost:8080` |

所有业务 API 前缀：`/api/v1`  
管理端 API 前缀：`/api/v1/admin`（需 publisher/editor 权限）

### 1.2 协议约定

| 项 | 约定 |
|----|------|
| 协议 | HTTP/1.1；流式对话使用 SSE（`text/event-stream`） |
| 编码 | UTF-8 |
| 请求体 | `application/json`（上传除外：`multipart/form-data`） |
| 时间 | ISO-8601 UTC，如 `2026-07-30T04:00:00Z` |
| ID 风格 | 前缀 + 短随机串，如 `s_` / `m_` / `att_` / `doc_` / `agt_` |
| 空值 | JSON `null`；数组空为 `[]`；对象空为 `{}` |

### 1.3 通用响应包络（建议）

非流式接口统一：

```json
{
  "code": 0,
  "message": "ok",
  "traceId": "tr_xxx",
  "data": {}
}
```

| code | 含义 |
|------|------|
| 0 | 成功 |
| 40001 | 参数错误 |
| 40101 | 未授权 |
| 40301 | 禁止访问（越权） |
| 40401 | 资源不存在 |
| 40901 | 状态冲突（如会话已关闭、确认态冲突、Agent 校验失败未发布） |
| 41301 | 上传过大 |
| 42201 | 配置校验失败（Admin 发布/validate） |
| 42901 | 限流 |
| 50001 | 内部错误 |
| 50002 | 上游 LLM 错误 |
| 50003 | 工具执行失败（可预期业务失败仍可用 code=0 + data 内 ok=false，见各接口） |

> Demo 也可对成功接口直接返回 `data` 本体（无包络）。**本文以带包络为规范**；示例中为可读性，成功时主要展示 `data`。

### 1.4 鉴权

| Header | 必填 | 说明 |
|--------|------|------|
| `X-API-Key` | 建议 | 与配置 `app.api-key` 比对；本地 Web Demo 可放宽 |
| `X-Trace-Id` | 否 | 客户端传入则透传；否则服务端生成 |
| `X-User-Id` | 否 | 可与 body.userId 双写校验；不一致则 403 |
| `X-Admin-Role` | Admin | Demo：`viewer` \| `editor` \| `publisher`；生产换 JWT roles |

### 1.5 限流（Demo）

| 维度 | 建议 |
|------|------|
| 按 userId | 30 次/分钟（chat） |
| 上传 | 10 次/分钟 |
| Admin 写操作 | 60 次/分钟 |
| 超限 | HTTP 429 + `code=42901` |

---

## 2. API 一览

| 分组 | Method | Path | 说明 |
|------|--------|------|------|
| 会话 | POST | `/api/v1/sessions` | 创建会话 |
| 会话 | GET | `/api/v1/sessions/{sessionId}` | 会话详情 |
| 会话 | GET | `/api/v1/sessions` | 按用户列会话 |
| 会话 | DELETE | `/api/v1/sessions/{sessionId}` | 关闭会话 |
| 对话 | POST | `/api/v1/chat` | 同步多轮对话 |
| 对话 | POST | `/api/v1/chat/stream` | SSE 流式对话 |
| 附件 | POST | `/api/v1/attachments` | 上传图片 |
| 附件 | GET | `/files/{fileName}` | 访问已上传文件（静态） |
| 知识库 | POST | `/api/v1/knowledge/ingest` | 文本入库 |
| 知识库 | POST | `/api/v1/knowledge/bootstrap` | 导入样例知识 |
| 知识库 | GET | `/api/v1/knowledge/docs` | 文档列表 |
| 知识库 | POST | `/api/v1/knowledge/search` | 检索调试 |
| MCP | GET | `/api/v1/mcp/tools` | MCP 工具列表 |
| MCP | POST | `/api/v1/mcp/call` | 调试调用 MCP（可选） |
| 调试 | GET | `/api/v1/debug/config` | 运行配置 |
| 调试 | GET | `/api/v1/debug/routes` | 路由日志 |
| 调试 | GET | `/api/v1/debug/tools` | 工具审计 |
| 调试 | GET | `/api/v1/debug/tickets` | Mock 工单列表 |
| 健康 | GET | `/actuator/health` | 探活 |
| Admin | GET/POST | `/api/v1/admin/agents` | Agent 列表 / 创建 |
| Admin | GET/PUT | `/api/v1/admin/agents/{code}` | Agent 详情 / 更新 draft |
| Admin | POST | `/api/v1/admin/agents/{code}/validate` | 校验 |
| Admin | POST | `/api/v1/admin/agents/{code}/publish` | 发布 |
| Admin | POST | `/api/v1/admin/agents/{code}/rollback` | 回滚 |
| Admin | GET | `/api/v1/admin/agents/{code}/versions` | 版本列表 |
| Admin | GET | `/api/v1/admin/agents/{code}/versions/{version}` | 某版 snapshot |
| Admin | POST | `/api/v1/admin/agents/{code}/trial` | 试运行 |
| Admin | GET | `/api/v1/admin/catalog/tools` | 本地工具目录 |
| Admin | GET | `/api/v1/admin/catalog/mcp-servers` | MCP 目录 |
| Admin | POST | `/api/v1/admin/catalog/mcp-servers/{id}/refresh` | 刷新 MCP tools |

---

## 3. 公共 Schema

### 3.1 SessionStatus

`active` | `pending_confirm` | `closed`

### 3.2 Intent

对外响应中的 `intent` 为 **string**（非严格枚举校验）。

**现行 Demo / Seed 常用值：**  
`knowledge` | `order` | `ticket` | `multimodal` | `chitchat` | `unclear` | `confirm_resume`

可配置 Agent 落地后：自定义子 Agent 时 `intent` **透传** `targetAgent` code（见 ADMIN §6.2.1）。客户端应按字符串处理。

### 3.3 AgentName

`supervisor` | `knowledge` | `order` | `ticket` | `vision` | `chitchat` | `confirm_executor`

### 3.4 Citation

```json
{
  "docId": "doc_abc",
  "title": "退换货政策",
  "content": "国内订单签收后 7 天内……",
  "score": 0.86,
  "source": "vector",
  "metadata": {
    "chunk_index": 0
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| docId | string | 文档 ID |
| title | string | 标题 |
| content | string | 片段 |
| score | number | 相似度或换算分，0～1 |
| source | string | `vector` \| `mcp` |
| metadata | object | 透传 |

### 3.5 ToolCallRecord

```json
{
  "name": "query_order",
  "arguments": {
    "orderId": "ORD20260730001",
    "userId": "u_001"
  },
  "result": "{\"ok\":true,\"order\":{...}}",
  "source": "local",
  "latencyMs": 42,
  "success": true
}
```

### 3.6 ConfirmationPayload

```json
{
  "action": "cancel_order",
  "orderId": "ORD20260730001",
  "userId": "u_001",
  "reason": "用户申请取消",
  "agentName": "order"
}
```

| action（本期） | 说明 |
|----------------|------|
| `cancel_order` | 确认后执行取消 |

### 3.7 Message

```json
{
  "id": "m_xxx",
  "role": "user",
  "content": "帮我查订单 ORD20260730001",
  "agentName": null,
  "citations": null,
  "toolCalls": null,
  "attachments": ["att_xxx"],
  "createdAt": "2026-07-30T04:00:00Z"
}
```

### 3.8 Session

```json
{
  "id": "s_xxx",
  "userId": "u_001",
  "channel": "web",
  "status": "active",
  "summary": null,
  "lastIntent": "order",
  "lastAgent": "order",
  "confirmationPayload": null,
  "createdAt": "2026-07-30T04:00:00Z",
  "updatedAt": "2026-07-30T04:01:00Z",
  "messages": []
}
```

### 3.9 ChatOptions

```json
{
  "enableRag": true,
  "enableMcp": true,
  "stream": false
}
```

---

## 4. 会话 API

### 4.1 创建会话

`POST /api/v1/sessions`

**Request**

```json
{
  "userId": "u_001",
  "channel": "web"
}
```

| 字段 | 必填 | 默认 | 说明 |
|------|------|------|------|
| userId | 否 | `u_001` | 用户 ID |
| channel | 否 | `web` | `web` \| `api` |

**Response `data`**：Session（`messages` 为空数组）

**错误**：`40001` 参数非法

---

### 4.2 获取会话

`GET /api/v1/sessions/{sessionId}`

**Response `data`**：Session（含全部 messages，按时间升序）

**错误**：`40401` 会话不存在

---

### 4.3 列会话

`GET /api/v1/sessions?userId=u_001`

| Query | 必填 | 说明 |
|-------|------|------|
| userId | 是 | 用户 ID |
| limit | 否 | 默认 20，最大 100 |

**Response `data`**

```json
{
  "items": [ { "...Session 不含 messages 或仅摘要..." } ],
  "total": 3
}
```

> Demo：`items` 可为不含 messages 的 Session 列表，按 `updatedAt` 倒序。

---

### 4.4 关闭会话

`DELETE /api/v1/sessions/{sessionId}`

**Response `data`**

```json
{ "ok": true, "sessionId": "s_xxx", "status": "closed" }
```

说明：关闭后对该 session 再 chat 返回 `40901`。  
清空确认态与否：关闭时清空 `confirmationPayload`。

---

## 5. 对话 API

### 5.1 同步对话

`POST /api/v1/chat`  
`Content-Type: application/json`

**Request**

```json
{
  "sessionId": "s_xxx",
  "userId": "u_001",
  "text": "国内订单退货几天？",
  "attachmentIds": [],
  "confirm": false,
  "confirmPayload": null,
  "locale": "zh-CN",
  "options": {
    "enableRag": true,
    "enableMcp": true
  }
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| sessionId | 否 | 空则自动创建会话 |
| userId | 是 | 必须与 session.userId 一致，否则 40301 |
| text | 条件 | `attachmentIds` 为空时必填；有附件时可空（服务端默认补「请结合图片处理」） |
| attachmentIds | 否 | 须属于当前用户会话或未绑定可认领 |
| confirm | 否 | `true` 表示确认待执行写操作 |
| confirmPayload | 否 | 不传则用 session 内 payload |
| locale | 否 | 默认 zh-CN |
| options | 否 | 见 ChatOptions |

**校验规则**

1. `text` 去空白后长度 0～2000；超长 `40001`。  
2. `attachmentIds` 最多 5 个；每个须存在且类型为图片。  
3. session `closed` → `40901`。  
4. `confirm=true` 但无 payload → `40901`。

**Response `data`（ChatResponse）**

```json
{
  "sessionId": "s_xxx",
  "answer": "根据《退换货政策》，国内订单签收后 7 天内可无理由退货……",
  "intent": "knowledge",
  "agentName": "knowledge",
  "confidence": 0.93,
  "reason": "询问退货时效",
  "citations": [
    {
      "docId": "doc_abc",
      "title": "退换货政策",
      "content": "国内订单签收后 7 天内……",
      "score": 0.88,
      "source": "vector",
      "metadata": {}
    }
  ],
  "toolCalls": [],
  "confirmRequired": false,
  "confirmationPayload": null,
  "mode": "spring-ai-multi-agent"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | string | |
| answer | string | 对用户最终话术 |
| intent | string | Supervisor 意图；确认短路可为 `confirm_resume` |
| agentName | string | 实际应答 Agent |
| confidence | number\|null | 路由置信度；确认短路可为 null |
| reason | string\|null | 路由原因 |
| citations | array | |
| toolCalls | array | |
| confirmRequired | boolean | 是否进入待确认 |
| confirmationPayload | object\|null | |
| mode | string | 固定 `spring-ai-multi-agent` |

**副作用**

- 落库 user / assistant 消息。  
- 更新 `lastIntent` / `lastAgent` / `updatedAt`。  
- 若 `confirmRequired`：`status=pending_confirm` 并写入 payload。  
- 若确认执行成功：`status=active`，清空 payload。

**错误码**

| code | HTTP | 场景 |
|------|------|------|
| 40001 | 400 | 缺 text/附件非法 |
| 40301 | 403 | userId 与 session 不匹配 |
| 40401 | 404 | session / attachment 不存在 |
| 40901 | 409 | 会话关闭或确认态非法 |
| 50002 | 502/500 | LLM 调用失败（可降级文案仍 200+code=0，由实现二选一；本文建议业务降级用 200） |

---

### 5.2 流式对话（SSE）

`POST /api/v1/chat/stream`  
`Accept: text/event-stream`  
Request body 同 `/chat`。

**响应头**

```http
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache
Connection: keep-alive
X-Trace-Id: tr_xxx
```

**事件格式**

```text
event: <name>
data: <json>

```

`data` 一律为 JSON 对象，且包含 `"event":"<name>"` 字段（便于前端只解析 data）。

#### 5.2.1 事件字典

| event | 何时 | data 字段 |
|-------|------|-----------|
| `intent` | Supervisor 完成 | `intent`, `targetAgent`, `confidence`, `reason`, `needClarify` |
| `agent` | 选定子 Agent | `agentName` |
| `retrieval` | Knowledge 检索完成 | `hitCount`, `topK` |
| `citation` | 每条引用 | `citation`（Citation 对象） |
| `tool_start` | 工具开始 | `name`, `arguments` |
| `tool_end` | 工具结束 | `name`, `result`, `success`, `source`, `latencyMs` |
| `confirm_required` | 需确认 | `answer`, `confirmationPayload` |
| `token` | 增量文本 | `text` |
| `final` | 结束 | 同 ChatResponse（可无逐 token 累积压力） |
| `error` | 失败 | `code`, `message` |
| `done` | 可选收尾 | `ok`: true |

#### 5.2.2 典型事件序

**知识问答**

```text
intent → agent → retrieval → citation* → token* → final → done
```

**取消待确认**

```text
intent → agent → confirm_required → final → done
```

（`confirm_required` 时可不推 token，或 token 与 answer 一致）

**确认执行**

```text
agent(agentName=confirm_executor) → tool_start → tool_end → token* → final → done
```

**多模态**

```text
intent → agent(vision) → token?(可选) → agent(ticket|order) → tool_* → token* → final
```

#### 5.2.3 示例片段

```text
event: intent
data: {"event":"intent","intent":"order","targetAgent":"order","confidence":0.9,"reason":"查询订单","needClarify":false}

event: agent
data: {"event":"agent","agentName":"order"}

event: tool_start
data: {"event":"tool_start","name":"query_order","arguments":{"orderId":"ORD20260730001","userId":"u_001"}}

event: tool_end
data: {"event":"tool_end","name":"query_order","success":true,"source":"local","latencyMs":35,"result":"{\"ok\":true}"}

event: token
data: {"event":"token","text":"您的订单当前状态为「待发货」。"}

event: final
data: {"event":"final","sessionId":"s_xxx","answer":"您的订单当前状态为「待发货」。","intent":"order","agentName":"order","confidence":0.9,"citations":[],"toolCalls":[...],"confirmRequired":false,"confirmationPayload":null,"mode":"spring-ai-multi-agent"}
```

**客户端约定**

1. 以 `final` 为准落库展示完整答案；`token` 仅用于打字机效果。  
2. 收到 `error` 后应终止；连接可能被服务端关闭。  
3. 超时建议客户端 180s。

---

### 5.3 确认流专用说明

| 客户端动作 | 请求要点 |
|------------|----------|
| 发起取消 | 正常 chat，拿到 `confirmRequired=true` |
| 确认执行 | `confirm=true`，`text` 可为「确认」；可不传 payload |
| 放弃 | `text` 含「取消/算了/不要了」等（**仅**在 `pending_confirm` 下由编排器识别，见 SUPERVISOR §11） |

**未实现扩展（勿对接）**

```json
{ "cancelConfirm": true }
```

本期**不提供** `cancelConfirm` 字段；放弃确认一律用语意否定或后续自然语言。实现后再升版本写入正式 Request Schema。

---

## 6. 附件 API

### 6.1 上传图片

`POST /api/v1/attachments`  
`Content-Type: multipart/form-data`

| Part | 必填 | 说明 |
|------|------|------|
| file | 是 | 图片文件 |
| sessionId | 否 | 绑定会话 |

**约束**

| 项 | 值 |
|----|----|
| 允许 MIME | `image/jpeg`, `image/png`, `image/webp`, `image/gif` |
| 单文件 | ≤ 5MB |
| 数量 | 单次 1 个文件 |

**Response `data`**

```json
{
  "id": "att_xxx",
  "originalName": "box.jpg",
  "contentType": "image/jpeg",
  "publicUrl": "/files/att_xxx.jpg",
  "sizeBytes": 204800,
  "sessionId": "s_xxx"
}
```

**错误**：`40001` 类型不符；`41301` 过大

### 6.2 访问文件

`GET /files/{fileName}`

- 公开只读（Demo）；生产应鉴权或签名 URL。  
- `Cache-Control` 可设短期缓存。

---

## 7. 知识库 API

### 7.1 文本入库

`POST /api/v1/knowledge/ingest`

```json
{
  "title": "运费说明补充",
  "content": "满 99 包邮……",
  "source": "manual"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| title | 是 | ≤ 200 字 |
| content | 是 | ≤ 100000 字（Demo） |
| source | 否 | 来源标记 |

**Response `data`**

```json
{
  "id": "doc_xxx",
  "title": "运费说明补充",
  "source": "manual",
  "chunkCount": 3,
  "createdAt": "2026-07-30T04:00:00Z"
}
```

### 7.2 Bootstrap 样例

`POST /api/v1/knowledge/bootstrap`

无 body。导入 `samples/knowledge/*.md` 或内置文案。

**Response `data`**

```json
{
  "ingested": 3,
  "docs": [ { "id": "...", "title": "...", "chunkCount": 2 } ]
}
```

幂等建议：已有同 source 文件可跳过或先清空再导入（Demo：若已有文档可仍追加；实现时在 README 说明）。

### 7.3 文档列表

`GET /api/v1/knowledge/docs`

**Response `data`**：`KnowledgeDoc[]`

#### KnowledgeDoc

```json
{
  "id": "doc_xxx",
  "title": "退换货政策",
  "source": "bootstrap:refund-policy.md",
  "chunkCount": 3,
  "createdAt": "2026-07-30T04:00:00Z"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 文档 ID |
| title | string | 标题 |
| source | string | 来源标记（manual / bootstrap:文件名 等） |
| chunkCount | int | 切片数 |
| createdAt | string | ISO-8601 UTC |

### 7.4 检索调试

`POST /api/v1/knowledge/search`

```json
{
  "query": "海外退货时效",
  "topK": 5
}
```

**Response `data`**

```json
{
  "hits": [
    {
      "content": "……",
      "score": 0.81,
      "metadata": { "doc_id": "doc_xxx", "title": "退换货政策" }
    }
  ]
}
```

说明：仅检索，不走完整 Agent；供调试与评测。

---

## 8. MCP API（调试向）

### 8.1 工具列表

`GET /api/v1/mcp/tools`

**Response `data`**

```json
{
  "enabled": true,
  "tools": [
    {
      "name": "search_docs",
      "description": "在知识库中检索",
      "source": "mcp"
    }
  ]
}
```

`enabled=false` 时 `tools=[]`。

### 8.2 调试调用

`POST /api/v1/mcp/call`

```json
{
  "name": "search_docs",
  "arguments": { "query": "退货", "top_k": 3 }
}
```

**Response `data`**

```json
{
  "result": { "ok": true, "hits": [] }
}
```

> 生产可关闭此接口或加管理员鉴权。

---

## 9. 调试 API

> 仅本地 / Demo 开放；可通过配置 `app.debug.enabled=true` 控制。

### 9.1 配置

`GET /api/v1/debug/config`

```json
{
  "mode": "spring-ai-multi-agent",
  "mcpEnabled": true,
  "ragTopK": 5,
  "ragScoreThreshold": 0.55,
  "routeConfidenceThreshold": 0.55,
  "sessionWindowSize": 10,
  "llmModel": "gpt-4o-mini",
  "embeddingModel": "text-embedding-3-small"
}
```

### 9.2 路由日志

`GET /api/v1/debug/routes?sessionId=s_xxx&limit=20`

```json
{
  "items": [
    {
      "id": 1,
      "sessionId": "s_xxx",
      "intent": "order",
      "targetAgent": "order",
      "confidence": 0.9,
      "reason": "查询订单",
      "needClarify": false,
      "createdAt": "2026-07-30T04:00:00Z"
    }
  ]
}
```

### 9.3 工具审计

`GET /api/v1/debug/tools?sessionId=s_xxx`

返回 `ToolCallRecord` 持久化列表（可含 id、createdAt）。

### 9.4 Mock 工单

`GET /api/v1/debug/tickets`

返回内存/表中工单列表。

---

## 10. 健康检查

`GET /actuator/health`

```json
{
  "status": "UP"
}
```

可选扩展：`components.db`、`components.vectorStore`、`components.mcp`。

---

## 11. Admin：可配置 Agent API

> 详细领域模型见 [AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md)。  
> 权限：`viewer` 只读；`editor` 可改 draft；`publisher` 可 publish/rollback。

### 11.1 AgentDefinition（请求/响应主体）

与配置设计 3.1 对齐，API 侧完整示例：

```json
{
  "id": "agt_001",
  "code": "order",
  "name": "订单履约",
  "description": "负责查单、物流与取消确认",
  "type": "WORKER",
  "status": "PUBLISHED",
  "version": 3,
  "publishedVersion": 3,
  "enabled": true,
  "modelConfig": {
    "chatModel": null,
    "temperature": 0.2,
    "maxTokens": 2048,
    "enableVision": false
  },
  "prompts": {
    "systemPrompt": "你是订单履约专员……",
    "userPromptTemplate": null,
    "outputMode": "TEXT"
  },
  "outputSchema": null,
  "tools": ["query_order", "query_logistics", "cancel_order"],
  "mcp": {
    "enabled": false,
    "serverIds": [],
    "toolAllowlist": []
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
  "ui": {
    "icon": "order",
    "tags": ["履约"],
    "sortOrder": 20
  },
  "updatedAt": "2026-07-30T04:00:00Z",
  "updatedBy": "admin"
}
```

Supervisor 示例差异：`type=SUPERVISOR`，`children` 非空，`routing` 有值，`tools` 不含 WRITE，`allowWriteTools=false`，`outputMode=JSON_SCHEMA`。

### 11.2 列表

`GET /api/v1/admin/agents`

| Query | 说明 |
|-------|------|
| type | `SUPERVISOR` \| `WORKER` |
| status | `DRAFT` \| `PUBLISHED` |
| enabled | `true` \| `false`（可选过滤） |
| q | 名称/code 模糊 |

**Response `data`**

```json
{
  "items": [
    {
      "code": "supervisor",
      "name": "主路由",
      "type": "SUPERVISOR",
      "status": "PUBLISHED",
      "publishedVersion": 2,
      "enabled": true,
      "description": "意图识别与子 Agent 路由",
      "updatedAt": "2026-07-30T04:00:00Z"
    }
  ],
  "total": 6
}
```

### 11.3 创建

`POST /api/v1/admin/agents`  
角色：`editor+`

**Request**：至少 `code`、`name`、`type`；其余可默认。  
**Response**：完整 AgentDefinition（`status=DRAFT`，`version=0`）。  
**错误**：code 冲突 → `40901`。

### 11.4 详情

`GET /api/v1/admin/agents/{code}`

返回 draft 全量；另附：

```json
{
  "draft": { "...AgentDefinition..." },
  "published": { "...线上快照或 null..." },
  "dirty": true
}
```

### 11.5 更新 draft

`PUT /api/v1/admin/agents/{code}`  
角色：`editor+`  
Body：AgentDefinition 可写字段（不可改 `code`；`publishedVersion` 只读）。

### 11.6 校验

`POST /api/v1/admin/agents/{code}/validate`

**Response `data`**

```json
{
  "ok": false,
  "errors": [
    { "path": "tools[0]", "message": "unknown tool code: foo" },
    { "path": "children", "message": "cycle detected: a -> b -> a" }
  ],
  "warnings": [
    { "path": "mcp.serverIds[0]", "message": "server DOWN" }
  ]
}
```

失败业务码：`42201`（若 ok=false 也可 HTTP 200 + ok 字段，Demo 推荐 **200 + ok** 便于表单展示；publish 时强制 ok）。

### 11.7 发布

`POST /api/v1/admin/agents/{code}/publish`  
角色：`publisher`

```json
{ "remark": "调整取消确认文案" }
```

先跑与 validate 相同门禁；失败 `42201`。  
成功：`publishedVersion++`，Runtime 热加载。

### 11.8 回滚

`POST /api/v1/admin/agents/{code}/rollback`  
角色：`publisher`

```json
{ "version": 2, "remark": "回滚误发布" }
```

将历史 snapshot 拷为新 published 版本（version 继续递增，保留审计）。

### 11.9 版本列表

`GET /api/v1/admin/agents/{code}/versions`

```json
{
  "items": [
    { "version": 3, "publishedAt": "...", "publishedBy": "admin", "remark": "..." }
  ]
}
```

`GET /api/v1/admin/agents/{code}/versions/{version}` 可取某版 snapshot。

### 11.10 试运行

`POST /api/v1/admin/agents/{code}/trial`  
角色：`editor+`

```json
{
  "text": "帮我查 ORD20260730001",
  "userId": "u_001",
  "attachmentIds": [],
  "useDraft": true,
  "mode": "single"
}
```

| 字段 | 说明 |
|------|------|
| useDraft | true=用未发布草稿；false=用已发布 |
| mode | `single`：只跑当前 Agent（WORKER 或 Supervisor 自身路由输出）；`full_route`：从 Supervisor 入口走完整路由（仅当存在已发布 supervisor 时有效） |

> ~~`asSupervisorEntry`~~ 已废弃，改用 `mode`，避免语义歧义。

**Response `data`**

```json
{
  "answer": "...",
  "agentCode": "order",
  "agentVersion": null,
  "routeTrace": [
    { "from": "supervisor", "to": "order", "confidence": 0.91 }
  ],
  "toolCalls": [],
  "promptsRendered": {
    "system": "（可截断/脱敏）"
  },
  "latencyMs": 1200
}
```

### 11.11 工具目录

`GET /api/v1/admin/catalog/tools`

```json
{
  "items": [
    {
      "code": "query_order",
      "name": "查询订单",
      "description": "根据订单号查询当前用户订单",
      "sideEffect": "READ",
      "ownerDomain": "order",
      "paramSchema": {}
    }
  ]
}
```

### 11.12 MCP 目录

`GET /api/v1/admin/catalog/mcp-servers`

```json
{
  "items": [
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
  ]
}
```

`POST /api/v1/admin/catalog/mcp-servers/{id}/refresh` — 重新 tools/list。

---

## 12. 错误响应示例

```json
{
  "code": 40401,
  "message": "session not found",
  "traceId": "tr_xxx",
  "data": null
}
```

SSE 错误：

```text
event: error
data: {"event":"error","code":50002,"message":"LLM timeout"}
```

配置校验失败：

```json
{
  "code": 42201,
  "message": "agent validation failed",
  "traceId": "tr_xxx",
  "data": {
    "ok": false,
    "errors": [{ "path": "children", "message": "cycle detected" }]
  }
}
```

---

## 13. 幂等与并发

| 场景 | 策略 |
|------|------|
| 同会话并发 chat | 建议会话级互斥锁；后到请求 409 或排队 |
| 重复 confirm | 第二次发现无 pending → 40901 或返回「无需确认」 |
| 重复 bootstrap | Demo 允许追加；可加 `force=true` 重建 |
| Agent 并发编辑 | 乐观锁：`updatedAt` 或 `draftRevision`；冲突 40901 |
| 并发 publish | 按 code 串行；后者等前者完成 |

可选请求头：`Idempotency-Key`（chat 写操作确认场景）。

---

## 14. OpenAPI 路径草稿（摘要 · 占位）

> **注意**：下列 YAML 仅为路径清单占位（`post: {}` 等为空），**不是**完整 OpenAPI 契约。  
> 实现阶段以 springdoc-openapi 生成的 spec 为准；字段定义以本文各节为准。

```yaml
openapi: 3.0.3
info:
  title: Spring AI Customer Service API
  version: 1.2.0
servers:
  - url: http://localhost:8080
paths:
  /api/v1/sessions:
    post: {}
    get: {}
  /api/v1/sessions/{sessionId}:
    get: {}
    delete: {}
  /api/v1/chat:
    post: {}
  /api/v1/chat/stream:
    post: {}
  /api/v1/attachments:
    post: {}
  /api/v1/knowledge/ingest:
    post: {}
  /api/v1/knowledge/bootstrap:
    post: {}
  /api/v1/knowledge/docs:
    get: {}
  /api/v1/knowledge/search:
    post: {}
  /api/v1/mcp/tools:
    get: {}
  /api/v1/mcp/call:
    post: {}
  /api/v1/debug/config:
    get: {}
  /api/v1/admin/agents:
    get: {}
    post: {}
  /api/v1/admin/agents/{code}:
    get: {}
    put: {}
  /api/v1/admin/agents/{code}/validate:
    post: {}
  /api/v1/admin/agents/{code}/publish:
    post: {}
  /api/v1/admin/agents/{code}/rollback:
    post: {}
  /api/v1/admin/agents/{code}/versions:
    get: {}
  /api/v1/admin/agents/{code}/versions/{version}:
    get: {}
  /api/v1/admin/agents/{code}/trial:
    post: {}
  /api/v1/admin/catalog/tools:
    get: {}
  /api/v1/admin/catalog/mcp-servers:
    get: {}
```

实现阶段可用 springdoc-openapi 生成完整 spec。

---

## 15. 调用示例（curl）

### 15.1 创建会话并问答

```bash
# 创建会话
curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: demo-api-key' \
  -d '{"userId":"u_001"}'

# 同步对话
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: demo-api-key' \
  -d '{
    "sessionId":"s_xxx",
    "userId":"u_001",
    "text":"国内订单退货几天？"
  }'
```

### 15.2 SSE

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-API-Key: demo-api-key' \
  -d '{
    "sessionId":"s_xxx",
    "userId":"u_001",
    "text":"帮我查订单 ORD20260730001"
  }'
```

### 15.3 上传 + 多模态

```bash
curl -s -X POST http://localhost:8080/api/v1/attachments \
  -H 'X-API-Key: demo-api-key' \
  -F 'file=@./damage.jpg' \
  -F 'sessionId=s_xxx'

curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: demo-api-key' \
  -d '{
    "sessionId":"s_xxx",
    "userId":"u_001",
    "text":"包裹破损了，帮我售后",
    "attachmentIds":["att_xxx"]
  }'
```

### 15.4 确认取消

```bash
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId":"s_xxx",
    "userId":"u_001",
    "text":"确认",
    "confirm":true
  }'
```

### 15.5 发布 Agent

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/agents/order/publish \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: publisher' \
  -d '{"remark":"更新提示词"}'
```

---

## 16. 前端对接要点

1. 客服页：`POST /sessions` → `/chat/stream`；维护 `confirmRequired`。  
2. 调试面板：`/debug/config`、`/knowledge/docs`、`/mcp/tools`。  
3. **Agent 管理页**：列表 → 编辑 draft → validate → publish；工具/MCP 从 catalog 勾选。  
4. CORS：允许本地静态页；Admin 与用户端可分路由。

---

## 17. 与内部编排契约的边界

| 层 | 文档 |
|----|------|
| 对外 HTTP/SSE + Admin | **本文** |
| Orchestrator / Agent DTO | [SUPERVISOR_SUBAGENT_DESIGN.md](./SUPERVISOR_SUBAGENT_DESIGN.md) |
| 可配置 Agent 模型 / 管理页字段 | [AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md) |
| 架构与里程碑 | [TECHNICAL_DESIGN.md](../TECHNICAL_DESIGN.md) |

对外 Chat API **不暴露** Supervisor 原始 JSON 全量（可在 debug/routes 或 admin trial 查看）。

---

## 18. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-30 | 首版：会话/对话/SSE/附件/知识库/MCP/调试 |
| v1.1 | 2026-07-30 | 增补 Admin Agent / Catalog API |
| v1.2 | 2026-08-07 | 标明实现状态；补 KnowledgeDoc；trial.mode；versions/{version}；cancelConfirm 标未实现；OpenAPI 占位说明 |
| v1.3 | 2026-08-07 | Admin 落地指向 ADMIN 技术设计；intent 改为可扩展 string |

---

**下一步**：Admin / 可配置 Agent 按 [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md) 与 [AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md) 落地；表结构以 ADMIN 文 §4 / AGENT_CONFIG §10 为准。