# 统一资源配置独立测试报告（持续更新）

状态：**RC01–RC15 全部取得对应证据，无未关闭 P0/P1**。RC14 真实 Kimi 由根协调在用户授权下完成，见 [LIVE_ACCEPTANCE](LIVE_ACCEPTANCE.md)；产品验收见 [ACCEPTANCE_REPORT](ACCEPTANCE_REPORT.md)。测试工程师 Agent；日期 2026-09-11，最后一次复测 00:05。依据 PRD 1.0 / RC01–RC15、TEST_PLAN v0.2。

## 环境与证据边界

独立测试不读取 `.env`，不调用真实 Kimi/OpenAI，不访问 8080。Java 切片使用随机内存 H2、自有临时回环 HttpServer、合成凭证与测试主密钥。整机使用根维护的隔离后端 18080、独立文件数据库；`qa-resource-http.mjs` 自带临时回环模型/HTTP/MCP 夹具，结束即关闭。它只创建 `q_*` 资源、新 Agent 和测试会话，内置工单也是该隔离应用的内存模拟数据。

离线模型证明请求协议、配置及执行控制，不证明真实供应商响应质量。新资源模型的 `stream` 为完整响应缓冲，未宣称上游逐 token 流式已验证。DNS 精确目标/地址规则与连接时固定解析已审查；不把静态审查或地址单元检查称为真实公网 DNS 重绑定实验。

## 当前执行结果

| 执行 | 结果与时间 | 证据 |
| --- | --- | --- |
| 独立生命周期 `QaResourceLifecycleTest` | 11/11（17:16:03 首轮 10 项，最终候选 11 项） | JPA 发布、并发、技能版本、凭证隔离、MCP 候选与定义约束 |
| 独立传输 `QaResourceTransportTest` | 8/8，同次运行 | 自有 HTTP/MCP 负向协议夹具 |
| 独立模型 `QaResourceModelTest` | 3/3，同次运行 | 8 个并发请求的两模型/两凭证隔离、冻结配置、非法工具返回、缓冲响应 |
| 开发测试复跑 | CredentialVault 10、ResourceTransport 8、ResourceModelFactory 2，全部通过 | 与上述独立21项合计 **41/41**；`.tools/qa-resource-slices.log` |
| 独立整机第一轮 | **17项通过、1项失败**，17:15:26 开始 | 原始失败见下方缺陷登记 RQA-06 |
| 独立整机复测 | **22/22 通过**，23:33:35 | [qa-resource-http.json](evidence/qa-resource-http.json)；RQA-06 关闭，并首次执行此前从未跑过的 4 项（版本失效后预算保留、零历史窗口与摘要开关、图片进入视觉模型与跨会话绑定拒绝、技能版本显式升级） |
| 旧数据库真实重启迁移 `QaResourceMigrationTest` | 1/1，连续三次独立执行均通过 | 合成旧会话/消息表 → 两次真实子 JVM 启动 → 旧列逐行比对一致、两次资源身份完全相同、且资源列表与关库后直接查询 `cfg_resource` 的结果一致 |
| 旧功能回归 `qa-legacy-regression.mjs` | **8/8 通过**，23:47 | [qa-legacy-regression.json](evidence/qa-legacy-regression.json) |
| 根整机集成复跑 `resource-integration-check.mjs` | **7/7 通过**，23:40 | [root-resource-integration.json](evidence/root-resource-integration.json)；此前证据对应 17:03 早期编译，已按要求在最终候选上重跑 |
| 页面与窄屏 | 五页面真实浏览器核对通过；窄屏发现并修复 RQA-07 | [ui-narrow-screen.json](evidence/ui-narrow-screen.json) |
| Java 全量套件 | **75/75 通过** | 12 个测试类，含开发自测与独立 QA 切片 |

命令（项目根目录；直接 Maven，不加载应用环境文件）：

