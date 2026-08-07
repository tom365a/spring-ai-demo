<!-- prompt-id: agent_knowledge_user version: v1 -->
<!-- 用途：Admin Seed / 设计对照；当前 Runtime 未通过 PromptLoader 加载 -->
<!-- 模板变量：summary, recentMessagesFormatted, retrievedBlocks, mcpBlocks, text -->

【会话摘要】{summary}
【最近对话】
{recentMessagesFormatted}

【检索结果】
{retrievedBlocks}

【MCP 补充】（可为空）
{mcpBlocks}

【用户问题】
{text}
