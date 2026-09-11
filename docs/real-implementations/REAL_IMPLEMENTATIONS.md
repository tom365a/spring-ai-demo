# 把模拟部分改成真实实现

日期：2026-09-11。原先在文档里标注为「明确是模拟的」三项——订单/工单、人工客服、知识检索——已全部换成真实实现。

之后在实机演示里又暴露出**第四处漏网的模拟**：`escalate_human` 工具本身。见第 5 节。

改造过程中另外发现并修复了一个**既有缺陷**（不是本轮引入的），见第 4 节。

## 1. 订单与工单：内存 Mock → 真实持久化

改造前：`OrderTicketTools` 里两个 `static` 集合，`MOCK_ORDERS` / `MOCK_TICKETS`，进程重启即丢。

改造后：

| 项 | 实现 |
| --- | --- |
| 实体 | `BizOrder`、`BizTicket`、`BizLogisticsTrace`（表 `biz_order` / `biz_ticket` / `biz_logistics_trace`） |
| 服务 | `OrderTicketService`，`@Transactional` |
| 取消并发 | `BizOrderRepository.lockById` 悲观锁，锁内复查状态 |
| 状态机 | 只有「待发货」可取消；已取消/已发货/已完成再取消返回明确业务拒绝 |
| 工单单号 | 日期 + 随机后缀，唯一约束兜底并重试；不再用时间戳（同毫秒会碰撞） |
| 物流轨迹 | 按运单号查表，不再是硬编码常量 |
| 种子数据 | `BusinessDataSeed`，按主键逐条判断，重启不重建、**不覆盖运行中产生的状态** |

工具返回结构（`ok` / `message` / `order` / `ticketId`）与改造前完全一致，因此提示词、工具 schema 和既有断言都不用改。

`cancellable` 改为由 `status` 派生的 `@Transient`，避免与状态字段漂移。

### 这不等于接上了客户的订单系统

要对接客户自己的订单/工单系统**不需要改这个类**——资源配置里的 HTTP 自定义工具和 MCP 就是为此设计的，属于配置工作。内置这套是离线演示的兜底数据。

## 2. 人工客服：LLM 扮演 → 真人坐席控制台

改造前：`SimulatedSupportAdapter` 让两个子 Agent（`human_support_1/2`）扮演人工，回复带【模拟人工客服】前缀。

改造后新增 `app.support.mode` 两档，**默认 `HUMAN`**：

- **HUMAN** —— 转人工后会话进入坐席队列，真人在管理端「人工坐席」页认领并打字回复，回复原样进入客户消息流。**全程没有任何模型参与。**
- **SIMULATED** —— 保留原行为，供无人值守演示使用。

| 环节 | 实现 |
| --- | --- |
| 队列/认领/回复/结束 | `SupportConsoleService` + `/api/v1/admin/support/*` |
| 认领并发 | `SupportAssignmentRepository.lockById` 悲观锁，两个坐席同时点只有一个拿到 |
| 客户侧取回 | `GET /api/v1/sessions/{id}/handoff/messages`，前端 3 秒轮询，按消息 id 去重渲染 |
| 坐席前端 | `js/support-app.js`，admin 第六个导航「人工坐席」 |
| 交还 | `close` 后 `assigned()` 返回 false，会话交还智能客服（否则会永远停在人工模式） |

客户在等待期间发消息只会得到投递回执（`awaitingOperator: true`），**不会收到任何伪造的回复**。

`SupportImAdapter` 接口保留。将来接企业微信/钉钉/飞书只需再实现一个适配器——但那需要租户和凭证才能验证，本轮没有做。

## 3. 知识检索：关键词匹配 → 本地语义向量

改造前：`LexicalFileStore` 关键词匹配（因为 Kimi 没有嵌入接口，而 OpenAI 嵌入要花钱）。

改造后：**本地 ONNX 中文嵌入**，`spring-ai-transformers` + `bge-small-zh-v1.5`（int8 量化，512 维，MIT 许可）。

