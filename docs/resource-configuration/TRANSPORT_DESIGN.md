# HTTP / MCP 传输设计与开发自测

本模块由根协调实现，供统一工具执行器调用；它不另建权限、确认或审计旁路。公开接口为 `HttpToolTransport.execute(config, arguments, credential, timeout)`、`McpToolTransport.discover(config, credential, timeout)`、`McpToolTransport.call(config, toolName, arguments, credential, timeout)`。资源配置和最终剩余预算由上层提供，凭证仅为请求内参数，不保存或输出。

## 目标与连接

target 必须精确声明 scheme、host、port，私网/回环还需 allowPrivate=true；允许内网不等于允许云元数据、未指定地址、多播、链路本地或保留地址。逐请求解析并验证全部 DNS 答案，再交给 Apache HttpClient 的固定 DnsResolver；连接使用已验证地址，不第二次解析，从而避免先检查地址、连接时重新解析的时序漏洞。TLS 仍按原主机校验证书，不改成关闭证书检查。

只使用HTTP(S)，拒绝URL用户信息、fragment、IPv6作用域和协议/主机/端口不匹配。请求不跟随重定向、不自动重试、不携带跨调用Cookie，不采用系统代理绕过目标校验。DNS等待、建连和读取均受总截止时间限制，绝对截止时间到达会取消底层请求，避免响应逐字节滴流无限延长调用。

返回TransportException包含稳定错误code和可读信息，不保留供应商错误体或底层可能包含地址凭证的异常链。HTTP状态错误、网络失败或响应格式错误均不能证明已发出的写操作没有执行，网关须显示结果待核实并禁止重试。

## HTTP 参数与响应

methods为GET/POST/PUT/PATCH/DELETE。mappings元素的sourceKind为INPUT/LITERAL；INPUT的source采用点路径和数组索引，LITERAL使用literal JSON值。targetKind为PATH/QUERY/HEADER/BODY，target是对应字段名称。默认必需；显式required=false允许缺失来源时跳过。JSON body按值类型构造，禁止脚本或原始代码求值。

字段路径支持 `$`、`customer.id`、`items[0].id`，不支持通配符/运算表达式；深度最多20，数组索引最大999。BODY整体`$`与子字段映射互斥，重复/类型冲突失败。路径占位符只允许固定URL路径段，输入不允许斜杠或`.`/`..`改变路由层级；查询单独编码。业务Header不能覆盖认证、Cookie、Host及传输字段，认证API Key Header也不能覆盖协议控制字段。

外层负责完整JSON Schema校验；传输重复检查映射/大小/地址。请求体最大1MiB，responseMaxBytes不超过1MiB，responsePath支持上述字段语法，缺失失败而JSON null有效。结果同时受resultMaxChars（最多32768字符）及UTF-8 32KiB约束。响应中若服务回显当前凭证，其文本被替换为[REDACTED]，错误体不回显。

## MCP 协议范围

依据正式[MCP Streamable HTTP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)和[初始化生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)，初始请求2025-11-25，接受协商2025-11-25/2025-06-18/2025-03-26。实现initialize、capabilities校验、notifications/initialized（202）、会话Header、协议Header、tools/list分页、tools/call与结束会话DELETE。

每次发现/调用建立独立会话，不共享凭证或全局服务端会话状态；发现的多页共用该会话。POST同时接受application/json和text/event-stream；SSE按event读取，忽略进度通知，响应ID必须匹配，收到最终响应即结束本次读取，不等待长连接关闭。服务端ping请求会回应，未声明支持的反向能力返回方法不支持，不执行本地命令或模型采样。总大小、总超时、分页100页/1000工具上限以及重复工具名/游标限制防止无限读取。

isError和JSON-RPC error明确失败；遇失效会话、提前断流不重放工具调用，下一次显式操作重新initialize，避免重复写入。未实现后台GET通知监听、断流自动恢复或历史HTTP+SSE传输，不把这些能力列为已验收。独立类型LEGACY_BRIDGE仅使用旧项目`/tools`和`/tools/call`，不会作为标准MCP统计。所有请求仍走同一目标校验和凭证隔离。

目录同步只返回候选定义，发布/差异/失败保留旧目录由资源生命周期负责。不同服务同名工具的全局身份、模型侧名称以及精确授权由上层目录映射负责。传输不因为发现工具就自动执行或授权。

## 每个 Agent 的模型实例

`ResourceModelFactory.create(snapshot, credential, timeout)` 为当前任务捕获的模型版本构建独立 ChatModel，使用同一目标校验与绝对超时传输。模型资源中的 provider、baseUrl、model 与凭证决定实际请求，Prompt 中的 model 字段不能替换所选资源。Kimi 使用 reasoning_effort / max_completion_tokens，OpenAI 使用 temperature / max_tokens；参数不混用。工具定义只传给模型，模型工厂不执行回调，所有实际调用交回统一运行时进行授权、确认、预算和审计。

原始 assistant 工具消息（包括 Kimi 推理字段）保存在任务内部元数据中，用于后续工具回合重放；公开回复与跨 Agent 历史不包含它。当前资源模型的 stream 以完整响应封装为单个流事件，不声明逐 token 流式能力。

`ResourceModelFactoryTest` 的两个测试验证同进程 Kimi/OpenAI 的地址、凭证、模型与参数隔离，禁止自动执行工具回调，保存工具消息附加字段，以及截断/错误响应处理。加上传输七项，2026-09-10 16:54 的开发自测共 9/9 通过；后续统一回归报告覆盖最终版本。

## 开发自测与证据

ResourceTransportTest使用随机本地端口、自有HTTP/MCP协议服务及合成凭证，不读.env、不调用OpenAI/Kimi，也不访问8080。首轮7/7通过，随后补充读流异常不会被主动关闭连接误报为超时的回归，传输共8项；覆盖真实HTTP五方法及字段类型/认证、地址和Header拒绝、302零重定向/写请求一次、401正文不泄露、响应大小/JSON null/字段缺失/绝对超时、MCP协商会话及分页SSE、旧桥和协议错误、字段路径表达式拒绝。17:16独立测试工程师合并执行的41项切片全部通过，包含传输8项与模型2项。最后相关代码修复后的结果以最终测试日志为准。

代码：`apps/java-gateway/src/main/java/com/demo/cs/infrastructure/resources/transport/`；日志：`.tools/resource-transport-test.log`。这些是传输开发自测，不能替代资源生命周期、三类工具统一确认、Agent端到端和独立QA验收。
