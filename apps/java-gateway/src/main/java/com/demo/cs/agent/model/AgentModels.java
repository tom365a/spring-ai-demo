package com.demo.cs.agent.model;

import java.util.List;
import java.util.Map;

public final class AgentModels {

    private AgentModels() {}

    public record RouteDecision(
            String intent,
            double confidence,
            String targetAgent,
            String reason,
            Slots slots,
            boolean needClarify,
            String clarifyQuestion
    ) {
        public RouteDecision {
            if (slots == null) slots = new Slots(null, null, null, null);
        }

        public static RouteDecision unclear(String question) {
            return new RouteDecision("unclear", 0.3, "none", "heuristic unclear",
                    new Slots(null, null, null, null), true, question);
        }
    }

    public record Slots(String orderId, String trackingNo, String category, String amountHint) {}

    public record SubAgentRequest(
            String sessionId,
            String userId,
            String text,
            String summary,
            List<Map<String, Object>> recentMessages,
            List<AttachmentView> attachments,
            RouteDecision route,
            boolean enableMcp
    ) {
        public SubAgentRequest {
            if (recentMessages == null) recentMessages = List.of();
            if (attachments == null) attachments = List.of();
        }
    }

    public record AttachmentView(String id, String contentType, String path, String publicUrl) {}

    public record Citation(
            String docId,
            String title,
            String content,
            double score,
            String source,
            Map<String, Object> metadata
    ) {
        public Citation {
            if (metadata == null) metadata = Map.of();
        }
    }

    public record ToolCallRecord(
            String name,
            Map<String, Object> arguments,
            String result,
            String source,
            long latencyMs,
            boolean success
    ) {
        public ToolCallRecord {
            if (arguments == null) arguments = Map.of();
        }
    }

    public record SubAgentResult(
            String agentName,
            String answer,
            List<Citation> citations,
            List<ToolCallRecord> toolCalls,
            boolean confirmRequired,
            Map<String, Object> confirmationPayload,
            String suggestedIntent,
            Slots suggestedSlots,
            String visionSummary
    ) {
        public SubAgentResult {
            if (citations == null) citations = List.of();
            if (toolCalls == null) toolCalls = List.of();
        }

        public static SubAgentResult simple(String agentName, String answer) {
            return new SubAgentResult(agentName, answer, List.of(), List.of(),
                    false, null, null, null, null);
        }

        public static SubAgentResult confirm(String agentName, String answer, Map<String, Object> payload) {
            return new SubAgentResult(agentName, answer, List.of(), List.of(),
                    true, payload, null, null, null);
        }
    }

    /** Maps orchestration outcome to API ChatResponse fields. */
    public record TurnOutcome(
            String sessionId,
            String answer,
            String intent,
            String agentName,
            Double confidence,
            String reason,
            List<Citation> citations,
            List<ToolCallRecord> toolCalls,
            boolean confirmRequired,
            Map<String, Object> confirmationPayload
    ) {
        public static final String MODE = "spring-ai-multi-agent";

        public TurnOutcome {
            if (citations == null) citations = List.of();
            if (toolCalls == null) toolCalls = List.of();
        }

        public static TurnOutcome fromRoute(
                String sessionId,
                RouteDecision route,
                SubAgentResult result
        ) {
            return new TurnOutcome(
                    sessionId,
                    result.answer(),
                    route.intent(),
                    result.agentName(),
                    route.confidence(),
                    route.reason(),
                    result.citations(),
                    result.toolCalls(),
                    result.confirmRequired(),
                    result.confirmationPayload()
            );
        }

        public static TurnOutcome confirmResume(
                String sessionId,
                String answer,
                List<ToolCallRecord> toolCalls
        ) {
            return new TurnOutcome(
                    sessionId,
                    answer,
                    "confirm_resume",
                    "confirm_executor",
                    null,
                    "用户确认执行待办操作",
                    List.of(),
                    toolCalls,
                    false,
                    null
            );
        }

        public Map<String, Object> toChatResponseMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("sessionId", sessionId);
            m.put("answer", answer);
            m.put("intent", intent != null ? intent : "");
            m.put("agentName", agentName != null ? agentName : "");
            m.put("confidence", confidence != null ? confidence : 0.0);
            m.put("reason", reason != null ? reason : "");
            m.put("citations", citations);
            m.put("toolCalls", toolCalls);
            m.put("confirmRequired", confirmRequired);
            m.put("confirmationPayload", confirmationPayload);
            m.put("mode", MODE);
            return m;
        }
    }
}
