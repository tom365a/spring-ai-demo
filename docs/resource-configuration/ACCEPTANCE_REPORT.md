# 统一资源配置产品验收报告

日期：2026-09-11。依据 [REQUIREMENTS_SCOPE V1.0](REQUIREMENTS_SCOPE.md) RC01–RC15 与 [PRD](PRD.md)。

结论：**RC01–RC15 逐项取得对应证据，本轮无未关闭 P0/P1，产品层面验收通过。** 该结论覆盖的是候选构建在隔离环境下的表现；**是否上线到 8080 用户应用由用户决定，本轮未部署、未改动用户数据、未改动运行中的应用**。

## 逐项验收

| RC | 结论 | 证据与验证方式 |
| --- | --- | --- |
| RC01 五个一级页面可实际操作 | 通过 | 真实浏览器：Agents/模型/工具/MCP/技能五页均从新 API 取到真实数据，含搜索、状态筛选、空态、详情弹窗；390px 窄屏逐页面逐弹窗量测。窄屏缺陷 RQA-07 已修复并复测。[ui-narrow-screen.json](evidence/ui-narrow-screen.json)、[FRONTEND_SELF_TEST](FRONTEND_SELF_TEST.md) |
| RC02 模型闭环与凭证不回显 | 通过 | 模型新增/编辑/启停/默认/连接测试在页面与接口两侧验证；`CredentialVaultTest` 10 项覆盖 AES-GCM、随机 nonce、AAD 身份、替换保留旧版本、CLEAR、白名单、缺密钥/错密钥/篡改与 JSON 脱敏；详情/列表/影响/历史/审计均不含明文 |
| RC03 同进程不同模型与凭证 | 通过 | `QaResourceModelTest` 以 8 个并发请求断言两模型两凭证互不串用；根集成 `Agent dropdown resource selection actually switches provider and model` 实测下拉切换后 provider/model/resourceId 全部改变；旧 Agent 迁移后可用见 RC13 |
| RC04 页面 HTTP 工具被真实调用 | 通过 | 整机与根集成中，页面创建的 HTTP 工具被模型实际调用，方法、路径、参数映射、认证与响应提取逐项断言；RC14 真实 Kimi 亦走同一条链路 |
| RC05 HTTP 负向约束 | 通过 | `QaResourceTransportTest` / `ResourceTransportTest` 覆盖协议、主机、端口、用户信息/片段、私网授权、元数据与保留地址、路径穿越、认证与传输 Header 覆盖、UTF-8 与分块响应上限、绝对超时；写请求结果不确定时不重试 |
| RC06 标准 MCP 与旧桥 | 通过 | 自有标准 Streamable HTTP 协议服务验证 initialize/发现/调用与会话、协议版本、错误与分页失败路径；旧 HTTP 桥单独标识并回归，未以桥冒充标准 MCP |
| RC07 多 MCP 不串用 | 通过 | 两个服务的同名原始工具生成不同身份且分别命中各自服务；同步只产生候选、发布后才生效；未勾选/已移除工具不可调用；同步失败保留上次发布目录 |
| RC08 技能与显式版本升级 | 通过 | 技能新版本发布后，未重新发布的 Agent 仍按显式 pin 注入 v1 指令（`olderExplicitSkillPinCanConfirmWhenNewerVersionAlreadyExists` 与整机 `skill version only upgrades after explicit Agent publication` 双向验证）；工具依赖去重、当前引用阻止删除、缺依赖版本回滚被拒 |
| RC09 三类工具统一确认 | 通过 | BUILTIN/HTTP/MCP × 聊天/试运行/工具测试九宫格全部通过：确认前零调用、确认后恰好一次、重放被拒、六个并发确认只有一个成功、取消与错会话零副作用、新消息使旧确认失效、资源发布新版本使待确认失效 |
| RC10 轮数/超时/记忆/转交 | 通过 | 0 轮禁止工具、11 项整批拒绝、单批两项只计一轮、单工具与任务超时、零历史窗口与摘要开关实际到达模型、主 Agent 单层最多转交 1 次且页面只读 |
| RC11 草稿/原子发布/重启恢复 | 通过 | 草稿隔离、影响 token 失效、同 token 并发发布只有一个成功、引用与删除约束、热加载；真实重启由 `QaResourceMigrationTest` 两次子 JVM 启动验证，并在关库后直接比对 `cfg_resource` |
| RC12 真实模型与工具轨迹 | 通过 | 试运行与正式聊天返回同一执行链的真实轨迹与模型诊断（provider/model/resourceId/版本/用量/耗时）；SSE 接口回归。按既有约定，Kimi 为完整响应缓冲输出，未宣称上游逐 token 流式 |
| RC13 旧功能无回归、旧数据不丢 | 通过 | `qa-legacy-regression.mjs` 8/8：主子路由、知识检索引文、只读工具直接执行、写工具确认后执行一次且重放被拒、取消零副作用、工单创建、多轮历史留存、调试配置端点。旧数据见 [MIGRATION_VERIFICATION](MIGRATION_VERIFICATION.md) |
| RC14 真实 Kimi、OpenAI 仅模拟 | 通过 | 用户授权后一次真实 kimi-k3 链路验收，2 次调用共 811 tokens，答复为夹具随机回执；同次运行中 OpenAI 资源停用且无凭证，未探测付费接口。[LIVE_ACCEPTANCE](LIVE_ACCEPTANCE.md) |
| RC15 三份报告齐全、无未关闭 P0/P1 | 通过 | [SELF_TEST_REPORT](SELF_TEST_REPORT.md)、[TEST_REPORT](TEST_REPORT.md)、本报告；缺陷 RQA-01…RQA-07 全部关闭 |

## 汇总执行结果

| 检查 | 结果 |
| --- | --- |
| Java 全量测试（12 个类） | 75/75 通过 |
| 独立整机 `qa-resource-http.mjs` | 22/22 通过 |
| 旧功能回归 `qa-legacy-regression.mjs` | 8/8 通过 |
| 根整机集成 `resource-integration-check.mjs` | 7/7 通过 |
| 真实重启迁移（含非空旧会话） | 通过，连续三次稳定 |
| 五页面与 390px 窄屏真实浏览器 | 通过 |
| RC14 真实 Kimi | 通过 |

复现命令见 [TEST_REPORT](TEST_REPORT.md)。

## 本轮明确不宣称的内容

- 不宣称模型回答质量、并发承载或长期稳定性。
- OpenAI 只有本地模拟协议证据，没有真实供应商证据。
- Kimi 的 SSE 是完整响应缓冲后输出，未验证上游逐 token 流式。
- 没有可用旧发布包，因此未做二进制回退演练；回退依赖 [MIGRATION_AND_DELIVERY](MIGRATION_AND_DELIVERY.md) 的备份与恢复说明。
- 订单/工单仍为内存模拟数据，重启丢失，与既有 Demo 定位一致。
