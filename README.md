# 智能客服 Demo（Spring AI）

基于 **Spring Boot 3 + Spring AI** 的可运行 Demo，实现：

- Supervisor 主 Agent 意图路由
- 子 Agent：knowledge / order / ticket / vision / chitchat
- RAG（默认本地 ONNX 中文嵌入 bge-small-zh，真实语义向量、无外部嵌入调用；另支持 PGVector 与关键词兜底）
- Function Call（查单 / 取消确认 / 物流 / 工单）
- 统一资源配置：Agents、模型、工具、MCP、技能五个一级页面
- 每个 Agent 独立选择 OpenAI / Kimi 模型，HTTP 自定义工具与标准 MCP Streamable HTTP（另保留旧 HTTP 桥）
- 多模态图片上传
- 多轮会话 + SSE 事件（资源模型响应当前缓冲后输出）
- 转人工进真人坐席队列：管理端「人工坐席」页认领、打字回复，客户端轮询取回，全程不经模型

## 文档地图

阅读顺序与权威说明见 [docs/README.md](./docs/README.md)。

界面改造（方向 A 控制台蓝 + 自托管思源黑体，SIL OFL 免费商用）见 [UI 改造说明](docs/ui-redesign/UI_REDESIGN.md)。

订单/工单、人工客服、知识检索三处原「模拟」已改为真实实现，另修复了转人工链路上的一批缺陷（对话内「转人工」真正入队、排队位次不再编造、排队期间不被闲置超时踢掉、关页面重开能回到原会话、写操作确认统一走卡片按钮），见 [真实实现说明](docs/real-implementations/REAL_IMPLEMENTATIONS.md)。全量测试 102/102。

最新资源配置需求、设计与验证见 [本轮需求范围](docs/resource-configuration/REQUIREMENTS_SCOPE.md)、[PRD](docs/resource-configuration/PRD.md)、[独立测试报告](docs/resource-configuration/TEST_REPORT.md) 和 [产品验收报告](docs/resource-configuration/ACCEPTANCE_REPORT.md)（RC01–RC15 全部通过，无未关闭 P0/P1；已部署到 8080 并完成真实模型验收）。早期设计文档中与新资源运行时不同的内容以本轮设计和代码为准。

| 文档 | 用途 |
|------|------|
| [docs/README.md](./docs/README.md) | **设计文档索引**（现行 / 演进） |
| [TECHNICAL_DESIGN.md](./TECHNICAL_DESIGN.md) | 总体架构 |
| [docs/SUPERVISOR_SUBAGENT_DESIGN.md](./docs/SUPERVISOR_SUBAGENT_DESIGN.md) | Supervisor / 子 Agent（**已实现**） |
| [docs/API_DESIGN.md](./docs/API_DESIGN.md) | HTTP/SSE + Admin API（已实现） |
| [docs/API_TEST.md](./docs/API_TEST.md) | 接口测试文档（curl / 冒烟） |
| [docs/agent-configuration/PRD.md](./docs/agent-configuration/PRD.md) | 本轮 Agent 页面配置需求与验收标准 |
| [docs/agent-configuration/ARCHITECTURE.md](./docs/agent-configuration/ARCHITECTURE.md) | 当前动态启用、技能、主子路由架构 |
| [docs/ADMIN_AGENT_TECHNICAL_DESIGN.md](./docs/ADMIN_AGENT_TECHNICAL_DESIGN.md) | 初期 Admin 后端设计，默认模式以本轮文档和代码为准 |
| [docs/ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./docs/ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md) | 管理前端 UI + 可配置联调（**已实现** `/admin.html`） |
| [docs/AGENT_CONFIG_DESIGN.md](./docs/AGENT_CONFIG_DESIGN.md) | 可配置 Agent 领域/管理页字段 |

> 配置可从管理页面创建并启用；内置种子 Prompt 双份约定见 [docs/README.md](./docs/README.md)。

## 快速开始

### 0. 拉取本地嵌入模型（首次必做，需联网一次）

`bge-small-zh-v1.5`（ONNX int8，23 MB）不随仓库分发，首次克隆后执行一次即可，之后本地缓存不再下载：

```powershell
powershell -File scripts/fetch-model.ps1
```

```bash
bash scripts/fetch-model.sh
```

脚本固定了 HuggingFace 的 revision（`Xenova/bge-small-zh-v1.5` @ `75c43b0`）并逐个校验 SHA256，上游改文件会直接失败而不是静默换掉模型。`scripts/dev.ps1` 和 `scripts/run-8080.ps1` 启动前会自动调用它。

无法访问 huggingface.co 时，把 `model.onnx` 与 `tokenizer.json` 手动放进 `apps/java-gateway/src/main/resources/models/bge-small-zh/`，或改用其它嵌入后端（`--app.vector.embedding=openai` / `--app.vector.backend=lexical`）。

### 1. 配置 Key

首次配置时复制 `.env.example` 为 `.env`，已有 `.env` 请直接编辑，避免覆盖密钥。
默认 `LLM_PROVIDER=kimi`，填写 `KIMI_API_KEY`；OpenAI 使用独立的 `OPENAI_API_KEY`。
详细说明见 [OpenAI / Kimi 接入说明](docs/model-providers/README.md)。
启动后在 `/admin.html` 的模型页面维护多个模型资源，Agent 下拉可独立选择。OpenAI 未充值时保持其资源停用；保存或打开配置页面不会自动发模型请求。页面录入凭证另需独立服务端 `APP_RESOURCE_MASTER_KEY`（32 字节 Base64），已有环境变量引用不需要数据库主密钥。

```powershell
.\scripts\dev.ps1 -Action run -Provider kimi
# OpenAI 充值并配置 OPENAI_API_KEY 后再切换：
# .\scripts\dev.ps1 -Action run -Provider openai
```

