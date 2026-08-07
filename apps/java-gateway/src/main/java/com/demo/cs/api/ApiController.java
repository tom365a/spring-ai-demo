package com.demo.cs.api;

import com.demo.cs.api.dto.ApiDtos.*;
import com.demo.cs.application.attachment.AttachmentService;
import com.demo.cs.application.knowledge.KnowledgeService;
import com.demo.cs.application.orchestrator.AgentOrchestrator;
import com.demo.cs.application.session.SessionService;
import com.demo.cs.config.AppProperties;
import com.demo.cs.domain.CsAgentRouteLog;
import com.demo.cs.domain.CsSession;
import com.demo.cs.domain.CsToolInvocation;
import com.demo.cs.infrastructure.mcp.McpBridgeClient;
import com.demo.cs.infrastructure.persistence.CsAgentRouteLogRepository;
import com.demo.cs.infrastructure.persistence.CsToolInvocationRepository;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final SessionService sessionService;
    private final AgentOrchestrator orchestrator;
    private final AttachmentService attachmentService;
    private final KnowledgeService knowledgeService;
    private final McpBridgeClient mcpClient;
    private final CsAgentRouteLogRepository routeLogRepo;
    private final CsToolInvocationRepository toolInvocationRepo;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String llmModel;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}")
    private String embeddingModel;

    public ApiController(
            SessionService sessionService,
            AgentOrchestrator orchestrator,
            AttachmentService attachmentService,
            KnowledgeService knowledgeService,
            McpBridgeClient mcpClient,
            CsAgentRouteLogRepository routeLogRepo,
            CsToolInvocationRepository toolInvocationRepo,
            AppProperties props,
            ObjectMapper objectMapper
    ) {
        this.sessionService = sessionService;
        this.orchestrator = orchestrator;
        this.attachmentService = attachmentService;
        this.knowledgeService = knowledgeService;
        this.mcpClient = mcpClient;
        this.routeLogRepo = routeLogRepo;
        this.toolInvocationRepo = toolInvocationRepo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ---- Sessions ----

    @PostMapping("/sessions")
    public ApiEnvelope<SessionResponse> createSession(@RequestBody(required = false) CreateSessionRequest req) {
        String userId = req != null && req.userId() != null ? req.userId() : "u_001";
        String channel = req != null && req.channel() != null ? req.channel() : "web";
        CsSession session = sessionService.createSession(userId, channel);
        return ApiEnvelope.ok(sessionService.toResponse(session, true));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiEnvelope<SessionResponse>> getSession(@PathVariable String sessionId) {
        CsSession session = sessionService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.error(40401, "session not found"));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(sessionService.toResponse(session, true)));
    }

    @GetMapping("/sessions")
    public ApiEnvelope<SessionListResponse> listSessions(
            @RequestParam String userId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<SessionResponse> items = sessionService.listByUser(userId, limit).stream()
                .map(s -> sessionService.toResponse(s, false))
                .toList();
        return ApiEnvelope.ok(new SessionListResponse(items, items.size()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiEnvelope<CloseSessionResponse>> closeSession(@PathVariable String sessionId) {
        CsSession session = sessionService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.error(40401, "session not found"));
        }
        sessionService.closeSession(sessionId);
        return ResponseEntity.ok(ApiEnvelope.ok(new CloseSessionResponse(true, sessionId, "closed")));
    }

    // ---- Chat ----

    @PostMapping("/chat")
    public ResponseEntity<ApiEnvelope<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            validateChatRequest(request);
            ChatResponse response = orchestrator.chat(normalizeOptions(request));
            return ResponseEntity.ok(ApiEnvelope.ok(response));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(ApiEnvelope.error(40301, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiEnvelope.error(40901, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.error(40001, e.getMessage()));
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        validateChatRequest(request);
        return orchestrator.chatStream(normalizeOptions(request));
    }

    // ---- Attachments ----

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiEnvelope<AttachmentResponse>> uploadAttachment(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "sessionId", required = false) String sessionId
    ) {
        try {
            var att = attachmentService.upload(file, sessionId);
            return ResponseEntity.ok(ApiEnvelope.ok(attachmentService.toResponse(att)));
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("too large")) {
                return ResponseEntity.status(413).body(ApiEnvelope.error(41301, e.getMessage()));
            }
            return ResponseEntity.status(400).body(ApiEnvelope.error(40001, e.getMessage()));
        }
    }

    // ---- Knowledge ----

    @PostMapping("/knowledge/ingest")
    public ResponseEntity<ApiEnvelope<KnowledgeDocResponse>> ingest(@RequestBody KnowledgeIngestRequest req) {
        if (req == null || req.title() == null || req.content() == null) {
            return ResponseEntity.status(400).body(ApiEnvelope.error(40001, "title and content required"));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(knowledgeService.ingest(req.title(), req.content(), req.source())));
    }

    @PostMapping("/knowledge/bootstrap")
    public ApiEnvelope<BootstrapResponse> bootstrap() {
        var result = knowledgeService.bootstrapSamples();
        return ApiEnvelope.ok(new BootstrapResponse(result.ingested(), result.docs()));
    }

    @GetMapping("/knowledge/docs")
    public ApiEnvelope<List<KnowledgeDocResponse>> listDocs() {
        return ApiEnvelope.ok(knowledgeService.listDocs());
    }

    @PostMapping("/knowledge/search")
    public ApiEnvelope<SearchResponse> search(@RequestBody SearchRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            return ApiEnvelope.error(40001, "query required");
        }
        return ApiEnvelope.ok(new SearchResponse(knowledgeService.search(req.query(), req.topK())));
    }

    // ---- MCP ----

    @GetMapping("/mcp/tools")
    public ApiEnvelope<McpToolsResponse> mcpTools() {
        return ApiEnvelope.ok(new McpToolsResponse(mcpClient.isEnabled(), mcpClient.listTools()));
    }

    @PostMapping("/mcp/call")
    public ApiEnvelope<McpCallResponse> mcpCall(@RequestBody McpCallRequest req) {
        if (req == null || req.name() == null) {
            return ApiEnvelope.error(40001, "name required");
        }
        Object result = mcpClient.callTool(req.name(), req.arguments());
        return ApiEnvelope.ok(new McpCallResponse(result));
    }

    // ---- Debug ----

    @GetMapping("/debug/config")
    public ApiEnvelope<Map<String, Object>> debugConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "spring-ai-multi-agent");
        config.put("mcpEnabled", props.mcp().enabled());
        config.put("ragTopK", props.rag().topK());
        config.put("ragScoreThreshold", props.rag().scoreThreshold());
        config.put("routeConfidenceThreshold", props.routeConfidenceThreshold());
        config.put("sessionWindowSize", props.sessionWindowSize());
        config.put("llmModel", llmModel);
        config.put("embeddingModel", embeddingModel);
        config.put("vectorBackend", props.vector().backend());
        config.put("agentConfigEnabled", props.agentConfig().enabled());
        config.put("agentConfigFallbackToLegacy", props.agentConfig().fallbackToLegacy());
        config.put("agentConfigSeedOnStartup", props.agentConfig().seedOnStartup());
        return ApiEnvelope.ok(config);
    }

    @GetMapping("/debug/routes")
    public ApiEnvelope<Map<String, Object>> debugRoutes(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<CsAgentRouteLog> logs;
        if (sessionId != null && !sessionId.isBlank()) {
            logs = routeLogRepo.findBySessionIdOrderByCreatedAtDesc(sessionId);
        } else {
            logs = routeLogRepo.findAll();
        }
        int cap = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> items = logs.stream().limit(cap).map(log -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", log.getId());
            m.put("sessionId", log.getSessionId());
            m.put("intent", log.getIntent());
            m.put("targetAgent", log.getTargetAgent());
            m.put("confidence", log.getConfidence());
            m.put("reason", log.getReason());
            m.put("needClarify", log.getNeedClarify());
            m.put("createdAt", log.getCreatedAt());
            return m;
        }).toList();
        return ApiEnvelope.ok(Map.of("items", items));
    }

    @GetMapping("/debug/tools")
    public ApiEnvelope<Map<String, Object>> debugTools(@RequestParam(required = false) String sessionId) {
        List<CsToolInvocation> invocations;
        if (sessionId != null && !sessionId.isBlank()) {
            invocations = toolInvocationRepo.findBySessionIdOrderByCreatedAtDesc(sessionId);
        } else {
            invocations = toolInvocationRepo.findAll();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (CsToolInvocation inv : invocations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", inv.getId());
            m.put("sessionId", inv.getSessionId());
            m.put("name", inv.getToolName());
            m.put("arguments", readJson(inv.getArgsJson()));
            m.put("result", inv.getResultJson());
            m.put("source", inv.getSource());
            m.put("latencyMs", inv.getLatencyMs());
            m.put("success", inv.getResultJson() != null && inv.getResultJson().contains("\"ok\":true"));
            m.put("createdAt", inv.getCreatedAt());
            items.add(m);
        }
        return ApiEnvelope.ok(Map.of("items", items));
    }

    @GetMapping("/debug/tickets")
    public ApiEnvelope<Map<String, Object>> debugTickets() {
        return ApiEnvelope.ok(Map.of("items", OrderTicketTools.mockTickets()));
    }

    // ---- Helpers ----

    private void validateChatRequest(ChatRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if ((request.text() == null || request.text().isBlank())
                && (request.attachmentIds() == null || request.attachmentIds().isEmpty())) {
            throw new IllegalArgumentException("text or attachmentIds required");
        }
        if (request.attachmentIds() != null && request.attachmentIds().size() > 5) {
            throw new IllegalArgumentException("at most 5 attachments");
        }
        if (Boolean.TRUE.equals(request.confirm()) && request.sessionId() != null) {
            CsSession session = sessionService.getSession(request.sessionId());
            if (session != null && session.getConfirmationPayload() == null
                    && (request.confirmPayload() == null || request.confirmPayload().isEmpty())) {
                throw new IllegalStateException("confirm requested but no payload");
            }
        }
    }

    private ChatRequest normalizeOptions(ChatRequest request) {
        ChatOptions opts = request.options();
        if (opts == null) {
            opts = new ChatOptions(true, true, false);
        }
        return new ChatRequest(
                request.sessionId(),
                request.userId(),
                request.text(),
                request.attachmentIds(),
                request.confirm(),
                request.confirmPayload(),
                request.locale(),
                opts
        );
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return json;
        }
    }
}
