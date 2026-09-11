# OpenAI 与 Kimi 模型接入迭代计划

编制及收口日期：2026-09-10。负责人：项目经理 Agent。最终阶段：M0–M4 已完成，Kimi K3 真实应用接入及 OpenAI 本地协议范围通过产品验收。OpenAI 真实在线调用未执行、仍待验。本文为独立新迭代，不改写已完成的 Agent 页面配置结项结论。

## 1. 目标与约束

支持通过明确配置选择 OpenAI 或 Kimi，保证 Agent 主子调用、工具往返、知识检索和聊天接口沿用现有应用链路。Kimi 本轮以根协调 Agent 已核对官方模型目录的 `kimi-k3` 为目标模型；K3 专用 ChatModel 必须保留工具往返所需的 `reasoning_content`，并通过协议及真实应用验证。

用户 OpenAI 尚未充值。本轮不将 OpenAI 付费调用作为必须执行项，不以 Kimi 验收结论推断 OpenAI 在线调用已验证。项目使用本地 lexical 关键词检索，避免知识导入、检索或启动过程触发 OpenAI embedding 调用；需要明确这是关键词检索，不能将其描述为 embedding 语义检索。

真实 Kimi Key 仅由根协调 Agent 保存在项目根 `.env` 中。本计划及项目经理不读取 `.env`、不调用 API、不记录或复制 Key。测试与报告仅记录脱敏配置、模型名称、请求类型、结果及限制；依赖凭证的真实请求由根协调 Agent 执行。

当前前端 SSE 是后端完成模型请求后发送结果的**缓冲式 SSE**，不是上游模型 token streaming。保持此语义并在使用和验收报告明确披露；本轮不把逐 token 流式响应列为已实现能力。

## 2. 角色与依赖

| 角色 | 本轮责任 | 交接证据 |
| --- | --- | --- |
| 项目经理 Agent | 计划、范围和质量门禁、证据汇总、最终收口 | 本计划及最终收口记录 |
| 产品 Agent/根协调 Agent | 明确供应商配置体验、错误提示与能力边界，最终核对用户路径 | 使用说明、逐项验收结论 |
| 全栈架构师 Agent | provider 配置、OpenAI/Kimi 模型选择、K3 协议及 reasoning_content 保留、错误处理、自测和设计说明 | 实现、设计/使用文档、自测 |
| 测试工程师 Agent | 独立离线协议测试、配置及错误路径、现有 Agent 功能回归 | 测试报告及脱敏协议证据 |
| 根协调 Agent | 用户授权下的真实 Kimi 调用、全应用联调、lexical 检索与零 OpenAI embedding 请求验证、最终用户交付 | 真实应用验收证据与执行边界 |

顺序为需求口径 → 设计及开发/自测 → 独立测试 → 真实全应用及产品路径验收 → 项目经理收口。环境探测和独立协议测试设计可以与实现并行；不得用单独短对话成功替代应用链路验收。

## 3. 阶段排期

以下为剩余工作实施前的有效角色工时估算，不是自然时钟承诺；完成时间和结果只依据实际证据填写。

| 阶段 | 负责人 | 依赖 | 预计投入 | 退出标准 |
| --- | --- | --- | --- | --- |
| M0 计划及范围 | 项目经理/根协调 | 用户需求、初步可用性检查 | 0.25–0.5 h | 本计划及供应商、检索、流式边界明确 |
| M1 接入及自测 | 架构师/根协调 | M0 | 1–3 h | provider 与 K3 往返协议可用，lexical 无 embedding 依赖，自测通过 |
| M2 独立测试 | 测试工程师 | M1 候选实现；测试准备可提前 | 1–2 h | 配置、reasoning_content、工具回调、失败路径及既有 Agent 回归有证据 |
| M3 真实应用验收 | 根协调/产品 | M2 可验收候选 | 0.5–1.5 h | 真实 Kimi 主子对话、工具、知识检索与缓冲式 SSE 用户流程完成；无隐藏 OpenAI embedding 请求 |
| M4 收口 | 项目经理 | M2/M3 报告 | 0.25–0.5 h | 交付齐全，无未关闭 P0/P1，限制逐项登记 |

实施前预计投入 3–7.5 有效角色工时，不含外部服务故障等待和重大缺陷返工。实际阶段在 2026-09-10 连续完成；未记录逐角色实际工时，不将估算冒充实际耗时。通过结论依据下列实际报告与产品签署。

## 4. 验收口径

