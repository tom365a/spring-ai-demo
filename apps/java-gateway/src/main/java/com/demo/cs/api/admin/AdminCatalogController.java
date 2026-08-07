package com.demo.cs.api.admin;

import com.demo.cs.api.dto.AdminDtos.*;
import com.demo.cs.api.dto.ApiDtos.ApiEnvelope;
import com.demo.cs.api.dto.ApiDtos.McpToolInfo;
import com.demo.cs.config.AppProperties;
import com.demo.cs.infrastructure.catalog.LocalToolCatalog;
import com.demo.cs.infrastructure.mcp.McpBridgeClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/catalog")
public class AdminCatalogController {

    private final LocalToolCatalog toolCatalog;
    private final McpBridgeClient mcpClient;
    private final AppProperties props;

    public AdminCatalogController(LocalToolCatalog toolCatalog, McpBridgeClient mcpClient, AppProperties props) {
        this.toolCatalog = toolCatalog;
        this.mcpClient = mcpClient;
        this.props = props;
    }

    @GetMapping("/tools")
    public ApiEnvelope<CatalogToolsResponse> tools(
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        AdminAgentController.requireRole(role, "viewer");
        List<CatalogToolItem> items = new ArrayList<>();
        for (LocalToolCatalog.ToolEntry e : toolCatalog.list()) {
            items.add(new CatalogToolItem(
                    e.code(), e.name(), e.description(),
                    e.sideEffect().name(), e.ownerDomain(), Map.of()
            ));
        }
        return ApiEnvelope.ok(new CatalogToolsResponse(items));
    }

    @GetMapping("/mcp-servers")
    public ApiEnvelope<McpServersResponse> mcpServers(
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        AdminAgentController.requireRole(role, "viewer");
        return ApiEnvelope.ok(new McpServersResponse(List.of(buildMcpItem(false))));
    }

    @PostMapping("/mcp-servers/{id}/refresh")
    public ApiEnvelope<McpServerItem> refresh(
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Role", required = false) String role
    ) {
        AdminAgentController.requireRole(role, "publisher");
        if (!"mcp_knowledge".equals(id)) {
            throw new IllegalArgumentException("unknown mcp server: " + id);
        }
        return ApiEnvelope.ok(buildMcpItem(true));
    }

    private McpServerItem buildMcpItem(boolean refresh) {
        String endpoint = props.mcp().httpBaseUrl();
        String status = "UNKNOWN";
        List<Map<String, Object>> tools = new ArrayList<>();
        if (!props.mcp().enabled()) {
            status = "DOWN";
        } else if (refresh || mcpClient.isEnabled()) {
            List<McpToolInfo> listed = mcpClient.listTools();
            if (listed.isEmpty() && !refresh) {
                status = props.mcp().enabled() ? "UNKNOWN" : "DOWN";
            } else {
                status = listed.isEmpty() ? "DOWN" : "UP";
            }
            for (McpToolInfo t : listed) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", t.name());
                row.put("description", t.description());
                row.put("sideEffect", "READ");
                tools.add(row);
            }
        }
        return new McpServerItem("mcp_knowledge", "知识库 MCP", "http", endpoint, status, tools);
    }
}
