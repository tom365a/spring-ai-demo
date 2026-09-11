package com.demo.cs.infrastructure.catalog;

import com.demo.cs.application.agentconfig.model.AgentDefinition;
import org.springframework.stereotype.Component;
import java.util.*;

/** Trusted application capabilities, never executable user-uploaded files. */
@Component
public class LocalSkillCatalog {
    public record SkillEntry(String code, String name, String description, List<String> applicableTypes,
                             String instructions, List<String> tools, boolean enableRag) {}
    private final List<SkillEntry> entries = List.of(
        new SkillEntry("clear_response", "清晰表达", "先给结论，再列出可执行步骤；不额外授予工具权限。",
            List.of("SUPERVISOR", "WORKER"), "回答时先给出明确结论，再按需要列出简洁、可执行的步骤。不要编造未核实的信息。", List.of(), false),
        new SkillEntry("order_lookup", "订单查询", "自动开放查询订单和查询物流两个只读工具。",
            List.of("SUPERVISOR", "WORKER"), "涉及订单状态或物流时先查询对应工具，根据工具实际结果回答，不编造订单或物流状态。",
            List.of("query_order", "query_logistics"), false),
        new SkillEntry("knowledge_answer", "知识检索问答", "自动检索项目知识库并将结果交给模型。",
            List.of("WORKER"), "依据提供的知识检索结果回答，标注引用；检索内容不足时明确说明未知。", List.of(), true)
    );
    public List<SkillEntry> list() { return entries; }
    public Optional<SkillEntry> get(String code) { return entries.stream().filter(s -> s.code().equals(code)).findFirst(); }
    public List<String> effectiveTools(AgentDefinition def) {
        Set<String> codes = new LinkedHashSet<>();
        def.tools().stream().filter(Objects::nonNull).forEach(codes::add);
        for (String skill : def.skills()) get(skill).ifPresent(s -> codes.addAll(s.tools()));
        return List.copyOf(codes);
    }
    public boolean ragEnabled(AgentDefinition def) {
        return def.capabilities().ragEnabled() || def.skills().stream().anyMatch(c -> get(c).map(SkillEntry::enableRag).orElse(false));
    }
    public String instructions(AgentDefinition def) {
        StringBuilder out = new StringBuilder();
        for (String code : new LinkedHashSet<>(def.skills())) get(code).ifPresent(s ->
            out.append("\n\n【技能：").append(s.name()).append("】\n").append(s.instructions()));
        return out.toString();
    }
}