| 编号 | 要求 | 必要证据及通过条件 |
| --- | --- | --- |
| MP01 | 供应商明确选择 | 脱敏配置示例及自动化证明 OpenAI/Kimi 选择对应模型与 endpoint；缺失/非法配置有明确结果，不静默跨供应商回退 |
| MP02 | K3 实际可用 | 根协调已核对 models 包含 kimi-k3、短对话成功仅作为预检查；应用真实模型请求成功需另行提供证据 |
| MP03 | reasoning_content 保留 | 独立协议测试验证工具调用 assistant 消息在下一轮请求中保留必要 reasoning_content，工具 ID、调用参数及结果正确关联；覆盖非工具普通回复 |
| MP04 | Agent 主子及工具链 | 真实应用选定主 Agent 后路由子 Agent并完成工具往返，结果可返回用户；请求和返回脱敏，不以单独 SDK 示例替代 |
| MP05 | 本地检索无 OpenAI embedding | 启动、样例/知识导入与检索在 lexical 模式工作；调用监测、测试替身或明确的负向断言证明这些路径未调用 OpenAI embedding；不得仅因未观察到扣费而判通过 |
| MP06 | 缓冲式 SSE 兼容 | 聊天 SSE 能传递最终结果/错误，页面可消费；文档明确等待完整上游结果，不宣称 token streaming |
| MP07 | 错误与边界 | 无效 key/模型、上游错误及不支持 provider 至少有离线失败测试；避免泄漏 key，不为测试故意消耗真实无效请求或购买配额 |
| MP08 | 回归质量 | 既有 Agent 创建、启用、运行版本、主子/技能/工具等必要回归通过，新增接入不破坏已验收闭环 |
| MP09 | OpenAI 兼容但未充值 | 离线配置/协议兼容验证通过；在线付费调用标记未执行，不把它列为真实云端通过项，也不阻断 Kimi 范围收口 |
| MP10 | 交付和凭证保护 | 提供配置/启动/模型与检索说明、测试报告、真实验收结论；Key 不进入文档/日志证据，旧结项文档保持原有结论 |

P0：凭证严重泄漏、数据破坏或系统整体不可用。P1：目标 Kimi 模型无法完成应用核心主子/工具流程、协议信息丢失、显式配置失效导致调用错误供应商、lexical 仍触发收费 embedding、关键回归失败。任何未关闭 P0/P1 阻止本轮通过。

## 5. 交付清单

| 实际交付 | 内容 | 状态 |
| --- | --- | --- |
| [PROJECT_PLAN.md](PROJECT_PLAN.md) | 角色、排期、验收口径、证据与收口 | 已交付 |
| [README.md](README.md) | 两提供方配置、K3 完整工具消息、密钥隔离、检索、启动与缓冲式 SSE 设计/使用说明 | 已交付 |
| Provider/K3 实现、lexical 检索实现、自动化测试与启动配置 | Kimi 专用 ChatModel、OpenAI 显式选择、工具协议、默认零 embedding、本地检索及安全加载配置 | 已交付；由独立测试及真实验收支持 |
| [TEST_REPORT.md](TEST_REPORT.md) | 7 项独立协议、24 项全量回归、最后受影响配置复验及边界 | 已交付 |
| [LIVE_ACCEPTANCE.md](LIVE_ACCEPTANCE.md)、[live-acceptance.json](evidence/live-acceptance.json) | 根协调 Agent 真实 Kimi 应用链路、工具审计、检索、动态 Agent、SSE 及页面证据 | 已交付，已脱敏 |
| [ACCEPTANCE_REPORT.md](ACCEPTANCE_REPORT.md) | 产品证据审查、范围及正式通过结论 | 已交付 |
| `scripts/dev.ps1`、`scripts/qa-kimi-live.mjs`、`.env.example`、compose 配置 | 常规启动与显式提供方切换、在线验收脚本、配置示例 | 已交付；Docker 未真实部署验收 |

## 6. 风险与处理

| 风险 | 处理 |
| --- | --- |
| K3 推理字段在工具往返丢失 | 已解决本轮范围：P01/P05 对完整 assistant/reasoning_content/扩展字段/工具关联断言通过；真实 query_order 审计成功 |
| OpenAI 未充值及隐式 embedding 请求 | 默认 lexical 无 EmbeddingModel、离线零网络断言及在线 disabled/lexical 状态通过；OpenAI 在线未验保留，显式 embedding 使用独立配置及凭证 |
| 单独短对话成功被扩大为系统验收 | 已取得 LIVE_ACCEPTANCE 的全应用主子、工具、动态 Agent、知识与 SSE 证据；产品正式核对通过 |
| 关键词检索效果不及语义检索 | 保留边界：本轮样例检索成功，不宣称语义召回质量；索引分开维护，切换需按 README 重新导入新增内容 |
| 缓冲式 SSE 被误认为 token streaming | 文档、测试、在线及产品报告均明确缓冲语义；Kimi 上游逐 token 流式仍未实现 |
| 真实 Key 被复制到交付物 | 根协调已完成源码/docs/scripts/.env.example/compose 精确 Key 扫描，未发现泄漏；项目经理未读取 .env，报告脱敏 |
| 远程配额、网络、模型变动 | Kimi 当前实际链路通过，不保证未来服务状态、复杂任务正确率或长时稳定性；OpenAI 充值后另做在线联调 |
| 既有工具轨迹 P2 | 保留旧阶段 ChatResponse.toolCalls 汇总缺口；本轮使用真实工具审计证明执行，不将此项关闭 |
| 临时验收服务与生产范围 | 根协调确认真实验收服务及测试浏览器已关闭；Docker/生产数据库/多实例/外部业务与 MCP 未验收 |

