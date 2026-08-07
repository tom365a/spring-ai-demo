<!-- prompt-id: agent_order_user version: v1 -->
<!-- 用途：Admin Seed / 设计对照；当前 Runtime 未通过 PromptLoader 加载 -->
<!-- 开发者附加上下文（非 system 正文，由编排注入） -->
<!-- 模板变量：userId, slotsJson, text, summary, recentMessagesFormatted -->

当前 userId={userId}
路由槽位={slotsJson}

【会话摘要】{summary}
【最近对话】
{recentMessagesFormatted}

【用户问题】
{text}