| 项 | 值 |
| --- | --- |
| 模型文件 | `resources/models/bge-small-zh/`，22.9 MB + 0.42 MB tokenizer。**不入 git 仓库**，由 `scripts/fetch-model.ps1` / `.sh` 从 HuggingFace 固定 revision 拉取并校验 SHA256，首次需联网一次 |
| 外部调用 | **0**——模型随包交付，运行时不请求任何嵌入服务，也不产生费用 |
| 默认后端 | `app.vector.backend=simple`（真向量）；设 `APP_VECTOR_BACKEND=lexical` 可退回关键词 |
| 嵌入来源 | `app.vector.embedding=local`（默认，`@Primary`）；设 `openai` 则不创建本地 bean，改用 OpenAI 嵌入 |

效果（走真实 `/knowledge/search` 接口，问法刻意避开原文关键词）：

| 提问 | 命中 | 相似度 |
| --- | --- | --- |
| 买回来觉得不合适，还能不能把东西送回去 | refund-policy.md | 0.603 |
| 进不去账号了，密码想不起来 | account-help.md | 0.449 |
| 快递大概几天能到我手上 | shipping-faq.md | 0.585 |

`LocalEmbeddingRetrievalTest` 里有一条**对照断言**：同样三篇文档、同样的问法，关键词检索给不出退货那条。若哪天关键词也能命中，这条对照会失败并提示换更强的例子——避免这个结论变成空话。

## 4. 顺带修掉的既有缺陷：业务拒绝被报成成功

`ToolExecutionGateway` 会检查内置工具返回里的 `ok/success` 字段来识别业务拒绝。但框架把工具返回的字符串又包了一层引号，`readTree` 拿到的是 `TextNode`，`result.has("ok")` 恒为 false——**这段判定从来没生效过**。

后果：取消一张已取消的订单，数据层正确拒绝了，但工具轨迹和模型看到的都是 `SUCCEEDED`。演示时表现为「说取消成功了，实际什么也没发生」。

修复：解开外层字符串再判定，并把真实拒绝原因带出来。

```
修复前： cancel_order=SUCCEEDED        （payload 里才写着「不可取消」）
修复后： cancel_order=BUSINESS_REJECTED 执行已停止：订单当前状态不可取消：已取消
```

回归测试见 `ResourceRuntimeSelfTest.builtinBusinessRejectionIsReportedAsRejectedNotSucceeded`。

## 5. 漏网的第四处模拟：escalate_human 只是嘴上转人工

前三项改完之后，实机演示出现这样一段对话：

```
agent:ticket   请确认是否执行：转人工
               已为您转接人工客服。当前排队第 3 位，预计等待约 5 分钟，请稍候。
你好
agent:chitchat 您好，人工客服仍在排队中（第 3 位，预计约 5 分钟）…
```

两个问题：**排队位次和等待时长是编的**，而且**转人工之后模型还在继续接话**。

根因在 `OrderTicketTools.escalateHuman`——它是个写死的桩：

```java
json(Map.of("ok", true, "queuePosition", 3, "estimatedWaitMinutes", 5,
            "message", "已为您转接人工客服，请稍候"))
```

它不落库、不排队、不通知任何人。`AgentOrchestrator` 靠 `humanSupport.assigned(sessionId)` 决定要不要停掉模型，而这个桩从来没建过 `SupportAssignment`，所以下一句必然被 supervisor 重新路由给模型；模型又从上文里把「第 3 位、5 分钟」抄了一遍。第 2 节做的真人坐席控制台，只有走 `/handoff` REST 接口才进得去，客户在对话里说「转人工」是进不去的。

修复：

| 项 | 改动 |
| --- | --- |
| 真正入队 | `escalateHuman` 调用 `HumanSupportService.transfer()`，落 `SupportAssignment`，`assigned()` 随即为真，下一句进人工通道 |
| 真实位次 | 新增 `HumanSupportService.waitingPosition()`，按 `WAITING` 队列进入时间算 1-based 序号 |
| 不报等待时长 | 删掉 `estimatedWaitMinutes`——没有任何数据能支撑这个数字，就不报 |
| 会话上下文 | 新增 `ToolSessionContext`。网关把内置工具放在**虚拟线程**里跑，调用方线程的 `ThreadLocal` 传不进去，转人工拿不到 sessionId；由 `ToolExecutionGateway` 在虚拟线程内补设一次 |
| 只回该说的字段 | 工具只返回 `ok` / `message` / `queuePosition` / `operatorName`。回传 `sessionId` 和状态枚举，模型会原样念给客户（实测念出过「状态 WAITING，会话 ID：s_525a…」） |
| 提示词 | `agent_ticket.md` 增加一条：只复述工具返回的字段，**严禁编造排队位次、等待时长、处理进度**。线上 `ticket` Agent 已发布到 v2，v1 可回滚 |

