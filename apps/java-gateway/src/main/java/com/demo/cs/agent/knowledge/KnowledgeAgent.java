package com.demo.cs.agent.knowledge;

import com.demo.cs.agent.SubAgent;
import com.demo.cs.agent.model.AgentModels.Citation;
import com.demo.cs.agent.model.AgentModels.SubAgentRequest;
import com.demo.cs.agent.model.AgentModels.SubAgentResult;
import com.demo.cs.application.knowledge.KnowledgeService;
import com.demo.cs.config.AppProperties;
import com.demo.cs.config.WebConfig.PromptLoader;
import com.demo.cs.infrastructure.mcp.McpBridgeClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeAgent implements SubAgent {

    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;
    private final KnowledgeService knowledgeService;
    private final McpBridgeClient mcpClient;
    private final AppProperties props;

    public KnowledgeAgent(
            ChatClient.Builder chatClientBuilder,
            PromptLoader promptLoader,
            KnowledgeService knowledgeService,
            McpBridgeClient mcpClient,
            AppProperties props
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.knowledgeService = knowledgeService;
        this.mcpClient = mcpClient;
        this.props = props;
    }

    @Override
    public String name() {
        return "knowledge";
    }

    @Override
    public SubAgentResult handle(SubAgentRequest request) {
        List<Document> docs = List.of();
        List<Citation> citations = new ArrayList<>();
        try {
            docs = knowledgeService.retrieveDocuments(request.text(), props.rag().topK());
            citations.addAll(knowledgeService.toCitations(docs));
        } catch (Exception e) {
            // embedding/vector failure should not crash the turn
            citations = new ArrayList<>();
            docs = List.of();
        }

        StringBuilder mcpBlocks = new StringBuilder();
        if (request.enableMcp() && mcpClient.isEnabled()) {
            try {
                List<Map<String, Object>> mcpHits = mcpClient.searchDocs(request.text(), props.rag().topK());
                int idx = citations.size() + 1;
                for (Map<String, Object> hit : mcpHits) {
                    String content = String.valueOf(hit.getOrDefault("snippet",
                            hit.getOrDefault("content", hit.getOrDefault("text", ""))));
                    String title = String.valueOf(hit.getOrDefault("name",
                            hit.getOrDefault("title", "MCP文档")));
                    double score = hit.get("score") instanceof Number n ? n.doubleValue() : 0.5;
                    citations.add(new Citation(
                            String.valueOf(hit.getOrDefault("doc_id", title)),
                            title, content, score, "mcp", hit
                    ));
                    mcpBlocks.append('[').append(idx++).append("] title=").append(title)
                            .append(" score=").append(score).append('\n')
                            .append(content).append("\n\n");
                }
            } catch (Exception ignored) {
            }
        }

        String retrievedBlocks = formatRetrieved(docs);
        String userPrompt = buildUserPrompt(request, retrievedBlocks, mcpBlocks.toString());
        try {
            String answer = chatClientBuilder.build()
                    .prompt()
                    .system(promptLoader.load("agent_knowledge.md"))
                    .user(userPrompt)
                    .call()
                    .content();
            return new SubAgentResult(name(), answer, citations, List.of(), false, null, null, null, null);
        } catch (Exception e) {
            String fallback = citations.isEmpty()
                    ? "知识服务暂时不可用（模型或向量接口异常）。请检查 LLM_API_KEY / base-url 配置后重试。"
                    : "模型调用失败，以下是检索到的相关片段，供参考：\n" + retrievedBlocks;
            return new SubAgentResult(name(), fallback, citations, List.of(), false, null, null, null, null);
        }
    }

    private String formatRetrieved(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() != null ? d.getMetadata() : Map.of();
            sb.append('[').append(i++).append("] title=")
                    .append(meta.getOrDefault("title", "片段")).append('\n');
            sb.append(d.getText()).append("\n\n");
        }
        if (sb.isEmpty()) {
            sb.append("（无检索命中）\n");
        }
        return sb.toString();
    }

    private String buildUserPrompt(SubAgentRequest request, String retrieved, String mcpBlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会话摘要】").append(request.summary() != null ? request.summary() : "无").append('\n');
        sb.append("【最近对话】\n");
        for (Map<String, Object> m : request.recentMessages()) {
            sb.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        sb.append("\n【检索结果】\n").append(retrieved);
        sb.append("\n【MCP 补充】\n").append(mcpBlocks.isBlank() ? "（无）\n" : mcpBlocks);
        sb.append("\n【用户问题】\n").append(request.text());
        return sb.toString();
    }
}
