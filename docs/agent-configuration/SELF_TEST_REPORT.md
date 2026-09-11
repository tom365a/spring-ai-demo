# Agent 配置开发自测报告

责任人：全栈架构师 Agent；日期：2026-09-10；候选：S3 首次交接。

## 结论

已完成核心前后端实现；开发自动化自测 **7 个测试、0 失败、0 错误**。独立全流程测试与产品验收尚由对应角色执行，本报告不替代它们。

执行：`powershell -File scripts/dev.ps1 -Action test -Offline`。2026-09-10 11:22:24 最终复验构建成功；日志 `.tools/architect-selftest.log`，JUnit XML/文本报告位于 `apps/java-gateway/target/surefire-reports/`。环境：Windows、项目便携 JDK 21.0.12、Maven 3.9.9、Spring Boot 3.4.2、Spring AI 1.0.0、本地 H2 独立内存库。首次下载 Surefire JUnit provider 后离线测试可运行。

## 测试结果

| 测试 | 实际验证 | 结果 |
| --- | --- | --- |
| lifecycleKeepsDraftSeparateAndRestoresPublishedSnapshot | 新建未启用、启用v1、保存草稿运行仍v1、发布v2、回滚产生v3并恢复技能、停用/启用、重载Registry | 通过 |
| skillsAndToolsReachRealChatClientPromptAndRemovalStopsThem | 捕获真实ChatClient传给ChatModel的Prompt：技能正文注入、工具回调合并去重、KnowledgeService真实方法调用及结果注入；移除技能/工具后不再有对应内容或检索调用 | 通过 |
| arbitraryMainRoutesToConfiguredWorkerAndDisabledMainIsRejected | 自定义主Agent经正式编排路由到自定义Worker；停用最后Worker后可读提示；停用主Agent后请求拒绝 | 通过 |
| serverRejectsInvalidRelationshipsAndCapabilities | Worker携带关联、无权限写工具、未知技能和未知模板变量拒绝 | 通过 |
| concurrentDraftRevisionRejectsOneWriter | 两线程相同revision更新，恰好一个成功，一个冲突 | 通过 |
| concurrentEnableIsIdempotent | 两线程启用同一草稿，两次均返回v1，Registry亦v1 | 通过 |
| apiPermissionsAutoCodeAndMalformedInput | viewer创建拒绝、editor自动code创建未启用、editor启用拒绝、publisher启用成功、非法JSON返回400 | 通过 |

## 已修复开发期缺陷

1. 原运行时使用 `tools(ToolCallback[])` 导致工具被当成普通注解对象，实际不绑定。改为 `toolCallbacks(...)` 并以模型参数捕获验证。
2. 原启用仅修改布尔值，创建默认启用但未发布。改为专用生命周期操作和草稿默认未启用。
3. 原运行时变更发生在事务提交前，改为提交后切换不可变Registry Map。
4. 原固定主入口与 Legacy fallback 绕开配置，已贯穿 supervisorCode 并在配置模式拒绝缺失版本。
5. 前端表单收集过程中发现多项选择器错误，已修复；主代理早期浏览器确认完整创建、启用和自定义主/子对话链路成功。

## 独立验证边界

自测的模型为Mockito替身，保留真实ChatClient组装逻辑；上述Prompt及工具回调断言证明传参和能力选择，不宣称远程LLM质量。知识服务使用可验证调用的替身，无外部模型费用。

真实HTTP流式链路、真实进程文件持久化重启、数据库提交失败、更多输入/权限/并发矩阵、窄屏与页面错误体验由独立测试报告记录。主代理已使用本机OpenAI兼容fixture完成浏览器早测，但该结果与自测分列，不计入上述7个JUnit测试。

模型选项/outputSchema/maxToolRounds等原有高级字段范围边界见 [BACKLOG](BACKLOG.md)。管理员角色仍为演示请求头，不宣称生产鉴权完成。