修复后同一条链路（真实 HTTP + 真实模型）：

```
1 首轮        agent=ticket | 需确认=true
2 转人工答复  「已转人工，您在坐席队列第 2 位，坐席接入后会在此直接回复。」
              含编造排队/等待时长? -> false
3 坐席队列    WAITING | 用户 u_001          ← 真的进了「人工坐席」页
4 下一句「你好」 agent=人工客服 | intent=human_support
              「消息已记录，正在为您接入坐席，请稍候。」
              被模型接走? -> false
```

第 2 位是真的：当时队列里确实还有一条更早的等待会话。

### 同一条链路上顺带修掉的三处

1. **排队中的客户会被判闲置超时。** `SessionService` 有个每秒跑一次的定时任务，会话 300 秒没输入就置 `closed`。可是排队等真人的客户本来就不打字——用户实测到的那句「连续5分钟未输入，本次会话已结束」正是这么来的，人还在队列里，会话已经没了。现在 `expire()` 先查有没有未结束的 `SupportAssignment`，有就跳过；坐席结束接管后重新受闲置规则约束。前端 `IDLE_MS` 计时同样在 `supportAssigned` 期间停掉。
2. **对话里转的人工，前端不知道自己已经在排队。** 前端只在点「转人工」按钮时才 `startSupportPolling()`。走工具转的人工，`supportAssigned` 始终是 false，坐席回复永远拉不回客户窗口。现在每轮对话结束（流式和确认两条路都有）调一次 `syncSupportState()` 核对真实接管状态，命中就进人工模式并开始轮询。
3. **同一句话显示两遍。** 进入人工模式后第一次轮询会把刚渲染过的消息再渲染一次。现在首次轮询静默登记 id、不渲染，只显示此后坐席发来的新消息。

（另外把坐席接入提示里重复的「客服 客服小张」改成「客服小张」。）

回归用例 `EscalateHumanToolTest`（6 条）：工具真的入队且模型随后停口、位次是数出来的不是编的、**排队期间不被闲置超时关掉且结束接管后恢复**、无会话时按业务拒绝返回而不是抛异常、越权转接被拒绝且不留下分配。

浏览器实测完整闭环（真实模型 + 真实 HTTP，两个标签页一客一坐席，全程走 UI）：

| 步骤 | 结果 |
| --- | --- |
| 对话里说「转人工」 | 弹出二次确认卡片，工具 `escalate_human`，参数已脱敏 |
| 确认执行 | 卡片消失，客户窗口出现一条「已为您转接人工客服：排队位置 第 1 位」，**只出现一次** |
| 会话标签 | `s_14159b8eb28a · 人工坐席`，`supportAssigned=true`，轮询已启动 |
| 管理端「人工坐席」页 | 队列里出现该会话，状态「等待接入」，最后一句「转人工」 |
| 认领会话 | 状态转「接管中」，客户窗口收到「坐席已接入」 |
| 坐席打字回复 | 3 秒内出现在客户窗口，署名坐席标识，不经模型 |
| 结束接管 | 客户端 `assigned=false`、轮询停止，会话交还智能客服 |

### 关掉页面再打开：客户会排在自己的影子会话后面

实测报告：转人工后关掉页面、重新打开，再转一次人工，排队变成「第 2 位」——前面那位就是他自己。

三个原因叠在一起：

1. 客户页每次加载都无条件 `createSession()`，`sessionId` 只存在 JS 变量里，刷新即丢。
2. 上一条会话已经进了坐席队列，客户再也回不去；坐席对着一个没人应答的窗口。
3. **上面那条 5 分钟闲置豁免把问题放大了**——原本这条孤儿会话 5 分钟后会被闲置回收，加了豁免之后它永远不过期，会一直占着队列。

修复：

