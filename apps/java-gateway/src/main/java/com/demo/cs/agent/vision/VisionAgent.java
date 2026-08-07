package com.demo.cs.agent.vision;

import com.demo.cs.agent.SubAgent;
import com.demo.cs.agent.model.AgentModels.Slots;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.agent.model.AgentModels.AttachmentView;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class VisionAgent implements SubAgent {

    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public VisionAgent(ChatClient.Builder chatClientBuilder, PromptLoader promptLoader, ObjectMapper objectMapper) {
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "vision";
    }

    @Override
    public SubAgentResult handle(SubAgentRequest request) {
        if (request.attachments() == null || request.attachments().isEmpty()) {
            return SubAgentResult.simple(name(), "未收到图片附件，请上传图片后再试，或改用文字描述您的问题。");
        }

        var spec = chatClientBuilder.build()
                .prompt()
                .system(promptLoader.load("agent_vision.md"));

        spec = spec.user(u -> {
            u.text(request.text() != null && !request.text().isBlank()
                    ? request.text()
                    : "请结合图片处理");
            for (AttachmentView att : request.attachments()) {
                u.media(new Media(
                        org.springframework.util.MimeTypeUtils.parseMimeType(att.contentType()),
                        new FileSystemResource(att.path())
                ));
            }
        });

        String raw = spec.call().content();
        return parseVisionResult(raw);
    }

    private SubAgentResult parseVisionResult(String raw) {
        String answer = raw;
        String suggestedIntent = null;
        Slots suggestedSlots = null;
        String visionSummary = null;

        try {
            int jsonStart = raw.lastIndexOf('{');
            int jsonEnd = raw.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                answer = raw.substring(0, jsonStart).trim();
                String json = raw.substring(jsonStart, jsonEnd + 1);
                JsonNode node = objectMapper.readTree(json);
                suggestedIntent = textOrNull(node.path("suggestedIntent"));
                visionSummary = textOrNull(node.path("scene"));
                JsonNode slotsNode = node.path("suggestedSlots");
                suggestedSlots = new Slots(
                        textOrNull(slotsNode.path("orderId")),
                        textOrNull(slotsNode.path("trackingNo")),
                        textOrNull(slotsNode.path("category")),
                        null
                );
            }
        } catch (Exception ignored) {
        }

        if (answer == null || answer.isBlank()) {
            answer = raw;
        }

        return new SubAgentResult(name(), answer, List.of(), List.of(),
                false, null, suggestedIntent, suggestedSlots, visionSummary);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String v = node.asText(null);
        return v != null && !v.isBlank() && !"null".equalsIgnoreCase(v) ? v : null;
    }
}
