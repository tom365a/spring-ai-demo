# 资源配置迁移与交付说明
日期：2026-09-10。责任：全栈架构师；迁移实测由根协作者在隔离旧库副本执行并提供证据。当前仍处最终验证阶段。

## 数据变更与幂等
新增`cfg_resource`、`cfg_resource_version`、`cfg_resource_credential`、`cfg_setting`、`cfg_resource_audit`、`cfg_pending_action`；`cs_tool_invocation`新增可空success列。使用现有JPA ddl-auto=update兼容H2/PostgreSQL，不删除旧表列。

ResourceBootstrap在AgentSeedService之前执行，按稳定code逐项幂等登记：
- 原17个内置工具保留原code，新增来源、风险、schema和规则的v1资源。
- 原3个可信技能保留code，v1指令/工具/RAG语义保持。历史Agent JSON中缺skillVersions的旧技能固定解释为v1，不跟随最新版本。
- 登记model_kimi与model_openai两个模型。按照启动提供方选择默认，只保存KIMI_API_KEY或OPENAI_API_KEY环境变量引用，绝不复制真实秘密到迁移文件。
- 原Agent code、草稿JSON、已发布版本及历史快照不批量改写。模型缺resourceId解释为系统默认；旧主Agent单层转交规范化为最多1次。
- 重复启动按既有code跳过，不重复版本、覆盖草稿、替换凭证或重设用户选择的默认模型。

旧待确认动作无法安全恢复外部执行状态。重启时新确认PENDING转INVALIDATED、EXECUTING转UNKNOWN；必须重新发起，绝不自动执行或重试写请求。公开会话保留。

## 密钥与安全配置
输入型凭证使用AES-256-GCM，随机12字节nonce，AAD绑定凭证版本ID。主密钥由部署环境`APP_RESOURCE_MASTER_KEY`提供，值为32随机字节的Base64；与数据库分开备份，不得写进Git、文档、日志或API响应。无主密钥时仍可使用ENV凭证；INPUT保存会明确失败。

默认允许引用`KIMI_API_KEY,OPENAI_API_KEY,OPENAI_EMBEDDING_API_KEY`。通过`app.resources.allowed-credential-env`显式配置白名单。前端KEEP/REPLACE/CLEAR均走服务端接口；清除先影响草稿，发布后生效。旧密文版本不会因替换或清除而被覆盖。

Windows启动器支持APP_变量读取，故.env可提供APP_RESOURCE_MASTER_KEY；启动器不会打印秘密。OpenAI未充值时不要执行其连接测试或设为默认；本次OpenAI测试全部使用本地模拟服务。

## 升级与验证
1. 停止待升级实例，确认H2数据库没有写入进程；备份旧应用包、数据库文件、上传/知识持久化目录以及独立凭证主密钥。PostgreSQL使用一致性备份。
2. 先在副本和隔离端口启动新版本。验证旧Agent数量/code/草稿/发布历史及会话仍在、新资源数正确、重复启动资源ID/版本数量不变。
3. 使用合成凭证与已授权loopback目标执行资源连接、HTTP读写确认、标准MCP同步、技能版本与主子模型路由验证。
4. QA和产品验收通过后，再由用户授权的部署流程切换正式实例。不要让两个进程同时写同一H2文件。
5. 首次真实模型调用由根协作者统一控制；其余启动验证和迁移不需要外部模型请求。

此轮根已建立旧库副本 `.tools/resource-migration-db/cs.mv.db`，原8表无资源表，备份记录 `.tools/resource-upgrade-backup.json`。最终幂等/逐表哈希证据待根迁移报告写入，不预先声称通过。

## 回退
停止新实例，恢复旧应用包和升级前数据库/文件备份，使用原启动配置启动。新增资源配置在旧版不可用，不应把升级后的新确认交给旧确认执行路径。回退不能撤销已发出的外部业务写动作；出现UNKNOWN必须人工核验外部状态后再决定后续操作。旧包与旧库一致回退是推荐路径，新表兼容性不代替回退演练。

## 运行边界
仅支持当前单实例协调；没有多实例缓存广播和跨节点确认仲裁。默认主转交最多1次。模型上游响应缓冲后以应用SSE返回，保留诊断但不把reasoning或内部工具结果放入公开历史。公开历史按完成问答轮计数；确认等待不消耗活跃预算，TTL30分钟。相关版本变化使原确认失效，同会话重新发起继承已耗轮数和剩余活跃预算。
