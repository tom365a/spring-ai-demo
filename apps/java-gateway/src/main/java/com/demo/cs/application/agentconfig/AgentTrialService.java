package com.demo.cs.application.agentconfig;

import com.demo.cs.agent.model.AgentModels.RouteDecision;
import com.demo.cs.agent.model.AgentModels.Slots;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.agent.runtime.ConfigurableAgentInvoker;
import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.agent.runtime.PublishedAgent;
import com.demo.cs.api.dto.AdminDtos.TrialRequest;
import com.demo.cs.api.dto.AdminDtos.TrialResponse;
import com.demo.cs.api.dto.ApiDtos.ChatRequest;
import com.demo.cs.api.dto.ApiDtos.ChatResponse;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.application.orchestrator.AgentOrchestrator;
import com.demo.cs.domain.AgtAgent;
import com.demo.cs.domain.CsSession;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentTrialService {

    private final AgentDefinitionService definitionService;
    private final DefinitionRegistry registry;
    private final ConfigurableAgentInvoker invoker;
    private final AgentOrchestrator orchestrator;

    public AgentTrialService(
            AgentDefinitionService definitionService,
            DefinitionRegistry registry,
            ConfigurableAgentInvoker invoker,
            @Lazy AgentOrchestrator orchestrator
    ) {
        this.definitionService = definitionService;
        this.registry = registry;
        this.invoker = invoker;
        this.orchestrator = orchestrator;
    }

    public TrialResponse trial(String code, TrialRequest request) {
        long start = System.currentTimeMillis();
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        boolean useDraft = request.useDraft() == null || request.useDraft();
        String mode = request.mode() != null ? request.mode() : "single";
        String userId = request.userId() != null && !request.userId().isBlank()
                ? (request.userId().startsWith("trial_") ? request.userId() : "trial_" + request.userId())
                : "trial_u_" + UUID.randomUUID().toString().substring(0, 6);

        if ("full_route".equalsIgnoreCase(mode)) {
            return fullRoute(code, request, userId, start);
        }
        return single(code, request, userId, useDraft, start);
    }

    private TrialResponse single(String code, TrialRequest request, String userId, boolean useDraft, long start) {
        AgtAgent agent = definitionService.require(code);
        AgentDefinition def;
        Integer version = null;
        if (useDraft) {
            def = definitionService.readDefinition(agent.getDraftJson());
        } else {
            PublishedAgent published = registry.get(code)
                    .orElseThrow(() -> new IllegalStateException("agent not published/enabled: " + code));
            def = published.definition();
            version = published.version();
        }

        CsSession fakeSession = new CsSession();
        fakeSession.setId("trial_" + UUID.randomUUID().toString().substring(0, 8));
        fakeSession.setUserId(userId);
        fakeSession.setStatus("active");

        List<Map<String, Object>> routeTrace = new ArrayList<>();
        Map<String, String> promptsRendered;

        if ("SUPERVISOR".equals(def.type())) {
            RouteDecision decision = invoker.route(
                    def, fakeSession, userId, request.text(),
                    request.attachmentIds() != null && !request.attachmentIds().isEmpty(),
                    request.attachmentIds() != null ? request.attachmentIds().size() : 0,
                    List.of(), null
            );
            routeTrace.add(Map.of(
                    "from", "supervisor",
                    "to", decision.targetAgent() != null ? decision.targetAgent() : "none",
                    "confidence", decision.confidence(),
                    "intent", decision.intent() != null ? decision.intent() : ""
            ));
            promptsRendered = invoker.renderPromptsPreview(def, new SubAgentRequest(
                    fakeSession.getId(), userId, request.text(), null, List.of(), List.of(),
                    decision, false
            ));
            String answer = "RouteDecision: targetAgent=" + decision.targetAgent()
                    + ", intent=" + decision.intent()
                    + ", confidence=" + decision.confidence()
                    + ", reason=" + decision.reason();
            return new TrialResponse(answer, code, version, routeTrace, List.of(), promptsRendered,
                    System.currentTimeMillis() - start);
        }

        RouteDecision fakeRoute = new RouteDecision(
                code, 1.0, code, "trial single", new Slots(null, null, null, null), false, null);
        SubAgentRequest subReq = new SubAgentRequest(
                fakeSession.getId(), userId, request.text(), null, List.of(), List.of(), fakeRoute, false
        );
        promptsRendered = invoker.renderPromptsPreview(def, subReq);
        SubAgentResult result = invoker.handle(def, version, subReq);
        routeTrace.add(Map.of("from", "trial", "to", code, "confidence", 1.0));
        return new TrialResponse(
                result.answer(),
                code,
                version,
                routeTrace,
                result.toolCalls(),
                promptsRendered,
                System.currentTimeMillis() - start
        );
    }

    private TrialResponse fullRoute(String code, TrialRequest request, String userId, long start) {
        ChatRequest chatReq = new ChatRequest(
                null,
                userId,
                request.text(),
                request.attachmentIds(),
                null,
                null,
                "zh-CN",
                null
        );
        ChatResponse resp = orchestrator.chat(chatReq);
        List<Map<String, Object>> routeTrace = new ArrayList<>();
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("from", "supervisor");
        hop.put("to", resp.agentName() != null ? resp.agentName() : "none");
        hop.put("confidence", resp.confidence() != null ? resp.confidence() : 0.0);
        hop.put("intent", resp.intent() != null ? resp.intent() : "");
        routeTrace.add(hop);
        return new TrialResponse(
                resp.answer(),
                resp.agentName() != null ? resp.agentName() : code,
                null,
                routeTrace,
                resp.toolCalls() != null ? resp.toolCalls() : List.of(),
                Map.of("system", "(full_route via orchestrator)"),
                System.currentTimeMillis() - start
        );
    }
}
