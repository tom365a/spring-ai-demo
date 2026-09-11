package com.demo.cs.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** K3 requires verbatim assistant reasoning/tool fields; Spring AI 1.0's OpenAI DTO discards them. */
public final class KimiChatModel implements ChatModel {
    public static final String RAW_ASSISTANT = "kimi.rawAssistant";
    private final ObjectMapper mapper;
    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final String reasoningEffort;
    private final int maxTokens;
    private final int maxToolRounds;
    private final Duration timeout;
    private final HttpClient client;

    public KimiChatModel(ObjectMapper mapper, String apiKey, String baseUrl, String model,
                         String reasoningEffort, int maxTokens, int maxToolRounds, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("KIMI_API_KEY is required for the Kimi provider");
        if (!List.of("low", "high", "max").contains(reasoningEffort)) throw new IllegalArgumentException("KIMI_REASONING_EFFORT must be low, high or max");
        if (maxTokens < 1 || maxToolRounds < 1 || timeoutSeconds < 1) throw new IllegalArgumentException("Kimi limits must be positive");
        String normalized = baseUrl.replaceAll("/+$", "");
        this.endpoint = URI.create(normalized + (normalized.endsWith("/v1") ? "" : "/v1") + "/chat/completions");
        if (!List.of("https", "http").contains(endpoint.getScheme()) || endpoint.getUserInfo() != null || endpoint.getQuery() != null) throw new IllegalArgumentException("Invalid Kimi base URL");
        this.mapper=mapper; this.apiKey=apiKey; this.model=model; this.reasoningEffort=reasoningEffort;
        this.maxTokens=maxTokens; this.maxToolRounds=maxToolRounds; this.timeout=Duration.ofSeconds(timeoutSeconds);
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds,15))).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().model(model).build();
    }

    @Override public ChatResponse call(Prompt prompt) {
        var options=prompt.getOptions();
        ToolCallingChatOptions toolOptions=options instanceof ToolCallingChatOptions t ? t : null;
        Map<String,ToolCallback> callbacks=new LinkedHashMap<>();
        if (toolOptions != null && toolOptions.getToolCallbacks()!=null) for (var callback:toolOptions.getToolCallbacks()) {
            callbacks.put(callback.getToolDefinition().name(),callback);
        }
        if (toolOptions != null && toolOptions.getToolNames()!=null && !toolOptions.getToolNames().isEmpty()) throw new IllegalArgumentException("Kimi requires explicit ToolCallbacks; named global tools are not enabled");
        ArrayNode messages=messages(prompt);
        ArrayNode toolDefinitions=mapper.createArrayNode();
        callbacks.forEach((name,callback) -> {
            ObjectNode function=toolDefinitions.addObject().put("type","function").putObject("function");
            function.put("name",name).put("description",callback.getToolDefinition().description());
            try { function.set("parameters",mapper.readTree(callback.getToolDefinition().inputSchema())); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid tool schema: "+name); }
        });
        int promptTokens=0, completionTokens=0;
        for (int round=0; round<=maxToolRounds; round++) {
            ObjectNode body=mapper.createObjectNode();
            body.put("model", options!=null && options.getModel()!=null ? options.getModel() : model);
            body.put("reasoning_effort",reasoningEffort).put("max_completion_tokens",maxTokens).put("stream",false);
            body.set("messages",messages);
            if (!toolDefinitions.isEmpty()) body.set("tools",toolDefinitions);
            JsonNode response=post(body);
            JsonNode choice=response.path("choices").path(0);
            promptTokens+=response.path("usage").path("prompt_tokens").asInt(0);
            completionTokens+=response.path("usage").path("completion_tokens").asInt(0);
            if ("length".equals(choice.path("finish_reason").asText())) throw new IllegalStateException("Kimi answer was truncated; increase KIMI_MAX_COMPLETION_TOKENS");
            JsonNode assistant=choice.path("message");
            if (!assistant.isObject()) throw new IllegalStateException("Kimi returned no assistant message");
            List<AssistantMessage.ToolCall> calls=new ArrayList<>();
            for (JsonNode toolCall:assistant.path("tool_calls")) calls.add(new AssistantMessage.ToolCall(
                    toolCall.path("id").asText(),toolCall.path("type").asText("function"),
                    toolCall.path("function").path("name").asText(),toolCall.path("function").path("arguments").asText()));
            if (calls.isEmpty() || (toolOptions!=null && Boolean.FALSE.equals(toolOptions.getInternalToolExecutionEnabled()))) {
                String content=assistant.path("content").asText("");
                if (calls.isEmpty() && content.isBlank()) throw new IllegalStateException("Kimi returned no answer; increase KIMI_MAX_COMPLETION_TOKENS if reasoning reached its limit");
                Map<String,Object> metadata=Map.of(RAW_ASSISTANT,assistant.deepCopy());
                return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(content,metadata,calls))))
                        .metadata(ChatResponseMetadata.builder().id(response.path("id").asText())
                                .model(response.path("model").asText(model)).keyValue("provider","kimi")
                                .usage(new DefaultUsage(promptTokens,completionTokens)).build()).build();
            }
            if (round==maxToolRounds) throw new IllegalStateException("Kimi tool round limit reached");
            // Preserve every assistant field, including reasoning_content and future provider extensions.
            messages.add(assistant.deepCopy());
            Set<String> callIds=new HashSet<>();
            for (var call:calls) {
                if (call.id().isBlank() || !callIds.add(call.id())) throw new IllegalStateException("Kimi returned invalid tool call IDs");
                var callback=callbacks.get(call.name());
                if (callback==null) throw new IllegalStateException("Kimi requested an unconfigured tool: "+call.name());
                Map<String,Object> context=toolOptions==null || toolOptions.getToolContext()==null ? Map.of() : toolOptions.getToolContext();
                String result=callback.call(call.arguments(),new ToolContext(context));
                messages.addObject().put("role","tool").put("tool_call_id",call.id()).put("content",Objects.toString(result,""));
            }
        }
        throw new IllegalStateException("Kimi tool round limit reached");
    }

    /** The app exposes buffered SSE; this adapter deliberately shares the exact same safe tool loop. */
    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(call(prompt))).subscribeOn(Schedulers.boundedElastic());
    }

    private JsonNode post(ObjectNode body) {
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization","Bearer "+apiKey)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("Kimi API request failed (HTTP "+response.statusCode()+"); check account quota and model access");
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Kimi request interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Kimi network request failed or timed out");
        }
    }

    private ArrayNode messages(Prompt prompt) {
        ArrayNode messages=mapper.createArrayNode();
        for (Message message:prompt.getInstructions()) {
            if (message instanceof AssistantMessage assistant && assistant.getMetadata().containsKey(RAW_ASSISTANT)) {
                messages.add(mapper.valueToTree(assistant.getMetadata().get(RAW_ASSISTANT))); continue;
            }
            if (message instanceof ToolResponseMessage tool) {
                for (var response:tool.getResponses()) messages.addObject().put("role","tool").put("tool_call_id",response.id()).put("content",response.responseData());
                continue;
            }
            ObjectNode row=messages.addObject().put("role",message.getMessageType().getValue()).put("content",Objects.toString(message.getText(),""));
            if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
                ArrayNode content=mapper.createArrayNode(); content.addObject().put("type","text").put("text",Objects.toString(message.getText(),""));
                for(var media:user.getMedia()) {
                    Object data=media.getData();
                    String url;
                    if(data instanceof String || data instanceof URI) {
                        url=data.toString();
                        if(!url.startsWith("data:") && !url.startsWith("ms://")) throw new IllegalArgumentException("Kimi images must be inline base64 or uploaded file references");
                    } else url="data:"+media.getMimeType()+";base64,"+Base64.getEncoder().encodeToString(media.getDataAsByteArray());
                    content.addObject().put("type","image_url").putObject("image_url").put("url",url);
                }
                row.set("content",content);
            }
            if(message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                throw new IllegalArgumentException("Kimi tool history requires the complete original assistant message");
            }
        }
        return messages;
    }
}
