package com.demo.cs.application.agentconfig;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.domain.AgtAgent;
import com.demo.cs.infrastructure.persistence.AgtAgentRepository;
import com.demo.cs.infrastructure.persistence.AgtAgentVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AgentDefinitionService {

    private final AgtAgentRepository agentRepo;
    private final AgtAgentVersionRepository versionRepo;
    private final ObjectMapper objectMapper;
    private final AgentDefinitionValidator validator;
    private final DefinitionRegistry registry;

    public AgentDefinitionService(
            AgtAgentRepository agentRepo,
            AgtAgentVersionRepository versionRepo,
            ObjectMapper objectMapper,
            AgentDefinitionValidator validator,
            DefinitionRegistry registry
    ) {
        this.agentRepo = agentRepo;
        this.versionRepo = versionRepo;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.registry = registry;
    }

    public AgentListResponse list(String type, String status, Boolean enabled, String q) {
        List<AgtAgent> all = agentRepo.findAllByOrderBySortOrderAscCodeAsc();
        List<AgentListItem> items = new ArrayList<>();
        for (AgtAgent a : all) {
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(a.getType())) continue;
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(a.getStatus())) continue;
            if (enabled != null && a.isEnabled() != enabled) continue;
            if (q != null && !q.isBlank()) {
                String qq = q.toLowerCase();
                boolean match = (a.getCode() != null && a.getCode().toLowerCase().contains(qq))
                        || (a.getName() != null && a.getName().toLowerCase().contains(qq));
                if (!match) continue;
            }
            items.add(new AgentListItem(
                    a.getCode(), a.getName(), a.getType(), a.getStatus(),
                    a.getPublishedVersion(), a.isEnabled(), a.getDescription(), a.getUpdatedAt()
            ));
        }
        return new AgentListResponse(items, items.size());
    }

    public AgentDetailResponse get(String code) {
        AgtAgent agent = require(code);
        AgentDefinition draft = readDefinition(agent.getDraftJson());
        AgentDefinition published = null;
        if (agent.getPublishedVersion() != null) {
            published = versionRepo.findByAgentCodeAndVersion(code, agent.getPublishedVersion())
                    .map(v -> readDefinition(v.getSnapshotJson()))
                    .orElse(null);
        }
        boolean dirty = published == null || !jsonEquals(agent.getDraftJson(),
                versionRepo.findByAgentCodeAndVersion(code, agent.getPublishedVersion())
                        .map(v -> v.getSnapshotJson()).orElse(null));
        return new AgentDetailResponse(
                draft, published, dirty, agent.getDraftRevision(), agent.getPublishedVersion(),
                agent.getStatus(), agent.isEnabled(), agent.getUpdatedAt(), agent.getUpdatedBy()
        );
    }

    @Transactional
    public AgentDetailResponse create(CreateAgentRequest req, String updatedBy) {
        if (req == null) throw new IllegalArgumentException("body is required");
        if ((req.name() == null || req.name().isBlank()) && (req.definition() == null || req.definition().name() == null || req.definition().name().isBlank())) throw new IllegalArgumentException("name is required");
        String generatedCode = req.code() == null || req.code().isBlank() ? "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : req.code().trim();
        if (agentRepo.existsByCode(generatedCode)) {
            throw new IllegalStateException("agent code already exists: " + req.code());
        }
        String type = req.type() != null ? req.type() : "WORKER";
        AgentDefinition def = req.definition() != null ? req.definition() : defaultDefinition(generatedCode, req.name(), type, req.description());
        def = withIdentity(def, generatedCode, req.name() != null ? req.name().trim() : def.name(), type,
                req.description() != null ? req.description() : def.description());

        AgtAgent agent = new AgtAgent();
        agent.setId("agt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        agent.setCode(def.code());
        agent.setName(def.name());
        agent.setDescription(def.description());
        agent.setType(def.type());
        agent.setStatus("DRAFT");
        agent.setEnabled(false);
        agent.setPublishedVersion(null);
        agent.setDraftRevision(0);
        agent.setSortOrder(def.ui() != null && def.ui().sortOrder() != null ? def.ui().sortOrder() : 100);
        validateDraft(def);
        agent.setDraftJson(writeDefinition(def));
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agent.setUpdatedBy(updatedBy != null ? updatedBy : "admin");
        agentRepo.save(agent);
        return get(agent.getCode());
    }

    @Transactional
    public AgentDetailResponse update(String code, UpdateAgentRequest req, String updatedBy) {
        AgtAgent agent = agentRepo.findForUpdateByCode(code).orElseThrow(() -> new IllegalArgumentException("agent not found: " + code));
        if (req == null) throw new IllegalArgumentException("body is required");
        if (req.draftRevision() != null && req.draftRevision() != agent.getDraftRevision()) {
            throw new IllegalStateException("draftRevision conflict, expected " + agent.getDraftRevision());
        }

        AgentDefinition current = readDefinition(agent.getDraftJson());
        AgentDefinition incoming = req.definition() != null ? req.definition() : current;
        String name = req.name() != null ? req.name() : (incoming.name() != null ? incoming.name() : agent.getName());
        name = name.trim();
        String description = req.description() != null ? req.description() : incoming.description();
        incoming = withIdentity(incoming, code, name, agent.getType(), description);

        if (req.enabled() != null && req.enabled() != agent.isEnabled()) throw new IllegalArgumentException("use enable/disable endpoint to change runtime state");
        if (req.sortOrder() != null) agent.setSortOrder(req.sortOrder());
        if (req.remark() != null) agent.setRemark(req.remark());

        validateDraft(incoming);
        agent.setName(name);
        agent.setDescription(description);
        agent.setDraftJson(writeDefinition(incoming));
        agent.setDraftRevision(agent.getDraftRevision() + 1);
        agent.setUpdatedAt(Instant.now());
        agent.setUpdatedBy(updatedBy != null ? updatedBy : "admin");
        agentRepo.save(agent);

        return get(code);
    }

    public ValidateResponse validate(String code) {
        AgtAgent agent = require(code);
        AgentDefinition def = readDefinition(agent.getDraftJson());
        var result = validator.validate(def);
        return new ValidateResponse(result.ok(), result.errors(), result.warnings());
    }

    public ValidateResponse validateDefinition(AgentDefinition def) {
        var result = validator.validate(def);
        return new ValidateResponse(result.ok(), result.errors(), result.warnings());
    }

    private void validateDraft(AgentDefinition def) {
        var result = validator.validateDraft(def);
        if (!result.ok()) throw new AgentValidationException("agent validation failed", result.errors(), result.warnings());
    }

    public AgtAgent require(String code) {
        return agentRepo.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + code));
    }

    public AgentDefinition readDefinition(String json) {
        try {
            return objectMapper.readValue(json, AgentDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("invalid agent definition json: " + e.getMessage());
        }
    }

    public String writeDefinition(AgentDefinition def) {
        try {
            return objectMapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new IllegalStateException("serialize agent definition failed: " + e.getMessage());
        }
    }

    private boolean jsonEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        try {
            return objectMapper.readTree(a).equals(objectMapper.readTree(b));
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    private AgentDefinition withIdentity(AgentDefinition def, String code, String name, String type, String description) {
        return new AgentDefinition(
                code, name, description, type,
                def.modelConfig(), def.prompts(), def.outputSchema(), def.tools(), def.skills(),
                def.mcp(), def.capabilities(), def.children(), def.routing(),
                def.policies(), def.memory(), def.ui(), def.skillVersions()
        );
    }

    private AgentDefinition defaultDefinition(String code, String name, String type, String description) {
        return new AgentDefinition(
                code,
                name != null ? name : code,
                description,
                type,
                new AgentDefinition.ModelConfig(null, 0.2, 2048, false),
                new AgentDefinition.Prompts("You are a helpful agent.", "{{text}}", "TEXT"),
                null,
                List.of(),
                List.of(),
                new AgentDefinition.McpConfig(false, List.of(), List.of()),
                new AgentDefinition.Capabilities(false),
                List.of(),
                null,
                new AgentDefinition.Policies(false, List.of(), 3, "SUPERVISOR".equals(type) ? 1 : 0),
                new AgentDefinition.Memory(true, null, true),
                new AgentDefinition.UiConfig(code, List.of(), 100)
        );
    }
}
