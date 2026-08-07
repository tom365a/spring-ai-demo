package com.demo.cs.api.dto;

import com.demo.cs.application.agentconfig.AgentValidationException.ValidationIssue;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.agent.model.AgentModels.ToolCallRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AdminDtos {

    private AdminDtos() {}

    public record AgentListItem(
            String code,
            String name,
            String type,
            String status,
            Integer publishedVersion,
            boolean enabled,
            String description,
            Instant updatedAt
    ) {}

    public record AgentListResponse(List<AgentListItem> items, int total) {}

    public record CreateAgentRequest(
            String code,
            String name,
            String type,
            String description,
            AgentDefinition definition
    ) {}

    public record UpdateAgentRequest(
            String name,
            String description,
            Boolean enabled,
            Integer sortOrder,
            Integer draftRevision,
            AgentDefinition definition,
            String remark,
            String updatedBy
    ) {}

    public record AgentDetailResponse(
            AgentDefinition draft,
            AgentDefinition published,
            boolean dirty,
            Integer draftRevision,
            Integer publishedVersion,
            String status,
            boolean enabled,
            Instant updatedAt,
            String updatedBy
    ) {}

    public record ValidateResponse(boolean ok, List<ValidationIssue> errors, List<ValidationIssue> warnings) {}

    public record PublishRequest(String remark) {}

    public record PublishResponse(
            String code,
            int publishedVersion,
            Instant publishedAt,
            String status
    ) {}

    public record RollbackRequest(int version, String remark) {}

    public record VersionItem(int version, Instant publishedAt, String publishedBy, String remark) {}

    public record VersionListResponse(List<VersionItem> items) {}

    public record VersionSnapshotResponse(int version, Instant publishedAt, String publishedBy, String remark, AgentDefinition snapshot) {}

    public record TrialRequest(
            String text,
            String userId,
            List<String> attachmentIds,
            Boolean useDraft,
            String mode
    ) {}

    public record TrialResponse(
            String answer,
            String agentCode,
            Integer agentVersion,
            List<Map<String, Object>> routeTrace,
            List<ToolCallRecord> toolCalls,
            Map<String, String> promptsRendered,
            long latencyMs
    ) {}

    public record CatalogToolItem(
            String code,
            String name,
            String description,
            String sideEffect,
            String ownerDomain,
            Map<String, Object> paramSchema
    ) {}

    public record CatalogToolsResponse(List<CatalogToolItem> items) {}

    public record McpServerItem(
            String id,
            String name,
            String transport,
            String endpoint,
            String status,
            List<Map<String, Object>> tools
    ) {}

    public record McpServersResponse(List<McpServerItem> items) {}
}