| 项 | 改动 |
| --- | --- |
| 恢复会话 | 客户页把 `sessionId` 存进 `localStorage`；加载时先查 `/sessions/{id}/handoff`，**仅当还在人工流程中**才恢复原会话（重放历史消息并直接进入人工模式、启动轮询），否则照旧开新会话。普通聊天「刷新＝新会话」的行为不变 |
| 回收孤儿 | `expire()` 不再无条件豁免。活跃度取「客户最后输入 / 转人工时间 / 客户最后一句 / 坐席最后一句」四者的最大值，超过 `app.support.abandon-timeout-seconds`（默认 1800 秒）就连同坐席分配一起关掉，让它退出队列 |
| 会话结束 | `markSessionEnded()` 清掉 localStorage，结束的会话不会被再次恢复 |

实测：转人工排到第 1 位 → 关掉页面重新打开 → **会话 id 不变**（`s_36d4adfbab30`），标签仍是「· 人工坐席」，轮询自动启动，历史消息完整重放，**队列条数仍是 1**。

回归用例 `abandonedHandoffEventuallyLeavesTheQueueInsteadOfBlockingItForever`：把全部活跃时间戳推到放弃阈值之外，断言坐席分配被关闭、退出队列、会话随之结束。

### 坐席台没做角色校验

实测报告：点「认领会话」弹出 `insufficient admin role: need editor, got viewer`。

坐席台完全没看角色，viewer 也能看到「认领会话 / 发送 / 结束接管」，点下去撞一个后端英文异常。改动：

- 权限不足的报错改成中文：「权限不足：该操作需要「editor」及以上角色，当前是「viewer」。请在右上角切换角色。」（全部 admin 接口共用）
- 坐席台按角色置灰：viewer 下三个动作按钮和回复框都 `disabled`，页面顶部显示提示条，说明要把右上角「角色」切到 editor

### 投递回执不该占一条聊天气泡

实测报告：人工接管期间每发一句，客户窗口就多出一条

```
agent:seat_y2yz
消息已发送给客服 seat_y2yz，请稍候。
```

后端的会话接口每轮都要返回一个 `answer`，真人模式下这个 `answer` 就是投递回执。前端不加区分地把它渲染成助手气泡，于是：每发一句多一条、挂着坐席署名（看起来像坐席在说话）、还把内部坐席标识念给客户。真实 IM 不会为「消息已发出」单开一条消息。

改动：

| 项 | 改动 |
| --- | --- |
| 前端 | `intent === "human_support"` 或 `diagnostics.awaitingOperator` 的 final 事件不进气泡，改写进输入框上方的状态行 `#supportStatus`；坐席真回话（轮询拿到新消息）、结束接管、换新会话时清空 |
| 文案 | 回执去掉坐席标识：等待接入时「已送达，正在为您接入坐席…」，已接管时「已送达坐席，等待回复…」 |
| 落库 | 回执本来就不入库（`HumanSupportService.chat()` 只 append 客户那条），所以是纯展示层改动，历史记录不受影响 |

实测对比：

```
改前                                   改后
已转人工…第 1 位                        已转人工…第 1 位
111                                    111
agent:seat_y2yz                        客服小李 | 坐席已接入，请继续描述您的问题。
消息已发送给客服 seat_y2yz，请稍候。      客服小李 | 222
seat_y2yz                              333
222                                    ────────────────────────
333                                    状态行：已送达坐席，等待回复…
agent:seat_y2yz
消息已发送给客服 seat_y2yz，请稍候。
```

坐席回复 `222` 到达时状态行自动清空，客户再发 `333` 时重新出现——状态行反映的是「最后一句有没有被回」。

### 模型把字段名念出来了

提示词原话是「只复述工具返回的 ticketId、status、queuePosition、message 等字段」，模型照办，输出成「已转人工：queuePosition=1。message：…」。改成「用一句自然中文转述工具返回的 message……不要输出字段名、等号或 JSON」，线上 `ticket` Agent 发布到 **v3**。修复后实测输出：「已转人工，您在坐席队列第 1 位，坐席接入后会在此直接回复。」

### 坐席显示名

坐席台原来只有自动生成的标识 `seat_xxxx`，认领时把它当显示名传给后端，客户看到的署名就是一串 id。队列页加了「坐席显示名」输入框（存 `localStorage`，留空则退回标识），会话页在认领按钮旁提示「将以「客服小张」身份认领」。

