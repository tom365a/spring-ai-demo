package com.demo.cs.agent.supervisor;

import com.demo.cs.agent.model.AgentModels.RouteDecision;
import com.demo.cs.agent.model.AgentModels.Slots;
import com.demo.cs.config.AppProperties;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.demo.cs.domain.CsAgentRouteLog;
import com.demo.cs.domain.CsSession;
import com.demo.cs.infrastructure.persistence.CsAgentRouteLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);
    private static final Pattern ORDER_PATTERN = Pattern.compile("ORD\\d{5,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING_PATTERN = Pattern.compile("[A-Z]{2}\\d{10,}|SF\\d{10,}", Pattern.CASE_INSENSITIVE);

    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final CsAgentRouteLogRepository routeLogRepo;
    private final double confidenceThreshold;

    public SupervisorAgent(
            ChatClient.Builder chatClientBuilder,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            CsAgentRouteLogRepository routeLogRepo,
            AppProperties props
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.routeLogRepo = routeLogRepo;
        this.confidenceThreshold = props.routeConfidenceThreshold();
    }

    public RouteDecision route(
            CsSession session,
            String userId,
            String text,
            boolean hasAttachments,
            int attachmentCount,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> confirmationPayload
    ) {
        RouteDecision decision = tryLlmRoute(session, userId, text, hasAttachments, attachmentCount,
                recentMessages, confirmationPayload);
        if (decision == null) {
            decision = heuristicRoute(text, hasAttachments);
        }
        decision = enrichSlots(decision, text);
        logRoute(session.getId(), decision);
        return decision;
    }

    public boolean needsClarify(RouteDecision decision) {
        return decision.needClarify()
                || decision.confidence() < confidenceThreshold
                || "unclear".equals(decision.intent());
    }

    private RouteDecision tryLlmRoute(
            CsSession session,
            String userId,
            String text,
            boolean hasAttachments,
            int attachmentCount,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> confirmationPayload
    ) {
        try {
            String system = promptLoader.load("supervisor_router.md");
            String userPrompt = buildUserPrompt(session, userId, text, hasAttachments, attachmentCount,
                    recentMessages, confirmationPayload);
            String raw = chatClientBuilder.build()
                    .prompt()
                    .system(system)
                    .user(userPrompt)
                    .call()
                    .content();
            return parseRouteDecision(raw);
        } catch (Exception e) {
            log.warn("Supervisor LLM route failed, using heuristic: {}", e.getMessage());
            return null;
        }
    }

    private String buildUserPrompt(
            CsSession session,
            String userId,
            String text,
            boolean hasAttachments,
            int attachmentCount,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> confirmationPayload
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会话状态】").append(session.getStatus()).append('\n');
        sb.append("【待确认摘要】").append(confirmationPayload != null ? confirmationPayload : "无").append('\n');
        sb.append("【会话摘要】").append(session.getSummary() != null ? session.getSummary() : "无").append('\n');
        sb.append("【最近对话】\n");
        for (Map<String, Object> m : recentMessages) {
            sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        sb.append("\n【本轮用户】\n");
        sb.append("userId=").append(userId).append('\n');
        sb.append("hasAttachments=").append(hasAttachments)
                .append(" attachmentCount=").append(attachmentCount).append('\n');
        sb.append("text:\n").append(text).append("\n\n请输出 RouteDecision JSON。");
        return sb.toString();
    }

    private RouteDecision parseRouteDecision(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return null;
        String json = extractJson(raw);
        JsonNode node = objectMapper.readTree(json);
        Slots slots = new Slots(
                textOrNull(node.path("slots").path("orderId")),
                textOrNull(node.path("slots").path("trackingNo")),
                textOrNull(node.path("slots").path("category")),
                textOrNull(node.path("slots").path("amountHint"))
        );
        return new RouteDecision(
                textOrNull(node.path("intent")),
                node.path("confidence").asDouble(0.5),
                textOrNull(node.path("targetAgent")),
                textOrNull(node.path("reason")),
                slots,
                node.path("needClarify").asBoolean(false),
                textOrNull(node.path("clarifyQuestion"))
        );
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw.trim();
    }

    private RouteDecision heuristicRoute(String text, boolean hasAttachments) {
        String t = text != null ? text : "";
        if (hasAttachments) {
            return new RouteDecision("multimodal", 0.75, "vision", "heuristic: has attachments",
                    new Slots(null, null, null, null), false, null);
        }
        if (containsAny(t, "退货", "退款", "政策", "运费", "包邮", "FAQ", "faq")) {
            return new RouteDecision("knowledge", 0.7, "knowledge", "heuristic: policy keywords",
                    new Slots(null, null, null, null), false, null);
        }
        if (containsAny(t, "订单", "取消", "物流", "运单", "ORD", "发货")) {
            return new RouteDecision("order", 0.7, "order", "heuristic: order keywords",
                    new Slots(null, null, null, null), false, null);
        }
        if (containsAny(t, "工单", "破损", "少件", "投诉", "人工")) {
            return new RouteDecision("ticket", 0.7, "ticket", "heuristic: ticket keywords",
                    new Slots(null, null, null, null), false, null);
        }
        if (t.trim().length() < 4) {
            return RouteDecision.unclear("您好，请问您想咨询订单、售后政策，还是需要其他帮助？");
        }
        return new RouteDecision("chitchat", 0.6, "chitchat", "heuristic: default chitchat",
                new Slots(null, null, null, null), false, null);
    }

    private RouteDecision enrichSlots(RouteDecision decision, String text) {
        Slots slots = decision.slots();
        String orderId = slots.orderId();
        String trackingNo = slots.trackingNo();
        if (orderId == null) {
            Matcher m = ORDER_PATTERN.matcher(text != null ? text : "");
            if (m.find()) orderId = m.group().toUpperCase();
        }
        if (trackingNo == null) {
            Matcher m = TRACKING_PATTERN.matcher(text != null ? text : "");
            if (m.find()) trackingNo = m.group().toUpperCase();
        }
        if (orderId != null || trackingNo != null) {
            slots = new Slots(orderId, trackingNo, slots.category(), slots.amountHint());
            return new RouteDecision(decision.intent(), decision.confidence(), decision.targetAgent(),
                    decision.reason(), slots, decision.needClarify(), decision.clarifyQuestion());
        }
        return decision;
    }

    private void logRoute(String sessionId, RouteDecision decision) {
        CsAgentRouteLog logEntry = new CsAgentRouteLog();
        logEntry.setSessionId(sessionId);
        logEntry.setIntent(decision.intent());
        logEntry.setTargetAgent(decision.targetAgent());
        logEntry.setConfidence(decision.confidence());
        logEntry.setReason(decision.reason());
        logEntry.setNeedClarify(decision.needClarify());
        logEntry.setCreatedAt(Instant.now());
        routeLogRepo.save(logEntry);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String v = node.asText(null);
        return v != null && !v.isBlank() && !"null".equalsIgnoreCase(v) ? v : null;
    }
}
