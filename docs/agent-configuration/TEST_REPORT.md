# Agent 配置独立测试报告

日期：2026-09-10；测试负责人：测试工程师 Agent。范围：PRD v1.0，单实例 Spring Boot local 配置闭环。候选由全栈架构师在 11:22:24 自测后交接；独立测试从交接后开始。

## 结论

本轮已执行范围内，核心 Agent 配置流程通过；**未发现未关闭 P0/P1，允许移交产品验收**。有 1 项非阻断 P2 工具轨迹汇总缺口，见缺陷表。该结论限于下述环境和证据，不等同于生产鉴权、真实云模型质量或外部 MCP 验收。

- 全量自动化：14 项，0 失败、0 错误、0 跳过，其中架构师自测 7 项、独立 QA 新增 7 项。
- 实际 HTTP：完整创建、启用、正式主子对话、Spring AI 工具往返、能力移除、草稿隔离、回滚、启停、依赖、试运行、实际进程重启、显式关闭模式、待确认动作约束均执行。
- 浏览器：由根协调 Agent 独立操作真实页面并提供 [UI_TEST_REPORT.md](UI_TEST_REPORT.md) 及 [页面运行证据](evidence/UI_RUNTIME_EVIDENCE.json)，本报告审核并引用，不将 API 脚本冒充页面操作。

## 环境和可复现证据

Windows、JDK 21.0.12.1、Maven 3.9.9、Spring Boot 3.4.2、Spring AI 1.0.0。JUnit 使用独立 H2 内存库；HTTP 使用 `jdbc:h2:file:./data/qa-acceptance;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE`，端口 18080，与页面验收 8080 及数据库隔离。模型替身仅监听 `127.0.0.1:18091`，不调用远程模型。

执行命令：

```powershell
./scripts/dev.ps1 -Action test -Offline
node scripts/qa-agent-http.mjs before
# 关闭实际 JVM，再以相同 H2 文件数据库启动应用
node scripts/qa-agent-http.mjs after
node scripts/qa-agent-http.mjs confirm
# 以 --app.agent-config.enabled=false 重启同一应用
node scripts/qa-agent-http.mjs disabled
# 恢复默认配置模式启动后，补验正式入口负向请求
node scripts/qa-agent-http.mjs negative
node scripts/qa-agent-evidence.mjs
```

HTTP 脚本要求本地应用与模型桩已启动；模型环境变量 `SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:18091`、`SPRING_AI_OPENAI_API_KEY=local-test-key`。脚本只写入本地合成测试 Agent 和测试订单，不访问外部订单系统。实际 Java 启动命令及日志由 `.tools/qa-server*.log`、`.tools/classpath.txt` 支持，测试数据保留供复核。

正式自动化结果：2026-09-10 11:28:51 `BUILD SUCCESS`，详见 [架构师测试结果](evidence/AgentConfigurationTest.txt)、[独立测试结果](evidence/QaAgentConfigurationTest.txt)。原始构建日志 `.tools/qa-maven.log`。HTTP 断言、实际模型请求及 JVM 启动 PID 统一保存在 [QA_RUNTIME_EVIDENCE.json](evidence/QA_RUNTIME_EVIDENCE.json)。

## 测试矩阵与执行结果

