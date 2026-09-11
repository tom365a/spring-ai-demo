# OpenAI / Kimi 接入产品验收报告

日期：2026-09-10。验收责任人：产品 Agent。依据：[接入说明](README.md)、[真实接入验收](LIVE_ACCEPTANCE.md)、[脱敏在线结果](evidence/live-acceptance.json)、[独立测试报告](TEST_REPORT.md) 及本轮页面相关实现。

## 验收结论

**通过当前范围的产品验收，可移交项目经理结项。** Kimi K3 已取得真实官方模型与应用路径的接入证据；OpenAI 已取得独立凭证、提供方切换及本地协议验证证据。当前用户可以默认使用 Kimi 运行既有 Agent 配置、主子调用、工具和知识问答流程，并在 OpenAI 账户可用后显式选择 OpenAI。

**OpenAI 真实在线调用未验收通过：因账户尚未充值，本轮没有发起真实 OpenAI 请求。** 本次通过不等于两家真实账户均已在线可用。没有发现本轮新增未关闭 P0/P1；既有 P2 工具调用汇总缺口继续保留。

## 方法与执行归属

本次产品 Agent 仅阅读指定交付文档、脱敏 JSON 和页面相关源代码，核对各项结论是否与证据一致；没有读取 `.env`、没有调用真实 API、没有重复执行在线测试或浏览器操作。

Kimi 在线调用及浏览器实测由根协调 Agent 执行，产品引用其记录；离线协议及回归由测试工程师执行。页面源代码核对确认模型标签读取 `llmProvider / llmModel`，知识检索标签读取服务端 `retrievalMode`，使用 textContent 展示，不把默认页面文字当作模型已连接的证据。

## 逐项验收

| 项目 | 证据与产品判断 | 结果 |
| --- | --- | --- |
| 提供方选择与使用说明 | README 给出默认 Kimi、显式 `-Provider kimi/openai`、本地配置检查和常规启动命令；明示 Kimi 失败不会自动调用 OpenAI。符合当前 Kimi 可用、OpenAI 未充值的使用条件 | 通过 |
| Kimi 真实应用生效 | 在线结果 `kimi-active-embedding-disabled` 显示 kimi / kimi-k3、配置 Agent 模式启用；LIVE_ACCEPTANCE 记录官方 Moonshot 地址和真实短对话成功 | 通过，根代理在线实测证据 |
| 主子 Agent 及真实工具执行 | `main-agent-routes-to-order-and-answers` 路由到 order；`real-tool-callback-audit` 的 sessionId 与聊天一致，query_order success=true，返回本地模拟订单金额/商品/状态与回答一致 | 通过；模型真实，订单为合成业务数据 |
| 动态 Agent 创建与启用 | 在线报告记录创建默认未启用、启用发布 v1；`create-enable-real-kimi-trial` 返回同一 Agent v1、指定口令及技能提示。既有 Agent 配置测试纳入回归 | 通过 |
| 知识检索与问答 | 入库检索命中合成“星河快递”文档，metadata.retrieval=lexical；真实 knowledge 试运行按标题回答三天。证明本轮样例可用，不推断通用语义召回或答案质量 | 通过，样例及关键词检索范围 |
| 流式页面接口 | 在线结果 Content-Type 为 text/event-stream，包含最终答案及 done，无 error；README 明示 Kimi 等待完整上游结果后缓冲输出 | 通过，缓冲式 SSE；非逐 token 上游流 |
| 页面模型信息 | 根代理浏览器记录显示 kimi / kimi-k3、本地关键词检索和已启用主路由；产品核对标签绑定真实配置接口及文本展示方式 | 通过；浏览器结论引用根代理 |
| K3 工具协议 | 独立 P01/P05 验证完整 assistant 扩展字段、reasoning_content、tool_call_id 及工具结果保留；未知工具拒绝、轮次上限生效 | 通过，本地协议测试 |
| 凭证与调用隔离 | 独立 P01/P02/P06 使用两种合成密钥及错误历史通用密钥验证不串用；默认 lexical 无 Embedding 外呼；显式 embedding 仅使用独立 OpenAI 凭证和模型配置 | 通过，本地配置和协议范围 |
| 错误处理 | 独立 P03/P04 验证不跟随重定向、不回显供应商错误正文及合成密钥、路径无重复 /v1、截断返回不冒充成功 | 通过，本地负向协议范围 |
| OpenAI 兼容路径 | 独立 P02 验证同步、上游 SSE、embedding 目标/凭证/模型参数，不访问 Kimi；真实 OpenAI 账户联调尚未执行 | 本地验证通过，真实在线待验 |
| 回归 | TEST_REPORT 记录全量 24/24 通过：Agent 配置 14、关键词检索 3、Provider 7；最后配置修复后受影响 Provider 7 项于 14:49:57 复验通过 | 通过；未将最后复验说成再次全量执行 |

## 保留范围与未完成项

- **OpenAI 在线验收待完成。** 充值并明确使用该提供方后，再验证真实账户权限、模型可用性和实际应用请求；当前不能以本地 fixture 代替在线结果。
- **Kimi 为缓冲输出。** 页面能够完成 SSE 对话，但不保证上游逐 token 展示；长工具链需要等待最终响应。
- **lexical 是本地关键词检索。** 默认不调用 Embedding API，词项覆盖分数不等同向量相似度。向量和 lexical 索引分别维护，切回向量模式需按说明重新导入切换后新增内容。
- **既有 P2 保留。** ChatResponse.toolCalls 仍可能为空；本轮实际 query_order 审计证明工具执行，但响应层汇总缺口未关闭。不得宣传完整工具轨迹汇总已交付。
- **部署与业务边界不扩张。** Docker 仅检查配置、未真实部署；本轮在线环境使用隔离本地数据库和模拟订单，不证明生产数据库、多实例、真实订单系统、外部 MCP 或生产身份认证可用。
- **效果范围有限。** 当前真实 Kimi 样例证明链路可用，不构成复杂场景正确率、RAG 召回率或长时稳定性保证；试运行 `promptsRendered` 不是原始模型请求捕获，本报告没有据此声称完整检索上下文已被逐字审计。

## 交接意见

当前范围足以满足“先使用可用 Kimi 完成项目模型接入，保留 OpenAI 显式切换”的交付需求，可由项目经理结项。最终说明应将“Kimi 真实接入通过”与“OpenAI 本地协议通过、在线待验”分开呈现，并保留缓冲 SSE、关键词检索和既有 P2 的说明。

正常使用按照 README 启动，不依赖在线验收临时服务。后续 OpenAI 真实验证、逐 token 输出和工具轨迹汇总可作为独立后续工作，不应倒填为本轮已完成。
