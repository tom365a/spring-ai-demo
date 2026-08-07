package com.demo.cs.api.dto;

import com.demo.cs.agent.model.AgentModels.Citation;
import com.demo.cs.agent.model.AgentModels.ToolCallRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiDtos {

    private ApiDtos() {}

    public record CreateSessionRequest(String userId, String channel) {}

    public record MessageResponse(
            String id,
            String role,
            String content,
            String agentName,
            List<Citation> citations,
            List<ToolCallRecord> toolCalls,
            List<String> attachments,
            Instant createdAt
    ) {}

    public record SessionResponse(
            String id,
            String userId,
            String channel,
            String status,
            String summary,
            String lastIntent,
            String lastAgent,
            Map<String, Object> confirmationPayload,
            Instant createdAt,
            Instant updatedAt,
            List<MessageResponse> messages
    ) {}

    public record ChatOptions(Boolean enableRag, Boolean enableMcp, Boolean stream) {
        public boolean ragEnabled() {
            return enableRag == null || enableRag;
        }

        public boolean mcpEnabled() {
            return enableMcp == null || enableMcp;
        }
    }

    public record ChatRequest(
            String sessionId,
            String userId,
            String text,
            List<String> attachmentIds,
            Boolean confirm,
            Map<String, Object> confirmPayload,
            String locale,
            ChatOptions options
    ) {}

    public record ChatResponse(
            String sessionId,
            String answer,
            String intent,
            String agentName,
            Double confidence,
            String reason,
            List<Citation> citations,
            List<ToolCallRecord> toolCalls,
            boolean confirmRequired,
            Map<String, Object> confirmationPayload,
            String mode
    ) {}

    public record AttachmentResponse(
            String id,
            String originalName,
            String contentType,
            String publicUrl,
            long sizeBytes,
            String sessionId
    ) {}

    public record KnowledgeIngestRequest(String title, String content, String source) {}

    public record KnowledgeDocResponse(
            String id,
            String title,
            String source,
            int chunkCount,
            Instant createdAt
    ) {}

    public record SearchRequest(String query, Integer topK) {}

    public record SearchHit(String content, double score, Map<String, Object> metadata) {}

    public record SearchResponse(List<SearchHit> hits) {}

    public record McpToolInfo(String name, String description, String source) {}

    public record McpToolsResponse(boolean enabled, List<McpToolInfo> tools) {}

    public record McpCallRequest(String name, Map<String, Object> arguments) {}

    public record McpCallResponse(Object result) {}

    public record SessionListResponse(List<SessionResponse> items, int total) {}

    public record CloseSessionResponse(boolean ok, String sessionId, String status) {}

    public record BootstrapResponse(int ingested, List<KnowledgeDocResponse> docs) {}

    /** Optional envelope – use when wrapping success responses. */
    public record ApiEnvelope<T>(int code, String message, String traceId, T data) {
        public static <T> ApiEnvelope<T> ok(T data) {
            return new ApiEnvelope<>(0, "ok", null, data);
        }

        public static <T> ApiEnvelope<T> ok(T data, String traceId) {
            return new ApiEnvelope<>(0, "ok", traceId, data);
        }

        public static <T> ApiEnvelope<T> error(int code, String message) {
            return new ApiEnvelope<>(code, message, null, null);
        }
    }
}
