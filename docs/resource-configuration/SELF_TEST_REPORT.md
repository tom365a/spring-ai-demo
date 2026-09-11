# 架构师功能自测报告
日期：2026-09-10。状态：开发自测通过的已执行项如下；独立QA、产品验收和迁移最终结论由对应报告确认，不以本报告替代。

## 环境与命令
Windows，Java21，SpringBoot3.4.2，SpringAI1.0.0。测试使用内存H2、合成凭证、仅loopback HTTP服务，没有读取真实.env或请求OpenAI/Kimi公网。

```powershell
$env:JAVA_HOME='C:/spring-ai-demo/.tools/jdk-21.0.12.1+1'
& .tools/apache-maven-3.9.9/bin/mvn.cmd -B -o '-Dmaven.repo.local=C:/spring-ai-demo/.tools/m2' -f apps/java-gateway/pom.xml test
```

最终候选全量套件 **75/75 通过**（12 个测试类），日志 `.tools/continue-full-test.log`。

## 已执行证据
| 套件 | 数量/结论 | 覆盖 |
| --- | --- | --- |
| CredentialVaultTest | 10/10通过，凭证协作者执行 | AES-GCM、随机nonce、AAD身份、替换保留旧版本、CLEAR、白名单、缺密钥/错密钥/篡改、JSON脱敏 |
| ResourceTransportTest及ResourceModelFactoryTest | 10项通过（8+2），根协作者 | HTTP映射/限额/目标DNS固定、MCP会话协议/同名隔离、模型供应商目标与凭证隔离、完整reasoning历史 |
| ResourceRuntimeSelfTest | 8/8通过（最终候选） | 真实HTTP写工具确认前0调用/确认后1调用/重放拒绝；0轮、11项整批拒绝；同批read+write计1轮且恢复不重做read；停用使确认失效；工具测试走同网关 |
| AgentConfigurationTest | 7/7通过，17:13 | 原创建/发布/草稿隔离/回滚/技能提示与工具/动态主子路由/角色校验回归 |
| Java主源码 | 92文件编译成功，17:13:53 | 新资源、执行器、API及旧业务完整编译 |

Maven日志：`.tools/resource-regression.log`、`.tools/resource-compile.log`；标准结果：`apps/java-gateway/target/surefire-reports`。旧路由测试已把模型mock从全局ChatModel注入改为每资源ResourceModelFactory，仍断言真实主子路由和停用行为。

根整机集成证据见 [evidence/root-resource-integration.json](evidence/root-resource-integration.json)，复现脚本 `scripts/resource-integration-check.mjs`。17:03 那份对应早期编译，已在最终候选上重跑，7/7 通过并覆盖原文件。

## 开发中发现并修复
- 配置页可伪造BUILTIN执行身份/把固有WRITE标记READ：新建仅HTTP，既有source/执行身份不可变，内置风险与schema不可改。
- HTTP路径含{id}被URI校验误拒：仅解析阶段替换路径占位，authority禁止占位，实际映射仍由安全传输编码。
- MCP schema绕过同步修改（独立QA RQA-05）：工具schema只随发现更新；服务目录只能同步增删与改定义，管理员只改已发现项风险/确认规则。
- 同会话确认并发与旧确认穿插：聊天和确认共用会话锁；新消息令原待确认失效；数据库悲观锁领取后外呼。
- 完成回复含旧PENDING轨迹影响历史：领取后移除待确认轨迹，正式完成公开问答才计历史轮。
- 工具诊断与实际失败不符：业务拒绝、传输安全错误、超限先判定再保存审计；写请求结果不确定不重试。
- 新运行时遗漏附件：已接入服务端附件加载、会话归属、模型vision能力和内联Media。
- 草稿测试字段仅显示未记录：保存对应revision的连接状态，后续改稿使旧测试过时。

## 收尾阶段修复

- `ResourceRuntimeSelfTest` 停在半途的编辑：`AgentDetailResponse` 没有 `definition()`，技能显式 pin 用例取草稿应为 `detail.draft()`。修正后测试编译并通过，整个测试模块恢复可运行。
- `QaResourceMigrationTest` 无法起子 JVM：夹具 `environment().clear()` 连 `TEMP`/`TMP` 一并清空，Windows 上 `java.io.tmpdir` 落到 `C:\WINDOWS\`，Tomcat 建临时目录被拒。改为清空后显式给子进程一个私有临时目录（并传 `-Djava.io.tmpdir`），保留“干净环境、无真实 Key”的原意。
- 同一夹具原本用 `process.destroy()` 强杀子 JVM（Windows 上即 TerminateProcess），且在 `/actuator/health` 一返回 200 就查询——而 `ApplicationRunner` 的种子迁移要到 `ApplicationReadyEvent` 才结束。两者叠加会让第二次启动读到只写了一半的库，看起来像“迁移不幂等、资源 ID 变了”。改为等 `/actuator/health/readiness` 变 UP、经 actuator 优雅关闭并断言退出码为 0，并在关库后直接查 `cfg_resource` 与 API 返回逐条比对。连续三次独立执行稳定通过：这是夹具缺陷，不是迁移缺陷，但原先的写法无法证明迁移，现在可以。
- `GlobalExceptionHandler` 的兜底分支返回 “请根据 traceId 排查服务端状态”，却从不记录该 traceId，日志里查不到任何对应条目。补上带 traceId 的 `log.error`；本轮第一个整机故障（缺 httpclient5 依赖导致的 `NoClassDefFoundError`）正是靠它一次定位。
- 窄屏动作条与 Agent 表格（独立 QA 记为 RQA-07）：`.resource-actions button{flex:1}` 的 flex-basis 为 0 使按钮永不换行，改为 `flex:1 1 auto;min-width:104px`；`#agentTable` 单元格加 `white-space:nowrap`，让本就 `overflow:auto` 的表格横向滚动而不是压缩列宽。

## 待独立门禁确认
全部门禁已关闭，产品验收见 [ACCEPTANCE_REPORT](ACCEPTANCE_REPORT.md)。RC01–RC13/RC15 的独立证据见 [TEST_REPORT](TEST_REPORT.md)，RC14 真实 Kimi 见 [LIVE_ACCEPTANCE](LIVE_ACCEPTANCE.md)。开发阶段通过不等于用户应用已经升级；本轮没有修改运行中的 8080，开发与独立测试也没有读取根 `.env`（只有经用户授权的 RC14 由专用启动脚本把 Key 送进子进程环境）。