```powershell
$env:JAVA_HOME='C:\spring-ai-demo\.tools\jdk-21.0.12.1+1'
& '.tools/apache-maven-3.9.9/bin/mvn.cmd' '-B' '-o' '-Dmaven.repo.local=C:\spring-ai-demo\.tools\m2' '-f' 'apps/java-gateway/pom.xml' 'test'

# 整机检查：隔离后端 + 自有回环夹具，全部为合成凭证
.\scripts\qa-isolated-backend.ps1 -Fresh -ModelBaseUrl http://127.0.0.1:18091 -KnowledgeDir samples/knowledge
node scripts/resource-test-services.mjs 18091          # 另开一个窗口
node scripts/qa-authorize-fixture-model.mjs 18080 18091 model_kimi
node scripts/qa-resource-http.mjs
node scripts/qa-legacy-regression.mjs
node scripts/resource-integration-check.mjs
.\scripts\qa-isolated-backend.ps1 -Stop
```

`qa-isolated-backend.ps1` 固定使用独立端口、独立 H2 文件库、独立上传目录和合成主密钥，从不读取根 `.env`，也不接触 8080 用户应用。种子模型默认指向死回环端口，只有显式传入夹具端口才会发出模型请求。

## 已取得的独立证据

- 伪装 BUILTIN/MCP 新建、内置 WRITE 风险降级、实现身份改写均拒绝。合法 URL PATH 占位符可保存；无有效必需凭证不能发布。凭证密文入库，详情/列表/影响/历史/审计不含秘密和凭证引用；草稿 CLEAR 不破坏当前发布版本。
- 草稿变化使旧影响 token 失效；两个同时发布同 token 只有一个成功。技能历史版本固定，当前引用阻止工具删除，只有历史引用时允许删除，再回滚缺依赖版本拒绝。
- MCP 同步只形成候选，发布前不生成运行工具；两个服务相同原始名称生成不同身份；候选移除不提前停用，发布后停用；工具 Schema 只能来自服务目录。
- 地址协议、主机、端口、用户信息/片段与私网许可检查；元数据/保留地址拒绝；路径穿越、认证和传输 Header 覆盖拒绝。UTF-8 结果限制、分块响应大小限制、持续慢速返回仍受绝对总超时限制。
- MCP 失效会话 404、SSE 最终响应前断开、错误响应 ID、不支持版本、循环分页均明确失败；已发工具调用不重建会话或自动重放。返回值和字段名中的合成凭证均脱敏。
- HTTP/MCP 的正式聊天、Agent 试运行、工具测试三个入口均先等待确认，再真实调用本地服务；内置 create_ticket 的聊天/试运行确认完成后真实创建模拟工单。一次动作六个并发确认仅一个成功、一次写入；取消/错会话无副作用。
- 新聊天消息使旧确认失效；确认完成的公开问答进入后续历史，私有推理和 tool 消息不共享，当前用户文本仅注入一次。零轮数禁止工具、11 项整批拒绝，单批两项只计一轮。资源发布新版本使待确认动作失效。viewer 保存、editor 凭证变更均拒绝。

## 缺陷登记

