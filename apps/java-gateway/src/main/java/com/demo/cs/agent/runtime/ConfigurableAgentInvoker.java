package com.demo.cs.agent.runtime;

import com.demo.cs.agent.model.AgentModels.AttachmentView;
import com.demo.cs.agent.model.AgentModels.Citation;
import com.demo.cs.agent.model.AgentModels.RouteDecision;
import com.demo.cs.agent.model.AgentModels.Slots;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.application.knowledge.KnowledgeService;
import com.demo.cs.config.AppProperties;
import com.demo.cs.domain.CsAgentRouteLog;
import com.demo.cs.domain.CsSession;
import com.demo.cs.infrastructure.catalog.LocalToolCatalog;
import com.demo.cs.infrastructure.mcp.McpBridgeClient;
import com.demo.cs.infrastructure.persistence.CsAgentRouteLogRepository;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ConfigurableAgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableAgentInvoker.class);
    private static final Pattern ORDER_PATTERN = Pattern.compile("ORD\\d{5,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING_PATTERN = Pattern.compile("[A-Z]{2}\\d{10,}|SF\\d{10,}", Pattern.CASE_INSENSITIVE);

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptTemplateRenderer promptRenderer;
    private final ChildrenCatalogRenderer childrenCatalogRenderer;
    private final ToolBindingFactory toolBindingFactory;
    private final LocalToolCatalog toolCatalog;
    private final DefinitionRegistry registry;
    private final KnowledgeService knowledgeService;
    private final McpBridgeClient mcpClient;
    private final OrderTicketTools orderTicketTools;
    private final CsAgentRouteLogRepository routeLogRepo;
    private final AppProperties props;

    public ConfigurableAgentInvoker(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            PromptTemplateRenderer promptRenderer,
            ChildrenCatalogRenderer childrenCatalogRenderer,
            ToolBindingFactory toolBindingFactory,
            LocalToolCatalog toolCatalog,
            DefinitionRegistry registry,
            KnowledgeService knowledgeService,
            McpBridgeClient mcpClient,
            OrderTicketTools orderTicketTools,
            CsAgentRouteLogRepository routeLogRepo,
            AppProperties props
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptRenderer = promptRenderer;
        this.childrenCatalogRenderer = childrenCatalogRenderer;
        this.toolBindingFactory = toolBindingFactory;
        this.toolCatalog = toolCatalog;
        this.registry = registry;
        this.knowledgeService = knowledgeService;
        this.mcpClient = mcpClient;
        this.orderTicketTools = orderTicketTools;
        this.routeLogRepo = routeLogRepo;
        this.props = props;
    }

    public RouteDecision route(
            AgentDefinition def,
            CsSession session,
            String userId,
            String text,
            boolean hasAttachments,
            int attachmentCount,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> confirmationPayload
    ) {
        RouteDecision decision = tryLlmRoute(def, session, userId, text, hasAttachments,
                attachmentCount, recentMessages, confirmationPayload);
        if (decision == null) {
            decision = heuristicRoute(text, hasAttachments, childrenCatalogRenderer.enabledCodes(def.children()));
        }
        decision = enrichSlots(decision, text);
        decision = normalizeTarget(decision, def);
        logRoute(session.getId(), decision);
        return decision;
    }

    public boolean needsClarify(AgentDefinition def, RouteDecision decision) {
        double threshold = props.routeConfidenceThreshold();
        if (def != null && def.routing() != null && def.routing().confidenceThreshold() != null) {
            threshold = def.routing().confidenceThreshold();
        }
        return decision.needClarify()
                || decision.confidence() < threshold
                || "unclear".equals(decision.intent())
                || decision.targetAgent() == null
                || "none".equals(decision.targetAgent());
    }

    public SubAgentResult handle(PublishedAgent published, SubAgentRequest request) {
        return handle(published.definition(), published.version(), request);
    }

    public SubAgentResult handle(AgentDefinition def, Integer version, SubAgentRequest request) {
        if (def == null) {
            return SubAgentResult.simple("unknown", "Agent 定义缺失");
        }
        String agentCode = def.code() != null ? def.code() : "worker";

        SubAgentResult confirmShortCircuit = maybeConfirmShortCircuit(def, agentCode, request);
        if (confirmShortCircuit != null) {
            return confirmShortCircuit;
        }

        String retrievedBlocks = "";
        String mcpBlocks = "";
        List<Citation> citations = new ArrayList<>();
        if (def.capabilities() != null && def.capabilities().ragEnabled()) {
            try {
                List<Document> docs = knowledgeService.retrieveDocuments(request.text(), props.rag().topK());
                citations.addAll(knowledgeService.toCitations(docs));
                retrievedBlocks = formatRetrieved(docs);
            } catch (Exception e) {
                log.debug("RAG retrieve skipped: {}", e.getMessage());
                retrievedBlocks = "（无检索命中）\n";
            }
            if (request.enableMcp() && (def.mcp() == null || def.mcp().enabled()) && mcpClient.isEnabled()) {
                mcpBlocks = loadMcpBlocks(request.text(), citations);
            }
        }

        Map<String, String> vars = buildVars(def, request, retrievedBlocks, mcpBlocks, null);
        String system = promptRenderer.render(
                def.prompts() != null ? def.prompts().systemPrompt() : "", vars);
        String userPrompt = promptRenderer.render(
                def.prompts() != null ? def.prompts().userPromptTemplate() : "{{text}}", vars);

        boolean enableVision = def.modelConfig() != null && def.modelConfig().visionEnabled();
        ToolCallback[] tools = toolBindingFactory.resolve(def.tools());

        orderTicketTools.setSessionId(request.sessionId());
        try {
            var promptSpec = chatClientBuilder.build().prompt().system(system);
            if (enableVision && request.attachments() != null && !request.attachments().isEmpty()) {
                promptSpec = promptSpec.user(u -> {
                    u.text(userPrompt);
                    for (AttachmentView att : request.attachments()) {
                        u.media(new Media(
                                org.springframework.util.MimeTypeUtils.parseMimeType(att.contentType()),
                                new FileSystemResource(att.path())
                        ));
                    }
                });
            } else {
                promptSpec = promptSpec.user(userPrompt);
            }
            if (tools.length > 0) {
                promptSpec = promptSpec.tools(tools);
            }
            String raw = promptSpec.call().content();
            if (enableVision) {
                return parseVisionResult(agentCode, raw);
            }
            return new SubAgentResult(agentCode, raw, citations, List.of(), false, null, null, null, null);
        } catch (Exception e) {
            log.warn("ConfigurableAgentInvoker handle failed for {}: {}", agentCode, e.getMessage());
            return SubAgentResult.simple(agentCode,
                    "服务暂时不可用（" + e.getClass().getSimpleName() + "）。请检查 LLM_API_KEY / base-url 后重试。");
        } finally {
            orderTicketTools.clearSessionId();
        }
    }

    public Map<String, String> renderPromptsPreview(AgentDefinition def, SubAgentRequest request) {
        Map<String, String> vars = buildVars(def, request, "", "", null);
        String system = promptRenderer.render(
                def.prompts() != null ? def.prompts().systemPrompt() : "", vars);
        String user = promptRenderer.render(
                def.prompts() != null ? def.prompts().userPromptTemplate() : "{{text}}", vars);
        return Map.of(
                "system", truncate(system, 4000),
                "user", truncate(user, 4000)
        );
    }

    private SubAgentResult maybeConfirmShortCircuit(AgentDefinition def, String agentCode, SubAgentRequest request) {
        List<String> requireConfirm = def.policies() != null ? def.policies().requireConfirmFor() : List.of();
        if (requireConfirm == null || requireConfirm.isEmpty()) {
            requireConfirm = def.tools() == null ? List.of() : def.tools().stream()
                    .filter(toolCatalog::isWrite)
                    .toList();
        }
        if (!requireConfirm.contains("cancel_order")) {
            return null;
        }
        String text = request.text() != null ? request.text() : "";
        if (!text.contains("取消")) {
            return null;
        }
        String orderId = resolveOrderId(request);
        if (orderId == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "cancel_order");
        payload.put("orderId", orderId);
        payload.put("userId", request.userId());
        payload.put("reason", "用户申请取消");
        payload.put("agentName", agentCode);
        String answer = "您申请取消订单 " + orderId + "。此操作不可撤销，请确认是否继续？回复「确认」即可执行取消。";
        return SubAgentResult.confirm(agentCode, answer, payload);
    }

    private RouteDecision tryLlmRoute(
            AgentDefinition def,
            CsSession session,
            String userId,
            String text,
            boolean hasAttachments,
            int attachmentCount,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> confirmationPayload
    ) {
        try {
            List<AgentDefinition.ChildRef> available = filterAvailableChildren(def.children());
            String childrenCatalog = childrenCatalogRenderer.render(available);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("childrenCatalog", childrenCatalog);
            vars.put("userId", userId != null ? userId : "");
            vars.put("summary", session.getSummary() != null ? session.getSummary() : "无");
            vars.put("recentMessages", formatMessages(recentMessages));
            vars.put("agentDescription", def.description() != null ? def.description() : "");
            vars.put("text", text != null ? text : "");
            vars.put("slots", "{}");
            vars.put("locale", "zh-CN");
            vars.put("visionSummary", "");
            vars.put("retrievedBlocks", "");
            vars.put("mcpBlocks", "");

            String system = promptRenderer.render(
                    def.prompts() != null ? def.prompts().systemPrompt() : "", vars);
            if (!system.contains(childrenCatalog) && !childrenCatalog.isBlank()) {
                system = system + "\n\n" + childrenCatalog;
            }

            String userPrompt = """
                    【会话状态】%s
                    【待确认摘要】%s
                    【会话摘要】%s
                    【最近对话】
                    %s
                    
                    【本轮用户】
                    userId=%s
                    hasAttachments=%s attachmentCount=%s
                    text:
                    %s
                    
                    请输出 RouteDecision JSON。
                    """.formatted(
                    session.getStatus(),
                    confirmationPayload != null ? confirmationPayload : "无",
                    session.getSummary() != null ? session.getSummary() : "无",
                    formatMessages(recentMessages),
                    userId,
                    hasAttachments,
                    attachmentCount,
                    text
            );

            String raw = chatClientBuilder.build()
                    .prompt()
                    .system(system)
                    .user(userPrompt)
                    .call()
                    .content();
            return parseRouteDecision(raw);
        } catch (Exception e) {
            log.warn("Configurable supervisor route failed: {}", e.getMessage());
            return null;
        }
    }

    private List<AgentDefinition.ChildRef> filterAvailableChildren(List<AgentDefinition.ChildRef> children) {
        if (children == null) return List.of();
        List<AgentDefinition.ChildRef> out = new ArrayList<>();
        for (AgentDefinition.ChildRef c : children) {
            if (c == null || !c.childEnabled()) continue;
            if (registry.get(c.agentCode()).isEmpty()) continue;
            out.add(c);
        }
        return out;
    }

    private RouteDecision normalizeTarget(RouteDecision decision, AgentDefinition def) {
        Set<String> allowed = childrenCatalogRenderer.enabledCodes(filterAvailableChildren(def.children()))
                .stream().collect(Collectors.toSet());
        allowed = new java.util.HashSet<>(allowed);
        allowed.add("none");
        String target = decision.targetAgent();
        if (target != null && !allowed.contains(target)) {
            return RouteDecision.unclear("该专责客服暂不可用，请换个方式描述您的需求。");
        }
        return decision;
    }

    private RouteDecision heuristicRoute(String text, boolean hasAttachments, List<String> childCodes) {
        Set<String> available = childCodes != null ? Set.copyOf(childCodes) : Set.of();
        String t = text != null ? text : "";
        if (hasAttachments && available.contains("vision")) {
            return new RouteDecision("multimodal", 0.75, "vision", "heuristic: has attachments",
                    new Slots(null, null, null, null), false, null);
        }
        if (containsAny(t, "退货", "退款", "政策", "运费", "包邮", "FAQ", "faq") && available.contains("knowledge")) {
            return new RouteDecision("knowledge", 0.7, "knowledge", "heuristic: policy keywords",
                    new Slots(null, null, null, null), false, null);
        }
        if (containsAny(t, "订单", "取消", "物流", "运单", "ORD", "发货") && available.contains("order")) {
            return new RouteDecision("order", 0.7, "order", "heuristic: order keywords",
                    new Slots(null, null, null, null), false, null);
        }
        if (containsAny(t, "工单", "破损", "少件", "投诉", "人工") && available.contains("ticket")) {
            return new RouteDecision("ticket", 0.7, "ticket", "heuristic: ticket keywords",
                    new Slots(null, null, null, null), false, null);
        }
        if (t.trim().length() < 4) {
            return RouteDecision.unclear("您好，请问您想咨询订单、售后政策，还是需要其他帮助？");
        }
        if (available.contains("chitchat")) {
            return new RouteDecision("chitchat", 0.6, "chitchat", "heuristic: default chitchat",
                    new Slots(null, null, null, null), false, null);
        }
        return RouteDecision.unclear("请问需要什么帮助？");
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

    private SubAgentResult parseVisionResult(String agentCode, String raw) {
        String answer = raw;
        String suggestedIntent = null;
        Slots suggestedSlots = null;
        String visionSummary = null;
        try {
            int jsonStart = raw.lastIndexOf('{');
            int jsonEnd = raw.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                answer = raw.substring(0, jsonStart).trim();
                JsonNode node = objectMapper.readTree(raw.substring(jsonStart, jsonEnd + 1));
                suggestedIntent = textOrNull(node.path("suggestedIntent"));
                visionSummary = textOrNull(node.path("scene"));
                JsonNode slotsNode = node.path("suggestedSlots");
                suggestedSlots = new Slots(
                        textOrNull(slotsNode.path("orderId")),
                        textOrNull(slotsNode.path("trackingNo")),
                        textOrNull(slotsNode.path("category")),
                        null
                );
            }
        } catch (Exception ignored) {
        }
        if (answer == null || answer.isBlank()) answer = raw;
        return new SubAgentResult(agentCode, answer, List.of(), List.of(),
                false, null, suggestedIntent, suggestedSlots, visionSummary);
    }

    private Map<String, String> buildVars(
            AgentDefinition def,
            SubAgentRequest request,
            String retrievedBlocks,
            String mcpBlocks,
            String childrenCatalog
    ) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("userId", request.userId() != null ? request.userId() : "");
        boolean injectSummary = def.memory() == null || !Boolean.FALSE.equals(def.memory().injectSummary());
        vars.put("summary", injectSummary && request.summary() != null ? request.summary() : "无");
        vars.put("recentMessages", formatMessages(request.recentMessages()));
        vars.put("childrenCatalog", childrenCatalog != null ? childrenCatalog : "");
        vars.put("agentDescription", def.description() != null ? def.description() : "");
        try {
            vars.put("slots", objectMapper.writeValueAsString(
                    request.route() != null ? request.route().slots() : Map.of()));
        } catch (Exception e) {
            vars.put("slots", "{}");
        }
        vars.put("locale", "zh-CN");
        vars.put("visionSummary", request.route() != null && request.text() != null
                && request.text().contains("[图片理解]") ? request.text() : "");
        vars.put("text", request.text() != null ? request.text() : "");
        vars.put("retrievedBlocks", retrievedBlocks != null ? retrievedBlocks : "");
        vars.put("mcpBlocks", mcpBlocks != null && !mcpBlocks.isBlank() ? mcpBlocks : "（无）");
        return vars;
    }

    private String loadMcpBlocks(String text, List<Citation> citations) {
        StringBuilder mcpBlocks = new StringBuilder();
        try {
            List<Map<String, Object>> mcpHits = mcpClient.searchDocs(text, props.rag().topK());
            int idx = citations.size() + 1;
            for (Map<String, Object> hit : mcpHits) {
                String content = String.valueOf(hit.getOrDefault("snippet",
                        hit.getOrDefault("content", hit.getOrDefault("text", ""))));
                String title = String.valueOf(hit.getOrDefault("name",
                        hit.getOrDefault("title", "MCP文档")));
                double score = hit.get("score") instanceof Number n ? n.doubleValue() : 0.5;
                citations.add(new Citation(
                        String.valueOf(hit.getOrDefault("doc_id", title)),
                        title, content, score, "mcp", hit
                ));
                mcpBlocks.append('[').append(idx++).append("] title=").append(title)
                        .append(" score=").append(score).append('\n')
                        .append(content).append("\n\n");
            }
        } catch (Exception ignored) {
        }
        return mcpBlocks.toString();
    }

    private String formatRetrieved(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() != null ? d.getMetadata() : Map.of();
            sb.append('[').append(i++).append("] title=")
                    .append(meta.getOrDefault("title", "片段")).append('\n');
            sb.append(d.getText()).append("\n\n");
        }
        if (sb.isEmpty()) sb.append("（无检索命中）\n");
        return sb.toString();
    }

    private String formatMessages(List<Map<String, Object>> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : recentMessages) {
            sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        return sb.toString();
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

    private String resolveOrderId(SubAgentRequest request) {
        if (request.route() != null && request.route().slots() != null && request.route().slots().orderId() != null) {
            return request.route().slots().orderId();
        }
        Matcher m = ORDER_PATTERN.matcher(request.text() != null ? request.text() : "");
        return m.find() ? m.group().toUpperCase() : null;
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

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw.trim();
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

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
