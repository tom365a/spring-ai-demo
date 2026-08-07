# 管理前端 UI × 可配置 Agent — 详细技术设计

> 文档版本：v1.0  
> 日期：2026-08-07  
> 状态：**U0–U4 已落地**（`static/admin.html` + `admin.css` + `js/admin-*.js`）  
> 前置：后端 Admin API / Runtime **A0–A4 已落地**（见 [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md)）  
> 关联：[AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md)、[API_DESIGN.md](./API_DESIGN.md) §11、客服页 `static/index.html`  
> 范围：① Admin 管理前端 UI ② 可配置 Agent 前端交互与 Runtime 开关联调 ③ 阶段 B（配置主导）衔接

---

## 1. 目标与非目标

### 1.1 要解决什么

| 缺口 | 现状 | 本文交付 |
|------|------|----------|
| 无管理界面 | 只能 curl Admin API | 可在浏览器维护 Agent |
| 配置化「看不见」 | flag 关闭时运营无感 | UI 展示线上版本 / dirty / 试运行 |
| 提示词难调 | 改 md 发版或盲改 JSON | Tab 编辑 + 校验 + Playground |
| 阶段 B 未做 | 硬编码 Agent 仍为主路径 | 给出 UI + 配置主导切换方案 |

### 1.2 成功标准

1. 运营用浏览器完成：列表 → 编辑 draft → validate → publish → 试运行，无需 curl。  
2. `X-Admin-Role` 控制按钮显隐；后端仍强制鉴权。  
3. SUPERVISOR 无法在 UI 勾选 WRITE 工具；环检测错误可读展示。  
4. Playground 能展示 `routeTrace` / `promptsRendered` / `toolCalls`。  
5. 文档说明如何打开 `APP_AGENT_CONFIG_ENABLED=true` 使线上对话吃配置。

### 1.3 非目标

- 独立 SPA 工程 / React 构建链（Demo 用静态页即可）  
- 完整 Diff 可视化库（简易文本对比即可）  
- SSO / 多租户 Admin  
- 在线编辑 MCP endpoint（Demo 锁死 seed）  
- 阶段 C 多业务线

---

## 2. 总体方案

### 2.1 技术选型（定稿）

| 项 | 决策 | 理由 |
|----|------|------|
| 载体 | `apps/java-gateway/src/main/resources/static/admin.html`（+ 可选 `admin.js` / `admin.css`） | 与现有客服页同栈，零构建 |
| 路由 | Hash 路由：`#/agents`、`#/agents/{code}`、`#/playground`、`#/catalog` | 无后端路由改造 |
| 数据 | 仅调已有 `/api/v1/admin/**` | 不新增业务 API（除可选试渲染，见 §6） |
| 样式 | 延续客服页视觉变量（深色青绿），管理页偏「工作台」密度 | 品牌一致、操作密度更高 |
| 状态 | 内存 + `sessionStorage` 缓存 role / 上次打开的 code | 刷新可恢复角色 |

**不做**：Vue/React CLI、组件库引入（可原生 + 少量 CSS）。

### 2.2 信息架构

```text
/admin.html
├── 顶栏：标题 · Runtime 开关状态提示 · 角色选择 · 链到客服页 /
├── 侧栏 / 顶 Tab
│   ├── Agents（默认）
│   ├── Catalog（工具 / MCP 只读）
│   └── Playground（可从编辑页跳入并带 code）
└── 主区
    ├── Agents 列表
    └── Agent 编辑器（多 Tab）+ 发布抽屉 / 版本抽屉
```

### 2.3 与后端的边界

```text
┌──────────── admin.html ────────────┐
│  UI State（draft 表单）             │
│  api.admin.*（fetch + X-Admin-Role）│
└───────────────┬────────────────────┘
                │ REST
┌───────────────▼────────────────────┐
│ AdminAgentController / Catalog     │  ← 已实现
│ DefinitionRegistry / Invoker       │  ← 已实现（flag 控制是否进对话）
└────────────────────────────────────┘
```

