package com.demo.cs.application.agentconfig;

import com.demo.cs.agent.runtime.*;
import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.domain.*;
import com.demo.cs.config.AppProperties;
import com.demo.cs.infrastructure.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Single instance lifecycle lock includes commit and runtime refresh. */
@Service
public class AgentPublishService {
    private final AgtAgentRepository agents;
    private final AgtAgentVersionRepository versions;
    private final AgentDefinitionService definitions;
    private final AgentDefinitionValidator validator;
    private final DefinitionRegistry registry;
    private final AppProperties props;
    private final TransactionTemplate tx;
    public AgentPublishService(AgtAgentRepository agents, AgtAgentVersionRepository versions,
        AgentDefinitionService definitions, AgentDefinitionValidator validator, DefinitionRegistry registry,
        AppProperties props, PlatformTransactionManager manager) {
        this.agents=agents; this.versions=versions; this.definitions=definitions; this.validator=validator;
        this.registry=registry; this.props=props; this.tx=new TransactionTemplate(manager);
    }
    public synchronized PublishResponse publish(String code, PublishRequest request, String actor) {
        requireConfig();
        PublishResponse response = tx.execute(status -> {
            AgtAgent agent = locked(code);
            return snapshot(agent, agent.getDraftJson(), request == null ? null : request.remark(), actor, false);
        });
        reloadRegistryFromDb();
        return response;
    }
    public synchronized PublishResponse enable(String code, String actor) {
        requireConfig();
        PublishResponse response = tx.execute(status -> {
            var agent=locked(code);
            validate(agent.getDraftJson());
            var current=agent.getPublishedVersion()==null ? Optional.<AgtAgentVersion>empty() : versions.findByAgentCodeAndVersion(code,agent.getPublishedVersion());
            if (current.isPresent() && current.get().getSnapshotJson().equals(agent.getDraftJson())) {
                agent.setEnabled(true); agent.setUpdatedAt(Instant.now()); agents.saveAndFlush(agent);
                return new PublishResponse(code,agent.getPublishedVersion(),agent.getUpdatedAt(),agent.getStatus());
            }
            return snapshot(agent,agent.getDraftJson(),"启用配置",actor,false);
        });
        reloadRegistryFromDb(); return response;
    }
    public synchronized AgentDetailResponse disable(String code, String actor) {
        tx.executeWithoutResult(status -> {
            var agent=locked(code); agent.setEnabled(false); agent.setUpdatedBy(actor); agent.setUpdatedAt(Instant.now()); agents.saveAndFlush(agent);
        });
        registry.remove(code);
        return definitions.get(code);
    }
    public synchronized PublishResponse rollback(String code, RollbackRequest request, String actor) {
        requireConfig();
        if (request==null) throw new IllegalArgumentException("version is required");
        PublishResponse response=tx.execute(status -> {
            var agent=locked(code);
            var previous=versions.findByAgentCodeAndVersion(code,request.version()).orElseThrow(() -> new IllegalArgumentException("version not found"));
            return snapshot(agent,previous.getSnapshotJson(),request.remark(),actor,true);
        });
        reloadRegistryFromDb(); return response;
    }
    private PublishResponse snapshot(AgtAgent agent, String json, String remark, String actor, boolean rollback) {
        validate(json);
        int next=Optional.ofNullable(agent.getPublishedVersion()).orElse(0)+1;
        Instant now=Instant.now();
        var version=new AgtAgentVersion(); version.setAgentCode(agent.getCode()); version.setVersion(next);
        version.setSnapshotJson(json); version.setPublishedAt(now); version.setPublishedBy(actor); version.setRemark(remark); versions.save(version);
        agent.setEnabled(true); agent.setStatus("PUBLISHED"); agent.setPublishedVersion(next); agent.setUpdatedAt(now); agent.setUpdatedBy(actor);
        if (rollback) {
            agent.setDraftJson(json); agent.setDraftRevision(agent.getDraftRevision()+1);
            var def=definitions.readDefinition(json); agent.setName(def.name()); agent.setDescription(def.description());
        }
        agents.saveAndFlush(agent);
        return new PublishResponse(agent.getCode(),next,now,"PUBLISHED");
    }
    private void validate(String json) {
        var result=validator.validate(definitions.readDefinition(json));
        if (!result.ok()) throw new AgentValidationException("配置校验失败",result.errors(),result.warnings());
    }
    private AgtAgent locked(String code) { return agents.findForUpdateByCode(code).orElseThrow(() -> new IllegalArgumentException("agent not found: "+code)); }
    private void requireConfig() { if (!props.agentConfig().enabled()) throw new IllegalStateException("配置运行模式已关闭，请开启 APP_AGENT_CONFIG_ENABLED 后启用Agent"); }
    public VersionListResponse listVersions(String code) {
        definitions.require(code);
        return new VersionListResponse(versions.findByAgentCodeOrderByVersionDesc(code).stream().map(v -> new VersionItem(v.getVersion(),v.getPublishedAt(),v.getPublishedBy(),v.getRemark())).toList());
    }
    public VersionSnapshotResponse getVersion(String code,int version) {
        var v=versions.findByAgentCodeAndVersion(code,version).orElseThrow(() -> new IllegalArgumentException("version not found"));
        return new VersionSnapshotResponse(v.getVersion(),v.getPublishedAt(),v.getPublishedBy(),v.getRemark(),definitions.readDefinition(v.getSnapshotJson()));
    }
    public synchronized void reloadRegistryFromDb() {
        List<PublishedAgent> loaded=new ArrayList<>();
        for(var agent:agents.findByStatusAndEnabledTrue("PUBLISHED")) {
            if(agent.getPublishedVersion()!=null) versions.findByAgentCodeAndVersion(agent.getCode(),agent.getPublishedVersion()).ifPresent(v -> loaded.add(new PublishedAgent(agent.getCode(),v.getVersion(),definitions.readDefinition(v.getSnapshotJson()))));
        }
        registry.clearAndLoad(loaded);
    }
}
