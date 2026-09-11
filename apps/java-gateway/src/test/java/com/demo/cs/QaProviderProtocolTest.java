package com.demo.cs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.prompt.Prompt;
import com.demo.cs.infrastructure.llm.KimiChatModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Offline wire-level tests. Only synthetic credentials, loopback servers, and in-memory DB. */
class QaProviderProtocolTest {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final String KIMI_KEY="qa-synthetic-kimi-credential";
 private static final String OPENAI_KEY="qa-synthetic-openai-credential";
 record Captured(String path,String authorization,JsonNode body){}
 static class Fixture implements AutoCloseable {
  final HttpServer server;
  final boolean reasoning;
  int forcedStatus=200;
  String redirectLocation;
  int toolRounds=2;
  String toolName="qa_read";
  String finishReason;
  final List<Captured> calls=new CopyOnWriteArrayList<>();
  Fixture(boolean reasoning) throws IOException {this.reasoning=reasoning;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::respond);server.start();}
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  @Override public void close(){server.stop(0);}
  void respond(HttpExchange exchange)throws IOException{
   try{
    var body=JSON.readTree(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();
    calls.add(new Captured(path,exchange.getRequestHeaders().getFirst("Authorization"),body));
    if(forcedStatus!=200){if(redirectLocation!=null)exchange.getResponseHeaders().set("Location",redirectLocation);byte[] data=("{\"error\":\"provider text "+KIMI_KEY+"\"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(forcedStatus,data.length);exchange.getResponseBody().write(data);return;}
    if(path.endsWith("/embeddings")){
     int count=body.path("input").isArray()?body.path("input").size():1;var data=new ArrayList<Map<String,Object>>();
     for(int i=0;i<count;i++)data.add(Map.of("object","embedding","index",i,"embedding",List.of(1.0,0.0,0.0)));
     reply(exchange,Map.of("object","list","model",body.path("model").asText(),"data",data,"usage",Map.of("prompt_tokens",1,"total_tokens",1)));return;
    }
    if(!path.endsWith("/chat/completions")){exchange.sendResponseHeaders(404,-1);return;}
    int results=0;for(var message:body.path("messages"))if(message.path("role").asText().equals("tool"))results++;
    boolean tool=body.path("tools").size()>0&&results<toolRounds;
    var message=new LinkedHashMap<String,Object>();message.put("role","assistant");if(reasoning){message.put("reasoning_content","QA_REASONING_ROUND_"+results);message.put("provider_extension",Map.of("opaque","QA_EXTENSION_"+results));}
    if(tool){message.put("content",null);message.put("tool_calls",List.of(Map.of("id","qa_call_"+results,"type","function","function",Map.of("name",toolName,"arguments","{\"value\":\"round_"+results+"\"}"))));}
    else message.put("content","QA_PROVIDER_OK");
    if(body.path("stream").asBoolean()){
     exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
     var delta=new LinkedHashMap<>(message);if(tool){var call=new LinkedHashMap<>((Map<String,Object>)((List<?>)message.get("tool_calls")).getFirst());call.put("index",0);delta.put("tool_calls",List.of(call));}
     var chunk=Map.of("id","qa-stream","object","chat.completion.chunk","created",1,"model","qa-model","choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",tool?"tool_calls":"stop")));
     exchange.getResponseBody().write(("data: "+JSON.writeValueAsString(chunk)+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().close();
    }else reply(exchange,Map.of("id","qa-call","object","chat.completion","created",1,"model","qa-model","choices",List.of(Map.of("index",0,"message",message,"finish_reason",finishReason!=null?finishReason:(tool?"tool_calls":"stop"))),"usage",Map.of("prompt_tokens",1,"completion_tokens",1,"total_tokens",2)));
   }finally{exchange.close();}
  }
  void reply(HttpExchange exchange,Object body)throws IOException{byte[] bytes=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.getResponseBody().close();}
 }
 ConfigurableApplicationContext start(String provider,Fixture kimi,Fixture openai){
  return start(provider,kimi,openai,true);
 }
 ConfigurableApplicationContext start(String provider,Fixture kimi,Fixture openai,boolean embedding){
  var props=new LinkedHashMap<String,Object>();
  props.put("spring.profiles.active","local,"+provider);
  props.put("KIMI_API_KEY",KIMI_KEY);props.put("OPENAI_API_KEY",OPENAI_KEY);
  props.put("KIMI_BASE_URL",kimi.url());props.put("OPENAI_BASE_URL",openai.url());
  props.put("KIMI_MODEL","qa-kimi-model");props.put("OPENAI_MODEL","qa-openai-model");
  props.put("OPENAI_EMBEDDING_MODEL","qa-openai-embedding-model");
  // embedding=true 表示这条用例要验证 OpenAI 嵌入链路，需显式关掉本地 ONNX 嵌入，
  // 否则 @Primary 的本地模型会接管，测不到凭证与目标隔离。
  if(embedding){props.put("EMBEDDING_PROVIDER","openai");props.put("app.vector.embedding","openai");}
  // Historical aliases are deliberately different, to detect accidental common-key fallback.
  props.put("LLM_API_KEY","qa-wrong-common-credential");props.put("SPRING_AI_OPENAI_API_KEY","qa-wrong-spring-common-credential");
  props.put("LLM_BASE_URL",openai.url());props.put("SPRING_AI_OPENAI_BASE_URL",openai.url());
  props.put("spring.datasource.url","jdbc:h2:mem:qa_provider_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
  props.put("app.agent-config.seed-on-startup","false");props.put("app.knowledge-sample-dir","./target/no-provider-samples");props.put("app.upload-dir","./target/qa-provider-uploads");
  var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("qa-only-synthetic-inputs",props));
  return new SpringApplicationBuilder(CustomerServiceApplication.class).environment(env).web(WebApplicationType.NONE).run();
 }
 ToolCallback callback(AtomicInteger executions){return new ToolCallback(){
  public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("qa_read").description("Local deterministic read tool").inputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}").build();}
  public String call(String input){executions.incrementAndGet();return "QA_TOOL_RESULT "+input;}
 };}
 void assertWire(List<Captured> calls,String key,boolean kimi){
  assertThat(calls).isNotEmpty();for(var call:calls){
   assertThat(call.authorization()).isEqualTo("Bearer "+key);
   if(call.path().endsWith("/chat/completions")){
    assertThat(call.body().has("thinking")).isFalse();
    if(kimi){assertThat(call.body().path("reasoning_effort").asText()).isEqualTo("low");assertThat(call.body().has("temperature")).isFalse();}
   }
  }
 }
 void assertReasoningAndToolIds(List<Captured> calls){
  assertThat(calls).hasSize(3);
  for(int request=0;request<calls.size();request++){
   var priorAssistants=new ArrayList<JsonNode>();var priorTools=new ArrayList<JsonNode>();
   for(var message:calls.get(request).body().path("messages")){
    if(message.path("role").asText().equals("assistant"))priorAssistants.add(message);
    if(message.path("role").asText().equals("tool"))priorTools.add(message);
   }
   assertThat(priorAssistants).hasSize(request);assertThat(priorTools).hasSize(request);
   for(int previous=0;previous<request;previous++){
    assertThat(priorAssistants.get(previous).path("reasoning_content").asText()).isEqualTo("QA_REASONING_ROUND_"+previous);
    assertThat(priorAssistants.get(previous).path("provider_extension").path("opaque").asText()).isEqualTo("QA_EXTENSION_"+previous);
    assertThat(priorAssistants.get(previous).path("tool_calls").get(0).path("id").asText()).isEqualTo("qa_call_"+previous);
    assertThat(priorTools.get(previous).path("tool_call_id").asText()).isEqualTo("qa_call_"+previous);
    assertThat(priorTools.get(previous).path("content").asText()).contains("QA_TOOL_RESULT");
   }
  }
 }
 @Test void kimiSyncAndBufferedStreamRetainReasoningAcrossToolsAndEmbeddingUsesOpenaiKey()throws Exception{
  try(var kimi=new Fixture(true);var openai=new Fixture(false);var context=start("kimi",kimi,openai)){
   var client=ChatClient.create(context.getBean(ChatModel.class));var executed=new AtomicInteger();
   var response=client.prompt().user("two tool rounds").toolCallbacks(callback(executed)).call().chatResponse();
   assertThat(Objects.requireNonNull(response).getResult().getOutput().getText()).isEqualTo("QA_PROVIDER_OK");assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(6);
   assertThat(executed.get()).isEqualTo(2);assertThat(kimi.calls).hasSize(3);assertWire(kimi.calls,KIMI_KEY,true);assertReasoningAndToolIds(kimi.calls);assertThat(openai.calls).isEmpty();
   kimi.calls.clear();executed.set(0);
   var chunks=client.prompt().user("stream two tool rounds").toolCallbacks(callback(executed)).stream().content().collectList().block(Duration.ofSeconds(20));
   assertThat(String.join("",Objects.requireNonNull(chunks))).contains("QA_PROVIDER_OK");assertThat(executed.get()).isEqualTo(2);assertThat(kimi.calls).hasSize(3);assertWire(kimi.calls,KIMI_KEY,true);
   assertReasoningAndToolIds(kimi.calls);assertThat(kimi.calls).allSatisfy(c->assertThat(c.body().path("stream").asBoolean()).isFalse());
   context.getBean(EmbeddingModel.class).embed("offline embedding");assertThat(openai.calls).hasSize(1);assertWire(openai.calls,OPENAI_KEY,false);
   assertThat(openai.calls.getFirst().path()).endsWith("/embeddings");assertThat(openai.calls.getFirst().body().path("model").asText()).isEqualTo("qa-openai-embedding-model");assertThat(kimi.calls).allSatisfy(c->assertThat(c.path()).endsWith("/chat/completions"));
  }
 }
 @Test void openaiProfileSyncAndStreamNeverUsesKimiKeyOrThinking()throws Exception{
  try(var kimi=new Fixture(true);var openai=new Fixture(false);var context=start("openai",kimi,openai)){
   var client=ChatClient.create(context.getBean(ChatModel.class));
   assertThat(client.prompt().user("hello").call().content()).isEqualTo("QA_PROVIDER_OK");
   assertThat(String.join("",Objects.requireNonNull(client.prompt().user("hello stream").stream().content().collectList().block(Duration.ofSeconds(20))))).contains("QA_PROVIDER_OK");
   context.getBean(EmbeddingModel.class).embed("offline embedding");assertThat(openai.calls).hasSize(3);assertThat(kimi.calls).isEmpty();assertWire(openai.calls,OPENAI_KEY,false);
   assertThat(openai.calls.stream().filter(c->c.path().endsWith("/embeddings")).toList()).hasSize(1).allSatisfy(c->assertThat(c.body().path("model").asText()).isEqualTo("qa-openai-embedding-model"));
  }
 }
 @Test void kimiDefaultEmbedsLocallyAndNeverCallsAnyProvider()throws Exception{
  // 检索已从关键词改为本地 ONNX 语义向量。要守住的保证不是「没有嵌入模型」，
  // 而是「嵌入在本机完成，任何供应商都收不到请求」。
  try(var kimi=new Fixture(true);var openai=new Fixture(false);var context=start("kimi",kimi,openai,false)){
   var embedding=context.getBean(EmbeddingModel.class);
   assertThat(embedding.getClass().getName()).contains("Transformers");
   assertThat(embedding.embed("本地嵌入")).hasSize(512);
   var vector=context.getBean(org.springframework.ai.vectorstore.VectorStore.class);
   vector.add(List.of(new org.springframework.ai.document.Document("国内订单签收后 7 天内可以无理由退货")));
   var hits=vector.similaritySearch("买回来不想要了能不能退");
   assertThat(hits).isNotEmpty();
   assertThat(openai.calls).isEmpty();assertThat(kimi.calls).isEmpty();
  }
 }
 @Test void kimiRedirectNeverForwardsAuthorizationToOtherProvider()throws Exception{
  try(var kimi=new Fixture(true);var openai=new Fixture(false)){
   kimi.forcedStatus=302;kimi.redirectLocation=openai.url()+"/v1/chat/completions";
   var model=new KimiChatModel(JSON,KIMI_KEY,kimi.url(),"qa-model","low",100,2,5);
   assertThatThrownBy(()->model.call(new Prompt("redirect test"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("302").hasMessageNotContaining(KIMI_KEY);
   assertThat(kimi.calls).hasSize(1);assertThat(openai.calls).isEmpty();
  }
 }
 @Test void kimiErrorsDoNotEchoCredentialOrProviderResponseBody()throws Exception{
  try(var kimi=new Fixture(true)){
   kimi.forcedStatus=401;var model=new KimiChatModel(JSON,KIMI_KEY,kimi.url()+"/v1","qa-model","low",100,2,5);
   assertThatThrownBy(()->model.call(new Prompt("auth test"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("401").hasMessageNotContaining(KIMI_KEY).hasMessageNotContaining("provider text");
   assertThat(kimi.calls.getFirst().path()).isEqualTo("/v1/chat/completions");
   kimi.forcedStatus=200;kimi.finishReason="length";
   assertThatThrownBy(()->model.call(new Prompt("truncated content test"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("truncated");
  }
 }
 @Test void kimiRejectsUnconfiguredToolsAndLimitsConfiguredToolRounds()throws Exception{
  try(var kimi=new Fixture(true)){
   var model=new KimiChatModel(JSON,KIMI_KEY,kimi.url(),"qa-model","low",100,2,5);var executions=new AtomicInteger();
   kimi.toolName="unconfigured_tool";
   assertThatThrownBy(()->ChatClient.create(model).prompt().user("bad tool").toolCallbacks(callback(executions)).call().content()).hasMessageContaining("unconfigured tool");assertThat(executions).hasValue(0);
   kimi.calls.clear();kimi.toolName="qa_read";kimi.toolRounds=99;
   assertThatThrownBy(()->ChatClient.create(model).prompt().user("endless tools").toolCallbacks(callback(executions)).call().content()).hasMessageContaining("round limit");
   assertThat(executions).hasValue(2);assertThat(kimi.calls).hasSize(3);assertReasoningAndToolIds(kimi.calls);
  }
 }
 @Test void missingKimiCredentialFailsWithoutAnyFallbackRequest()throws Exception{
  try(var kimi=new Fixture(true)){
   assertThatThrownBy(()->new KimiChatModel(JSON,"",kimi.url(),"qa-model","low",100,2,5)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KIMI_API_KEY");
   assertThat(kimi.calls).isEmpty();
  }
 }
}