客服页 `/` **不改**业务逻辑；仅顶栏加「Agent 管理」链接。  
对话是否读配置：**环境变量 / yml**，UI 只展示当前 debug 态（见 §5.4），不在 Demo 里热改服务器 flag（避免误开生产）。

---

## 3. 页面详细设计

### 3.1 顶栏

| 元素 | 行为 |
|------|------|
| 标题 | 「Agent 管理」 |
| Runtime 徽标 | 调 `GET /api/v1/debug/config` 扩展字段或独立 `GET /api/v1/admin/runtime`（可选，见 §6）；显示 `configRuntime: on/off` |
| 角色下拉 | `viewer` / `editor` / `publisher` → 写入 `sessionStorage.adminRole`，后续请求带 `X-Admin-Role` |
| 链接 | 「客服演示」→ `/` |

权限矩阵（前端隐藏，后端仍校验）：

| 能力 | viewer | editor | publisher |
|------|--------|--------|-----------|
| 列表 / 详情 / 版本 / Catalog | ✓ | ✓ | ✓ |
| 保存 draft / validate / trial | | ✓ | ✓ |
| publish / rollback / mcp refresh | | | ✓ |
| 创建 Agent | | ✓ | ✓ |

### 3.2 Agents 列表页 `#/agents`

**数据**：`GET /api/v1/admin/agents?type=&status=&enabled=&q=`

**表格列**：

| 列 | 来源 |
|----|------|
| code | code |
| 名称 | name |
| 类型 | SUPERVISOR / WORKER 徽章 |
| 生命周期 | status |
| 线上版本 | publishedVersion 或「未发布」 |
| 启用 | enabled 开关（editor+ 可点，调 PUT） |
| 更新时间 | updatedAt |
| 操作 | 编辑 / 试运行 |

**工具条**：搜索框、类型筛选、状态筛选、`+ 创建 Agent`（editor+）。

**创建对话框**：仅 `code` / `name` / `type` / `description` → `POST /api/v1/admin/agents` → 跳转编辑页。

**空态**：引导「Seed 应在启动时写入 6 个 Agent；若列表空请检查 seed-on-startup」。

### 3.3 Agent 编辑页 `#/agents/{code}`

**加载**：`GET /api/v1/admin/agents/{code}` → `draft` / `published` / `dirty` / `draftRevision`。

**顶区摘要条**：

- code（只读）、type（只读）、dirty 黄点、publishedVersion、enabled  
- 按钮：保存 draft · 校验 · 发布 · 回滚 · 试运行  

**保存**：`PUT` body 含完整 `definition`（由表单汇总）+ `draftRevision`；冲突 409 → 提示刷新。

#### Tab 1 — 基本信息

字段同 AGENT_CONFIG §6.2；`enabled` 开关即时反映到表单（随保存提交）。

#### Tab 2 — 模型

| 控件 | 绑定 |
|------|------|
| chatModel | 文本/下拉可空；placeholder「全局默认」 |
| temperature | range 0–1 step 0.05 |
| maxTokens | number |
| enableVision | checkbox（WORKER） |

#### Tab 3 — 提示词

- `systemPrompt` / `userPromptTemplate`：等宽 textarea，字数统计 vs `prompt-max-length`  
- `outputMode`：TEXT | JSON_SCHEMA  
- `outputSchema`：JSON textarea（条件显示）  
- **占位符芯片**：点击插入光标处（白名单见 AGENT_CONFIG §11 更新版）  
- **试渲染**（可选 API §6.1）：预览区展示渲染后 system；无 API 时前端用样例变量本地 replace（与后端白名单一致）

#### Tab 4 — 工具

- `GET /api/v1/admin/catalog/tools` → 按 `ownerDomain` 分组 checkbox  
- 展示 `sideEffect` 徽章；WRITE 勾选时 toast「将进入确认策略」  
- SUPERVISOR：WRITE 项 `disabled`  
- 勾选结果 → `definition.tools[]`

#### Tab 5 — MCP

- `GET /api/v1/admin/catalog/mcp-servers`  
- `mcp.enabled`、server 多选、按 server 展开 tool allowlist  
- Refresh：`POST .../mcp-servers/{id}/refresh`（publisher）  
- Demo：endpoint 只读展示，不可编辑

