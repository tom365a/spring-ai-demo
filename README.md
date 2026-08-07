# 智能客服 Demo（Spring AI）

基于 **Spring Boot 3 + Spring AI** 的可运行 Demo，实现：

- Supervisor 主 Agent 意图路由
- 子 Agent：knowledge / order / ticket / vision / chitchat
- RAG（PGVector 或 local SimpleVectorStore）
- Function Call（查单 / 取消确认 / 物流 / 工单）
- MCP（knowledge-mcp HTTP 桥）
- 多模态图片上传
- 多轮会话 + SSE 流式

## 文档地图

阅读顺序与权威说明见 [docs/README.md](./docs/README.md)。

| 文档 | 用途 |
|------|------|
| [docs/README.md](./docs/README.md) | **设计文档索引**（现行 / 演进） |
| [TECHNICAL_DESIGN.md](./TECHNICAL_DESIGN.md) | 总体架构 |
| [docs/SUPERVISOR_SUBAGENT_DESIGN.md](./docs/SUPERVISOR_SUBAGENT_DESIGN.md) | Supervisor / 子 Agent（**已实现**） |
| [docs/API_DESIGN.md](./docs/API_DESIGN.md) | HTTP/SSE + Admin API（已实现） |
| [docs/API_TEST.md](./docs/API_TEST.md) | 接口测试文档（curl / 冒烟） |
| [docs/ADMIN_AGENT_TECHNICAL_DESIGN.md](./docs/ADMIN_AGENT_TECHNICAL_DESIGN.md) | Admin 后端（**A0–A4 已实现**；默认 flag 关闭） |
| [docs/ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./docs/ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md) | 管理前端 UI + 可配置联调（**已实现** `/admin.html`） |
| [docs/AGENT_CONFIG_DESIGN.md](./docs/AGENT_CONFIG_DESIGN.md) | 可配置 Agent 领域/管理页字段 |

> 配置以代码 + Prompt 文件为主；Prompt 双份约定见 [docs/README.md](./docs/README.md)。

## 快速开始

### 1. 配置 Key

```bash
cp .env.example .env
# 编辑 LLM_API_KEY（及可选 SPRING_AI_OPENAI_*）
```

### 2. 本地极简模式（推荐先跑通）

需 **JDK 21 + Maven**。使用 H2 + 文件向量库，不依赖 Docker/Postgres/MCP：

```bash
cd apps/java-gateway
export LLM_API_KEY=sk-xxx
export SPRING_AI_OPENAI_API_KEY=$LLM_API_KEY
# 若用兼容网关，设置 SPRING_AI_OPENAI_BASE_URL（一般不含 /v1）
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

打开 http://localhost:8080/

知识样例路径默认 `../../samples/knowledge`（相对 `apps/java-gateway`）。

### 3. Docker Compose 全量

```bash
cp .env.example .env   # 填入 LLM_API_KEY
docker compose up -d --build
```

| 服务 | 地址 |
|------|------|
| 客服 UI / API | http://localhost:8080/ |
| Knowledge MCP | http://localhost:3100/health |
| Postgres | localhost:5432 |

## 演示剧本

1. 问：「国内订单退货时效是多久？」→ knowledge + 引用  
2. 追问：「海外呢？」→ 多轮上下文  
3. 「帮我查订单 ORD20260730001」→ order + tool  
4. 「取消订单 ORD20260730001」→ 待确认 → 再发「确认」  
5. 「包裹破损了，帮我开个工单」→ ticket  
6. 上传图片 +「帮我售后」→ vision → 二次路由  

用户：`u_001` · 可取消订单：`ORD20260730001`

## 主要 API

| Method | Path |
|--------|------|
| POST | `/api/v1/sessions` |
| POST | `/api/v1/chat` |
| POST | `/api/v1/chat/stream` |
| POST | `/api/v1/attachments` |
| POST | `/api/v1/knowledge/bootstrap` |
| GET | `/api/v1/debug/config` |

## 目录

```text
apps/java-gateway/     # Spring AI 应用
mcp-servers/knowledge-mcp/
samples/knowledge/
docs/                  # 设计文档与 prompts/
```

## 说明

- Embedding 默认维度 1536（`text-embedding-3-small`），换模型请改 `application.yml`。  
- `local` profile 关闭 MCP 与 PGVector。  
- 订单/工单为内存 Mock，重启丢失。  
- LLM 使用 OpenAI 或任意 OpenAI 兼容网关。  
- Admin Agent API：`/api/v1/admin/agents`（Header `X-Admin-Role`）。  
- **管理前端**：http://localhost:8080/admin.html（列表 / 编辑 / 发布 / Playground）。  
- 对话走已发布配置：启动前设 `APP_AGENT_CONFIG_ENABLED=true`（默认 Legacy 固定 Agent）。  

### Admin 联调剧本

1. `mvn spring-boot:run -Dspring-boot.run.profiles=local`  
2. 打开 `/admin.html`，角色选 `publisher`。  
3. 编辑 `order` 提示词 → 保存 → 校验 → 发布。  
4. Playground `single` 验证；再设 `APP_AGENT_CONFIG_ENABLED=true` 重启后用客服页验证真实对话。  
