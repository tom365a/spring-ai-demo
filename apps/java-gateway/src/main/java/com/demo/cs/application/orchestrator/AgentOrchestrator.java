package com.demo.cs.application.orchestrator;

import com.demo.cs.agent.SubAgent;
import com.demo.cs.agent.model.AgentModels.AttachmentView;
import com.demo.cs.agent.model.AgentModels.Citation;
import com.demo.cs.agent.model.AgentModels.RouteDecision;
import com.demo.cs.agent.model.AgentModels.Slots;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.agent.model.AgentModels.ToolCallRecord;
import com.demo.cs.agent.model.AgentModels.TurnOutcome;
import com.demo.cs.agent.runtime.ConfigurableAgentInvoker;
import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.agent.runtime.PublishedAgent;
import com.demo.cs.agent.supervisor.SupervisorAgent;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.api.dto.ApiDtos.ChatResponse;
import com.demo.cs.application.attachment.AttachmentService;
import com.demo.cs.application.session.SessionService;
import com.demo.cs.config.AppProperties;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.demo.cs.domain.CsAttachment;
import com.demo.cs.domain.CsSession;
import com.demo.cs.infrastructure.tools.OrderTicketTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final SessionService sessionService;
    private final AttachmentService attachmentService;
    private final SupervisorAgent supervisorAgent;
    private final OrderTicketTools orderTicketTools;
    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final Map<String, SubAgent> agents;
    private final AppProperties props;
    private final DefinitionRegistry definitionRegistry;
    private final ConfigurableAgentInvoker configurableInvoker;
    private final com.demo.cs.application.resources.ManagedAgentRuntime managedRuntime;
    private final com.demo.cs.application.support.HumanSupportService humanSupport;

    public AgentOrchestrator(
            SessionService sessionService,
            AttachmentService attachmentService,
            SupervisorAgent supervisorAgent,
            OrderTicketTools orderTicketTools,
            ChatClient.Builder chatClientBuilder,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            List<SubAgent> subAgents,
            AppProperties props,
            DefinitionRegistry definitionRegistry,
            ConfigurableAgentInvoker configurableInvoker,
            com.demo.cs.application.resources.ManagedAgentRuntime managedRuntime,
            com.demo.cs.application.support.HumanSupportService humanSupport
    ) {
        this.sessionService = sessionService;
        this.attachmentService = attachmentService;
        this.supervisorAgent = supervisorAgent;
        this.orderTicketTools = orderTicketTools;
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.props = props;
        this.definitionRegistry = definitionRegistry;
        this.configurableInvoker = configurableInvoker;
        this.managedRuntime = managedRuntime;
        this.humanSupport = humanSupport;
        this.agents = new LinkedHashMap<>();
        for (SubAgent agent : subAgents) {
            this.agents.put(agent.name(), agent);
        }
    }

    public ChatResponse chat(ChatRequest request) {
        TurnOutcome outcome = executeTurn(request, null);
        return toChatResponse(outcome);
    }

    public SseEmitter chatStream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        new Thread(() -> {
            try {
                executeTurn(request, event -> {
                    try {
                        sendEvent(emitter, event);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                sendEvent(emitter, Map.of("event", "done", "ok", true));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE chat failed", e);
                try {
                    sendEvent(emitter, Map.of("event", "error", "code", 50001,
                            "message", e.getMessage() != null ? e.getMessage() : "error"));
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        }).start();
        return emitter;
    }

    private TurnOutcome executeTurn(ChatRequest request, Consumer<Map<String, Object>> eventSink) {
        if(humanSupport.assigned(request.sessionId())) {
            TurnOutcome outcome=humanSupport.chat(request);emitFinal(eventSink,outcome);return outcome;
        }
        if (props.agentConfig().enabled()) {
            TurnOutcome outcome;
            try {outcome=managedRuntime.chat(request,eventSink);}
            catch(SecurityException e){throw e;}
            catch(RuntimeException e){outcome=humanSupport.unavailable(request);}
            emitFinal(eventSink,outcome);
            return outcome;
        }
        String supervisorCode = request.supervisorCode() == null || request.supervisorCode().isBlank() ? "supervisor" : request.supervisorCode();
        boolean configEnabled = props.agentConfig().enabled();
        Optional<PublishedAgent> publishedSupervisor = configEnabled ? definitionRegistry.get(supervisorCode) : Optional.empty();
        if (configEnabled && (publishedSupervisor.isEmpty() || !"SUPERVISOR".equals(publishedSupervisor.get().definition().type()))) throw new IllegalArgumentException("主Agent不存在或未启用: " + supervisorCode);
        if (!configEnabled && request.supervisorCode() != null && !request.supervisorCode().isBlank()) throw new IllegalStateException("配置运行模式已关闭");
        String userId = request.userId() != null ? request.userId() : "u_001";
        CsSession session = resolveSession(request, userId);
        if ("closed".equals(session.getStatus())) {
            throw new IllegalStateException("session is closed");
        }
        if (!userId.equals(session.getUserId())) {
            throw new SecurityException("userId mismatch");
        }

        String text = normalizeText(request);
        List<String> attachmentIds = request.attachmentIds() != null ? request.attachmentIds() : List.of();
        List<AttachmentView> attachments = loadAttachments(attachmentIds);

        sessionService.appendMessage(session.getId(), "user", text, null, null, null, attachmentIds);

        boolean enableMcp = request.options() == null || request.options().mcpEnabled();
        Map<String, Object> storedPayload = sessionService.getConfirmationPayload(session);
        Map<String, Object> confirmPayload = storedPayload;

        // Confirm resume short-circuit
        if ("pending_confirm".equals(session.getStatus())) {
            if (Boolean.TRUE.equals(request.confirm()) || isAffirmative(text)) {
                TurnOutcome outcome = executeConfirm(session, confirmPayload, eventSink);
                persistAssistant(session.getId(), outcome);
                refreshSummary(session.getId());
                return outcome;
            }
            if (isNegative(text)) {
                sessionService.clearConfirm(session.getId());
                TurnOutcome outcome = new TurnOutcome(
                        session.getId(),
                        "好的，已取消该操作，如需其他帮助请告诉我。",
                        "confirm_resume",
                        "confirm_executor",
                        null,
                        "用户拒绝确认",
                        List.of(),
                        List.of(),
                        false,
                        null
                );
                persistAssistant(session.getId(), outcome);
                return outcome;
            }
        }

        List<Map<String, Object>> recent = sessionService.recentMessages(session.getId());
        boolean hasAttachments = !attachments.isEmpty();


        RouteDecision route;
        boolean needsClarify;
        if (publishedSupervisor.isPresent()) {
            route = configurableInvoker.route(
                    publishedSupervisor.get().definition(),
                    session, userId, text, hasAttachments, attachments.size(),
                    recent, storedPayload
            );
            needsClarify = configurableInvoker.needsClarify(publishedSupervisor.get().definition(), route);
        } else {
            route = supervisorAgent.route(
                    session, userId, text, hasAttachments, attachments.size(),
                    recent, storedPayload
            );
            needsClarify = supervisorAgent.needsClarify(route);
        }
        emit(eventSink, "intent", Map.of(
                "intent", route.intent() != null ? route.intent() : "",
                "targetAgent", route.targetAgent() != null ? route.targetAgent() : "",
                "confidence", route.confidence(),
                "reason", route.reason() != null ? route.reason() : "",
                "needClarify", route.needClarify()
        ));

        if (needsClarify) {
            String clarify = route.clarifyQuestion() != null && !route.clarifyQuestion().isBlank()
                    ? route.clarifyQuestion()
                    : "请问您想咨询订单、售后政策，还是需要其他帮助？";
            TurnOutcome outcome = new TurnOutcome(
                    session.getId(), clarify, route.intent(), supervisorCode,
                    route.confidence(), route.reason(), List.of(), List.of(), false, null
            );
            sessionService.updateRouting(session.getId(), route.intent(), supervisorCode);
            persistAssistant(session.getId(), outcome);
            emitFinal(eventSink, outcome);
            refreshSummary(session.getId());
            return outcome;
        }

        // Safety: attachments → vision unless explicitly unrelated
        String targetAgent = route.targetAgent();
        if (!configEnabled && hasAttachments && !"multimodal".equals(route.intent()) && !"vision".equals(targetAgent)) {
            targetAgent = "vision";
            route = new RouteDecision("multimodal", route.confidence(), "vision",
                    "orchestrator: attachments present", route.slots(), false, null);
        }

        SubAgentResult result = dispatch(session, userId, text, recent, attachments, route, enableMcp, eventSink);

        // Vision secondary route (once)
        if (!configEnabled && "vision".equals(result.agentName()) && result.suggestedIntent() != null
                && List.of("order", "ticket", "knowledge").contains(result.suggestedIntent())) {
            Slots merged = mergeSlots(route.slots(), result.suggestedSlots());
            String enrichedText = text;
            if (result.visionSummary() != null && !result.visionSummary().isBlank()) {
                enrichedText = text + "\n[图片理解]" + result.visionSummary();
            }
            RouteDecision secondary = new RouteDecision(
                    result.suggestedIntent(), 0.85, result.suggestedIntent(),
                    "vision secondary route", merged, false, null
            );
            emit(eventSink, "agent", Map.of("agentName", secondary.targetAgent()));
            SubAgentResult second = dispatch(session, userId, enrichedText, recent, List.of(),
                    secondary, enableMcp, eventSink);
            if (result.answer() != null && !result.answer().isBlank()) {
                second = new SubAgentResult(
                        second.agentName(),
                        result.answer() + "\n" + second.answer(),
                        second.citations(),
                        second.toolCalls(),
                        second.confirmRequired(),
                        second.confirmationPayload(),
                        null, null, result.visionSummary()
                );
            }
            result = second;
            route = secondary;
        }

        TurnOutcome outcome = TurnOutcome.fromRoute(session.getId(), route, result);
        finalizeSession(session.getId(), route, result);
        persistAssistant(session.getId(), outcome);
        emit(eventSink, "token", Map.of("text", outcome.answer()));
        emitFinal(eventSink, outcome);
        refreshSummary(session.getId());
        return outcome;
    }

    private SubAgentResult dispatch(
            CsSession session,
            String userId,
            String text,
            List<Map<String, Object>> recent,
            List<AttachmentView> attachments,
            RouteDecision route,
            boolean enableMcp,
            Consumer<Map<String, Object>> eventSink
    ) {
        String agentName = route.targetAgent();
        if (agentName == null || "none".equals(agentName)) {
            return SubAgentResult.simple("supervisor",
                    route.clarifyQuestion() != null ? route.clarifyQuestion() : "请问需要什么帮助？");
        }
        emit(eventSink, "agent", Map.of("agentName", agentName));
        SubAgentRequest req = new SubAgentRequest(
                session.getId(), userId, text, session.getSummary(),
                recent, attachments, route, enableMcp
        );

        SubAgentResult result;
        boolean configEnabled = props.agentConfig().enabled();
        Optional<PublishedAgent> published = configEnabled ? definitionRegistry.get(agentName) : Optional.empty();
        try {
            if (published.isPresent()) {
                result = configurableInvoker.handle(published.get(), req);
            } else if (!configEnabled && agents.containsKey(agentName)) {
                result = agents.get(agentName).handle(req);
            } else {
                return SubAgentResult.simple("supervisor", "该专责客服暂不可用，请换个方式描述您的需求。");
            }
        } catch (Exception e) {
            result = SubAgentResult.simple(agentName,
                    "服务暂时不可用（" + e.getClass().getSimpleName() + "）。请检查 LLM_API_KEY / base-url 后重试。");
        }
        if (result.confirmRequired()) {
            emit(eventSink, "confirm_required", Map.of(
                    "answer", result.answer(),
                    "confirmationPayload", result.confirmationPayload() != null ? result.confirmationPayload() : Map.of()
            ));
        }
        return result;
    }

    private TurnOutcome executeConfirm(
            CsSession session,
            Map<String, Object> payload,
            Consumer<Map<String, Object>> eventSink
    ) {
        if (payload == null) {
            throw new IllegalStateException("no confirmation payload");
        }
        if (props.agentConfig().enabled()) {
            String code=String.valueOf(payload.getOrDefault("agentName",""));
            var source=definitionRegistry.get(code).orElseThrow(() -> new IllegalStateException("待确认操作的Agent已停用"));
            String requestedAction=String.valueOf(payload.getOrDefault("action",""));
            if (!configurableInvoker.allowedTools(source.definition()).contains(requestedAction)) throw new IllegalStateException("Agent已不允许执行该工具");
        }
        emit(eventSink, "agent", Map.of("agentName", "confirm_executor"));
        String action = String.valueOf(payload.getOrDefault("action", ""));
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        String answer;

        orderTicketTools.setSessionId(session.getId());
        try {
            if ("cancel_order".equals(action)) {
                String orderId = String.valueOf(payload.get("orderId"));
                String userId = String.valueOf(payload.get("userId"));
                String reason = String.valueOf(payload.getOrDefault("reason", "用户确认取消"));
                emit(eventSink, "tool_start", Map.of("name", "cancel_order",
                        "arguments", Map.of("orderId", orderId, "userId", userId, "reason", reason)));
                long start = System.currentTimeMillis();
                String result = orderTicketTools.cancelOrder(orderId, userId, reason);
                long latency = System.currentTimeMillis() - start;
                boolean success = result.contains("\"ok\":true");
                toolCalls.add(new ToolCallRecord("cancel_order",
                        Map.of("orderId", orderId, "userId", userId, "reason", reason),
                        result, "local", latency, success));
                emit(eventSink, "tool_end", Map.of(
                        "name", "cancel_order", "result", result, "success", success,
                        "source", "local", "latencyMs", latency
                ));
                answer = success
                        ? "订单 " + orderId + " 已成功取消，退款将原路返回。"
                        : "取消失败：" + result;
            } else {
                answer = "暂不支持该确认操作：" + action;
            }
        } finally {
            orderTicketTools.clearSessionId();
        }

        sessionService.clearConfirm(session.getId());
        TurnOutcome outcome = TurnOutcome.confirmResume(session.getId(), answer, toolCalls);
        emit(eventSink, "token", Map.of("text", answer));
        emitFinal(eventSink, outcome);
        return outcome;
    }

    private void finalizeSession(String sessionId, RouteDecision route, SubAgentResult result) {
        sessionService.updateRouting(sessionId, route.intent(), result.agentName());
        if (result.confirmRequired() && result.confirmationPayload() != null) {
            sessionService.setConfirmState(sessionId, result.confirmationPayload());
        }
    }

    private void persistAssistant(String sessionId, TurnOutcome outcome) {
        sessionService.appendMessage(
                sessionId,
                "assistant",
                outcome.answer(),
                outcome.agentName(),
                outcome.citations(),
                outcome.toolCalls(),
                null
        );
    }

    private void refreshSummary(String sessionId) {
        try {
            var messages = sessionService.allMessages(sessionId);
            if (messages.isEmpty()) return;
            StringBuilder transcript = new StringBuilder();
            for (var m : messages) {
                transcript.append(m.getRole()).append(": ").append(m.getContent()).append('\n');
            }
            String template = promptLoader.load("summarize_session.md");
            String prompt = template.replace("{transcript}", transcript.toString());
            String summary = chatClientBuilder.build().prompt().user(prompt).call().content();
            sessionService.updateSummary(sessionId, summary);
        } catch (Exception e) {
            log.debug("summary update skipped: {}", e.getMessage());
        }
    }

    private CsSession resolveSession(ChatRequest request, String userId) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            CsSession session = sessionService.getSession(request.sessionId());
            if (session == null) {
                throw new IllegalArgumentException("session not found");
            }
            return session;
        }
        return sessionService.createSession(userId, "web");
    }

    private String normalizeText(ChatRequest request) {
        String text = request.text();
        if (text == null || text.isBlank()) {
            if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
                return "请结合图片处理";
            }
            throw new IllegalArgumentException("text is required");
        }
        if (text.length() > 2000) {
            throw new IllegalArgumentException("text too long");
        }
        return text.trim();
    }

    private List<AttachmentView> loadAttachments(List<String> attachmentIds) {
        List<AttachmentView> views = new ArrayList<>();
        for (String id : attachmentIds) {
            CsAttachment att = attachmentService.get(id);
            if (att == null) {
                throw new IllegalArgumentException("attachment not found: " + id);
            }
            views.add(new AttachmentView(att.getId(), att.getContentType(), att.getFilePath(), att.getPublicUrl()));
        }
        return views;
    }

    private Slots mergeSlots(Slots base, Slots extra) {
        if (base == null && extra == null) return new Slots(null, null, null, null);
        if (base == null) return extra;
        if (extra == null) return base;
        return new Slots(
                extra.orderId() != null ? extra.orderId() : base.orderId(),
                extra.trackingNo() != null ? extra.trackingNo() : base.trackingNo(),
                extra.category() != null ? extra.category() : base.category(),
                base.amountHint()
        );
    }

    private boolean isAffirmative(String text) {
        if (text == null) return false;
        return text.contains("确认") || text.contains("是的") || text.contains("同意")
                || text.equals("好") || text.contains("好的");
    }

    private boolean isNegative(String text) {
        if (text == null) return false;
        return text.contains("不要了") || text.contains("算了") || text.contains("拒绝")
                || (text.contains("取消") && !text.contains("取消订单") && !text.contains("ORD"));
    }

    private ChatResponse toChatResponse(TurnOutcome outcome) {
        return new ChatResponse(
                outcome.sessionId(),
                outcome.answer(),
                outcome.intent(),
                outcome.agentName(),
                outcome.confidence(),
                outcome.reason(),
                outcome.citations(),
                outcome.toolCalls(),
                outcome.confirmRequired(),
                outcome.confirmationPayload(),
                TurnOutcome.MODE, outcome.diagnostics()
        );
    }

    private void emit(Consumer<Map<String, Object>> sink, String event, Map<String, Object> data) {
        if (sink == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("event", event);
        sink.accept(payload);
    }

    private void emitFinal(Consumer<Map<String, Object>> sink, TurnOutcome outcome) {
        if (sink == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(outcome.toChatResponseMap());
        payload.put("event", "final");
        sink.accept(payload);
    }

    private void sendEvent(SseEmitter emitter, Map<String, Object> data) throws IOException {
        String event = String.valueOf(data.getOrDefault("event", "message"));
        emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
    }
}
