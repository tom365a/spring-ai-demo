<!-- prompt-id: agent_ticket_user version: v1 -->
<!-- 用途：Admin Seed / 设计对照；当前 Runtime 未通过 PromptLoader 加载 -->
<!-- 模板变量：userId, slotsJson, text, summary, recentMessagesFormatted, visionSummary -->

当前 userId={userId}
路由槽位={slotsJson}

【会话摘要】{summary}
【最近对话】
{recentMessagesFormatted}
【图片理解摘要】（可为空）
{visionSummary}

【用户问题】
{text}
