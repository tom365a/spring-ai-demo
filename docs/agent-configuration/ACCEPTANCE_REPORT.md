# Agent 页面配置产品验收报告

日期：2026-09-10。验收责任人：产品 Agent。阶段：S5，前置为测试工程师正式交付 [TEST_REPORT.md](TEST_REPORT.md)。需求基线：[PRD.md](PRD.md) v1.0，含最终统一的“允许停用被主引用子 Agent，运行时动态过滤”规则。

## 1. 验收结论

**通过本轮 Agent 页面配置功能验收，可移交项目经理 S6 收口。** 在单实例 local 环境及受控模型调用链范围内，用户可以从页面完整创建 Agent、点击启用自动发布加载，并在正式聊天页选择新主 Agent 调用其子 Agent。工具和技能已验证实际进入运行请求，配置不再只是页面保存的数据。

AC01–AC18 均取得与本轮范围相符的证据；没有未关闭 P0/P1。保留一项非阻断 P2：自动 ToolCallback 已执行，但 ChatResponse.toolCalls 尚未汇总这些自动调用事件。该项已进入 [BACKLOG.md](BACKLOG.md)，不把工具真实执行表述为完整工具审计功能已经完成。

本结论不表示整个项目功能全部完成，也不表示生产身份认证、真实云模型效果、外部 MCP、多实例或生产数据库部署已通过验收。

## 2. 验收方法与证据来源

本次产品验收执行了三类核对：

1. 逐项复核 PRD 与架构、前后端详细设计及候选源代码；重点读取创建/技能表单、生命周期事务、运行注册表、技能注入与工具回调、主 Agent 入口及停用过滤实现。
2. 核对测试工程师的 14 项 JUnit 原始文本结果、13 个实际 HTTP 阶段检查、14 条实际主/子模型请求与四次 JVM 启动记录；核对根协调 Agent 的真实浏览器操作报告与页面运行数据。
3. 产品 Agent 直接对最新 `127.0.0.1:8080` 服务进行只读 HTTP 复核：运行目录、验收子 Agent 详情、主 Agent 详情、技能目录、名称/类型/启用组合筛选、历史版本。实际响应已保存为 [PRODUCT_READONLY_EVIDENCE.json](evidence/PRODUCT_READONLY_EVIDENCE.json)。确认配置模式开启，子 Agent `agent_02a1b3db9ecf4a6a` 已发布/加载 v2、技能为 clear_response/order_lookup；主 Agent `ui_accept_main` 已加载 v1 并关联该子 Agent，过滤查询正确返回一项。

产品 Agent 尝试独立打开浏览器时工具返回 `Browser is not available: iab`，因此本次未完成产品 Agent 自己的浏览器操作。页面实测结论明确引用根协调 Agent 已完成的 [UI_TEST_REPORT.md](UI_TEST_REPORT.md)，并由产品审查其 [UI_RUNTIME_EVIDENCE.json](evidence/UI_RUNTIME_EVIDENCE.json) 及对应源代码；不将只读 HTTP 或报告审查冒充新的浏览器实测。

主要证据：

- [TEST_REPORT.md](TEST_REPORT.md)：独立测试范围、缺陷及复测记录。
- [AgentConfigurationTest.txt](evidence/AgentConfigurationTest.txt)、[QaAgentConfigurationTest.txt](evidence/QaAgentConfigurationTest.txt)：各 7 项测试，合计 14 项，0 失败、0 错误、0 跳过。
- [QA_RUNTIME_EVIDENCE.json](evidence/QA_RUNTIME_EVIDENCE.json)：HTTP 断言、实际模型请求、重启进程记录。
- [UI_TEST_REPORT.md](UI_TEST_REPORT.md) 及 [UI_RUNTIME_EVIDENCE.json](evidence/UI_RUNTIME_EVIDENCE.json)：浏览器完整业务流程与配套运行数据。
- [SELF_TEST_REPORT.md](SELF_TEST_REPORT.md)、[ARCHITECTURE.md](ARCHITECTURE.md)、[DETAILED_DESIGN.md](DETAILED_DESIGN.md)：设计、自测和实现范围。

## 3. PRD 逐项验收