队列每 5 秒整块重渲染，正在输入时会把光标和半截名字冲掉，所以刷新会在输入框获得焦点时让路。实测：输入「客服小张」→ 保持聚焦跨过一次刷新，值和光标都在；失焦后重渲染，值从 localStorage 恢复；UI 认领后客户侧收到「客服小张 │ 坐席已接入，请继续描述您的问题。」

### 写操作确认：不要让用户打字，点按钮

实测报告：「请回复"确认取消"，我再为您发起取消。」

平台本来就有结构化确认卡片（操作／工具／参数／有效期 + 「确认执行 / 取消操作」按钮），写工具在网关层被强制拦截，不点确认不会执行。但模型在调用工具**之前**又自己加了一轮口头确认，于是变成两道确认：先让用户打字「确认」，模型才去调工具，工具再弹卡片。

根因在提示词。`agent_order.md` 原来写的是「取消订单：先向用户复述将取消的订单号与影响」，模型理解成「先问一遍，等回复」。

改动：

| 项 | 改动 |
| --- | --- |
| `agent_order.md` | 改成「直接调用 cancel_order，平台会弹确认卡片由用户点按；不要自己再问一遍，更不要让用户回复「确认」「是」这类文字」 |
| `agent_ticket.md` | 同样加一句写操作确认走卡片 |
| `agent_defect_comp.md` | 「等待用户确认」补充为走卡片按钮 |
| 线上版本 | `order` v2、`ticket` v4、`defect_comp` v2，旧版本均可回滚 |

实测（真实模型，连跑 5 次）：

```
#1..#5  confirmRequired=true | 让用户打字=false | "请确认是否执行：取消订单"
首轮直接出卡片 5/5，仍多一轮文字 0/5
```

浏览器里同样是一步到位：用户说「我想取消订单 ORD20260730001」→ 直接出确认卡片（工具 `cancel_order`、参数已脱敏、「确认执行 / 取消操作」按钮）。点「取消操作」回「已取消该操作。」（验收没有真的执行取消，演示订单仍是待发货）。

开工单同样：「包裹破损了，订单 …，帮我开个工单」→ 首轮 `confirmRequired=true`，「请确认是否执行：创建工单」。

**还没统一的两处，说清楚：** 瑕疵补偿流程里的子订单确认（`ConfigurableAgentInvoker` 的硬编码分支）和售后单确认（`DefectCompensationTools` 的工具返回文案）**仍然是文字确认**——它们不是文案问题，是机制问题：这两步没有走确认卡片，而是用 `text.contains("确认")` 判断。改成卡片要把这段流水线接进 `ManagedAgentRuntime` 的 pending-Task 确认机制，不是改一句话能解决的。另外 `OrderAgent` / `TicketAgent` / `ConfigurableAgentInvoker` 里那三处「回复「确认」」文案我一并改成了指向按钮，但**这三条路径在当前配置下（`app.agent-config.enabled=true`）是不走的**，改的是死代码的文案。

### 二选一也映射到卡片的两个按钮

写操作的「要不要执行」早就是卡片了，但瑕疵补偿流程里的「同意 / 拒绝售后单」是一次**二选一**，
现有卡片的语义是「对某一个工具执行 yes/no」，表达不了它，所以一直靠用户打字。

做法是把二选一压到卡片本来就有的两个按钮上，不新增交互形态：

| 项 | 改动 |
| --- | --- |
| 工具元数据 | `LocalToolCatalog.ToolEntry` 增加可选 `Choice(prompt, confirmLabel, cancelLabel, rejectTool)`，随 `ResourceBootstrap` 写进工具资源配置 |
| 卡片渲染 | `ManagedAgentRuntime.pause()` 读到 `choice` 时，把 `confirmLabel`/`cancelLabel` 带进 payload，标题用 `choice.prompt`；前端按 payload 渲染按钮文案，没有就退回「确认执行 / 取消操作」 |
| 取消分支 | `confirmLocked()` 原来一律回「已取消该操作。」。现在若挂了 `rejectTool`，就用同一份参数执行那个工具——「拒绝」是个真动作，不是静默放弃 |
| 失败兜底 | 拒绝动作执行失败不把异常抛到前台：回「已记录您的拒绝，但后续处理未能完成」，并落 `REJECT_ACTION_FAILED` 事件 |
| 提示词 | `agent_defect_comp.md` 改为直接调 `aggre_as_order_callback`，明确不要自己问「同意还是拒绝」、不要自行调 `reject_as_order_callback` |