#### Tab 6 — 子 Agent（仅 SUPERVISOR）

- 候选：列表中 `type=WORKER && status=PUBLISHED && enabled`  
- 行内：agentCode、alias、whenToUse、priority、enabled、上移下移、删除  
- 保存前本地可做简单环提示；最终以 validate 为准

#### Tab 7 — 策略 / 记忆 / 能力

| 字段 | 控件 |
|------|------|
| allowWriteTools | WORKER 开关；SUPERVISOR 强制 false 禁用 |
| requireConfirmFor | 多选（已选 WRITE tools） |
| maxToolRounds / maxChildHops | number |
| routing.strategy / confidenceThreshold / fallbackAgentCode | SUPERVISOR |
| memory.* | 开关 / number |
| capabilities.enableRag | WORKER 开关（knowledge 默认 true） |

#### Tab 8 — 发布

- 调用 `POST .../validate` 展示 errors（红）/ warnings（黄），path 可点击跳 Tab  
- dirty 时展示简易 Diff：对 `systemPrompt`、`tools`、`children` 做行级对比（published vs draft）  
- 发布备注 → `POST .../publish`  
- 版本列表 `GET .../versions`；点版本看 snapshot；`rollback` 填 version + remark  

### 3.4 Playground `#/playground?code=`

**表单**：

| 字段 | 说明 |
|------|------|
| agent code | 下拉 |
| useDraft | checkbox 默认 true |
| mode | `single` / `full_route` |
| text | 必填 |
| userId | 默认 `trial_u_demo`（后端会加 trial_ 前缀规则已实现） |
| attachmentIds | Demo 可先不做上传；高级：复用 `/api/v1/attachments` |

**提交**：`POST /api/v1/admin/agents/{code}/trial`

**结果区**：

1. answer（主文）  
2. routeTrace（时间线）  
3. toolCalls（JSON）  
4. promptsRendered.system（折叠，脱敏已由后端截断）  
5. latencyMs  

**注意**：`full_route` 依赖线上 supervisor 配置；`agent-config.enabled=false` 时 Orchestrator 仍走 legacy——Playground `single` 用 Invoker 直跑 definition，**不依赖** flag；`full_route` 走 Orchestrator，**受 flag 影响**。UI 需文案提示：

> full_route 仅在 `APP_AGENT_CONFIG_ENABLED=true` 时走配置化 Supervisor；否则结果可能来自固定 Agent。

### 3.5 Catalog 页 `#/catalog`

只读两栏：本地 Tools 表、MCP Servers 表（状态 UP/DOWN、tools 列表）。publisher 可 Refresh。

---

## 4. 前端模块划分（静态实现）

建议拆文件（仍无构建）：

```text
static/
├── index.html          # 客服（已有；加管理入口）
├── admin.html          # 壳 + 路由
├── admin.css           # 工作台样式
└── js/
    ├── admin-api.js    # fetch 封装、envelope 解析、角色头
    ├── admin-store.js  # 当前 draft、dirty、revision
    ├── admin-list.js
    ├── admin-editor.js # Tab 表单 ↔ AgentDefinition 互转
    ├── admin-playground.js
    └── admin-app.js    # hashchange、权限、toast
```

### 4.1 `admin-api.js` 约定

```text
api(path, { method, body, role })
  → headers: Content-Type, X-Admin-Role, 可选 X-API-Key
  → 解析 { code, message, data }
  → code≠0 或 HTTP 4xx/5xx → throw ApiError(code, message, data)
```

409 / 422：把 `data.errors` 交给编辑器高亮。

### 4.2 表单 ↔ `AgentDefinition` 映射

编辑器内部维护一份与后端 `AgentDefinition` 同构的 JS 对象；Tab 只是视图。  
保存时整包 PUT，避免分字段补丁漏字段。

缺省合并：打开详情时 `draft` 与空字段用 seed 同款默认（temperature 0.2 等），防止 null 覆盖。

### 4.3 乐观锁

