package com.demo.cs.config;

import com.demo.cs.infrastructure.llm.KimiChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "kimi")
public class KimiChatConfiguration {
    @Bean
    @Primary
    public KimiChatModel kimiChatModel(ObjectMapper mapper,
            @Value("${app.llm.kimi.api-key:}") String apiKey,
            @Value("${app.llm.kimi.base-url:https://api.moonshot.cn}") String baseUrl,
            @Value("${app.llm.kimi.model:kimi-k3}") String model,
            @Value("${app.llm.kimi.reasoning-effort:low}") String reasoningEffort,
            @Value("${app.llm.kimi.max-completion-tokens:4096}") int maxTokens,
            @Value("${app.llm.kimi.max-tool-rounds:6}") int maxToolRounds,
            @Value("${app.llm.kimi.timeout-seconds:90}") int timeoutSeconds) {
        return new KimiChatModel(mapper, apiKey, baseUrl, model, reasoningEffort, maxTokens, maxToolRounds, timeoutSeconds);
    }
}
