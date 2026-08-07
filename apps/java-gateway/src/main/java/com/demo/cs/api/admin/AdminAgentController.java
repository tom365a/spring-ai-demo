package com.demo.cs.api.admin;

import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.application.agentconfig.AgentDefinitionService;
import com.demo.cs.application.agentconfig.AgentPublishService;
import com.demo.cs.application.agentconfig.AgentTrialService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/agents")
public class AdminAgentController {

    private final AgentDefinitionService definitionService;
    private final AgentPublishService publishService;
    private final AgentTrialService trialService;

    public AdminAgentController(
            AgentDefinitionService definitionService,
            AgentPublishService publishService,
            AgentTrialService trialService
    ) {
        this.definitionService = definitionService;
        this.publishService = publishService;
        this.trialService = trialService;
    }

    @GetMapping
    public ApiEnvelope<AgentListResponse> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "viewer");
        return ApiEnvelope.ok(definitionService.list(type, status, enabled, q));
    }

    @PostMapping
    public ApiEnvelope<AgentDetailResponse> create(
            @RequestBody CreateAgentRequest request,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "editor");
        return ApiEnvelope.ok(definitionService.create(request, "admin"));
    }

    @GetMapping("/{code}")
    public ApiEnvelope<AgentDetailResponse> get(
            @PathVariable String code,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "viewer");
        return ApiEnvelope.ok(definitionService.get(code));
    }

    @PutMapping("/{code}")
    public ApiEnvelope<AgentDetailResponse> update(
            @PathVariable String code,
            @RequestBody UpdateAgentRequest request,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "editor");
        return ApiEnvelope.ok(definitionService.update(code, request, "admin"));
    }

    @PostMapping("/{code}/validate")
    public ApiEnvelope<ValidateResponse> validate(
            @PathVariable String code,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "editor");
        return ApiEnvelope.ok(definitionService.validate(code));
    }

    @PostMapping("/{code}/publish")
    public ApiEnvelope<PublishResponse> publish(
            @PathVariable String code,
            @RequestBody(required = false) PublishRequest request,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "publisher");
        return ApiEnvelope.ok(publishService.publish(code, request, "admin"));
    }

    @PostMapping("/{code}/rollback")
    public ApiEnvelope<PublishResponse> rollback(
            @PathVariable String code,
            @RequestBody RollbackRequest request,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "publisher");
        return ApiEnvelope.ok(publishService.rollback(code, request, "admin"));
    }

    @GetMapping("/{code}/versions")
    public ApiEnvelope<VersionListResponse> versions(
            @PathVariable String code,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "viewer");
        return ApiEnvelope.ok(publishService.listVersions(code));
    }

    @GetMapping("/{code}/versions/{version}")
    public ApiEnvelope<VersionSnapshotResponse> version(
            @PathVariable String code,
            @PathVariable int version,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "viewer");
        return ApiEnvelope.ok(publishService.getVersion(code, version));
    }

    @PostMapping("/{code}/trial")
    public ApiEnvelope<TrialResponse> trial(
            @PathVariable String code,
            @RequestBody TrialRequest request,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        requireRole(role, "editor");
        return ApiEnvelope.ok(trialService.trial(code, request));
    }

    static void requireRole(String roleHeader, String minRole) {
        String role = roleHeader != null && !roleHeader.isBlank() ? roleHeader.trim().toLowerCase() : "viewer";
        int have = rank(role);
        int need = rank(minRole);
        if (have < need) {
            throw new SecurityException("insufficient admin role: need " + minRole + ", got " + role);
        }
    }

    private static int rank(String role) {
        return switch (role) {
            case "publisher", "admin" -> 3;
            case "editor" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
