# 开发与验证环境

日期：2026-09-10。工作目录为 `C:\spring-ai-demo`，当前目录未包含 Git 仓库，不能生成真实提交号或 Git 差异记录。

## 构建基线

机器 PATH 初始无 Java/Maven。本次在项目 `.tools` 准备官方便携 Microsoft OpenJDK 21 与 Apache Maven 3.9.9，未修改系统安装。依赖缓存位于 `.tools/m2`；`.gitignore` 已忽略工具与运行数据库目录。

修改业务代码前执行 `mvn test`：`BUILD SUCCESS`，同时为 `No tests to run`。这只能证明初始项目编译成功，不能作为任何功能通过的证据。日志：`.tools/baseline-build.log`。本轮最终结果以 SELF_TEST_REPORT.md 和 TEST_REPORT.md 为准。

## 常规使用

在项目根目录的 PowerShell 中执行：

```powershell
./scripts/dev.ps1 -Action test
./scripts/dev.ps1 -Action run
```

脚本自动使用项目 `.tools` 中的便携工具；不存在时使用系统 JAVA_HOME/PATH 中的工具。运行默认 local profile、8080 端口，进入 `/admin.html`。可通过 `-Port 8081` 更换端口；依赖已完整缓存后可使用 `-Offline`。第一次运行新 Maven 目标可能仍需下载相应官方插件依赖。

真实对话需在启动进程前配置有效模型服务，例如 `SPRING_AI_OPENAI_API_KEY` 与 `SPRING_AI_OPENAI_BASE_URL`。请勿把真实 Key 写进交付文档、日志或版本库。

## 受控模型验证

```powershell
node scripts/mock-openai.mjs 18091 .tools/mock-openai-requests.jsonl
```

另一个终端配置以下变量后启动应用：

```powershell
$env:SPRING_AI_OPENAI_API_KEY='local-test-key'
$env:SPRING_AI_OPENAI_BASE_URL='http://127.0.0.1:18091'
./scripts/dev.ps1 -Action run
```

该服务仅监听本机环回地址，使用合成输入；可验证真实 Spring AI 请求、提示词/技能、工具定义与调用和主子 Agent 路由。它不是云模型，不验证真实模型回答质量。请求会写入指定 JSONL 文件，测试时不要输入真实敏感数据。

功能测试使用独立端口、独立数据库与上传目录，避免测试 Agent 混入正常演示数据。具体进程重启和页面操作证据由测试报告列出。