| ID | 产品核对结果与依据 | 结论 |
| --- | --- | --- |
| AC01 | UI 报告证明完整创建名称、简介、提示词、工具及技能；产品直接读取已发布内容，中文及特殊文本完整保留 | 通过 |
| AC02 | 页面 code 留空创建成功，QA auto-code-full-draft 记录 enabled=false；新建无运行项；前端一次提交完整 definition | 通过 |
| AC03 | UI 点击启用后显示加载 v1；QA 启用后立即正式聊天成功；产品复核当前版本与 Registry 一致 | 通过 |
| AC04 | QA draft-isolation 与浏览器 V1→草稿 V2→发布 V2 流程均捕获实际模型系统提示；草稿保存未提前生效 | 通过 |
| AC05 | 实际请求工具为 query_order/query_logistics，手选与技能依赖去重；后续 role=tool 消息含示例订单成功返回；全部移除并发布后 tools 消失 | 通过 |
| AC06 | QA 实际系统消息包含技能正文，发布移除后正文和派生工具消失，回滚恢复；KnowledgeService 调用及检索结果注入由 JUnit 验证；产品直接核对可信目录说明和适用类型 | 通过，知识分支为调用链验证 |
| AC07 | UI 类型切换控制子关联区，主 Agent 候选仅启用 WORKER；QA 拒绝 WORKER 关联、自引用、重复、引用主 Agent、不存在及空子项；主无有效子启用拒绝 | 通过 |
| AC08 | UI 新主 Agent 可在正式聊天页选择并通过 SSE 调度新 Worker；QA HTTP 独立完成任意 code 主→子→工具往返；产品复核两者当前运行及关联状态 | 通过 |
| AC09 | QA negative 阶段在合法 userId 前提下验证不存在、WORKER、停用主入口拒绝；代码显式校验 supervisorCode，无静默默认回落 | 通过 |
| AC10 | UI 和 QA 均允许停用被引用唯一 Worker，随后主 Agent 返回无可用子提示，未新调度子模型；恢复后可用；主重新启用/发布会重新验证关联 | 通过 |
| AC11 | QA 数据库 beforeCommit 异常与实际约束失败未污染运行版本，8 轮发布/停用竞争保持一致；双线程启用幂等及 revision 冲突通过；前端请求期间禁用提交 | 通过，单 JVM 范围 |
| AC12 | QA 证据包含 PID 10184→15644 的实际进程重启，文件数据库恢复发布 v3/技能、隔离草稿并保留 Seed 修改；产品在最新重启实例再次确认 UI Worker v2/Main v1 已恢复 | 通过 |
| AC13 | QA 覆盖空白、长度边界、未知工具/技能/变量、非法关系、JSON/code/type；UI 窄屏提交必填错误后保留名称；代码使用表单错误区展示服务端错误 | 通过 |
| AC14 | QA viewer/editor/非法角色的启停发布回滚均拒绝，PUT enabled 不能绕过；publisher 正常使用；UI 角色按钮与开关已复测 | 通过，演示角色门禁范围 |
| AC15 | 默认模式实际加载；QA 第三 JVM 显式关闭配置模式后运行接口无项目、启用及显式主聊天 409；页面关闭提示已做代码检查，未另执行关闭模式浏览器流程 | 通过，后端执行及页面代码核对 |
| AC16 | QA 版本、发布快照试运行、回滚为新版本、skills 缺省兼容通过；产品直接复核 v1/v2 历史及组合过滤返回正确项目；UI 编辑回显正常 | 通过，本轮关联回归范围 |
| AC17 | 根协调 Agent 在真实管理页 390×650 同源 iframe 验证单列可滚动提交，错误保留内容及特殊文本安全显示；产品核对前端动态值转义和响应式实现 | 通过，布局视口范围 |
| AC18 | PRD/原型、架构/详细设计、自测、独立测试、页面证据及本报告完整；剩余能力单独登记 BACKLOG，执行与证据来源区分清楚 | 通过 |

## 4. 产品流程核对

当前核心路径符合用户目标：创建页面一次输入业务信息及能力，生成未启用草稿；详情点击启用完成后端校验、发布和加载；创建主 Agent 时选择已启用子 Agent，再去聊天页刷新列表并选择该主 Agent；修改草稿后通过“发布更新”让新版本生效。后端自动生成标识，用户无需编写 Agent 类或手动注册运行实例。

列表、详情和聊天入口均由真实运行目录辅助展示状态。发布与启用在事务成功后更新不可变 Registry 快照；失败证据未见未提交版本泄漏。停用被引用子 Agent 的统一产品规则已经写入 PRD、架构设计、测试与实际实现，没有残留“必须先解除依赖才允许停用”的门禁。

本轮技能为项目内置的清晰表达、订单查询及知识检索问答，不涉及 Codex 本机技能或任意上传执行。页面目录说明了依赖能力，运行层应用技能指令、工具合并或知识检索。生成的 PRD 原型是设计参考，未作为真实业务实现证据。

## 5. 保留项与验收边界

| 项目 | 判定与后续处理 |
| --- | --- |
| QA-01，P2 工具调用汇总 | 开放、不阻断。工具实际执行已由协议往返和返回结果证明，但 ChatResponse.toolCalls 为 []；后续补齐调用事件与审计汇总 |
| 受控模型 | 实测本地 OpenAI 兼容服务验证 Spring AI 协议、提示词、工具与路由，不证明真实云模型回答质量、复杂自然语言路由质量或外部 MCP 可用性 |
| 知识检索效果 | 自测证明 KnowledgeService 被调用及结果注入，未验证真实知识库检索召回率和答案引用质量 |
| 单实例部署 | 事务并发、运行一致性和重启在单 JVM/H2 验证；多实例同步、PostgreSQL 部署与磁盘故障未验收 |
| Demo 权限 | X-Admin-Role 角色门禁已验，不具备服务端真实身份认证；不能称为可直接公开部署的生产管理端 |
| 浏览器覆盖 | 完整页面操作来源为根协调 Agent 的实测；产品此次只读复核。390px iframe 验证布局，不替代真实移动设备兼容性 |
| 其他已有高级能力 | 模型高级参数、结构化输出、每 Agent 记忆、工具预算、标准 MCP 协议等历史待完成能力参见 BACKLOG；未被本轮验收扩大为已完成 |

## 6. 交接

产品 Agent 同意项目经理将本轮 Agent 配置闭环记为验收通过并完成项目收口；项目经理应在最终交付说明中保留上述 P2 和环境范围，提供常规启动方式及文档入口。验收使用的受控模型服务不应作为用户真实模型环境继续默认运行。
