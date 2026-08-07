package com.demo.cs.infrastructure.mcp;

import com.demo.cs.api.dto.ApiDtos.McpToolInfo;
import com.demo.cs.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class McpBridgeClient {

    private static final Logger log = LoggerFactory.getLogger(McpBridgeClient.class);

    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public McpBridgeClient(AppProperties props, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.props = props;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return props.mcp().enabled();
    }

    public List<McpToolInfo> listTools() {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            String body = restClient.get()
                    .uri(props.mcp().httpBaseUrl() + "/tools")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return List.of();
            }
            Map<String, Object> root = objectMapper.readValue(body, new TypeReference<>() {});
            Object toolsObj = root.containsKey("tools") ? root.get("tools") : root.get("data");
            if (toolsObj == null) {
                return List.of();
            }
            List<Map<String, Object>> raw = objectMapper.convertValue(toolsObj, new TypeReference<>() {});
            List<McpToolInfo> out = new ArrayList<>();
            for (Map<String, Object> t : raw) {
                out.add(new McpToolInfo(
                        String.valueOf(t.getOrDefault("name", "")),
                        String.valueOf(t.getOrDefault("description", "")),
                        "mcp"
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("MCP listTools failed: {}", e.getMessage());
            return List.of();
        }
    }

    public Object callTool(String name, Map<String, Object> arguments) {
        if (!isEnabled()) {
            return Map.of("ok", false, "message", "MCP disabled");
        }
        try {
            Map<String, Object> req = Map.of("name", name, "arguments", arguments != null ? arguments : Map.of());
            String body = restClient.post()
                    .uri(props.mcp().httpBaseUrl() + "/tools/call")
                    .body(req)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return Map.of("ok", false, "message", "empty response");
            }
            return objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            log.warn("MCP callTool {} failed: {}", name, e.getMessage());
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    /** Search docs via MCP if available. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchDocs(String query, int topK) {
        Object result = callTool("search_docs", Map.of("query", query, "top_k", topK));
        if (result instanceof Map<?, ?> map) {
            Object hits = map.get("hits");
            if (hits == null && map.get("result") instanceof Map<?, ?> nested) {
                hits = nested.get("hits");
            }
            if (hits instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        }
        return List.of();
    }
}
