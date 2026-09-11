# 旧数据库升级与重复启动验证

2026-09-10，根协调对停止状态下的原本机 H2 数据库做独立备份，并将副本放到 `.tools/resource-migration-db/cs.mv.db`。本验证仅使用副本、18081 端口、合成模型凭证和本地协议服务；原数据库没有参与测试写入。备份位置与文件 SHA256 记录在本机 `.tools/resource-upgrade-backup.json`。

原库包含 8 张旧业务表、7 个 Agent、7 份 Agent 已发布版本和 3 条知识文档元数据；原会话、附件、消息和调用表为空。本结果不能单独证明非空历史会话的迁移，相关行为由下节的自动化测试补充。

验证先为每张旧表保存原列集合、行数和逐行排序后的 SHA256 聚合值，数据原文不导出。第一次使用新增资源配置代码启动副本后，查询资源管理 API，确认生成 22 项资源，原 7 个 Agent 仍可加载；停止进程后按原列集合重算行哈希，8 表全部一致。

第二次启动同一副本后，资源 ID、代码、版本号、启停标记与第一次完全相同，没有重复生成资源。再次停止后，8 张旧表的原列数据行哈希仍与升级前完全相同。此验证覆盖加表/加列迁移、原始 Agent 草稿与版本 JSON 保留，以及种子初始化幂等性。

证据：

- `evidence/migration-before.txt`：旧表原列、行数与哈希。
- `evidence/migration-after-first.txt`、`evidence/migration-after-second.txt`：两次启动后完全一致的旧列哈希。
- `evidence/migration-first-resources.json`、`evidence/migration-second-resources.json`：两次资源身份与版本快照。
- 本机启动日志 `.tools/resource-migration-first.log`、`.tools/resource-migration-second.log`，分别于 17:15:14 与 17:16:40 完成启动。

这不是旧二进制回退演练：工作区没有可用于完整回退演练的旧发布包。升级交付应保留本次可运行包、数据库与独立主密钥，后续版本按交付说明备份与恢复。

## 非空历史会话的自动化重启验证

`QaResourceMigrationTest`（`apps/java-gateway/src/test/java/com/demo/cs/QaResourceMigrationTest.java`）补上了上节缺的那一半：它先用纯 SQL 建出迁移前的 `cs_session`/`cs_message` 两张旧表并写入一条历史会话（摘要、用户提问、助手回复各一条），然后对同一个文件库真实拉起两次子 JVM。每次启动后断言：

- 会话接口仍返回原摘要与两条原始消息，顺序与内容不变；
- 资源列表非空，且第二次启动与第一次的资源 `id:code:publishedVersion` 完全一致（种子迁移幂等、不重复生成）；
- 进程优雅退出且退出码为 0，关库后按原列重算旧表逐行值，与升级前完全相同；
- 关库后直接查询 `cfg_resource` 的结果与该次 API 返回逐条一致，证明列表里的资源确实落了盘，而不是只存在于内存。

收尾阶段修正了这个夹具的两处问题，否则它无法证明迁移：一是 `environment().clear()` 把 `TEMP`/`TMP` 一起清掉，Windows 上 Tomcat 建不出临时目录，子 JVM 根本起不来；二是原先在 `/actuator/health` 返回 200 时就查询并用 `process.destroy()` 强杀进程——而种子迁移跑在 `ApplicationRunner` 里，要到 `ApplicationReadyEvent` 才结束，强杀又可能丢掉尚未落盘的提交，两者叠加会让第二次启动看起来像“迁移不幂等、资源 ID 变了”。现改为等待 `/actuator/health/readiness` 变 UP、经 actuator 优雅关闭并断言退出码。修正后连续三次独立执行稳定通过。

