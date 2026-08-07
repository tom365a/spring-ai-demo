package com.demo.cs.application.agentconfig;

import com.demo.cs.agent.runtime.DefinitionRegistry;
import com.demo.cs.api.dto.AdminDtos.PublishRequest;
import com.demo.cs.application.agentconfig.model.AgentDefinition;
import com.demo.cs.application.agentconfig.model.AgentDefinition.*;
import com.demo.cs.config.AppProperties;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.demo.cs.infrastructure.persistence.AgtAgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(20)
public class AgentSeedService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentSeedService.class);

    private final AppProperties props;
    private final AgtAgentRepository agentRepo;
    private final AgentDefinitionService definitionService;
    private final AgentPublishService publishService;
    private final PromptLoader promptLoader;
    private final DefinitionRegistry registry;

    public AgentSeedService(
            AppProperties props,
            AgtAgentRepository agentRepo,
            AgentDefinitionService definitionService,
            AgentPublishService publishService,
            PromptLoader promptLoader,
            DefinitionRegistry registry
    ) {
        this.props = props;
        this.agentRepo = agentRepo;
        this.definitionService = definitionService;
        this.publishService = publishService;
        this.promptLoader = promptLoader;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (props.agentConfig().seedOnStartup()) {
                var existing = agentRepo.findByCode("supervisor");
                if (existing.isEmpty()) {
                    log.info("Seeding default agent definitions...");
                    seedAll();
                    log.info("Seeded and published 6 agents");
                } else if (existing.get().getPublishedVersion() == null) {
                    log.info("Supervisor draft exists but unpublished; publishing seed agents...");
                    ensurePublished("knowledge");
                    ensurePublished("order");
                    ensurePublished("ticket");
                    ensurePublished("vision");
                    ensurePublished("chitchat");
                    ensurePublished("supervisor");
                }
            }
            publishService.reloadRegistryFromDb();
            log.info("DefinitionRegistry loaded {} enabled agents", registry.allEnabled().size());
        } catch (Exception e) {
            log.warn("Agent seed/registry load skipped: {}", e.getMessage(), e);
        }
    }

    private void ensurePublished(String code) {
        agentRepo.findByCode(code).ifPresent(a -> {
            if (a.getPublishedVersion() == null) {
                publishService.publish(code, new PublishRequest("seed recover"), "seed");
            }
        });
    }

    private void seedAll() {
        createAndPublish(knowledge());
        createAndPublish(order());
        createAndPublish(ticket());
        createAndPublish(vision());
        createAndPublish(chitchat());
        createAndPublish(supervisor());
    }

    private void createAndPublish(AgentDefinition def) {
        var create = new com.demo.cs.api.dto.AdminDtos.CreateAgentRequest(
                def.code(), def.name(), def.type(), def.description(), def
        );
        definitionService.create(create, "seed");
        publishService.publish(def.code(), new PublishRequest("seed v1"), "seed");
    }

    private AgentDefinition supervisor() {
        String system = promptLoader.load("supervisor_router.md");
        String user = toDoubleBrace(promptLoader.load("supervisor_router_user.md"));
        if (user == null || user.isBlank()) {
            user = "{{childrenCatalog}}\n\n{{text}}";
        }
        List<ChildRef> children = List.of(
                new ChildRef("knowledge", "知识问答", "政策、FAQ、退换货规则、运费说明、包邮", 10, true),
                new ChildRef("order", "订单履约", "查订单、发货、取消订单、物流、运单", 20, true),
                new ChildRef("ticket", "售后工单", "破损、少件、投诉、开工单、转人工", 30, true),
                new ChildRef("vision", "视觉理解", "本轮有图片/附件需要看图才能继续", 40, true),
                new ChildRef("chitchat", "闲聊", "打招呼、你是谁、能做什么", 50, true)
        );
        return new AgentDefinition(
                "supervisor", "主路由", "意图识别与子 Agent 路由", "SUPERVISOR",
                new ModelConfig(null, 0.1, 1024, false),
                new Prompts(system, user, "JSON_SCHEMA"),
                Map.of("type", "RouteDecision"),
                List.of(),
                new McpConfig(false, List.of(), List.of()),
                new Capabilities(false),
                children,
                new Routing(props.routeConfidenceThreshold(), true, null),
                new Policies(false, List.of(), 0, 0),
                new Memory(true, null, true),
                new UiConfig("supervisor", List.of("路由"), 1)
        );
    }

    private AgentDefinition knowledge() {
        return worker("knowledge", "知识问答", "政策 FAQ 与知识检索",
                "agent_knowledge.md", "agent_knowledge_user.md",
                List.of(), true, false, List.of(), false, 0, 10);
    }

    private AgentDefinition order() {
        return worker("order", "订单履约", "查单、物流、取消确认",
                "agent_order.md", "agent_order_user.md",
                List.of("query_order", "query_logistics", "cancel_order"),
                false, false, List.of("cancel_order"), true, 0, 20);
    }

    private AgentDefinition ticket() {
        return worker("ticket", "售后工单", "创建工单与转人工",
                "agent_ticket.md", "agent_ticket_user.md",
                List.of("create_ticket", "escalate_human"),
                false, false, List.of(), true, 0, 30);
    }

    private AgentDefinition vision() {
        return worker("vision", "视觉理解", "图片理解与二次路由建议",
                "agent_vision.md", null,
                List.of(), false, true, List.of(), false, 1, 40);
    }

    private AgentDefinition chitchat() {
        return worker("chitchat", "闲聊", "寒暄与能力介绍",
                "agent_chitchat.md", null,
                List.of(), false, false, List.of(), false, 0, 50);
    }

    private AgentDefinition worker(
            String code, String name, String description,
            String systemFile, String userFile,
            List<String> tools, boolean enableRag, boolean enableVision,
            List<String> requireConfirm, boolean allowWrite, int maxChildHops, int sortOrder
    ) {
        String system = promptLoader.load(systemFile);
        String user;
        if (userFile != null) {
            user = toDoubleBrace(promptLoader.load(userFile));
        } else {
            user = defaultUserTemplate(code);
        }
        if (user == null || user.isBlank()) {
            user = defaultUserTemplate(code);
        }
        return new AgentDefinition(
                code, name, description, "WORKER",
                new ModelConfig(null, 0.2, 2048, enableVision),
                new Prompts(system, user, "TEXT"),
                null,
                tools,
                new McpConfig(false, List.of(), List.of()),
                new Capabilities(enableRag),
                List.of(),
                null,
                new Policies(allowWrite, requireConfirm, 3, maxChildHops),
                new Memory(true, null, true),
                new UiConfig(code, List.of(), sortOrder)
        );
    }

    private String defaultUserTemplate(String code) {
        if ("knowledge".equals(code)) {
            return """
                    【会话摘要】{{summary}}
                    【最近对话】
                    {{recentMessages}}
                    
                    【检索结果】
                    {{retrievedBlocks}}
                    
                    【MCP 补充】
                    {{mcpBlocks}}
                    
                    【用户问题】
                    {{text}}
                    """;
        }
        if ("vision".equals(code)) {
            return "{{text}}";
        }
        if ("chitchat".equals(code)) {
            return """
                    【会话摘要】{{summary}}
                    【最近对话】
                    {{recentMessages}}
                    
                    【用户】
                    {{text}}
                    """;
        }
        return """
                当前 userId={{userId}}
                路由槽位={{slots}}
                
                【会话摘要】{{summary}}
                【最近对话】
                {{recentMessages}}
                
                【用户问题】
                {{text}}
                """;
    }

    private String toDoubleBrace(String template) {
        if (template == null || template.isBlank()) return template;
        java.util.Map<String, String> aliases = new java.util.LinkedHashMap<>();
        aliases.put("userId", "userId");
        aliases.put("slotsJson", "slots");
        aliases.put("summary", "summary");
        aliases.put("recentMessagesFormatted", "recentMessages");
        aliases.put("text", "text");
        aliases.put("retrievedBlocks", "retrievedBlocks");
        aliases.put("mcpBlocks", "mcpBlocks");
        aliases.put("childrenCatalog", "childrenCatalog");
        aliases.put("agentDescription", "agentDescription");
        aliases.put("locale", "locale");
        aliases.put("visionSummary", "visionSummary");
        aliases.put("slots", "slots");
        aliases.put("recentMessages", "recentMessages");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?<!\\{)\\{([a-zA-Z][a-zA-Z0-9_]*)\\}(?!\\})");
        java.util.regex.Matcher m = p.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String mapped = aliases.getOrDefault(key, key);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("{{" + mapped + "}}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
