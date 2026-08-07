<!-- prompt-id: supervisor_router_user version: v1 -->
<!-- 用途：Admin Seed / 设计对照写入 userPromptTemplate；当前 Runtime 未通过 PromptLoader 加载 -->
<!-- 模板变量：sessionStatus, confirmationPayloadSummary, summary, recentMessagesFormatted, userId, hasAttachments, attachmentCount, text -->

【会话状态】{sessionStatus}
【待确认摘要】{confirmationPayloadSummary|无}
【会话摘要】{summary|无}
【最近对话】
{recentMessagesFormatted}

【本轮用户】
userId={userId}
hasAttachments={hasAttachments} attachmentCount={attachmentCount}
text:
{text}

请输出 RouteDecision JSON。
