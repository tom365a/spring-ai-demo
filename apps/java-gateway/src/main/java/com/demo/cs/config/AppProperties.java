package com.demo.cs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String apiKey,
        String uploadDir,
        String knowledgeSampleDir,
        int sessionWindowSize,
        double routeConfidenceThreshold,
        Rag rag,
        Mcp mcp,
        Vector vector,
        AgentConfig agentConfig
) {
    public record Rag(int topK, double scoreThreshold) {}
    public record Mcp(boolean enabled, String httpBaseUrl) {}
    public record Vector(String backend) {}

    public record AgentConfig(
            boolean enabled,
            boolean seedOnStartup,
            boolean fallbackToLegacy,
            int promptMaxLength,
            int trialSessionTtlMinutes
    ) {
        public AgentConfig {
            if (promptMaxLength <= 0) promptMaxLength = 20000;
            if (trialSessionTtlMinutes <= 0) trialSessionTtlMinutes = 60;
        }
    }

    public AppProperties {
        if (rag == null) rag = new Rag(5, 0.55);
        if (mcp == null) mcp = new Mcp(false, "http://localhost:3100");
        if (vector == null) vector = new Vector("pgvector");
        if (agentConfig == null) {
            agentConfig = new AgentConfig(false, true, true, 20000, 60);
        }
        if (sessionWindowSize <= 0) sessionWindowSize = 10;
        if (routeConfidenceThreshold <= 0) routeConfidenceThreshold = 0.55;
    }
}
