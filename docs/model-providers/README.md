# OpenAI / Kimi K3 接入与切换

日期：2026-09-10；负责人：全栈架构师 Agent。当前默认选择 Kimi；OpenAI 仅在明确选择时使用，不检查或自动消耗未充值账户，也不会因 Kimi 失败自动回落 OpenAI。

## 本地启动

根目录 `.env` 存储本机配置，示例见 [`.env.example`](../../.env.example)。不要把真实密钥写入代码、聊天截图或提交记录。`scripts/dev.ps1` 只在 `run` 时读取 `.env`；`test` 和 `package` 不加载它。

```powershell
# 默认读取 .env 的 LLM_PROVIDER；没有该项时选择 kimi。
.\scripts\dev.ps1 -Action run

# 显式选择优先于 .env。
.\scripts\dev.ps1 -Action run -Provider kimi
.\scripts\dev.ps1 -Action run -Provider openai

# 不发请求，只验证所选提供方的本地配置是否齐备。
.\scripts\dev.ps1 -Action run -Provider kimi -ValidateConfigOnly
```

Windows PowerShell 如果因执行策略拒绝脚本，可仅对该进程使用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev.ps1 -Action run -Provider kimi`。不需要修改机器全局执行策略。端口默认 8080，可加 `-Port 18080`。首次运行需下载 Maven/插件依赖，缓存齐备后可加 `-Offline`。

脚本以数据解析 `.env`，不执行内容，不展开 `$()`、反引号或 `${...}`，支持空行、注释、可选 export、单引号和双引号包围值。已有进程环境变量优先于文件；`-Provider` 明确覆盖 LLM_PROVIDER。日志只显示提供方名称和“已加载”，密钥不会进入命令行参数。可用 `-EnvFile` 指定其他配置文件。

## 配置

| 项目 | Kimi | OpenAI |
| --- | --- | --- |
| LLM_PROVIDER / -Provider | kimi | openai |
| 密钥 | KIMI_API_KEY | OPENAI_API_KEY |
| 服务根地址 | KIMI_BASE_URL，默认 https://api.moonshot.cn | OPENAI_BASE_URL，默认 https://api.openai.com |
| 模型 | KIMI_MODEL，默认 kimi-k3 | OPENAI_MODEL，默认保持项目原模型 gpt-4o-mini |
| Spring profiles | local,kimi | local,openai |

Kimi 地址可填写服务根或末尾 `/v1`，适配器确保聊天路径只有一个 `/v1/chat/completions`。OpenAI 继续使用 Spring AI 标准适配器，BASE_URL 通常填写服务根地址，不包含 `/v1`。使用新 provider profile 时，不从旧 `LLM_API_KEY` 或 `SPRING_AI_OPENAI_API_KEY` 取另一家提供方的密钥。

Kimi 调用使用独立 `KimiChatModel`，默认 `KIMI_REASONING_EFFORT=low`、`KIMI_MAX_COMPLETION_TOKENS=4096`、`KIMI_MAX_TOOL_ROUNDS=6`、`KIMI_TIMEOUT_SECONDS=90`。每次 HTTP 请求有超时，不做跨提供方回退，也不自动重试。达到工具轮次上限或返回 `finish_reason=length` 时明确失败；可按任务需要调整预算。

## K3 工具回合兼容

K3 始终启用思考，使用顶层 `reasoning_effort` 控制强度；它要求工具回合原样传回完整 assistant message。官方同时建议省略固定采样参数。适配器因此不发送 `thinking=disabled` 或项目原来的 `temperature=0.2`。参考 [Kimi K3 官方说明](https://platform.kimi.com/docs/guide/kimi-k3-quickstart)。

Spring AI 1.0.0 的 OpenAI 消息类型没有 `reasoning_content` 字段。Kimi 专用模型保留原始 assistant JSON，在同一次调用的下一轮工具请求中完整回传；工具仅从当前 Agent 绑定的 ToolCallback 白名单执行，逐项携带对应 `tool_call_id`。多工具轮次共享请求内上下文，不使用全局消息缓存。完整 assistant 内容还保存在模型返回消息的私有元数据中，供显式携带历史的调用复用，不作为页面答案或日志输出。

工具循环最终只把 `content` 交给页面，响应元数据包含提供方、模型、请求 ID 及汇总各轮次的 token 用量。网络和 HTTP 错误不回显供应商响应体、密钥或思考正文；禁止自动跟随 HTTP 重定向，避免把 Authorization 转发到其他主机。OpenAI 密钥按官方要求仅在服务端使用，参考 [OpenAI API 鉴权](https://developers.openai.com/api/reference/overview#authentication)。

Kimi 的 `ChatModel.stream()` 当前复用同步工具循环，在取得完整最终答案后返回单个响应；网页仍通过原有 SSE 输出结果。这是缓冲输出，不是上游逐 token 推送，不显示思考流。该实现保证普通调用与流式接口的工具历史一致；如需真正逐 token 输出，需要另做增量消息拼接和取消传播。

## 知识检索与独立 Embedding

两个 provider profile 默认 `app.vector.backend=lexical`，使用本地关键词检索，无 Embedding API 请求。Kimi 默认不会尝试向 Moonshot 发送 OpenAI embeddings 请求，也不会偷偷使用 OpenAI 余额；`RAG_SCORE_THRESHOLD` 默认 0.15，表示词项匹配分数，不等同向量相似度。

未来明确启用 OpenAI Embedding 时，配置组合为：

```dotenv
EMBEDDING_PROVIDER=openai
APP_VECTOR_BACKEND=simple
OPENAI_EMBEDDING_API_KEY=填写授权使用的OpenAI密钥
OPENAI_EMBEDDING_BASE_URL=https://api.openai.com
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
RAG_SCORE_THRESHOLD=0.55
```

Embedding 密钥缺省回落 `OPENAI_API_KEY`、地址回落 `OPENAI_BASE_URL`，绝不回落 KIMI_API_KEY 或 KIMI_BASE_URL。开启 Embedding 是独立配置选择，启动知识库初始化及后续检索可能产生 OpenAI 请求和费用。未充值或未授权时保持默认 lexical。`simple` 保存本地向量；provider profiles 默认排除 pgvector 自动配置，现有 PostgreSQL 仍保存业务数据。

## Docker

`docker compose up --build` 读取根 `.env`，使用 `LLM_PROVIDER` 选择 `kimi` 或 `openai` profile，沿用 PostgreSQL、MCP 和上传卷。容器内配置使用 env_file，未在 compose 命令中拼接 API key。Docker 运行不加载 local H2 profile。

本轮仅检查 compose 配置变更，是否实际运行 Docker 见独立测试报告；不要将配置兼容等同已部署成功。

## 验证边界

开发编译已通过。开发脚本使用独立合成 `.env` 验证 `kimi/openai` 显式切换、文字形式 `$()` 不执行及输出不包含合成密钥，测试过程中未读取真实 `.env`、未调用真实 API。协议测试由独立测试工程师使用两个回环 HTTP fixture 验证工具 reasoning 历史、密钥隔离、错误、重定向和默认零 Embedding 外呼，详见 [测试报告](TEST_REPORT.md)。真实 Kimi 联调由根代理记录在 [真实接入验收](LIVE_ACCEPTANCE.md)，OpenAI 未充值状态不做付费探测。
