# RC14 真实供应商验收（Kimi）

日期：2026-09-11 00:05（证据时间戳 2026-09-10T16:05:08Z UTC）。执行：根协调 Agent，经用户明确授权后进行一次真实计费调用。

## 边界

- 只验证 **Kimi**。OpenAI 在本次运行中资源为**停用且无凭证**（`enabled=false`、`credential.configured=false`），不存在隐式外呼，因此 OpenAI 仍只有本地模拟协议证据，不标记为真实通过。
- 隔离运行：端口 18082、独立 H2 文件库 `.tools/qa-live-run/live`、独立上传目录。**未接触 8080 用户应用**，未改动用户数据。
- 凭证由服务端持有：`scripts/qa-live-backend.ps1` 只把 `.env` 中的 `KIMI_API_KEY` 放进子进程环境块，不作为 Maven/JVM 参数、不回显、不写日志。验收脚本自身从不读取 Key。运行后核对：后端日志与证据文件均不包含该 Key，也不含任何 `sk-` 形态的串。
- 被调用的工具指向本机自有夹具 18091（`scripts/resource-test-services.mjs`），只读、无业务副作用。

## 结果：通过

```powershell
.\scripts\qa-live-backend.ps1                       # 18082，真实 Kimi 凭证只进子进程环境
node scripts/resource-test-services.mjs 18091       # 自有只读夹具
node scripts/resource-kimi-live-check.mjs --live
.\scripts\qa-live-backend.ps1 -Stop
```

证据：[evidence/kimi-live-resource.json](evidence/kimi-live-resource.json)

链路是**通过页面接口新建的资源**，不是内置写死的路径：脚本先创建并发布一个 HTTP 只读工具资源（目标 `http://127.0.0.1:18091/http/ping`，显式授权本地目标），再创建并发布一个绑定该工具、模型下拉选中 `model_kimi` 的 Agent，然后做一次试运行。

实测：

| 断言 | 实测值 |
| --- | --- |
| 模型确实是资源选中的真实 kimi-k3 | 两次调用 `provider=KIMI`、`model=kimi-k3`、`resourceId` 均为 `model_kimi` 的资源 ID |
| 真实 token 用量（非桩） | 223+101=324、395+92=487 |
| 模型真的调用了页面配置的工具 | `toolCalls` 一条，`source=HTTP`、`state=SUCCEEDED`、9ms、资源 v1 |
| 夹具确实被请求 | 只读请求 +1，写入 +0 |
| 返回的是服务端不可预测的真实回执 | 答复正文即夹具本次随机生成的 `HTTP_RECEIPT_2c0bcd04bb7a`，模型无法猜出 |
| 只读工具不触发确认 | `confirmRequired=false` |
| 轮数预算生效 | `maxToolRounds=1`，实际 `toolRounds=1` |

一次试运行共产生 2 次 Kimi 请求（第一次决定调用工具，第二次基于工具结果作答），合计 811 tokens，符合“极少量真实调用”的约定。

## 不宣称的内容

本次只证明真实供应商链路可用与资源选择真实生效，不构成对模型回答质量、并发能力或长期稳定性的结论。Kimi 的 SSE 仍是完整响应缓冲后输出，未宣称上游逐 token 流式。