每次 GET/PUT 成功更新本地 `draftRevision`。  
PUT 必须带回；409 → Modal「配置已被他人更新，重新加载？」。

---

## 5. 可配置 Agent（Runtime 侧）联调设计

### 5.1 两条路径（对运营说清楚）

| 路径 | 条件 | 谁在答用户 |
|------|------|------------|
| Legacy | `app.agent-config.enabled=false`（默认） | 固定 Java Agent + classpath system prompt |
| Config | `enabled=true` 且 Registry 有 published | `ConfigurableAgentInvoker` 读 DB 快照 |

**Admin UI 在两条路径下都可改配置**；只有 Config 路径影响 `/api/v1/chat`。

### 5.2 UI 与 flag 的关系

```text
运营改 Prompt → Save → Validate → Publish
        │
        ├─ Registry 热更新（已实现）
        └─ 若 flag=false：客服页仍用旧硬编码；Playground single 仍可验证新 Prompt
           若 flag=true：下一轮 chat 立即用新版本
```

顶栏徽标文案建议：

- `对话 Runtime: Legacy` — 黄  
- `对话 Runtime: Config v{n}` — 绿（可显示 supervisor publishedVersion）

### 5.3 推荐联调剧本（写入 README / 本页帮助抽屉）

1. local 启动，打开 `/admin.html`，角色选 publisher。  
2. 改 `order` systemPrompt 加一句固定口头禅 → 保存 → 校验 → 发布。  
3. Playground `single` + useDraft/published 确认口头禅出现。  
4. 设 `APP_AGENT_CONFIG_ENABLED=true` 重启 → 客服页查单，确认口头禅进入真实对话。  
5. rollback order 到 v1 → 口头禅消失。

### 5.4 阶段 B（配置主导）— 设计预告

目标：去掉对话路径对固定 `*Agent` PromptLoader 的依赖。

| 步骤 | 内容 |
|------|------|
| B1 | 默认 `enabled=true`（local 可先 true） |
| B2 | Orchestrator 仅在 `fallback-to-legacy=true` 且 Registry miss 时回落 |
| B3 | 评测剧本全绿后，`fallback-to-legacy=false` |
| B4 | 固定 Agent 类降级为「仅 Tool 实现参考」或删除 Prompt 调用 |

**UI 配合**：Catalog 旁增加「迁移检查」面板（可选）：对比 Registry 六件套是否齐、enabled、supervisor children 是否完整。

本文 **不实现** 阶段 B 代码，只约束前端文案与验收顺序。

### 5.5 `*_user.md` 与配置模板

- Seed 已把 user 模板写入 `userPromptTemplate`。  
- Config 路径：`ConfigurableAgentInvoker` 渲染模板（已实现）。  
- Legacy 路径：仍代码内拼装。  
- UI：提示词 Tab 编辑的即 DB 中模板；与 `docs/prompts/*_user.md` 双份同步仍靠人工/后续脚本，编辑器可加「从 classpath 重置」按钮（调后端可选 API 或前端写死说明）。

---

## 6. 可选后端小增强（服务 UI，非必须）

若纯静态不够，可追加（实现阶段按需）：

| API | 说明 |
|-----|------|
| `GET /api/v1/admin/runtime` | `{ configEnabled, fallbackToLegacy, registrySize, supervisorVersion }` |
| `POST /api/v1/admin/agents/{code}/preview-prompt` | body 样例变量 → 返回渲染后 system/user（复用 PromptTemplateRenderer） |
| `GET /api/v1/admin/agents/{code}/diff` | 服务端计算 draft vs published 关键字段 diff |

**首期可不做**：前端用现有 API + 本地渲染/对比即可。

---

## 7. 视觉与交互规范

### 7.1 与客服页的关系

| | 客服 `/` | 管理 `/admin.html` |
|--|---------|-------------------|
| 气质 | 对话、宽松 | 工作台、紧凑 |
| 色板 | 同 CSS 变量 | 同变量，表格/表单密度 ↑ |
| 圆角 | 大卡片 | 中等；Tab 底边线 |

避免：另一套紫色主题、过重阴影、无表情图标墙。

