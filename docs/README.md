# 设计文档索引

> 更新日期：2026-08-07

## 推荐阅读顺序

| 顺序 | 你想… | 读 |
|------|--------|-----|
| 1 | 了解整体架构与已落地能力 | [../TECHNICAL_DESIGN.md](../TECHNICAL_DESIGN.md) |
| 2 | 看固定多 Agent / Prompt 契约（**已实现**） | [SUPERVISOR_SUBAGENT_DESIGN.md](./SUPERVISOR_SUBAGENT_DESIGN.md) |
| 3 | 看对外 HTTP/SSE | [API_DESIGN.md](./API_DESIGN.md) |
| 4 | **接口测试（curl / 冒烟）** | [API_TEST.md](./API_TEST.md) |
| 5 | **实现 Admin / 可配置 Agent 后端** | [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md) |
| 6 | **实现管理前端 UI + 配置联调** | [ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md) |

## 现行方案（实现以代码为准）

| 文档 | 说明 | 实现状态 |
|------|------|----------|
| [../TECHNICAL_DESIGN.md](../TECHNICAL_DESIGN.md) | 总体架构、选型、里程碑 | P0 已落地于 `apps/java-gateway` |
| [SUPERVISOR_SUBAGENT_DESIGN.md](./SUPERVISOR_SUBAGENT_DESIGN.md) | Supervisor / 子 Agent 契约与 Prompt | **已实现**（固定五子 Agent） |
| [API_DESIGN.md](./API_DESIGN.md) | 对外 HTTP/SSE + Admin API | **业务 + Admin API 已实现** |
| [API_TEST.md](./API_TEST.md) | 接口测试用例与冒烟脚本说明 | 配套 `scripts/smoke-api.sh` |
| [prompts/](./prompts/) | Prompt 资产（设计源） | System：Runtime 加载；`*_user.md`：Seed/配置模板 |

## 演进 / 待实现

| 文档 | 说明 | 实现状态 |
|------|------|----------|
| [ADMIN_AGENT_TECHNICAL_DESIGN.md](./ADMIN_AGENT_TECHNICAL_DESIGN.md) | Admin 后端落地（DDL / Runtime / API） | **A0–A4 已实现**（默认 flag 关闭） |
| [AGENT_CONFIG_DESIGN.md](./AGENT_CONFIG_DESIGN.md) | 可配置 Agent 领域模型 / 管理页字段字典 | 领域设计；后端已用 |
| [ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md](./ADMIN_UI_CONFIG_TECHNICAL_DESIGN.md) | **管理前端 UI + 可配置联调** | **U0–U4 已实现**（`/admin.html`） |

## Prompt 双份约定

1. **设计与评审源**：`docs/prompts/*.md`  
2. **运行时加载**：`apps/java-gateway/src/main/resources/prompts/*.md`  
3. 修改 Prompt 时**两处同步**；以 resources 能被应用加载为准。  
4. 文件首行保留 `<!-- prompt-id: ... version: ... -->`。  
5. **`*_user.md` / `supervisor_router_user.md`**：供 Admin Seed 写入 `userPromptTemplate` 与设计对照；**当前 Java Runtime 尚未 `PromptLoader` 加载**（user 侧多在代码内拼装）。落地 Admin 后由配置模板生效。

## 相关入口

- 快速开始与演示剧本：[../README.md](../README.md)  
- 环境变量示例：[../.env.example](../.env.example)
