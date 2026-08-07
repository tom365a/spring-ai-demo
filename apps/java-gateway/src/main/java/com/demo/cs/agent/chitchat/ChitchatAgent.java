package com.demo.cs.agent.chitchat;

import com.demo.cs.agent.SubAgent;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.config.WebConfig.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChitchatAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(ChitchatAgent.class);

    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;

    public ChitchatAgent(ChatClient.Builder chatClientBuilder, PromptLoader promptLoader) {
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
    }

    @Override
    public String name() {
        return "chitchat";
    }

    @Override
    public SubAgentResult handle(SubAgentRequest request) {
        String userPrompt = """
                【会话摘要】%s
                【最近对话】
                %s
                
                【用户】
                %s
                """.formatted(
                request.summary() != null ? request.summary() : "无",
                formatMessages(request),
                request.text()
        );

        try {
            String answer = chatClientBuilder.build()
                    .prompt()
                    .system(promptLoader.load("agent_chitchat.md"))
                    .user(userPrompt)
                    .call()
                    .content();
            return SubAgentResult.simple(name(), answer);
        } catch (Exception e) {
            log.warn("ChitchatAgent LLM failed: {}", e.getMessage());
            return SubAgentResult.simple(name(),
                    "你好！我是智能客服 Demo。当前模型接口不可用，请配置有效的 LLM_API_KEY 后重试。"
                            + "你也可以直接查询订单（如 ORD20260730001）或咨询退货政策。");
        }
    }

    private String formatMessages(SubAgentRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : request.recentMessages()) {
            sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        return sb.toString();
    }
}