### 7.2 关键交互

1. **dirty**：任意字段变更置位；离开路由 `confirm`。  
2. **校验失败**：滚动到首个 error.path 对应 Tab。  
3. **WRITE 工具**：勾选时自动加入 `requireConfirmFor`（可取消）。  
4. **enabled=false**：列表灰显；编辑页顶条警告「Runtime 不可路由到此 Agent」。  
5. **Toast**：成功/失败右上角 3s。

### 7.3 响应式

- ≥1100px：侧栏 + 主区  
- ＜1100px：顶 Tab + 单列；Playground 结果叠放  

---

## 8. 安全

1. Demo 角色仅 Header；页面脚注「生产须换 JWT」。  
2. 不在前端存 API Key（若网关要 Key，用与客服页相同的可选配置，默认 demo-api-key）。  
3. promptsRendered 展示区按只读；不提供「一键写入生产会话」。  
4. trial 默认 userId 带 `trial_` 前缀提示。

---

## 9. 实现排期

| 阶段 | 内容 | 验收 |
|------|------|------|
| **U0** | `admin.html` 壳 + 角色 + 列表 + 创建 | 能看到 Seed 六 Agent |
| **U1** | 编辑器 Tab：基本/模型/提示词/工具/策略 + 保存/校验 | validate 错误上屏 |
| **U2** | 子 Agent Tab + 发布/版本/回滚 + dirty/revision | publish 后列表版本 +1 |
| **U3** | Playground + Catalog + 客服页入口链接 | trial 出 routeTrace |
| **U4** | Runtime 徽标 + 联调说明 + Diff 简易版 | README 剧本走通 |
| **U5**（可选） | preview-prompt / runtime API | 试渲染与后端一致 |

建议：**U0–U3** 作为管理前端 MVP；U4 随文档；U5 按需。

---

## 10. 测试计划（前端）

| 编号 | 场景 | 期望 |
|------|------|------|
| TU1 | viewer 打开编辑页 | 无保存/发布按钮 |
| TU2 | editor 保存 order Prompt | dirty 清零，refresh 内容在 |
| TU3 | SUPERVISOR 勾选 cancel_order | UI 禁选或保存后 validate 失败展示 |
| TU4 | 制造环 children | validate errors 含 cycle |
| TU5 | 双页同 revision 保存 | 后者 409 提示 |
| TU6 | Playground single useDraft | 未发布文案可跑出，Registry 不变 |
| TU7 | publish 后 Playground useDraft=false | 与线上一致 |
| TU8 | enabled=false knowledge | 列表灰显；说明文案可见 |
| TU9 | 移动端宽度 | 可完成保存与发布，无横向死锁 |

---

## 11. 文件与入口清单（落地时）

| 路径 | 动作 |
|------|------|
| `static/admin.html` | 新建 |
| `static/admin.css` | 新建 |
| `static/js/admin-*.js` | 新建 |
| `static/index.html` | 顶栏加「Agent 管理」→ `/admin.html` |
| `README.md` | 增加 Admin UI 与 flag 联调小节 |
| 本文 | 实现中/已落地后改状态 |

后端原则上 **零改动** 可完成 U0–U3；U5 才需小 API。

---

## 12. 与现有文档的职责划分

| 文档 | 职责 |
|------|------|
| **本文** | 管理**前端** UI + 可配置联调/阶段 B 衔接 |
| ADMIN_AGENT_TECHNICAL_DESIGN | 后端包、DDL、Invoker、Admin API 实现（已落地） |
| AGENT_CONFIG_DESIGN | 领域模型与字段字典 |
| API_DESIGN §11 | HTTP 契约 |

冲突时：字段以 AGENT_CONFIG / AdminDtos 为准；交互以**本文**为准；表结构以 ADMIN §4 为准。

---

## 13. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-08-07 | 首版：Admin 静态管理端 + 可配置 Runtime 联调与阶段 B 预告 |
| v1.1 | 2026-08-07 | U0–U4 落地：admin.html / css / js；debug/config 增加 Runtime 字段；客服页入口 |