## 7. 进度与证据

| 阶段 | 当前状态 | 证据与备注 |
| --- | --- | --- |
| M0 | 已完成 | 根协调交接需求与初步检查；本计划落盘 |
| 预检查 | 已完成（根协调提供） | 官方 models 包含 kimi-k3、短对话成功；项目经理未自行读取凭证或调用 API |
| M1 | 已完成 | README/实现及开发验证；架构师 14:43:06 编译交接，K3 完整工具历史、provider 隔离、lexical 与配置加载落地；后续 embedding 模型绑定补齐并复验 |
| M2 | 已完成 | TEST_REPORT：14:45:06 全量 24/24（原配置14+lexical3+provider7），0失败/错误/跳过；最后配置修复后 Provider 7/7 于14:49:57复验，未误称此时重跑全部24项 |
| M3 | 已完成 | LIVE_ACCEPTANCE/脱敏 JSON：真实 kimi-k3 主子路由/query_order success审计、入库检索、知识回答、新建启用v1真实试运行、缓冲SSE、页面模型与检索栏；产品 ACCEPTANCE_REPORT 正式通过当前范围 |
| M4 | 已完成 | 项目经理已读取设计/使用、独立测试、真实验收及产品报告后收口；无本轮新增未关闭P0/P1，OpenAI在线待验、旧P2与部署/流式边界保留 |

### 验收口径核对

| 编号 | 结项依据 | 结论 |
| --- | --- | --- |
| MP01 | Provider P01/P02/P06 独立端点/密钥及缺失密钥拒绝；README显式切换且不跨提供方回退 | 通过当前配置/协议范围 |
| MP02 | LIVE_ACCEPTANCE官方 models/短对话及真实应用 kimi-k3结果 | Kimi真实通过 |
| MP03 | P01/P05 多轮完整assistant/reasoning_content/未知字段、tool_call_id及工具结果断言 | 通过 |
| MP04 | 真实主路由order、query_order审计success=true，session与回答一致 | 通过，订单为本地模拟数据 |
| MP05 | P07无EmbeddingModel及添加/查询两服务零请求；在线embedding disabled、lexical检索与知识问答 | 通过默认本地关键词检索范围 |
| MP06 | 真实text/event-stream、最终答案及done无error；Kimi上游stream=false | 缓冲式SSE通过，非上游token streaming |
| MP07 | P03/P04/P05/P06重定向不泄密、HTTP异常不回显正文、截断/未知工具/超轮次/缺失密钥拒绝 | 通过已执行离线负向范围 |
| MP08 | 全量24项通过；最后受影响Provider7项复验通过 | 通过 |
| MP09 | OpenAI P02同步/SSE/embedding及提供方/凭证/模型隔离 | 本地协议通过，真实在线未执行 |
| MP10 | 交付报告齐全，产品正式签署；根协调精确Key扫描无泄露并确认服务关闭；旧配置结项文档未改 | 通过 |

## 8. 最终收口

2026-09-10，项目经理根据独立测试和产品正式验收完成 M4：**当前范围交付完成。Kimi K3 真实官方服务与应用流程通过；OpenAI 本地配置与协议通过，真实在线调用未执行、待账户可用后另行验收。未发现本轮新增未关闭 P0/P1。**

证据包含全量 24/24、最终受配置修复影响的 Provider 7/7、真实 Kimi 主子调用与工具审计、动态 Agent 启用试运行、知识导入检索问答、缓冲式 SSE 和页面状态。产品仅审查报告/脱敏证据/页面源码；真实在线与浏览器操作归属根协调 Agent，未把报告审查冒称产品再次在线执行。

旧阶段 `ChatResponse.toolCalls` 汇总 P2 保留；Kimi 上游逐 token streaming、OpenAI 在线、Docker实际部署、生产身份认证、多实例、生产数据库、外部业务/MCP及复杂模型效果不属于已通过范围。lexical 是本地关键词检索，默认不调用收费 embedding。

根协调 Agent 已确认关闭临时真实验收服务及浏览器测试页。正常使用在项目根运行 `.\scripts\dev.ps1 -Action run -Provider kimi`，默认端口8080；明确使用OpenAI时改为 `-Provider openai` 并具备有效账户配置，应用不会从Kimi失败自动回退OpenAI。操作与配置详见本目录 README。项目经理本轮未读取 `.env`、未调用真实 API、未重跑测试，也未修改上一轮 Agent 配置结项文档。