### 2. 本地极简模式（推荐先跑通）

需 **JDK 21 + Maven**。上述 Windows 脚本自动读取 `.env`，优先使用 `.tools` 中的运行环境。
使用 H2，不依赖 Docker/Postgres/MCP。直接运行 Maven 时请自行导出对应环境变量，例如：

```bash
bash scripts/fetch-model.sh          # 首次必做
cd apps/java-gateway
export KIMI_API_KEY=your-key
mvn spring-boot:run -Dspring-boot.run.profiles=local,kimi
```

打开 http://localhost:8080/

知识样例路径默认 `../../samples/knowledge`（相对 `apps/java-gateway`）。

### 3. Docker Compose 全量

```bash
# 先按第 1 步配置 .env
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
4. 「取消订单 ORD20260730001」→ **首轮直接弹确认卡片**（操作 / 工具 / 参数 / 有效期 + 「确认执行 / 取消操作」按钮），写操作一律点按钮，不需要回复文字  
5. 「包裹破损了，帮我开个工单」→ ticket，同样先出确认卡片  
6. 上传图片 +「帮我售后」→ 主 Agent 转交有图片能力的子 Agent（所选模型须支持并启用 vision 能力）  
7. 「转人工」→ 确认卡片 → 会话进入坐席队列；另开 `/admin.html` 的「人工坐席」页，角色切 `editor`，填坐席显示名 → 认领 → 打字回复，3 秒内出现在客户窗口  

用户：`u_001` · 可取消订单：`ORD20260730001`

## 主要 API

| Method | Path |
|--------|------|
| POST | `/api/v1/sessions` |
| POST | `/api/v1/chat` |
| POST | `/api/v1/chat/stream` |
| POST | `/api/v1/attachments` |
| POST | `/api/v1/knowledge/bootstrap` |
| POST | `/api/v1/confirmations/{id}` |
| POST | `/api/v1/sessions/{id}/handoff` |
| GET | `/api/v1/sessions/{id}/handoff/messages` |
| GET/POST | `/api/v1/admin/support/**`（坐席队列 / 认领 / 回复 / 结束，需 `X-Admin-Role: editor`） |
| GET | `/api/v1/debug/config` |

## 目录

```text
apps/java-gateway/     # Spring AI 应用（models/bge-small-zh 由 scripts/fetch-model 拉取，不入库）
mcp-servers/knowledge-mcp/
samples/knowledge/
docs/                  # 设计文档与 prompts/
```

## 说明

- Embedding 默认走本地 ONNX（bge-small-zh-v1.5，512 维）。**运行时零外部调用**，但模型文件不入库，首次需执行 `scripts/fetch-model.ps1` / `.sh` 拉取一次。设 `app.vector.embedding=openai` 可改用 OpenAI 嵌入（1536 维）。  
- `local` profile 关闭 MCP 与 PGVector。  
- 订单/工单为真实持久化的演示数据（状态机 + 悲观锁 + 事务），重启保留；接客户自有系统请用 HTTP 自定义工具或 MCP。  
- 转人工默认由真人坐席在 `/admin.html` 的「人工坐席」页接管；设 `app.support.mode=SIMULATED` 可切回子 Agent 模拟。  
- 排队中的会话不受 5 分钟闲置超时影响（客户本来就在等人）；超过 `app.support.abandon-timeout-seconds`（默认 1800 秒）无任何动作才连同坐席分配一起回收，避免弃单永久占队。  
- 客户端把会话 id 存在 `localStorage`：关掉页面重开时，**仅当仍在人工流程中**才恢复原会话，普通聊天「刷新＝新会话」不变。  
- 写工具（取消订单 / 创建工单 / 转人工等）由网关强制拦截并弹确认卡片，用户点「确认执行」才真正执行；提示词禁止模型另外索要文字确认。  
- LLM 支持独立 OpenAI / Kimi K3 配置；K3 专用适配保留工具回合的推理字段。  
- Admin Agent API：`/api/v1/admin/agents`（Header `X-Admin-Role`）。  
- **管理前端**：http://localhost:8080/admin.html（列表 / 编辑 / 发布 / Playground）。  
- 默认对话走已发布配置；点击启用后自动加载，无需重启。设 `APP_AGENT_CONFIG_ENABLED=false` 可切回 Legacy，配置启用接口会明确拒绝。  
- local 使用文件数据库 `apps/java-gateway/data/cs`（从 Java gateway 工作目录启动时），已启用 Agent 和草稿重启后保留。  

### Admin 联调剧本

1. 在项目根目录执行 `./scripts/dev.ps1 -Action run`；也可在 Java gateway 目录使用上述 Maven 启动命令。  
2. 打开 `/admin.html`，角色选 `publisher`（当前为受控 Demo 角色机制）。  
3. 创建子 Agent，一次填写名称、简介、提示词、工具和技能；标识可留空。创建后点击“启用”。  
4. 创建主 Agent，关联已启用的子 Agent并启用；到客服页刷新主 Agent 列表、选中后对话。  
5. 编辑已启用 Agent 时，“保存 draft”不影响线上；“发布更新”后下一次调用使用新配置。  

在 8080 上跑正式演示实例（从 `.env` 读取 Key，只写进子进程环境块，不回显不记日志）：

```powershell
powershell -File scripts/run-8080.ps1 -Port 8080
powershell -File scripts/run-8080.ps1 -Stop
```

构建测试：`./scripts/dev.ps1 -Action test`。环境与受控模型验证见 [环境说明](docs/agent-configuration/ENVIRONMENT.md)，项目后续缺口见 [BACKLOG](docs/agent-configuration/BACKLOG.md)。
