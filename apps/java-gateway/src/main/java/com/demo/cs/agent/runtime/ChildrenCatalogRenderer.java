package com.demo.cs.agent.runtime;

import com.demo.cs.application.agentconfig.model.AgentDefinition.ChildRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class ChildrenCatalogRenderer {

    public String render(List<ChildRef> children) {
        if (children == null || children.isEmpty()) {
            return "（无可路由子 Agent）";
        }
        List<ChildRef> sorted = new ArrayList<>();
        for (ChildRef c : children) {
            if (c != null && c.childEnabled() && c.agentCode() != null && !c.agentCode().isBlank()) {
                sorted.add(c);
            }
        }
        sorted.sort(Comparator.comparingInt(c -> c.priority() != null ? c.priority() : 100));
        StringBuilder sb = new StringBuilder();
        sb.append("你可路由到的子 Agent：\n");
        int i = 1;
        for (ChildRef c : sorted) {
            String alias = c.alias() != null && !c.alias().isBlank() ? c.alias() : c.agentCode();
            String when = c.whenToUse() != null ? c.whenToUse() : "";
            sb.append(i++).append(". ").append(c.agentCode())
                    .append("（").append(alias).append("）— ").append(when).append('\n');
        }
        sb.append("请输出 targetAgent（必须是上表 code 之一，或 none 表示仅澄清）。");
        return sb.toString();
    }

    public List<String> enabledCodes(List<ChildRef> children) {
        if (children == null) return List.of();
        List<String> codes = new ArrayList<>();
        for (ChildRef c : children) {
            if (c != null && c.childEnabled() && c.agentCode() != null && !c.agentCode().isBlank()) {
                codes.add(c.agentCode());
            }
        }
        return codes;
    }
}