| PRD | 实际验证与证据 | 结果 |
| --- | --- | --- |
| AC01–02 | 页面一次填写完整业务字段并留空 code；HTTP 保存中文、特殊文本、提示词、工具、技能；新建 enabled=false、Registry 无项；自动生成标识 | 通过 |
| AC03 | 专用 enable 生成 v1，runtime 返回同版；页面显示已启用并加载；随后正式模型请求成功，无重启 | 通过 |
| AC04 | QA_HTTP_V2 保存草稿后实际模型仍收到 V1；发布后收到 V2；浏览器独立重复 V1→V2 流程 | 通过 |
| AC05–06 | V1 请求系统含清晰表达、订单查询两段技能正文；手选 query_order 和技能依赖合并为 query_order/query_logistics；工具实际返回 ORD20260730001、ok=true；删除全部工具技能发布 V2 后请求无技能正文和 tools；回滚 v3 恢复能力 | 通过 |
| AC06 补充 | knowledge_answer 自测捕获 KnowledgeService 检索调用及结果注入；移除后不调用。此分支使用服务替身验证调用链，未评估真实 RAG 检索质量 | 通过（调用链范围） |
| AC07 | API 拒绝 worker 携带子关联、自关联、重复、引用主、引用不存在、null 子项；主无可用子启用失败；页面仅主展示关联 | 通过 |
| AC08 | 页面选择新主走 SSE；独立实际 HTTP 新主→自动生成 worker code→模型和读取工具两轮往返。证据包含主/子各自系统提示词 | 通过 |
| AC09 | 使用合法 userId，显式 WORKER、不存在和停用主标识请求拒绝；不以缺 userId 的 400 作为此项证据 | 通过 |
| AC10 | 允许停用正在被引用的 worker；Registry 移除；同一主零可用子返回明确提示，无新的子模型调用；主重新启用时因依赖停用返回 422；恢复子后主可启用 | 通过 |
| AC11 | QA 在真实 JpaTransactionManager 提交阶段注入 beforeCommit 异常：事务回滚、历史仅 v1、Registry 仍 V1；另以超长 remark 触发 H2 约束失败，未新增版本或启用残留；8 轮发布与停用并发后 DB/Registry 一致；同时启用幂等、同 revision 竞争恰好一成功一冲突 | 通过 |
| AC12 | 真正结束 PID 10184 后新 JVM PID 15644 使用同一 H2 文件：运行 v3/技能恢复，未发布草稿不加载，已启用 Agent 的未发布改动仍隔离；默认 Seed 上用户修改保留；重启后实际工具再次成功 | 通过 |
| AC13 | 空白名称/提示词、129 字符名称、20001 字符提示词、未知变量、未知或 null 工具/技能、非法类型/code、重复 code、非法 JSON 返回 4xx，未创建非法行；窄屏表单错误后名称保留 | 通过 |
| AC14 | viewer/editor/非法角色尝试 enable/disable/publish/rollback 均 403；editor PUT enabled=true 绕过被 400 拒绝；publisher 正常启停；页面角色显隐 | 通过 |
| AC15 | 默认配置模式真实加载；第三 JVM 显式关闭模式 runtime=false 且 items=[]，enable 与显式主聊天返回 409；管理页读取该标志并显示关闭模式提示已查代码，关闭模式页面未另做浏览器操作 | 通过（后端运行与页面代码检查） |
| AC16 | 版本列表、发布快照试运行、回滚形成 v3 并恢复技能；旧配置无 skills 可正常创建及 Seed 启用；列表/回显/角色切换见 UI 报告 | 通过 |
| AC17 | 实际管理页 390×650 同源 iframe：单列可滚动至提交、必填错误保留内容；特殊文本按文本显示 | 通过（布局视口验证） |
| AC18 | PRD/原型、架构、详细设计、自测和本报告存在且与结果对应；产品验收报告应在本报告交接后由产品签署 | 待产品签署最终文档 |

额外回归：取消订单进入服务器待确认状态后，停用来源 Agent 或移除 cancel_order 并发布，再提交确认均 409。请求同时携带伪造旧工具/Agent 的 confirmPayload，仍无法绕过服务端保存的动作与当前能力检查。

## 缺陷与复测

| 编号 | 等级 | 描述与处理 | 状态 |
| --- | --- | --- | --- |
| DEV-01 | P1 | 开发期启用仅改 enabled、无真实版本；专用生命周期操作已补齐，页面与独立 HTTP/重启回归通过 | 已关闭 |
| DEV-02 | P1 | 开发期 ToolCallback 使用错误绑定接口导致工具未绑定；候选改用 toolCallbacks，实际协议往返与订单成功结果复测 | 已关闭 |
| DEV-03 | P1 | 开发期多元素选择器误用阻断页面初始化；修复后完整页面流程重跑通过 | 已关闭 |
| DEV-04 | P2 | 页面角色变化后列表开关状态未立即刷新；根协调 Agent 已在 viewer→publisher 流程复测通过 | 已关闭 |
| QA-01 | P2 | 自动 ToolCallback 已执行，但 ChatResponse.toolCalls 仍为 []，对话调试汇总不完整。工具执行证据来自实际模型 role=tool 和订单返回；详见 BACKLOG 工具轨迹汇总项 | 开放，不阻断本轮配置闭环 |

独立 QA 第一轮出现 1 项测试请求问题：publish 负向权限请求误带不支持的 version 字段，因此请求反序列化先返回 400。改为符合接口的请求后全权限矩阵通过，未作为业务缺陷。HTTP 脚本一度缺少 userId，已修正并单独重跑主入口负向断言；不沿用该次误判。

## 验证边界

- 模型为本地确定性替身；验证真实 Spring AI 请求、提示词、工具执行和路由，不证明真实云模型回答、模型选择参数、结构化输出质量或外部 MCP 服务可用性。
- 现有 X-Admin-Role 为演示权限控制；本报告验证角色门禁，未把它认定为生产身份认证。
- 并发和重启覆盖单 JVM/单实例及 H2；多实例同步、PostgreSQL 部署、断电/磁盘损坏和真实移动设备兼容性未执行。
- AC18 的产品最终签署属于下一阶段；其他后续项目能力明确列于 [BACKLOG.md](BACKLOG.md)，不以本轮“无未关闭 P0/P1”推断整个系统没有任何缺陷。
