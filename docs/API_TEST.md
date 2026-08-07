# 接口测试文档

> 文档版本：v1.0  
> 日期：2026-08-07  
> Base URL：`http://localhost:8080`  
> 契约依据：[API_DESIGN.md](./API_DESIGN.md)  
> 实现：`apps/java-gateway`

---

## 1. 环境准备

### 1.1 启动（推荐 local）

```bash
cd apps/java-gateway
export LLM_API_KEY=sk-xxx
export SPRING_AI_OPENAI_API_KEY=$LLM_API_KEY
# 可选：对话走可配置 Agent
# export APP_AGENT_CONFIG_ENABLED=true
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

| 项 | 值 |
|----|-----|
| 客服 UI | http://localhost:8080/ |
| Admin UI | http://localhost:8080/admin.html |
| 健康检查 | `GET /actuator/health` |

### 1.2 通用约定

| 项 | 说明 |
|----|------|
| 成功包络 | `{ "code": 0, "message": "ok", "traceId": null, "data": ... }` |
| 业务失败 | `code != 0`（见 §7） |
| Demo 用户 | `u_001` |
| 可取消订单 | `ORD20260730001` |
| Admin 角色头 | `X-Admin-Role: viewer \| editor \| publisher` |
| API Key（可选） | `X-API-Key: demo-api-key`（local 可不强制） |

下文示例省略包络，断言时请检查 `code == 0` 且使用 `data` 字段。

```bash
# 便于复制：取 data
alias jdata='python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin).get(\"data\"),ensure_ascii=False,indent=2))"'
```

---

## 2. 冒烟清单（建议顺序）

| # | 用例 | 期望 |
|---|------|------|
| S1 | `GET /actuator/health` | `status=UP` |
| S2 | 创建会话 → 同步 chat 问退货 | `agentName=knowledge` 或有答案 |
| S3 | 查单 `ORD20260730001` | `agentName=order` |
| S4 | 取消订单 → 确认 | 先 `confirmRequired=true`，再确认后成功话术 |
| S5 | `POST /knowledge/bootstrap` + `GET /knowledge/docs` | docs ≥ 1 |
| S6 | `GET /api/v1/admin/agents`（viewer） | 6 个 Seed Agent |
| S7 | viewer 调 publish | `40301` |
| S8 | publisher validate + publish order | `ok` / 版本递增 |
| S9 | trial single order | 有 `answer` / `latencyMs` |
| S10 | SSE chat | 收到 `intent`/`final`/`done` 类事件 |

---

## 3. 业务 API

### 3.1 健康检查

```bash
curl -s http://localhost:8080/actuator/health
# 期望: {"status":"UP"}
```

### 3.2 会话

#### 创建

```bash
curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u_001","channel":"web"}' | tee /tmp/s.json | jdata
```

记录 `data.id` 为 `SESSION_ID`：

```bash
export SESSION_ID=$(python3 -c "import json;print(json.load(open('/tmp/s.json'))['data']['id'])")
echo $SESSION_ID
```

#### 详情 / 列表 / 关闭

```bash
curl -s "http://localhost:8080/api/v1/sessions/$SESSION_ID" | jdata
curl -s "http://localhost:8080/api/v1/sessions?userId=u_001&limit=20" | jdata
curl -s -X DELETE "http://localhost:8080/api/v1/sessions/$SESSION_ID" | jdata
# 关闭后再 chat → 期望 code=40901
```

### 3.3 同步对话

```bash
# 若会话已关，先重新创建
export SESSION_ID=$(curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' -d '{"userId":"u_001"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")

# 知识问答
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{
    \"sessionId\":\"$SESSION_ID\",
    \"userId\":\"u_001\",
    \"text\":\"国内订单退货几天？\"
  }" | jdata
# 期望: data.answer 非空；intent/agentName 多为 knowledge

# 查订单
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{
    \"sessionId\":\"$SESSION_ID\",
    \"userId\":\"u_001\",
    \"text\":\"帮我查订单 ORD20260730001\"
  }" | jdata
# 期望: agentName=order；答案含订单状态
```

#### 取消确认流

```bash
# 1) 发起取消
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{
    \"sessionId\":\"$SESSION_ID\",
    \"userId\":\"u_001\",
    \"text\":\"取消订单 ORD20260730001\"
  }" | tee /tmp/cancel.json | jdata
# 期望: confirmRequired=true；confirmationPayload.action=cancel_order

# 2) 确认
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{
    \"sessionId\":\"$SESSION_ID\",
    \"userId\":\"u_001\",
    \"text\":\"确认\",
    \"confirm\":true
  }" | jdata
# 期望: agentName=confirm_executor；答案含「已成功取消」类文案

# 3) 另开会话测放弃：取消 → 回复「算了」
# 期望: 不执行工具；提示已取消操作
```

#### 负例

```bash
# userId 与会话不一致 → 40301
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"userId\":\"u_other\",\"text\":\"hi\"}"

# 空 text 且无附件 → 40001
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"userId\":\"u_001\",\"text\":\"\"}"
```

### 3.4 SSE 流式对话

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d "{
    \"sessionId\":\"$SESSION_ID\",
    \"userId\":\"u_001\",
    \"text\":\"帮我查订单 ORD20260730001\"
  }"
```

**期望事件（顺序大致）**：`intent` → `agent` →（可选 `tool_*`）→ `token*` → `final` → `done`  
客户端以 `final` 为准落完整答案。

### 3.5 附件上传

```bash
# 准备任意图片，如 samples 或本地 jpg
curl -s -X POST http://localhost:8080/api/v1/attachments \
  -F "file=@./path/to/image.jpg" \
  -F "sessionId=$SESSION_ID" | tee /tmp/att.json | jdata

export ATT_ID=$(python3 -c "import json;print(json.load(open('/tmp/att.json'))['data']['id'])")

curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d "{
    \"sessionId\":\"$SESSION_ID\",
    \"userId\":\"u_001\",
    \"text\":\"包裹破损了，帮我售后\",
    \"attachmentIds\":[\"$ATT_ID\"]
  }" | jdata
# 期望: 路由含 vision / ticket 相关结果（依赖 LLM）
```

访问文件：`GET http://localhost:8080/files/{fileName}`（`data.publicUrl`）。

### 3.6 知识库

```bash
curl -s -X POST http://localhost:8080/api/v1/knowledge/bootstrap | jdata
curl -s http://localhost:8080/api/v1/knowledge/docs | jdata

curl -s -X POST http://localhost:8080/api/v1/knowledge/ingest \
  -H 'Content-Type: application/json' \
  -d '{"title":"测试补充","content":"满 99 包邮（测试）","source":"manual"}' | jdata

curl -s -X POST http://localhost:8080/api/v1/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"退货","topK":5}' | jdata
```

> `local` profile 使用 SimpleVectorStore；无有效 Embedding Key 时检索可能为空，但不应 500。

### 3.7 MCP（调试）

```bash
curl -s http://localhost:8080/api/v1/mcp/tools | jdata
# local 默认 mcpEnabled=false → tools=[]

curl -s -X POST http://localhost:8080/api/v1/mcp/call \
  -H 'Content-Type: application/json' \
  -d '{"name":"search_docs","arguments":{"query":"退货","top_k":3}}' | jdata
```

### 3.8 调试

```bash
curl -s http://localhost:8080/api/v1/debug/config | jdata
# 关注: agentConfigEnabled / mcpEnabled / llmModel / vectorBackend

curl -s "http://localhost:8080/api/v1/debug/routes?sessionId=$SESSION_ID&limit=20" | jdata
curl -s "http://localhost:8080/api/v1/debug/tools?sessionId=$SESSION_ID" | jdata
curl -s http://localhost:8080/api/v1/debug/tickets | jdata
```

---

## 4. Admin API

所有 Admin 请求建议带：

```bash
-H 'X-Admin-Role: viewer|editor|publisher'
```

缺省按 `viewer` 处理。

### 4.1 Agent 列表 / 详情

```bash
curl -s http://localhost:8080/api/v1/admin/agents \
  -H 'X-Admin-Role: viewer' | jdata
# 期望: total=6；含 supervisor/knowledge/order/ticket/vision/chitchat

curl -s 'http://localhost:8080/api/v1/admin/agents?type=WORKER&q=order' \
  -H 'X-Admin-Role: viewer' | jdata

curl -s http://localhost:8080/api/v1/admin/agents/order \
  -H 'X-Admin-Role: viewer' | jdata
# 期望: draft / published / dirty / draftRevision
```

### 4.2 创建（editor）

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/agents \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: editor' \
  -d '{
    "code":"demo_worker",
    "name":"演示工人",
    "type":"WORKER",
    "description":"接口测试用"
  }' | jdata
# 期望: status=DRAFT
# 重复 code → 40901
```

### 4.3 更新 draft（乐观锁）

```bash
# 先取 revision
REV=$(curl -s http://localhost:8080/api/v1/admin/agents/order \
  -H 'X-Admin-Role: editor' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['draftRevision'])")

# 拉取完整 draft 后改 systemPrompt（示例用 python 拼 body）
curl -s http://localhost:8080/api/v1/admin/agents/order \
  -H 'X-Admin-Role: editor' > /tmp/order.json

python3 <<'PY'
import json
raw=json.load(open("/tmp/order.json"))["data"]
draft=raw["draft"]
draft["prompts"]["systemPrompt"]=draft["prompts"]["systemPrompt"]+"\n# api-test-marker"
body={
  "name": draft["name"],
  "description": draft.get("description"),
  "draftRevision": raw["draftRevision"],
  "definition": draft
}
open("/tmp/order_put.json","w").write(json.dumps(body,ensure_ascii=False))
print("revision", raw["draftRevision"])
PY

curl -s -X PUT http://localhost:8080/api/v1/admin/agents/order \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: editor' \
  -d @/tmp/order_put.json | jdata
# 期望: draftRevision = 原值+1；dirty=true

# 用旧 revision 再 PUT → 40901
```

### 4.4 校验 / 发布 / 版本 / 回滚

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/agents/order/validate \
  -H 'X-Admin-Role: editor' | jdata
# 期望: ok=true（或 errors 可读）

# viewer 发布 → 40301
curl -s -X POST http://localhost:8080/api/v1/admin/agents/order/publish \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: viewer' \
  -d '{"remark":"should fail"}'

# publisher 发布
curl -s -X POST http://localhost:8080/api/v1/admin/agents/order/publish \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: publisher' \
  -d '{"remark":"api test publish"}' | jdata
# 期望: publishedVersion 递增

curl -s http://localhost:8080/api/v1/admin/agents/order/versions \
  -H 'X-Admin-Role: viewer' | jdata

curl -s http://localhost:8080/api/v1/admin/agents/order/versions/1 \
  -H 'X-Admin-Role: viewer' | jdata

# 回滚到 v1（会再产生新版本号）
curl -s -X POST http://localhost:8080/api/v1/admin/agents/order/rollback \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: publisher' \
  -d '{"version":1,"remark":"api test rollback"}' | jdata
```

### 4.5 试运行（trial）

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/agents/order/trial \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: editor' \
  -d '{
    "text":"帮我查 ORD20260730001",
    "userId":"trial_u_demo",
    "useDraft":true,
    "mode":"single"
  }' | jdata
# 期望: answer / latencyMs；可选 toolCalls

curl -s -X POST http://localhost:8080/api/v1/admin/agents/supervisor/trial \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: editor' \
  -d '{
    "text":"国内退货几天？",
    "useDraft":false,
    "mode":"full_route"
  }' | jdata
# 说明: full_route 受 APP_AGENT_CONFIG_ENABLED 影响；false 时可能走 Legacy
```

### 4.6 Catalog

```bash
curl -s http://localhost:8080/api/v1/admin/catalog/tools \
  -H 'X-Admin-Role: viewer' | jdata
# 期望: query_order / cancel_order / create_ticket 等

curl -s http://localhost:8080/api/v1/admin/catalog/mcp-servers \
  -H 'X-Admin-Role: viewer' | jdata

curl -s -X POST http://localhost:8080/api/v1/admin/catalog/mcp-servers/mcp_knowledge/refresh \
  -H 'X-Admin-Role: publisher' | jdata
```

### 4.7 校验门禁负例（SUPERVISOR 挂 WRITE）

在 Admin UI 或 PUT draft 时给 `supervisor.tools` 加入 `cancel_order`，再：

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/agents/supervisor/validate \
  -H 'X-Admin-Role: editor' | jdata
# 期望: ok=false；errors 含 WRITE / allowWriteTools

curl -s -X POST http://localhost:8080/api/v1/admin/agents/supervisor/publish \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Role: publisher' \
  -d '{"remark":"should 422"}'
# 期望: code=42201
```

---

## 5. 页面联调（非 curl）

| 页面 | 检查点 |
|------|--------|
| `/` | 演示剧本：退货 / 查单 / 取消确认 |
| `/admin.html` | 角色切换；列表 6 Agent；编辑保存；校验；发布；Playground |

---

## 6. 配置化 Runtime 对比测试

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | `agentConfigEnabled=false`（默认） | debug/config 为 false；chat 用固定 Prompt |
| 2 | Admin 改 order systemPrompt 加标记并 publish | Playground single 可见标记 |
| 3 | 客服页 chat（flag 仍 false） | 不一定出现标记 |
| 4 | `APP_AGENT_CONFIG_ENABLED=true` 重启 | debug 为 true；chat 出现标记 |
| 5 | rollback order | 标记消失 |

---

## 7. 错误码速查

| code | HTTP | 含义 | 典型场景 |
|------|------|------|----------|
| 0 | 200 | 成功 | |
| 40001 | 400 | 参数错误 | 缺 text、非法 body |
| 40301 | 403 | 禁止 | userId 不匹配；Admin 角色不足 |
| 40401 | 404 | 不存在 | 会话 / Agent 不存在 |
| 40901 | 409 | 冲突 | 会话关闭；draftRevision 冲突；code 重复 |
| 42201 | 422 | 配置校验失败 | Admin publish/validate |
| 50001 | 500 | 内部错误 | |
| 50002 | 502/500 | LLM 错误 | Key 无效等（可能降级为文案） |

---

## 8. 自动化建议（可选）

可将 §2 冒烟清单写成 shell：

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
curl -sf "$BASE/actuator/health" | grep -q UP
SID=$(curl -sf -X POST "$BASE/api/v1/sessions" -H 'Content-Type: application/json' \
  -d '{"userId":"u_001"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
curl -sf -X POST "$BASE/api/v1/chat" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"userId\":\"u_001\",\"text\":\"国内订单退货几天？\"}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0 and d['data']['answer']"
curl -sf "$BASE/api/v1/admin/agents" -H 'X-Admin-Role: viewer' \
  | python3 -c "import sys,json;assert json.load(sys.stdin)['data']['total']>=6"
echo "smoke ok SID=$SID"
```

保存为 `scripts/smoke-api.sh` 后：`chmod +x scripts/smoke-api.sh && ./scripts/smoke-api.sh`。

---

## 9. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-08-07 | 首版：业务 API + Admin API curl 用例与冒烟清单 |
