# 统一资源配置详细设计

日期：2026-09-10；责任：全栈架构师 Agent。R2接口基线，具体实现和错误码在开发后同步。

## 资源API与页面契约

统一前缀 `/api/v1/admin/resources`，ApiEnvelope沿用现有格式。GET集合支持kind/q/enabled/source，返回items。POST创建与PUT/{id}保存 `{kind,code?,name,description,config,draftRevision?,credentialChange?}`。credentialChange为 `{action:KEEP|REPLACE|CLEAR,source:ENV|INPUT,envName?,value?}`，仅publisher可变更凭证/目标授权。

GET/{id}返回draft、published、draftRevision、publishedVersion、dirty、enabled、status、credential状态、references、connectionStatus、lastTestRevision。草稿/发布视图中的config不含credentialRef、密文或明文。

GET/{id}/impact?action=publish|enable|disable|default返回token/currentVersion/targetVersion/changes/references；相应POST生命周期携带impactToken。GET/{id}/versions查询历史，POST/{id}/rollback携带version和impactToken。DELETE删除未引用资源。POST/{id}/test执行显式连接/工具测试；POST MCP/{id}/sync保存候选目录，发布目录复用publish。GET/{id}/references供引用面板。

MODEL config：provider KIMI/OPENAI、baseUrl、model、timeoutSeconds、parameters、capabilities、target。Kimi parameters允许reasoningEffort/maxCompletionTokens，OpenAI允许temperature/maxTokens；第一版Agent继承资源默认参数，不开放无效覆盖。

TOOL config：source BUILTIN/HTTP/MCP、sideEffect READ/WRITE、requireConfirm、inputSchema、timeoutSeconds。HTTP还含method/url/mappings/auth/target/responsePath/responseMaxBytes/resultMaxChars。映射行 `{sourceKind:INPUT|LITERAL,source,literal,targetKind:PATH|QUERY|HEADER|BODY,target}`；路径采用点号及数组下标，响应根为`$`。默认额外输入字段拒绝；支持对象/数组/基础类型、required/properties/items/enum等可实现schema约束，不支持任意代码。

MCP config：transport STREAMABLE_HTTP/LEGACY_BRIDGE、url、auth、target、timeoutSeconds、候选tools。auth为NONE/BEARER/API_KEY_HEADER及headerName；实际凭证仅由外层解析后传给运输层。

SKILL config：instructions、applicableTypes、tools统一code数组、enableRag、usageNotes。Agent保持skills code数组，增加skillVersions映射显式版本；modelConfig增加resourceId和inheritDefaults；policies增加toolTimeoutSeconds/taskTimeoutSeconds；现有memory.windowSize按公开问答轮解释。

## 传输边界

`HttpToolTransport.execute(JsonNode config,JsonNode arguments,String credential,Duration timeout):JsonNode`负责受控HTTP及提取结果。

`McpToolTransport.discover(JsonNode config,String credential,Duration timeout):List<JsonNode>`返回原始工具定义；`call(JsonNode config,String toolName,JsonNode arguments,String credential,Duration timeout):JsonNode`负责协议调用。运输层不做Agent授权/确认，所有入口在上层执行网关统一拦截。异常不得回显响应体或凭证。

## 任务与确认

确认API `/api/v1/confirmations/{id}` 仅接受 `{sessionId,confirm:true|false}`；浏览器不能替换参数或资源版本。响应扩展现有confirmRequired/confirmationPayload；payload提供id、会话、30分钟失效时间及脱敏动作摘要。正式聊天、Agent试运行和工具测试共用该状态机。

任务快照固定主子Agent及所需资源。工具批次预占后逐项执行，等待确认冻结当前批次进度；恢复从未执行项继续，已完成只读不重做。参数和版本哈希在恢复时重新校验。失效版本不能直接执行原确认；重新发起使用既有已耗预算，避免确认重置上限。

状态至少PENDING、EXECUTING、SUCCEEDED、REJECTED、EXPIRED、INVALIDATED、FAILED、UNKNOWN。原子领取防重，不持有数据库事务等待整个外部HTTP；外部执行与数据库提交不能宣称分布式exactly-once，发送后不确定结果禁止自动重试。

## 可测切片

| 切片 | RC | 首要证据 |
| --- | --- | --- |
| 资源/凭证/版本 | 02/08/11/13 | 合成密钥不回显、密文可解/错key失败、草稿隔离、迁移幂等 |
| 统一网关/确认/预算 | 09/10/12 | 三来源执行前次数0、确认仅1、批次和剩余预算、轨迹真实 |
| 模型/HTTP/MCP | 03–07 | 同进程不同目标/key、标准协议与旧桥、同步失败保旧目录 |
| Agent/技能/记忆 | 08/10/13 | 显式技能升级、精确白名单、公开历史轮与主子截止时间 |
| 五页面/集成 | 01/02/04/07/12 | 真实浏览器操作及影响确认、字段不是仅存储 |
| 重启/真实验证 | 11/13–15 | 旧库副本重启/回退、根协调Kimi、独立报告 |
