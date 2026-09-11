package com.demo.cs.application.resources;

import com.demo.cs.infrastructure.resources.transport.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Per-resource, per-version models. Tool execution belongs exclusively to the unified gateway. */
@Component
public class ResourceModelFactory {
    public static final String RAW_ASSISTANT="resource.rawAssistant";
    private final ObjectMapper mapper;
    public ResourceModelFactory(ObjectMapper mapper) { this.mapper=mapper; }

    public ChatModel create(ResourceSnapshot snapshot,String credential,Duration timeout) {
        if(!snapshot.kind().equals("MODEL"))throw error("资源不是模型配置");
        if(credential==null || credential.isBlank())throw error("模型凭证尚未配置或不可用");
        if(timeout==null || timeout.isNegative() || timeout.isZero())throw error("模型调用预算已耗尽");
        return new ConfiguredModel(snapshot,credential,timeout);
    }

    private final class ConfiguredModel implements ChatModel {
        final ResourceSnapshot snapshot; final ObjectNode config; final String credential,provider,model,url;
        final Duration timeout;
        ConfiguredModel(ResourceSnapshot snapshot,String credential,Duration timeout) {
            this.snapshot=snapshot;this.config=(ObjectNode)snapshot.config().deepCopy();this.credential=credential;
            provider=config.path("provider").asText().toUpperCase(Locale.ROOT);
            if(!Set.of("KIMI","OPENAI").contains(provider))throw error("不支持的模型供应商");
            model=config.path("model").asText();if(model.isBlank())throw error("模型标识不能为空");
            String base=config.path("baseUrl").asText().replaceAll("/+$","");
            try {URI uri=URI.create(base);if(uri.getRawQuery()!=null || uri.getRawFragment()!=null)throw error("模型服务地址不能包含查询或片段");}
            catch(IllegalArgumentException e){throw error("模型服务地址无效");}
            url=base+(base.endsWith("/v1")?"":"/v1")+"/chat/completions";
            int seconds=config.path("timeoutSeconds").asInt(90);
            if(seconds<1 || seconds>600)throw error("模型超时应为1–600秒");
            this.timeout=timeout.compareTo(Duration.ofSeconds(seconds))<0?timeout:Duration.ofSeconds(seconds);
            config.putObject("auth").put("type","BEARER");
        }
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().model(model).internalToolExecutionEnabled(false).build();
        }
        public ChatResponse call(Prompt prompt) {
            ObjectNode body=mapper.createObjectNode().put("model",model).put("stream",false);
            JsonNode parameters=config.path("parameters");
            if(provider.equals("KIMI")) {
                String effort=parameters.path("reasoningEffort").asText("low");
                if(!Set.of("low","high","max").contains(effort))throw error("Kimi 推理强度不支持");
                if(parameters.has("temperature") || parameters.has("thinking"))throw error("Kimi K3 不支持该采样或思考开关");
                int tokens=parameters.path("maxCompletionTokens").asInt(4096);checkTokens(tokens);
                body.put("reasoning_effort",effort).put("max_completion_tokens",tokens);
            } else {
                int tokens=parameters.path("maxTokens").asInt(2048);checkTokens(tokens);
                body.put("max_tokens",tokens);
                if(parameters.has("temperature")) {
                    if(!parameters.path("temperature").isNumber())throw error("温度必须是数值");
                    double value=parameters.path("temperature").asDouble();
                    if(!Double.isFinite(value) || value<0 || value>2)throw error("温度应为0–2");
                    body.put("temperature",value);
                }
            }
            body.set("messages",messages(prompt));
            if(prompt.getOptions() instanceof ToolCallingChatOptions tools) {
                if(tools.getToolNames()!=null && !tools.getToolNames().isEmpty())throw error("模型只能使用当前任务提供的明确工具定义");
                ArrayNode definitions=mapper.createArrayNode();Set<String> names=new HashSet<>();
                if(tools.getToolCallbacks()!=null)for(var callback:tools.getToolCallbacks()) {
                    String name=callback.getToolDefinition().name();
                    if(!names.add(name))throw error("模型工具名称重复");
                    ObjectNode function=definitions.addObject().put("type","function").putObject("function");
                    function.put("name",name).put("description",Objects.toString(callback.getToolDefinition().description(),""));
                    try {function.set("parameters",mapper.readTree(callback.getToolDefinition().inputSchema()));}
                    catch(Exception e){throw error("模型工具Schema无效");}
                }
                if(!definitions.isEmpty())body.set("tools",definitions);
            }
            byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
            if(payload.length>32*1_048_576)throw error("模型请求超过32 MiB限制");
            var headers=GuardedHttp.authHeaders(config,credential);headers.put("Accept","application/json");
            GuardedHttp.Response response;
            try {
                response=GuardedHttp.request(config,"POST",url,headers,payload,timeout,2*1_048_576);
            } catch (TransportException failure) {
                if (failure.code().equals("NETWORK_ERROR")) throw new TransportException("NETWORK_ERROR","模型服务连接失败，请检查后端网络权限和模型接口地址");
                if (failure.code().equals("TIMEOUT")) throw new TransportException("TIMEOUT","模型请求超时，请检查网络或调整模型请求超时设置");
                throw failure;
            }
            JsonNode parsed;
            try {parsed=mapper.readTree(response.body());}
            catch(Exception e){throw error("模型响应不是有效JSON");}
            if(parsed==null)throw error("模型响应为空");
            JsonNode choice=parsed.path("choices").path(0);
            String finish=choice.path("finish_reason").asText();
            if(finish.equals("length"))throw error("模型输出被截断，请调整输出预算后重试");
            if(finish.equals("content_filter"))throw error("模型服务拒绝生成当前内容");
            JsonNode assistant=choice.path("message");
            if(!assistant.isObject() || !assistant.path("role").asText("assistant").equals("assistant"))throw error("模型未返回有效助手消息");
            List<AssistantMessage.ToolCall> calls=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(JsonNode call:assistant.path("tool_calls")) {
                String id=call.path("id").asText(), name=call.path("function").path("name").asText();
                if(id.isBlank() || !ids.add(id) || name.isBlank() || !call.path("type").asText().equals("function") || !call.path("function").path("arguments").isTextual())throw error("模型工具调用格式无效");
                calls.add(new AssistantMessage.ToolCall(id,"function",name,call.path("function").path("arguments").asText()));
            }
            String answer=assistant.path("content").asText("");
            if(answer.isBlank() && calls.isEmpty())throw error("模型没有返回答案或工具请求");
            answer=answer.replace(credential,"[REDACTED]");
            Map<String,Object> metadata=new LinkedHashMap<>();metadata.put(RAW_ASSISTANT,assistant.deepCopy());
            metadata.put("resourceId",snapshot.id());metadata.put("resourceVersion",snapshot.version());
            var usage=parsed.path("usage");
            return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(answer,metadata,calls))))
                    .metadata(ChatResponseMetadata.builder().id(parsed.path("id").asText()).model(parsed.path("model").asText(model))
                            .keyValue("provider",provider.toLowerCase(Locale.ROOT)).keyValue("resourceId",snapshot.id())
                            .keyValue("resourceName",snapshot.name()).keyValue("resourceVersion",snapshot.version())
                            .usage(new DefaultUsage(usage.path("prompt_tokens").asInt(0),usage.path("completion_tokens").asInt(0))).build()).build();
        }
        public Flux<ChatResponse> stream(Prompt prompt) {return Flux.defer(()->Flux.just(call(prompt))).subscribeOn(Schedulers.boundedElastic());}

        private ArrayNode messages(Prompt prompt) {
            ArrayNode messages=mapper.createArrayNode();
            for(Message message:prompt.getInstructions()) {
                if(message instanceof AssistantMessage assistant) {
                    Object raw=assistant.getMetadata().get(RAW_ASSISTANT);
                    if(raw==null)raw=assistant.getMetadata().get("kimi.rawAssistant");
                    if(raw!=null) {messages.add(mapper.valueToTree(raw));continue;}
                    if(provider.equals("KIMI") && assistant.hasToolCalls())throw error("Kimi 工具历史必须保留原始助手消息");
                }
                if(message instanceof ToolResponseMessage tool) {
                    for(var result:tool.getResponses())messages.addObject().put("role","tool").put("tool_call_id",result.id()).put("content",result.responseData());
                    continue;
                }
                ObjectNode row=messages.addObject().put("role",message.getMessageType().getValue()).put("content",Objects.toString(message.getText(),""));
                if(message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                    ArrayNode calls=row.putArray("tool_calls");
                    for(var call:assistant.getToolCalls())calls.addObject().put("id",call.id()).put("type","function")
                            .putObject("function").put("name",call.name()).put("arguments",call.arguments());
                }
                if(message instanceof UserMessage user && !user.getMedia().isEmpty()) {
                    ArrayNode content=mapper.createArrayNode();content.addObject().put("type","text").put("text",Objects.toString(message.getText(),""));
                    for(var media:user.getMedia()) {
                        Object data=media.getData();String mediaUrl;
                        if(data instanceof String || data instanceof URI) {
                            mediaUrl=data.toString();if(!mediaUrl.startsWith("data:image/") && !mediaUrl.startsWith("ms://"))throw error("图片只支持内联数据或已上传文件引用");
                        } else mediaUrl="data:"+media.getMimeType()+";base64,"+Base64.getEncoder().encodeToString(media.getDataAsByteArray());
                        content.addObject().put("type","image_url").putObject("image_url").put("url",mediaUrl);
                    }
                    row.set("content",content);
                }
            }
            return messages;
        }
    }
    private static void checkTokens(int n){if(n<1 || n>131072)throw error("输出Token预算超出支持范围");}
    private static TransportException error(String message){return new TransportException("MODEL_ERROR",message);}
}
