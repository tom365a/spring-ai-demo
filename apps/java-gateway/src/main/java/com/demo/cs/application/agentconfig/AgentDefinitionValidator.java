package com.demo.cs.application.agentconfig;

import com.demo.cs.application.agentconfig.AgentValidationException.ValidationIssue;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.application.agentconfig.model.AgentDefinition.ChildRef;
import com.demo.cs.config.AppProperties;
import com.demo.cs.infrastructure.catalog.LocalToolCatalog;
import com.demo.cs.infrastructure.persistence.AgtAgentRepository;
import com.demo.cs.agent.runtime.PromptTemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AgentDefinitionValidator {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private final LocalToolCatalog toolCatalog;
    private final AgtAgentRepository agentRepo;
    private final PromptTemplateRenderer promptRenderer;
    private final AppProperties props;

    public AgentDefinitionValidator(
            LocalToolCatalog toolCatalog,
            AgtAgentRepository agentRepo,
            PromptTemplateRenderer promptRenderer,
            AppProperties props
    ) {
        this.toolCatalog = toolCatalog;
        this.agentRepo = agentRepo;
        this.promptRenderer = promptRenderer;
        this.props = props;
    }

    public ValidationResult validate(AgentDefinition def) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        if (def == null) {
            errors.add(new ValidationIssue("definition", "definition is required"));
            return new ValidationResult(false, errors, warnings);
        }

        if (def.code() == null || !CODE_PATTERN.matcher(def.code()).matches()) {
            errors.add(new ValidationIssue("code", "code must match ^[a-z][a-z0-9_]{1,63}$"));
        }
        if (def.name() == null || def.name().isBlank()) {
            errors.add(new ValidationIssue("name", "name is required"));
        }
        if (def.type() == null || !(def.type().equals("SUPERVISOR") || def.type().equals("WORKER"))) {
            errors.add(new ValidationIssue("type", "type must be SUPERVISOR or WORKER"));
        }

        String systemPrompt = def.prompts() != null ? def.prompts().systemPrompt() : null;
        int maxLen = props.agentConfig().promptMaxLength();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            errors.add(new ValidationIssue("prompts.systemPrompt", "systemPrompt is required"));
        } else if (systemPrompt.length() > maxLen) {
            errors.add(new ValidationIssue("prompts.systemPrompt", "systemPrompt exceeds max length " + maxLen));
        }

        String userTpl = def.prompts() != null ? def.prompts().userPromptTemplate() : null;
        if (userTpl != null && userTpl.length() > maxLen) {
            errors.add(new ValidationIssue("prompts.userPromptTemplate", "userPromptTemplate exceeds max length " + maxLen));
        }

        for (String unknown : promptRenderer.findUnknownPlaceholders(systemPrompt)) {
            errors.add(new ValidationIssue("prompts.systemPrompt", "unknown placeholder: {{" + unknown + "}}"));
        }
        for (String unknown : promptRenderer.findUnknownPlaceholders(userTpl)) {
            errors.add(new ValidationIssue("prompts.userPromptTemplate", "unknown placeholder: {{" + unknown + "}}"));
        }

        List<String> tools = def.tools() != null ? def.tools() : List.of();
        for (int i = 0; i < tools.size(); i++) {
            String code = tools.get(i);
            if (toolCatalog.get(code).isEmpty()) {
                errors.add(new ValidationIssue("tools[" + i + "]", "unknown tool code: " + code));
            }
        }

        boolean isSupervisor = "SUPERVISOR".equals(def.type());
        if (isSupervisor) {
            if (def.policies() != null && def.policies().writeToolsAllowed()) {
                errors.add(new ValidationIssue("policies.allowWriteTools", "SUPERVISOR must set allowWriteTools=false"));
            }
            for (int i = 0; i < tools.size(); i++) {
                if (toolCatalog.isWrite(tools.get(i))) {
                    errors.add(new ValidationIssue("tools[" + i + "]", "SUPERVISOR cannot bind WRITE tool: " + tools.get(i)));
                }
            }
            List<ChildRef> children = def.children() != null ? def.children() : List.of();
            if (children.isEmpty()) {
                errors.add(new ValidationIssue("children", "SUPERVISOR requires at least one child"));
            }
            for (int i = 0; i < children.size(); i++) {
                ChildRef c = children.get(i);
                if (c == null || c.agentCode() == null || c.agentCode().isBlank()) {
                    errors.add(new ValidationIssue("children[" + i + "].agentCode", "agentCode is required"));
                    continue;
                }
                if (c.whenToUse() == null || c.whenToUse().isBlank()) {
                    warnings.add(new ValidationIssue("children[" + i + "].whenToUse", "whenToUse is empty"));
                }
            }
            detectCycles(def.code(), children, errors);
        }

        if (def.prompts() != null && "JSON_SCHEMA".equalsIgnoreCase(def.prompts().outputMode())) {
            if (def.outputSchema() == null || def.outputSchema().isEmpty()) {
                warnings.add(new ValidationIssue("outputSchema", "JSON_SCHEMA mode without outputSchema"));
            }
        }

        if (def.mcp() != null && def.mcp().enabled() && def.mcp().toolAllowlist().isEmpty()) {
            warnings.add(new ValidationIssue("mcp.toolAllowlist", "empty allowlist defaults to READ-only MCP tools"));
        }

        if (def.policies() != null && def.policies().maxChildHops() != null && def.policies().maxChildHops() > 2) {
            errors.add(new ValidationIssue("policies.maxChildHops", "maxChildHops must be <= 2"));
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    private void detectCycles(String rootCode, List<ChildRef> children, List<ValidationIssue> errors) {
        Map<String, List<String>> graph = new java.util.LinkedHashMap<>();
        graph.put(rootCode, children.stream()
                .filter(c -> c != null && c.agentCode() != null)
                .map(ChildRef::agentCode)
                .toList());

        for (ChildRef c : children) {
            if (c == null || c.agentCode() == null) continue;
            agentRepo.findByCode(c.agentCode()).ifPresent(entity -> {
                try {
                    // soft: only detect direct back-ref to supervisor / mutual via draft if loaded later
                } catch (Exception ignored) {
                }
            });
            if (c.agentCode().equals(rootCode)) {
                errors.add(new ValidationIssue("children", "cycle detected: " + rootCode + " -> " + rootCode));
            }
        }

        // DFS across known children links stored in DB drafts is heavy; detect cycles among declared refs
        // including child→parent if child also lists root (simple multi-hop via all agents' children)
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<String> path = new ArrayList<>();
        if (dfsCycle(rootCode, graph, visiting, visited, path, errors)) {
            return;
        }
        // Expand graph from DB for one hop of children's children (draft JSON children)
        for (ChildRef c : children) {
            if (c == null || c.agentCode() == null) continue;
            agentRepo.findByCode(c.agentCode()).ifPresent(childEntity -> {
                try {
                    // lazy: if child draft mentions root as child, cycle
                    String json = childEntity.getDraftJson();
                    if (json != null && json.contains("\"agentCode\":\"" + rootCode + "\"")) {
                        errors.add(new ValidationIssue("children",
                                "cycle detected: " + rootCode + " -> " + c.agentCode() + " -> " + rootCode));
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    private boolean dfsCycle(
            String node,
            Map<String, List<String>> graph,
            Set<String> visiting,
            Set<String> visited,
            List<String> path,
            List<ValidationIssue> errors
    ) {
        if (visiting.contains(node)) {
            path.add(node);
            errors.add(new ValidationIssue("children", "cycle detected: " + String.join(" -> ", path)));
            return true;
        }
        if (visited.contains(node)) return false;
        visiting.add(node);
        path.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (dfsCycle(next, graph, visiting, visited, path, errors)) return true;
        }
        path.remove(path.size() - 1);
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    public record ValidationResult(boolean ok, List<ValidationIssue> errors, List<ValidationIssue> warnings) {}
}
