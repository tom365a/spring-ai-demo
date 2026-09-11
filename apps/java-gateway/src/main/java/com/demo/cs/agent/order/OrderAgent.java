package com.demo.cs.agent.order;

import com.demo.cs.agent.SubAgent;
import com.demo.cs.agent.model.AgentModels.Slots;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OrderAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(OrderAgent.class);
    private static final Pattern ORDER_PATTERN = Pattern.compile("ORD\\d{5,}", Pattern.CASE_INSENSITIVE);

    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;
    private final OrderTicketTools tools;

    public OrderAgent(ChatClient.Builder chatClientBuilder, PromptLoader promptLoader, OrderTicketTools tools) {
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.tools = tools;
    }

    @Override
    public String name() {
        return "order";
    }

    @Override
    public SubAgentResult handle(SubAgentRequest request) {
        String orderId = resolveOrderId(request);
        if (isCancelIntent(request.text()) && orderId != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "cancel_order");
            payload.put("orderId", orderId);
            payload.put("userId", request.userId());
            payload.put("reason", "用户申请取消");
            payload.put("agentName", name());
            String answer = "您申请取消订单 " + orderId + "。此操作不可撤销，请在下方确认卡片点「确认执行」继续。";
            return SubAgentResult.confirm(name(), answer, payload);
        }

        tools.setSessionId(request.sessionId());
        try {
            String userPrompt = """
                    当前 userId=%s
                    路由槽位=%s
                    
                    【会话摘要】%s
                    【最近对话】
                    %s
                    
                    【用户问题】
                    %s
                    """.formatted(
                    request.userId(),
                    request.route().slots(),
                    request.summary() != null ? request.summary() : "无",
                    formatMessages(request),
                    request.text()
            );

            String answer = chatClientBuilder.build()
                    .prompt()
                    .system(promptLoader.load("agent_order.md"))
                    .user(userPrompt)
                    .tools(tools)
                    .call()
                    .content();

            return SubAgentResult.simple(name(), answer);
        } catch (Exception e) {
            log.warn("OrderAgent LLM failed, fallback to direct tool: {}", e.getMessage());
            return fallbackDirect(request.userId(), orderId);
        } finally {
            tools.clearSessionId();
        }
    }

    private SubAgentResult fallbackDirect(String userId, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return SubAgentResult.simple(name(), "请提供订单号（如 ORD20260730001），或配置有效的 LLM_API_KEY 后重试。");
        }
        String result = tools.queryOrder(orderId, userId);
        var tc = new com.demo.cs.agent.model.AgentModels.ToolCallRecord(
                "query_order",
                Map.of("orderId", orderId, "userId", userId),
                result,
                "local",
                0L,
                true
        );
        return new SubAgentResult(name(), "订单查询结果：\n" + result, List.of(), List.of(tc),
                false, null, null, null, null);
    }

    private boolean isCancelIntent(String text) {
        return text != null && text.contains("取消");
    }

    private String resolveOrderId(SubAgentRequest request) {
        Slots slots = request.route().slots();
        if (slots != null && slots.orderId() != null) {
            return slots.orderId();
        }
        Matcher m = ORDER_PATTERN.matcher(request.text() != null ? request.text() : "");
        return m.find() ? m.group().toUpperCase() : null;
    }

    private String formatMessages(SubAgentRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : request.recentMessages()) {
            sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        return sb.toString();
    }
}