| 编号 | 优先级 | 实测问题 | 修复/复测状态 |
| --- | --- | --- | --- |
| RQA-01 | P1 | 新工具可伪装 BUILTIN；可冒用内置实现 | 架构锁定来源与实现身份；独立测试通过 |
| RQA-02 | P1 | 内置 WRITE 可改 READ 并撤销确认 | 架构锁定内置基础风险；独立测试通过 |
| RQA-03 | P1 | 合法 PATH 模板 URL 保存被拒绝 | 架构区分路径模板与 authority；独立测试通过 |
| RQA-04 | P1 | 无必需凭证的模型/认证工具仍可发布 | 架构增加必需凭证校验；独立测试通过 |
| RQA-05 | P1 | MCP 工具普通保存可篡改协议 Schema | 17:11:15 复现；架构锁定 Schema 与服务同步目录；17:16:03 10项生命周期复测通过 |
| RQA-06 | P1 | 内置工具测试入口拒绝系统生成 Schema 的 `$schema`，九宫格缺一格 | 17:15:26 真实 HTTP 复现；架构在 Schema 白名单中接纳 `$schema`/`$id` 两个纯元注释（只校验为字符串，从不解析或拉取）；23:33:35 整机复测 22/22 通过，九宫格补齐，**已关闭** |
| RQA-07 | P2 | 390px 窄屏下资源详情底部动作条六个按钮被压到约 50px 宽，中文标签逐字换行，按钮高达 144px；Agent 列表表格同样把 `名称`/`状态`/`线上版本` 列压成每行几个字 | 真实浏览器复现并量测；根因是窄屏媒体查询里 `.resource-actions button{flex:1}` 的 flex-basis 为 0，六个按钮在 `flex-wrap:wrap` 下永远不换行。改为 `flex:1 1 auto;min-width:104px`，并对本就横向滚动的 `#agentTable` 单元格加 `white-space:nowrap`。复测：动作条变为三行、每个按钮 39px 高单行标签；表格行高统一 52px 并在自身容器内滚动；五个页面与全部详情弹窗的“被压扁控件”计数为 0，页面级横向溢出为 0；桌面布局不受影响。**已关闭** |

最初生命周期两项异常源于 QA 合成主密钥 Base64 长度错误，已修正夹具，不计业务缺陷。最初传输测试将普通 HTTP 自定义 MCP Header 误作保留字段，已依真实传输约束校准，不计业务缺陷。

原始失败留档：[生命周期初次](evidence/qa-resource-lifecycle-initial.txt)、[MCP Schema 初次](evidence/qa-resource-mcp-schema-initial.txt)。修复不删除初次失败证据。

## 本轮补齐的门禁

- **RC01 页面与窄屏**：五个一级页面在真实浏览器中从新 API 取到真实数据；390px 下逐页面、逐弹窗量测，发现并修复 RQA-07 后无残留。见 [ui-narrow-screen.json](evidence/ui-narrow-screen.json)。
- **RC08 技能 Agent 升级**：整机 `skill version only upgrades after explicit Agent publication` 与切片 `olderExplicitSkillPinCanConfirmWhenNewerVersionAlreadyExists` 双向验证——新版本发布后，未重新发布的 Agent 仍按显式 pin 注入 v1 指令。
- **RC10/RC11 超时与记忆**：整机新增 `zero history window and independent summary switch reach model`、`version-invalidated confirmation retains exhausted round budget in same session`，与既有 0 轮、11 项整批、单批两项计一轮共同覆盖。
- **真实重启迁移（含旧会话）**：`QaResourceMigrationTest` 以合成的迁移前 `cs_session`/`cs_message` 数据起步，真实拉起两次子 JVM，断言旧列逐行一致、两次资源身份完全相同。
- **完整旧功能回归**：`qa-legacy-regression.mjs` 8/8，覆盖主子路由、知识检索引文、只读工具直接执行、写工具确认后执行一次且重放被拒、取消后零副作用、工单创建、多轮历史留存、调试配置端点。

- **RC14 真实 Kimi**：一次真实 kimi-k3 调用链路通过——页面新建的 HTTP 只读工具 + 下拉选中的模型资源，两次真实调用共 811 tokens，答复正文为夹具本次随机生成、模型无法猜出的回执。OpenAI 资源在该次运行中停用且无凭证，不存在隐式外呼。见 [LIVE_ACCEPTANCE](LIVE_ACCEPTANCE.md)。

## 收尾说明

RC01–RC15 门禁已全部关闭，产品验收见 [ACCEPTANCE_REPORT](ACCEPTANCE_REPORT.md)。浏览器、根集成、真实供应商、迁移证据分别引用，不能互相替代；本报告不把离线夹具证据当作真实供应商证据，OpenAI 仍只有本地模拟协议证据。
