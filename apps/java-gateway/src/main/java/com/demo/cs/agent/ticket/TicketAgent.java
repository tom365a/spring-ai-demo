package com.demo.cs.agent.ticket;

import com.demo.cs.agent.SubAgent;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TicketAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(TicketAgent.class);
    private static final Pattern ORDER_PATTERN = Pattern.compile("ORD\\d{5,}", Pattern.CASE_INSENSITIVE);

    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;
    private final OrderTicketTools tools;

    public TicketAgent(ChatClient.Builder chatClientBuilder, PromptLoader promptLoader, OrderTicketTools tools) {
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.tools = tools;
    }

    @Override
    public String name() {
        return "ticket";
    }

    @Override
    public SubAgentResult handle(SubAgentRequest request) {
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
                    .system(promptLoader.load("agent_ticket.md"))
                    .user(userPrompt)
                    .tools(tools)
                    .call()
                    .content();

            return SubAgentResult.simple(name(), answer);
        } catch (Exception e) {
            log.warn("TicketAgent LLM failed, fallback to confirm flow: {}", e.getMessage());
            return fallbackConfirm(request);
        } finally {
            tools.clearSessionId();
        }
    }

    private SubAgentResult fallbackConfirm(SubAgentRequest request) {
        String text = request.text() != null ? request.text() : "";
        if (!(text.contains("工单") || text.contains("投诉") || text.contains("售后"))) {
            return SubAgentResult.simple(name(), "工单服务暂时不可用（模型接口异常）。请检查 LLM_API_KEY 后重试。");
        }
        Matcher m = ORDER_PATTERN.matcher(text);
        String orderId = m.find() ? m.group().toUpperCase() : null;
        String category = text.contains("投诉") ? "投诉" : "售后";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "create_ticket");
        payload.put("userId", request.userId());
        payload.put("category", category);
        payload.put("description", text);
        payload.put("orderId", orderId);
        payload.put("agentName", name());
        return SubAgentResult.confirm(name(),
                "将为您创建「" + category + "」工单" + (orderId != null ? "（关联 " + orderId + "）" : "")
                        + "。请回复「确认」继续。",
                payload);
    }

    private String formatMessages(SubAgentRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : request.recentMessages()) {
            sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        return sb.toString();
    }
}