`aggre_as_order_callback` 的配置：确认=同意，取消=拒绝并执行 `reject_as_order_callback`，线上已发布到 v2。

回归用例（`ResourceRuntimeSelfTest`，用两个 HTTP 端点分别计数，能确切区分执行了哪一个）：

- `twoWayChoiceRendersBothButtonsAndCancelRunsTheRejectTool`——卡片带「同意/拒绝」文案，点拒绝后
  reject 端点命中 1 次、agree 端点 **0 次**，答复是拒绝工具返回的文案
- `plainWriteCancelStillDoesNothing`——普通写工具没有 `choice`，payload 不带按钮文案，取消仍然只回
  「已取消该操作。」且不发起任何请求

**一处没能在现有演示库上生效，说清楚：** `confirm_as_order_callback` 本质是只读的（把待确认售后单读出来告知用户），
代码里已改成 `READ`，这样新建库只会弹一张卡片。但现有 8080 的库里它是 `WRITE`，而平台有一道
**刻意的校验**——内置工具的读写风险不允许通过接口降级（`ResourceService`：「内置工具读写风险不可降低或篡改」）。
这道校验是对的，我没有绕过它。所以在这个已存在的库上，该工具仍会多弹一张卡片；新装环境没有这个问题。
要在现有库上修正，需要把它从 `defect_comp` 的工具列表里摘掉、删除该资源行、重启让 bootstrap 按新目录重新登记——
这是对运行中数据库的多步操作，等你确认再做。

### 5 分钟闲置豁免的实测（带对照组）

| | 排队会话 `s_14159b8eb28a` | 对照组 `s_77d42a61abea`（同样空闲，无坐席分配） |
| --- | --- | --- |
| 前端闲置计时 | 603 秒，`Date.now()-lastInputAt >= IDLE_MS` 为 **true** | — |
| 前端 `sessionEnded` | **false**，无「本次会话已结束」横幅，输入框可用 | — |
| 服务端会话状态 | 10 分钟后仍为 **active** | 越过阈值后 **closed** |

对照组是关键：它证明定时任务在同一时间窗内确实在工作，排队会话活下来是因为豁免生效，不是因为计时器没跑。结束接管后闲置计时立即恢复并正确判定会话结束——这条也是当场看到的。

## 6. 验证

| 检查 | 结果 |
| --- | --- |
| Java 全量测试 | **102/102 通过**（改造前 83，新增 19） |
| `BizOrderTicketTest` | 5/5——状态机、越权、**8 线程并发取消只成功一次**、单号唯一、轨迹查表 |
| `SupportConsoleTest` | 4/4——排队不伪造回复、真人回复原样送达、**6 坐席并发认领只有一个成功**、结束后交还 |
| `LocalEmbeddingRetrievalTest` | 3/3——512 维真向量、改写句命中、关键词对照组落空 |
| 真实重启 | 取消订单与工单跨进程重启保留，工单号一致（`TK202609110OHDC3`） |
| 转人工全链路（真实 HTTP） | 转人工 → 排队 → 认领 → 真人回复 → 客户轮询取回 → 结束交还，无模拟前缀 |
| 语义检索（真实 HTTP） | 三条改写句全部命中正确文档 |
| `EscalateHumanToolTest` | 6/6——真入队、真位次、排队期间不被闲置超时关掉、无会话按业务拒绝、越权被拒 |
| 对话内转人工（真实 HTTP + 真实模型） | 「转人工」→ 确认 → 真的进坐席队列 → 下一句由人工通道接管，无编造数字 |

## 7. 本轮没做的

- **接企业微信/钉钉/飞书真实 IM**：代码能写，但没有租户和凭证就无法验证，不做未经验证的交付。
- **接客户自己的订单系统**：属于配置（HTTP 工具 / MCP），不是代码工作。
- 嵌入模型用的是 int8 量化版以控制包体积。需要更高精度时把 `model.onnx` 换成同仓库的 fp32 版（90.5 MB）即可，代码不用改。
