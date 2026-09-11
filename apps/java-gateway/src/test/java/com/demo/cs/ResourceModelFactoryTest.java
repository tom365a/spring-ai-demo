package com.demo.cs;

import com.demo.cs.application.resources.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ResourceModelFactoryTest {
    final ResourceTransportTest fixtures=new ResourceTransportTest();
    final ObjectMapper json=new ObjectMapper();
    final ResourceModelFactory factory=new ResourceModelFactory(json);
    ResourceSnapshot model(ResourceTransportTest.Server server,String provider) {
        ObjectNode config=server.config().put("baseUrl",server.url()+"/v1").put("provider",provider).put("model",provider.equals("KIMI")?"kimi-k3":"gpt-4o-mini");
        config.putObject("parameters");
        return new ResourceSnapshot(provider,provider,"MODEL",provider+" fixture",1,config,null);
    }
    ToolCallback tool(AtomicInteger executions) {return new ToolCallback(){
        public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("verify_tool").description("synthetic").inputSchema("{\"type\":\"object\"}").build();}
        public String call(String input){executions.incrementAndGet();return "controlled-result";}
    };}

    @Test void isolatesProvidersAndPreservesRawReasoningWithoutExecutingCallbacks()throws Exception {
        List<JsonNode> kimiBodies=new CopyOnWriteArrayList<>(),openaiBodies=new CopyOnWriteArrayList<>();
        try(var kimi=fixtures.new Server(x->{
            assertThat(x.getRequestURI().getPath()).isEqualTo("/v1/chat/completions");
            assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-kimi");
            JsonNode body=json.readTree(x.getRequestBody());kimiBodies.add(body);
            boolean hasResult=false;for(JsonNode m:body.path("messages"))if(m.path("role").asText().equals("tool"))hasResult=true;
            Map<String,Object> assistant=hasResult?Map.of("role","assistant","content","Kimi tool result accepted"):
                    Map.of("role","assistant","content","","reasoning_content","opaque private reasoning","provider_extension",Map.of("ticket",7),
                            "tool_calls",List.of(Map.of("id","call-1","type","function","function",Map.of("name","verify_tool","arguments","{}"))));
            fixtures.reply(x,200,Map.of("id","kimi-response","model","kimi-k3","choices",List.of(Map.of("message",assistant,"finish_reason",hasResult?"stop":"tool_calls")),"usage",Map.of("prompt_tokens",4,"completion_tokens",3)));
        });var openai=fixtures.new Server(x->{
            assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-openai");
            JsonNode body=json.readTree(x.getRequestBody());openaiBodies.add(body);
            fixtures.reply(x,200,Map.of("model","gpt-4o-mini","choices",List.of(Map.of("message",Map.of("role","assistant","content","OpenAI fixture response"),"finish_reason","stop"))));
        })) {
            var kimiModel=factory.create(model(kimi,"KIMI"),"synthetic-kimi",Duration.ofSeconds(3));
            var openaiModel=factory.create(model(openai,"OPENAI"),"synthetic-openai",Duration.ofSeconds(3));
            AtomicInteger executions=new AtomicInteger();
            var options=ToolCallingChatOptions.builder().model("not-allowed-to-override-resource-model").toolCallbacks(tool(executions)).internalToolExecutionEnabled(false).build();
            var first=kimiModel.call(new Prompt(List.of(new UserMessage("Verify configured tool")),options));
            assertThat(first.getResult().getOutput().hasToolCalls()).isTrue();assertThat(executions.get()).isZero();
            AssistantMessage assistant=first.getResult().getOutput();
            var second=kimiModel.call(new Prompt(List.of(new UserMessage("Verify configured tool"),assistant,
                    new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("call-1","verify_tool","controlled-result")))),options));
            assertThat(second.getResult().getOutput().getText()).isEqualTo("Kimi tool result accepted");
            assertThat(second.getMetadata().getUsage().getTotalTokens()).isEqualTo(7);
            assertThat(kimiBodies.get(1).path("messages").get(1).path("reasoning_content").asText()).isEqualTo("opaque private reasoning");
            assertThat(kimiBodies.get(1).path("messages").get(1).path("provider_extension").path("ticket").asInt()).isEqualTo(7);
            assertThat(openaiModel.call(new Prompt("hello")).getResult().getOutput().getText()).contains("OpenAI");
            assertThat(openaiModel.stream(new Prompt("hello stream")).blockLast(Duration.ofSeconds(3)).getResult().getOutput().getText()).contains("OpenAI");
            for(var body:kimiBodies){assertThat(body.path("model").asText()).isEqualTo("kimi-k3");assertThat(body.path("reasoning_effort").asText()).isEqualTo("low");assertThat(body.has("temperature")).isFalse();}
            for(var body:openaiBodies){assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");assertThat(body.has("reasoning_effort")).isFalse();}
            assertThat(executions.get()).isZero();
        }
    }

    @Test void errorsAndTruncationDoNotExposeProviderBodyOrCredential()throws Exception {
        try(var server=fixtures.new Server(x->fixtures.reply(x,200,Map.of("choices",List.of(Map.of("finish_reason","length","message",Map.of("content","partial"))))))) {
            assertThatThrownBy(()->factory.create(model(server,"KIMI"),"synthetic-secret",Duration.ofSeconds(2)).call(new Prompt("hi"))).hasMessageContaining("截断");
        }
        try(var server=fixtures.new Server(x->fixtures.reply(x,401,Map.of("error","synthetic-secret error-body")))) {
            assertThatThrownBy(()->factory.create(model(server,"OPENAI"),"synthetic-secret",Duration.ofSeconds(2)).call(new Prompt("hi")))
                    .hasMessageContaining("401").hasMessageNotContaining("synthetic-secret").hasMessageNotContaining("error-body");
        }
    }
}
