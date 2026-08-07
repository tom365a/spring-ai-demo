package com.demo.cs.application.agentconfig;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.agent.runtime.PublishedAgent;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.domain.AgtAgent;
import com.demo.cs.domain.AgtAgentVersion;
import com.demo.cs.infrastructure.persistence.AgtAgentRepository;
import com.demo.cs.infrastructure.persistence.AgtAgentVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AgentPublishService {

    private final AgtAgentRepository agentRepo;
    private final AgtAgentVersionRepository versionRepo;
    private final AgentDefinitionService definitionService;
    private final AgentDefinitionValidator validator;
    private final DefinitionRegistry registry;

    public AgentPublishService(
            AgtAgentRepository agentRepo,
            AgtAgentVersionRepository versionRepo,
            AgentDefinitionService definitionService,
            AgentDefinitionValidator validator,
            DefinitionRegistry registry
    ) {
        this.agentRepo = agentRepo;
        this.versionRepo = versionRepo;
        this.definitionService = definitionService;
        this.validator = validator;
        this.registry = registry;
    }

    @Transactional
    public PublishResponse publish(String code, PublishRequest request, String publishedBy) {
        synchronized (lockFor(code)) {
            AgtAgent agent = definitionService.require(code);
            AgentDefinition def = definitionService.readDefinition(agent.getDraftJson());
            var result = validator.validate(def);
            if (!result.ok()) {
                throw new AgentValidationException("agent validation failed", result.errors(), result.warnings());
            }

            int nextVer = (agent.getPublishedVersion() != null ? agent.getPublishedVersion() : 0) + 1;
            Instant now = Instant.now();

            AgtAgentVersion version = new AgtAgentVersion();
            version.setAgentCode(code);
            version.setVersion(nextVer);
            version.setSnapshotJson(agent.getDraftJson());
            version.setPublishedAt(now);
            version.setPublishedBy(publishedBy != null ? publishedBy : "admin");
            version.setRemark(request != null ? request.remark() : null);
            versionRepo.save(version);

            agent.setStatus("PUBLISHED");
            agent.setPublishedVersion(nextVer);
            agent.setUpdatedAt(now);
            agent.setUpdatedBy(publishedBy != null ? publishedBy : "admin");
            if (request != null && request.remark() != null) {
                agent.setRemark(request.remark());
            }
            agentRepo.save(agent);

            if (agent.isEnabled()) {
                registry.replace(code, new PublishedAgent(code, nextVer, def));
            } else {
                registry.remove(code);
            }

            return new PublishResponse(code, nextVer, now, agent.getStatus());
        }
    }

    @Transactional
    public PublishResponse rollback(String code, RollbackRequest request, String publishedBy) {
        if (request == null) throw new IllegalArgumentException("version is required");
        synchronized (lockFor(code)) {
            AgtAgent agent = definitionService.require(code);
            AgtAgentVersion historical = versionRepo.findByAgentCodeAndVersion(code, request.version())
                    .orElseThrow(() -> new IllegalArgumentException("version not found: " + request.version()));

            AgentDefinition def = definitionService.readDefinition(historical.getSnapshotJson());
            var result = validator.validate(def);
            if (!result.ok()) {
                throw new AgentValidationException("agent validation failed", result.errors(), result.warnings());
            }

            int nextVer = (agent.getPublishedVersion() != null ? agent.getPublishedVersion() : 0) + 1;
            Instant now = Instant.now();

            AgtAgentVersion version = new AgtAgentVersion();
            version.setAgentCode(code);
            version.setVersion(nextVer);
            version.setSnapshotJson(historical.getSnapshotJson());
            version.setPublishedAt(now);
            version.setPublishedBy(publishedBy != null ? publishedBy : "admin");
            version.setRemark(request.remark() != null ? request.remark() : "rollback to v" + request.version());
            versionRepo.save(version);

            agent.setDraftJson(historical.getSnapshotJson());
            agent.setStatus("PUBLISHED");
            agent.setPublishedVersion(nextVer);
            agent.setDraftRevision(agent.getDraftRevision() + 1);
            agent.setUpdatedAt(now);
            agent.setUpdatedBy(publishedBy != null ? publishedBy : "admin");
            agent.setRemark(version.getRemark());
            agentRepo.save(agent);

            if (agent.isEnabled()) {
                registry.replace(code, new PublishedAgent(code, nextVer, def));
            } else {
                registry.remove(code);
            }

            return new PublishResponse(code, nextVer, now, agent.getStatus());
        }
    }

    public VersionListResponse listVersions(String code) {
        definitionService.require(code);
        List<VersionItem> items = new ArrayList<>();
        for (AgtAgentVersion v : versionRepo.findByAgentCodeOrderByVersionDesc(code)) {
            items.add(new VersionItem(v.getVersion(), v.getPublishedAt(), v.getPublishedBy(), v.getRemark()));
        }
        return new VersionListResponse(items);
    }

    public VersionSnapshotResponse getVersion(String code, int version) {
        definitionService.require(code);
        AgtAgentVersion v = versionRepo.findByAgentCodeAndVersion(code, version)
                .orElseThrow(() -> new IllegalArgumentException("version not found: " + version));
        return new VersionSnapshotResponse(
                v.getVersion(), v.getPublishedAt(), v.getPublishedBy(), v.getRemark(),
                definitionService.readDefinition(v.getSnapshotJson())
        );
    }

    public void reloadRegistryFromDb() {
        List<PublishedAgent> enabled = new ArrayList<>();
        for (AgtAgent agent : agentRepo.findByStatusAndEnabledTrue("PUBLISHED")) {
            if (agent.getPublishedVersion() == null) continue;
            versionRepo.findByAgentCodeAndVersion(agent.getCode(), agent.getPublishedVersion())
                    .ifPresent(v -> enabled.add(new PublishedAgent(
                            agent.getCode(),
                            v.getVersion(),
                            definitionService.readDefinition(v.getSnapshotJson())
                    )));
        }
        registry.clearAndLoad(enabled);
    }

    private Object lockFor(String code) {
        return ("agt-publish-" + code).intern();
    }
}
