package com.demo.cs;

import com.demo.cs.application.resources.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class QaResourceModelTest {
 final QaResourceTransportTest f=new QaResourceTransportTest();
 final ObjectMapper json=new ObjectMapper();
 final ResourceModelFactory factory=new ResourceModelFactory(json);
 ResourceSnapshot config(QaResourceTransportTest.Fixture fixture,String provider,String name){var c=fixture.config().put("provider",provider).put("model",name).put("baseUrl",fixture.url());c.putObject("parameters");return new ResourceSnapshot(name,name,"MODEL",name,7,c,null);}
 Object completion(Object message){return Map.of("choices",List.of(Map.of("finish_reason","stop","message",message)));}
 @Test void simultaneousResourcesKeepCredentialsAndModelSelectionIsolated()throws Exception {
  var seen=new CopyOnWriteArrayList<String>();
  try(var first=f.new Fixture(x->{var b=json.readTree(x.getRequestBody());assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer qa-key-a");assertThat(b.path("model").asText()).isEqualTo("qa-model-a");assertThat(b.has("reasoning_effort")).isTrue();seen.add("a");f.reply(x,completion(Map.of("role","assistant","content","result a")));});
      var second=f.new Fixture(x->{var b=json.readTree(x.getRequestBody());assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer qa-key-b");assertThat(b.path("model").asText()).isEqualTo("qa-model-b");assertThat(b.has("reasoning_effort")).isFalse();seen.add("b");f.reply(x,completion(Map.of("role","assistant","content","result b")));});
      var executor=Executors.newVirtualThreadPerTaskExecutor()){
   var a=factory.create(config(first,"KIMI","qa-model-a"),"qa-key-a",Duration.ofSeconds(3));var b=factory.create(config(second,"OPENAI","qa-model-b"),"qa-key-b",Duration.ofSeconds(3));
   var override=ToolCallingChatOptions.builder().model("forged-model").build();List<Future<String>> replies=new ArrayList<>();
   for(int i=0;i<8;i++){var chosen=i%2==0?a:b;replies.add(executor.submit(()->chosen.call(new Prompt(List.of(new UserMessage("hello")),override)).getResult().getOutput().getText()));}
   for(int i=0;i<8;i++)assertThat(replies.get(i).get(6,TimeUnit.SECONDS)).isEqualTo(i%2==0?"result a":"result b");assertThat(seen.stream().filter("a"::equals)).hasSize(4);assertThat(seen.stream().filter("b"::equals)).hasSize(4);
  }
 }
 @Test void malformedToolResponsesAndUnscopedToolNamesCannotReachExecution()throws Exception {
  try(var server=f.new Fixture(x->{f.reply(x,completion(Map.of("role","assistant","content","", "tool_calls",List.of(
   Map.of("id","duplicate","type","function","function",Map.of("name","first","arguments","{}")),Map.of("id","duplicate","type","function","function",Map.of("name","second","arguments","{}"))))));})){
   var model=factory.create(config(server,"OPENAI","qa-model"),"qa-key",Duration.ofSeconds(3));assertThatThrownBy(()->model.call(new Prompt("hi"))).hasMessageContaining("工具调用格式");
   var unscoped=ToolCallingChatOptions.builder().toolNames("cancel_order").build();assertThatThrownBy(()->model.call(new Prompt(List.of(new UserMessage("hi")),unscoped))).hasMessageContaining("明确工具定义");assertThat(server.count).hasValue(1);
  }
 }
 @Test void bufferedStreamKeepsResourceSnapshotAndRedactsPublicAnswer()throws Exception {
  try(var server=f.new Fixture(x->{var b=json.readTree(x.getRequestBody());assertThat(b.path("model").asText()).isEqualTo("original");assertThat(b.path("stream").asBoolean()).isFalse();f.reply(x,completion(Map.of("role","assistant","content","echo qa-secret")));})){
   var snapshot=config(server,"KIMI","original");var model=factory.create(snapshot,"qa-secret",Duration.ofSeconds(3));((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.config()).put("model","modified-after-capture");
   var result=model.stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(4));assertThat(result).hasSize(1);assertThat(result.getFirst().getResult().getOutput().getText()).isEqualTo("echo [REDACTED]");assertThat((Object)result.getFirst().getMetadata().get("resourceVersion")).isEqualTo(7);
  }
 }
}
