package com.demo.cs.application.agentconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentDefinition(
        String code,
        String name,
        String description,
        String type,
        ModelConfig modelConfig,
        Prompts prompts,
        Map<String, Object> outputSchema,
        List<String> tools,
        List<String> skills,
        McpConfig mcp,
        Capabilities capabilities,
        List<ChildRef> children,
        Routing routing,
        Policies policies,
        Memory memory,
        UiConfig ui,
        Map<String,Integer> skillVersions
) {
    public AgentDefinition(String code,String name,String description,String type,ModelConfig modelConfig,Prompts prompts,Map<String,Object> outputSchema,List<String> tools,List<String> skills,McpConfig mcp,Capabilities capabilities,List<ChildRef> children,Routing routing,Policies policies,Memory memory,UiConfig ui) {
        this(code,name,description,type,modelConfig,prompts,outputSchema,tools,skills,mcp,capabilities,children,routing,policies,memory,ui,Map.of());
    }
    public AgentDefinition {
        if (skillVersions == null) skillVersions = Map.of();
        if (tools == null) tools = List.of();
        if (skills == null) skills = List.of();
        if (children == null) children = List.of();
        if (modelConfig == null) modelConfig = new ModelConfig(null, 0.2, 2048, false);
        if (prompts == null) prompts = new Prompts("", "", "TEXT");
        if (mcp == null) mcp = new McpConfig(false, List.of(), List.of());
        if (capabilities == null) capabilities = new Capabilities(false);
        if (policies == null) policies = new Policies(false, List.of(), 3, 0);
        policies = new Policies(policies.allowWriteTools(),policies.requireConfirmFor(),policies.maxToolRounds(),"SUPERVISOR".equals(type)?1:0,policies.toolTimeoutSeconds(),policies.taskTimeoutSeconds());
        if (memory == null) memory = new Memory(true, null, true);
        if (ui == null) ui = new UiConfig(null, List.of(), 100);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelConfig(String chatModel, Double temperature, Integer maxTokens, Boolean enableVision, String resourceId, Boolean inheritDefaults) {
        public ModelConfig(String chatModel,Double temperature,Integer maxTokens,Boolean enableVision) {this(chatModel,temperature,maxTokens,enableVision,null,true);}
        public ModelConfig {
            if (inheritDefaults == null) inheritDefaults = true;
            if (temperature == null) temperature = 0.2;
            if (maxTokens == null) maxTokens = 2048;
            if (enableVision == null) enableVision = false;
        }

        public boolean visionEnabled() {
            return Boolean.TRUE.equals(enableVision);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prompts(String systemPrompt, String userPromptTemplate, String outputMode) {
        public Prompts {
            if (systemPrompt == null) systemPrompt = "";
            if (userPromptTemplate == null) userPromptTemplate = "{{text}}";
            if (outputMode == null) outputMode = "TEXT";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpConfig(boolean enabled, List<String> serverIds, List<String> toolAllowlist) {
        public McpConfig {
            if (serverIds == null) serverIds = List.of();
            if (toolAllowlist == null) toolAllowlist = List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(Boolean enableRag) {
        public Capabilities {
            if (enableRag == null) enableRag = false;
        }

        public boolean ragEnabled() {
            return Boolean.TRUE.equals(enableRag);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChildRef(
            String agentCode,
            String alias,
            String whenToUse,
            Integer priority,
            Boolean enabled
    ) {
        public ChildRef {
            if (priority == null) priority = 100;
            if (enabled == null) enabled = true;
        }

        public boolean childEnabled() {
            return !Boolean.FALSE.equals(enabled);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Routing(Double confidenceThreshold, Boolean allowNone, String clarifyPrompt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policies(
            Boolean allowWriteTools,
            List<String> requireConfirmFor,
            Integer maxToolRounds,
            Integer maxChildHops,
            Integer toolTimeoutSeconds,
            Integer taskTimeoutSeconds
    ) {
        public Policies(Boolean allowWriteTools,List<String> requireConfirmFor,Integer maxToolRounds,Integer maxChildHops) {this(allowWriteTools,requireConfirmFor,maxToolRounds,maxChildHops,30,120);}
        public Policies {
            if (toolTimeoutSeconds == null) toolTimeoutSeconds = 30;
            if (taskTimeoutSeconds == null) taskTimeoutSeconds = 120;
            if (allowWriteTools == null) allowWriteTools = false;
            if (requireConfirmFor == null) requireConfirmFor = List.of();
            if (maxToolRounds == null) maxToolRounds = 3;
            if (maxChildHops == null) maxChildHops = 0;
        }

        public boolean writeToolsAllowed() {
            return Boolean.TRUE.equals(allowWriteTools);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Memory(Boolean injectSummary, Integer windowSize, Boolean injectDescriptionToSupervisor) {
        public Memory {
            if (windowSize == null) windowSize = 10;
            if (injectSummary == null) injectSummary = true;
            if (injectDescriptionToSupervisor == null) injectDescriptionToSupervisor = true;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UiConfig(String icon, List<String> tags, Integer sortOrder) {
        public UiConfig {
            if (tags == null) tags = List.of();
            if (sortOrder == null) sortOrder = 100;
        }
    }
}
