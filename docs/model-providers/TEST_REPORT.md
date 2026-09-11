# OpenAI / Kimi Provider 独立协议测试报告

日期：2026-09-10；负责人：测试工程师 Agent。测试对象：14:43:06 架构师编译交接的 Provider 候选、同轮本地词法检索实现及根协调 Agent 后续补齐的 Kimi profile embedding 模型名绑定。

## 结论

**独立离线协议测试 7/7 通过；全量回归 24/24 通过，0 失败、0 错误、0 跳过。未发现本轮新增的未关闭 P0/P1。** 该结论证明本地协议、配置隔离和回归行为；没有调用真实 Kimi/OpenAI API，不证明账户模型权限、额度、计费、回答质量或外部服务可用性。

全量回归由原 Agent 配置测试 14 项、本地词法检索测试 3 项、独立 Provider 测试 7 项组成。全量在 14:45:06 完成；截断/usage补强断言于 14:46:15 复验通过；根协调 Agent 修复 Kimi profile 的 embedding 模型名绑定后，新增两个 profile 的实际请求模型名断言，Provider 7 项在 **14:49:57** 最后复验通过。最后一轮仅重跑受配置修复影响的 Provider 测试；未将早先全量结果冒称该时间重新跑完。

## 范围和约束

测试不读取 `.env`，不连接真实模型，不使用真实密钥。测试上下文移除操作系统环境变量及系统属性来源，只注入合成 Kimi/OpenAI 密钥、两个随机端口的 127.0.0.1 HTTP 服务和独立 H2 内存库。这样可以直接断言每个请求的目标、Authorization、模型参数及工具历史，同时避免误发真实凭证。

OpenAI 协议基线参考实际查阅的官方 [Chat Completions 接口](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。独立核对 [Kimi K3 官方说明](https://platform.kimi.com/docs/guide/kimi-k3-quickstart)：K3 始终开启思考，多轮工具调用需原样保留完整 assistant。测试因此检查 reasoning_content 和附加字段保留，不发送 thinking=disabled，reasoning_effort 使用 low。Kimi `stream(Prompt)` 本轮为缓冲响应，实际所有上游请求 stream=false；OpenAI 单独验证真实上游 SSE 解析，两者不混称。

## 已执行矩阵

| 编号 | 自动化场景 | 实际断言与结果 |
| --- | --- | --- |
| P01 | Kimi profile 同步与缓冲 stream，各两轮实际 ToolCallback 往返 | 每条调用链三次 HTTP 都到 Kimi，工具执行两次；reasoning_content、未知嵌套扩展字段、tool_call_id 及工具结果完整保留；无 temperature/thinking；同步三轮 usage 累计 6。显式打开 embedding 后仅该请求到 OpenAI，使用独立 OpenAI key，请求 model 确为配置的 qa-openai-embedding-model。通过 |
| P02 | OpenAI profile 同步、上游 SSE 与 embedding | 三次请求只到 OpenAI，Authorization 均为 OpenAI 合成 key；Kimi 服务零请求，无 thinking 字段；embedding 请求 model 确为配置的 qa-openai-embedding-model。通过 |
| P03 | Kimi 返回重定向到第二 Provider 地址 | 原端收到一次请求并报 302，目标服务零请求，Authorization 未跟随发送。通过 |
| P04 | 401、/v1 路径与截断内容 | 错误正文故意含合成密钥，最终异常不回显正文/密钥，仅含 HTTP 状态；路径不重复 /v1；finish_reason=length 即便有非空 content 仍拒绝为成功。通过 |
| P05 | 未配置工具与无限工具请求 | 未配置工具调用次数 0；配置工具轮次上限设 2 时执行恰好两次，第三个模型请求后报轮次超限。历史保留断言同时通过 |
| P06 | 缺失 Kimi 密钥 | 构造模型立即失败，发送次数 0，不使用通用历史密钥回落。通过 |
| P07 | 默认 Kimi 无 embedding、本地检索零网络 | 无 EmbeddingModel Bean；本地词法存储添加和查询成功，Kimi/OpenAI 两个服务都零请求。通过 |

Provider 配置测试在同一个进程同时放置两种不同合成密钥，并故意配置两个错误的历史通用密钥。Kimi 与 OpenAI profile 使用各自密钥的断言通过；这覆盖“切换 Provider 后仍串用旧通用密钥”的风险。两个 Provider 默认不开 embedding；需要显式 `EMBEDDING_PROVIDER=openai` 才执行有独立凭证的 embedding 测试。

测试文件：[QaProviderProtocolTest.java](../../apps/java-gateway/src/test/java/com/demo/cs/QaProviderProtocolTest.java)。测试产生的服务在每项结束时关闭，Spring 上下文/H2 使用独立测试空间，不操作页面验收数据库。

## 执行命令和证据

直接调用 Maven，未使用会加载 `.env` 的启动脚本：

```powershell
$env:JAVA_HOME='C:\spring-ai-demo\.tools\jdk-21.0.12.1+1'
& '.tools/apache-maven-3.9.9/bin/mvn.cmd' '-B' '-o' '-Dmaven.repo.local=C:\spring-ai-demo\.tools\m2' '-f' 'apps/java-gateway/pom.xml' '-Dtest=QaProviderProtocolTest' 'test'
& '.tools/apache-maven-3.9.9/bin/mvn.cmd' '-B' '-o' '-Dmaven.repo.local=C:\spring-ai-demo\.tools\m2' '-f' 'apps/java-gateway/pom.xml' 'test'
```

日志：[Provider 最后复验](../../.tools/provider-qa.log)、[全量回归](../../.tools/provider-regression.log)。JUnit 原始报告：[独立 Provider 结果](../../apps/java-gateway/target/surefire-reports/com.demo.cs.QaProviderProtocolTest.txt)，同目录 XML 含逐项测试名与执行时间。环境：Windows、JDK 21.0.12.1、Maven 3.9.9、Spring Boot 3.4.2、Spring AI 1.0.0。

## 缺陷和边界

- 本轮独立测试未复现新的业务缺陷；全部断言均在本地真实 HTTP 协议和 Spring 配置上下文执行，未以 Mockito ChatModel 替代 Provider 适配器。
- 根协调 Agent 审查发现 Kimi profile 缺少 OPENAI_EMBEDDING_MODEL 绑定，已补齐；独立 QA 对 Kimi/OpenAI 两 profile 注入同一合成模型名并捕获真实 HTTP body.model，14:49:57 复测通过。此项为 review 发现后验证修复，不假称独立测试先复现过修复前状态。
- 初始讨论中的 thinking=disabled 方案在候选前已按官方 K3 契约纠正，未作为该参数已支持的交付结果。
- Kimi 为缓冲 stream；长请求需要等待完整模型/工具链结束后才能向应用返回内容。未验证 Kimi 上游增量 SSE，不宣称已经实现。
- 本报告未读取真实 `.env`。启动脚本的合成 `.env` 安全加载（不输出密钥、不执行 `$()`）由架构师执行并交接，属于开发验证，未计入独立七项协议测试。
- 真实模型连通性和效果由根协调 Agent 的获授权在线验证另外记录；本报告不继承尚未执行的在线结果。
- 原配置阶段开放 P2 `ChatResponse.toolCalls` 汇总缺口仍沿用原 BACKLOG，不因 Provider 切换自动关闭。
