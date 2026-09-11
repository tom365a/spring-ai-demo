# OpenAI / Kimi 接入真实验收

日期：2026-09-10。执行：根协调 Agent。范围：Kimi K3 真实云端调用及现有应用路径；OpenAI 使用离线协议验证，未发真实付费请求。

## 环境与结果

使用 `dev.ps1 -Action run -Provider kimi -Port 18080` 启动，最终候选以缓存依赖离线启动。H2 使用独立内存库，上传索引使用 `.tools/kimi-live-final-uploads`，未修改正常运行数据库。应用访问 Kimi 官方 `https://api.moonshot.cn/v1/chat/completions`，模型 `kimi-k3`；提供方目录接口和短对话先行验证成功。

| 验收项 | 实际结果 |
| --- | --- |
| 模型与凭证 | 官方模型列表包含 kimi-k3，短对话返回“连接成功”；密钥只保存在被忽略的根 `.env`，报告不含凭证 |
| 应用生效 | `/debug/config` 显示 kimi / kimi-k3、embeddingModel=disabled、vectorBackend=lexical；配置 Agent 运行模式启用 |
| 知识入库和检索 | 导入“星河快递”合成文档后命中，metadata.retrieval=lexical，查询覆盖分数 1.0 |
| 主子调用 | 主 Agent 以 0.98 置信度路由 order，返回待发货、无线耳机、299 元等本地模拟订单信息 |
| 实际工具执行 | `/debug/tools?sessionId=...` 中 query_order 审计 success=true，参数为测试用户 u_001 与订单 ORD20260730001；结果对应上述回答，不仅依据模型自述判断执行成功 |
| 动态 Agent | 创建默认未启用的验收 Agent，启用后发布版本 1；真实试运行返回其提示词要求的“星河连接已就绪。” |
| 知识问答 | knowledge 已发布版本试运行回答“根据《接入验证物流说明》，星河快递配送时效为三天。” |
| SSE | 真实 `/chat/stream` 返回 text/event-stream 并完成 done 事件，无 error 事件；这是缓冲式 SSE |
| 页面 | 浏览器实际打开应用，模型栏显示“kimi / kimi-k3”，知识检索栏显示“本地关键词检索”，主 Agent 下拉显示已启用主路由 |

完整脱敏接口结果：[live-acceptance.json](evidence/live-acceptance.json)。可复验脚本：[qa-kimi-live.mjs](../../scripts/qa-kimi-live.mjs)，必须显式传 `--live`；它不读取 Key，只请求已运行应用，运行会产生所选模型用量。验收数据包含合成客服业务资料，不涉及真实订单。

```powershell
# 先启动隔离测试应用，再在项目根运行；不要对正式数据重复执行验收建档。
node scripts/qa-kimi-live.mjs --live
```

## 限制与交付决定

Kimi 本轮接入通过；OpenAI 接入代码、独立凭证和协议路径通过本地模拟服务验证，充值后仍需一次真实账户联调。没有以未充值账户做额度探测或自动回退。

K3 上游逐 token 推送不在本次实现内。关键词检索不等于语义向量检索；已有向量文件首次切换时仅复制原始文本至独立 lexical 文件，保留旧文件，后续两种索引分别维护，切回向量模式应重新导入切换后新增的文档。

旧阶段 P2：聊天响应 toolCalls 汇总仍可能为空，本次使用实际工具审计作为验收依据；该缺口未被本次提供方接入自动关闭。Docker 配置已调整，未进行真实容器部署验收。

离线测试及错误分支证据见 [TEST_REPORT.md](TEST_REPORT.md)。本次未发现新增未关闭 P0/P1。真实验收服务及浏览器测试页在验收后关闭；本机正常使用仍通过默认 8080 启动命令启动。
