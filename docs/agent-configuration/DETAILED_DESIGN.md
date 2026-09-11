# Agent 配置前后端详细设计

责任人：全栈架构师 Agent；日期：2026-09-10。

## 前端

沿用 `/admin.html`，列表显示简介、草稿/启用/停用及发布版本。完整创建弹窗包含可选 code、必填名称和提示词、简介、类型、工具、技能；主 Agent 额外显示当前已启用 WORKER 候选。code 为空后端生成稳定唯一标识，类型切为 WORKER 清空关联。提交期间禁用创建按钮；错误显示在表单内，保留输入。

编辑页工具面板增加技能；技能显示用途、适用类型和依赖，保存和回显与版本快照一致。“保存草稿”只调用 PUT；“启用/发布更新”先保存当前表单并校验，成功后调用生命周期接口，显示实际加载版本。校验/保存失败中止发布。列表切换启用调用专用接口，限制 publisher 权限。

`/index.html` 增加主 Agent 下拉和刷新，读取实际运行目录；切换主 Agent 创建新会话。请求显式传递选中主 Agent code，普通及流式由同一编排执行。试运行 full_route 只接受已启用主 Agent；页面切换该模式取消草稿勾选，服务端也拒绝草稿全链路。

弹窗在桌面双列、窄屏单列，最大高度 90vh 并内部滚动。动态值使用转义或 textContent。

## 接口

统一沿用 `{code,message,traceId,data}` 响应。成功 code=0；参数错误 HTTP400；权限 HTTP403；冲突 HTTP409；配置校验 HTTP422，data.errors 提供 path/message。

| 方法与路径 | 权限 | 行为 |
| --- | --- | --- |
| POST `/api/v1/admin/agents` | editor | code可选；definition一次携带完整配置，创建未启用草稿 |
| PUT `/api/v1/admin/agents/{code}` | editor | 保存草稿，支持draftRevision；enabled不能借此更改 |
| POST `.../{code}/validate` | editor | 按可启用标准验证草稿 |
| POST `.../{code}/enable` | publisher | 校验、发布或复用相同快照、事务提交、加载 |
| POST `.../{code}/disable` | publisher | 事务停用并从Registry移除 |
| POST `.../{code}/publish` | publisher | 发布当前草稿并启用，产生新版本 |
| POST `.../{code}/rollback` | publisher | 历史快照重新校验并生成新版本 |
| GET `/api/v1/admin/catalog/skills` | viewer | 返回可信技能定义与依赖 |
| GET `/api/v1/agents/runtime` | 普通只读入口 | configEnabled及当前加载项code/name/type/loadedVersion，不泄露提示词 |
| POST `/api/v1/chat`、`/chat/stream` | 既有聊天入口 | 增加可选supervisorCode，缺省supervisor |

创建示例：

```json
{"name":"订单助手","type":"WORKER","description":"查询订单和物流","definition":{"prompts":{"systemPrompt":"依据查询结果回答。","userPromptTemplate":"{{text}}"},"tools":[],"skills":["order_lookup","clear_response"]}}
```

## 字段与校验

code格式保持 `^[a-z][a-z0-9_]{1,63}$`，自动生成以 `agent_` 开头；名称非空且最多128字符；简介最多1000字符；系统提示非空且受 `prompt-max-length` 限制，未知模板变量拒绝。非法 JSON 类型返回400，不作为500。

工具及技能未知项、空项、非法工具依赖拒绝；WRITE 需要 WORKER 及 allowWriteTools。WORKER 携带 children 拒绝。主 Agent 自引用、重复、关联不存在或主 Agent 均拒绝；启用时至少一个有效关联，并检查所有启用关联目标已有启用快照。

草稿可暂不关联子 Agent，以支持先创建后配置；启用严格校验。启用失败保持原版本及状态。更新业务身份固定 code/type，名称首尾空白被去除。

## 技能目录

| code | 名称 | 适用 | 实际效果 |
| --- | --- | --- | --- |
| clear_response | 清晰表达 | 主/子 | 系统提示追加先结论、步骤、不可编造的指令 |
| order_lookup | 订单查询 | 主/子 | 追加查询指令，合并query_order/query_logistics只读工具 |
| knowledge_answer | 知识检索问答 | 子 | 追加引用指令，调用KnowledgeService并注入检索结果 |

多选按配置顺序合并，工具去重；直接选择工具不因移除其来源技能而删除。移除技能并发布后新模型请求不再包含其指令和派生权限。运行级捕获见自测/测试报告。

## 自测与运行

执行 `powershell -File scripts/dev.ps1 -Action test`；首次下载依赖后可加 `-Offline`。`AgentConfigurationTest` 使用真实Spring容器/JPA/H2/ChatClient和可捕获ChatModel替身验证调用参数，并发测试使用独立线程与事务；它不等同于远端模型回答质量测试。

本地启动 `powershell -File scripts/dev.ps1 -Action run`，浏览器访问 `/admin.html`，使用现有演示 publisher/admin 角色完成创建启用，再到聊天页刷新选择。真实模型配置仍由环境变量提供；独立测试可使用 `scripts/mock-openai.mjs` 的本机OpenAI兼容服务，不应把替身标记为真实模型。
